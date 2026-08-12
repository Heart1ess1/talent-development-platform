package com.talent.platform.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class EvaluationService {
  public static final List<String> COMPONENTS = List.of("EXAM", "TASK", "MENTOR", "STATION", "TRAINING");
  private static final Set<String> AUTO_COMPONENTS = Set.of("EXAM", "TASK");
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public EvaluationService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

  public Map<String,Object> monthlyDetail(Long employeeId, YearMonth month) {
    return calculate(employeeId, month, schemeFor(employeeId, month)).detail();
  }

  public boolean isLocked(Long employeeId, YearMonth month) {
    var rows = db.queryForList("select status from score_summary where employee_id=? and summary_type='MONTH' and period_key=? order by version desc limit 1", employeeId, month.toString());
    return !rows.isEmpty() && "PUBLISHED".equals(String.valueOf(rows.get(0).get("status")));
  }

  @Transactional
  public int generateMonth(YearMonth month) {
    var employees = db.queryForList("select id from employee where status='ACTIVE' and batch_id is not null");
    int count = 0;
    for (var employee : employees) {
      Long id = number(employee.get("id")).longValue();
      if (isLocked(id, month)) continue;
      try { writeMonthly(id, month, schemeFor(id, month), null); count++; }
      catch (BusinessException ignored) { /* 未配置适用方案的员工不生成 */ }
    }
    return count;
  }

  @Transactional
  public Long generateMonth(Long employeeId, YearMonth month, Map<String,Object> scheme, String reopenReason) {
    return writeMonthly(employeeId, month, scheme, reopenReason);
  }

  @Transactional
  public void refreshDraftIfPresent(Long employeeId, YearMonth month) {
    var rows = db.queryForList("select id,scheme_id from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='DRAFT' order by version desc limit 1", employeeId, month.toString());
    if (rows.isEmpty()) return;
    Map<String,Object> row = rows.get(0);
    updateMonthly(number(row.get("id")).longValue(), calculate(employeeId, month, schemeById(number(row.get("scheme_id")).longValue())));
  }

  @Transactional
  public Map<String,Object> refreshDraft(Long summaryId) {
    var row = db.queryForMap("select employee_id,period_key,scheme_id from score_summary where id=? and summary_type='MONTH' and status='DRAFT'", summaryId);
    Long employeeId = number(row.get("employee_id")).longValue();
    MonthlyCalculation calculation = calculate(employeeId, YearMonth.parse(String.valueOf(row.get("period_key"))), schemeById(number(row.get("scheme_id")).longValue()));
    updateMonthly(summaryId, calculation);
    return calculation.detail();
  }

  @Transactional
  public int generateQuarter(int year, int quarter) {
    if (quarter < 1 || quarter > 4) throw new BusinessException(400, "季度必须为1到4");
    int first = (quarter - 1) * 3 + 1;
    YearMonth lastMonth = YearMonth.of(year, first + 2);
    String periodKey = year + "-Q" + quarter;
    int count = 0;
    for (var employee : db.queryForList("select id from employee where status='ACTIVE' and batch_id is not null")) {
      Long employeeId = number(employee.get("id")).longValue();
      Map<String,Object> scheme;
      try { scheme = schemeFor(employeeId, lastMonth); }
      catch (BusinessException ignored) { continue; }
      List<BigDecimal> weights = List.of(decimal(scheme,"quarter_month1_weight"), decimal(scheme,"quarter_month2_weight"), decimal(scheme,"quarter_month3_weight"));
      List<Map<String,Object>> months = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      BigDecimal total = BigDecimal.ZERO;
      for (int i = 0; i < 3; i++) {
        String key = YearMonth.of(year, first + i).toString();
        var scores = db.queryForList("select id,final_score,version from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED' and final_score is not null order by version desc limit 1", employeeId, key);
        BigDecimal score = scores.isEmpty() ? null : decimal(scores.get(0), "final_score");
        if (score == null) missing.add(key);
        BigDecimal contribution = score == null ? null : score.multiply(weights.get(i)).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        if (contribution != null) total = total.add(contribution);
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("month", key); item.put("weight", weights.get(i)); item.put("score", score); item.put("contribution", contribution == null ? null : contribution.setScale(2, RoundingMode.HALF_UP));
        item.put("monthlySummaryId", scores.isEmpty() ? null : scores.get(0).get("id"));
        months.add(item);
      }
      BigDecimal finalScore = missing.isEmpty() ? total.setScale(2, RoundingMode.HALF_UP) : null;
      Integer version = nextOrDraftVersion(employeeId, "QUARTER", periodKey);
      var draft = db.queryForList("select id from score_summary where employee_id=? and summary_type='QUARTER' and period_key=? and status='DRAFT' order by version desc limit 1", employeeId, periodKey);
      String snapshot = toJson(Map.of("schemeId", scheme.get("id"), "months", months));
      if (draft.isEmpty()) {
        db.update("insert into score_summary(employee_id,summary_type,period_key,version,scheme_id,final_score,missing_items,quarter_snapshot) values(?,'QUARTER',?,?,?,?,?,cast(? as json))", employeeId, periodKey, version, scheme.get("id"), finalScore, String.join(",", missing), snapshot);
      } else {
        db.update("update score_summary set scheme_id=?,final_score=?,missing_items=?,quarter_snapshot=cast(? as json),generated_at=now() where id=?", scheme.get("id"), finalScore, String.join(",", missing), snapshot, draft.get(0).get("id"));
      }
      count++;
    }
    return count;
  }

  public Map<String,Object> schemeFor(Long employeeId, YearMonth month) {
    Long batchId = db.queryForObject("select batch_id from employee where id=?", Long.class, employeeId);
    if (batchId == null) throw new BusinessException(400, "员工未绑定培养批次");
    var rows = db.queryForList("select s.*,t.name template_name from score_scheme s left join evaluation_template t on t.id=s.template_id where s.batch_id=? and s.status in ('PUBLISHED','RETIRED') and s.effective_month<=? order by s.effective_month desc,s.version desc limit 1", batchId, month.atDay(1));
    if (rows.isEmpty()) throw new BusinessException(400, "该月份没有已发布的评分方案");
    return rows.get(0);
  }

  public Map<String,Object> schemeById(Long id) { return db.queryForMap("select s.*,t.name template_name from score_scheme s left join evaluation_template t on t.id=s.template_id where s.id=?", id); }

  public BigDecimal componentMaxScore(Long employeeId,YearMonth month,String component) {
    if(!COMPONENTS.contains(component))throw new BusinessException(400,"不支持的评分项");
    return maxScore(schemeFor(employeeId,month),component);
  }

  private Long writeMonthly(Long employeeId, YearMonth month, Map<String,Object> scheme, String reopenReason) {
    if (isLocked(employeeId, month) && (reopenReason == null || reopenReason.isBlank())) throw new BusinessException(400, "已发布月度汇总必须填写原因后重开");
    MonthlyCalculation calculation = calculate(employeeId, month, scheme);
    var draft = db.queryForList("select id from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='DRAFT' order by version desc limit 1", employeeId, month.toString());
    if (!draft.isEmpty()) {
      Long id = number(draft.get(0).get("id")).longValue(); updateMonthly(id, calculation); return id;
    }
    Integer version = nextOrDraftVersion(employeeId, "MONTH", month.toString());
    Map<String,BigDecimal> scores = calculation.scores();
    db.update("insert into score_summary(employee_id,summary_type,period_key,version,scheme_id,exam_score,task_score,mentor_score,station_score,training_score,bonus,deduction,component_snapshot,final_score,missing_items,reopen_reason) values(?,'MONTH',?,?,?,?,?,?,?,?,?,?,cast(? as json),?,?,?)",
      employeeId, month.toString(), version, scheme.get("id"), scores.get("EXAM"), scores.get("TASK"), scores.get("MENTOR"), scores.get("STATION"), scores.get("TRAINING"), calculation.bonus(), calculation.deduction(), calculation.snapshot(), calculation.finalScore(), String.join(",", calculation.missing()), reopenReason);
    return db.queryForObject("select last_insert_id()", Long.class);
  }

  private void updateMonthly(Long id, MonthlyCalculation c) {
    Map<String,BigDecimal> scores = c.scores();
    db.update("update score_summary set scheme_id=?,exam_score=?,task_score=?,mentor_score=?,station_score=?,training_score=?,bonus=?,deduction=?,component_snapshot=cast(? as json),final_score=?,missing_items=?,generated_at=now() where id=? and status='DRAFT'",
      c.schemeId(), scores.get("EXAM"), scores.get("TASK"), scores.get("MENTOR"), scores.get("STATION"), scores.get("TRAINING"), c.bonus(), c.deduction(), c.snapshot(), c.finalScore(), String.join(",", c.missing()), id);
  }

  private MonthlyCalculation calculate(Long employeeId, YearMonth month, Map<String,Object> scheme) {
    LocalDate start = month.atDay(1), end = month.atEndOfMonth();
    SourceAggregate exam = examAggregate(employeeId,start,scheme);
    SourceAggregate task = taskAggregate(employeeId,start,end,scheme);
    Map<String,List<Map<String,Object>>> manual = manualScores(employeeId, start);
    ManualAggregate mentor=mentorAggregate(employeeId,manual.getOrDefault("MENTOR",List.of()));
    StationAggregate station=stationAggregate(employeeId,month,scheme,manual.getOrDefault("STATION",List.of()));
    ManualAggregate training=singleManualAggregate(manual.getOrDefault("TRAINING",List.of()));
    Map<String,Map<String,Object>> overrides = overrideScores(employeeId, start);
    Map<String,BigDecimal> sourceScores = new LinkedHashMap<>();
    sourceScores.put("EXAM", exam.score()); sourceScores.put("TASK", task.score());
    sourceScores.put("MENTOR",mentor.score()); sourceScores.put("STATION",station.score()); sourceScores.put("TRAINING",training.score());

    List<Map<String,Object>> components = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    List<EvaluationRules.WeightedScore> weightedScores = new ArrayList<>();
    Map<String,BigDecimal> effectiveScores = new LinkedHashMap<>();
    for (String code : COMPONENTS) {
      boolean enabled = bool(scheme.get(code.toLowerCase() + "_enabled"));
      BigDecimal weight = decimal(scheme, code.toLowerCase() + "_weight");
      BigDecimal fullScore = maxScore(scheme,code);
      BigDecimal source = sourceScores.get(code);
      Map<String,Object> override = overrides.get(code);
      BigDecimal overrideScore = override == null ? null : decimal(override, "override_score");
      BigDecimal effective = overrideScore != null ? overrideScore : source;
      BigDecimal normalized = effective == null ? null : effective.multiply(new BigDecimal("100")).divide(fullScore,6,RoundingMode.HALF_UP);
      BigDecimal contribution = enabled && normalized != null ? normalized.multiply(weight).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP) : null;
      if (enabled && effective == null) missing.add(code);
      weightedScores.add(new EvaluationRules.WeightedScore(enabled,weight,normalized));
      effectiveScores.put(code, effective);
      Map<String,Object> item = new LinkedHashMap<>();
      item.put("code", code); item.put("enabled", enabled); item.put("weight", weight); item.put("fullScore", fullScore); item.put("sourceType", AUTO_COMPONENTS.contains(code) ? "AUTO" : "MANUAL");
      item.put("sourceScore", source); item.put("overrideScore", overrideScore); item.put("effectiveScore", effective); item.put("weightedScore", contribution == null ? null : contribution.setScale(2, RoundingMode.HALF_UP));
      item.put("status", !enabled ? "DISABLED" : effective == null ? "PENDING" : overrideScore != null ? "OVERRIDDEN" : AUTO_COMPONENTS.contains(code) ? "AUTOMATIC" : "SUBMITTED");
      if("EXAM".equals(code))item.put("breakdown",exam.items());
      if("TASK".equals(code))item.put("breakdown",task.items());
      if("MENTOR".equals(code)){item.put("breakdown",mentor.entries());item.put("submittedCount",mentor.submitted());item.put("requiredCount",mentor.required());item.put("partialScore",mentor.partialScore());}
      if("STATION".equals(code)){item.put("breakdown",station.entries());item.put("aggregationMode",station.mode());item.put("submittedCount",station.submitted());item.put("requiredCount",station.required());}
      if("TRAINING".equals(code)){item.put("breakdown",training.entries());item.put("submittedCount",training.submitted());item.put("requiredCount",training.required());}
      if(List.of("MENTOR","STATION","TRAINING").contains(code)){
        List<Map<String,Object>> entries=switch(code){case "MENTOR"->mentor.entries();case "STATION"->station.entries();default->training.entries();};
        String names=entries.stream().map(x->Objects.toString(x.get("evaluatorName"),"")).filter(x->!x.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、"));
        if(!names.isBlank())item.put("evaluatorName",names);
      }
      if (override != null) { item.put("overrideReason", override.get("reason")); item.put("overrideBy", override.get("operator_name")); item.put("overrideAt", override.get("updated_at")); }
      components.add(item);
    }
    BigDecimal bonus = sumAdjustment(employeeId, start, "BONUS").min(decimal(scheme, "bonus_cap"));
    BigDecimal deduction = sumAdjustment(employeeId, start, "DEDUCTION").min(decimal(scheme, "deduction_cap"));
    BigDecimal finalScore = EvaluationRules.finalScore(weightedScores,bonus,deduction);
    boolean locked = isLocked(employeeId, month);
    Map<String,Object> detail = new LinkedHashMap<>();
    detail.put("employeeId", employeeId); detail.put("month", month.toString()); detail.put("schemeId", scheme.get("id")); detail.put("schemeVersion", scheme.get("version")); detail.put("templateId",scheme.get("template_id"));detail.put("templateName",scheme.get("template_name"));detail.put("locked", locked);
    detail.put("components", components); detail.put("bonus", bonus); detail.put("deduction", deduction); detail.put("finalScore", finalScore); detail.put("missingItems", missing);
    String snapshot = toJson(Map.of("schemeId", scheme.get("id"), "schemeVersion", scheme.get("version"), "components", components));
    return new MonthlyCalculation(number(scheme.get("id")).longValue(), detail, effectiveScores, bonus, deduction, finalScore, missing, snapshot);
  }

  private Map<String,List<Map<String,Object>>> manualScores(Long employeeId, LocalDate month) {
    Map<String,List<Map<String,Object>>> result = new HashMap<>();
    for (var row : db.queryForList("select m.*,u.display_name evaluator_name from monthly_evaluation m join sys_user u on u.id=m.evaluator_user_id where m.employee_id=? and m.period_month=? order by m.submitted_at", employeeId, month))
      result.computeIfAbsent(String.valueOf(row.get("evaluator_type")),ignored->new ArrayList<>()).add(row);
    return result;
  }

  private SourceAggregate examAggregate(Long employeeId,LocalDate month,Map<String,Object> scheme){
    var rows=db.queryForList("""
      select p.id source_id,p.name source_name,
        coalesce((select max(a.total_score*100/nullif(coalesce(
          (select sum(aq.score) from exam_attempt_question aq where aq.attempt_id=a.id),
          (select sum(pq.score) from exam_paper_question pq where pq.paper_id=p.paper_id)),0))
          from exam_attempt a where a.plan_id=p.id and a.employee_id=ea.employee_id and a.published=true),
          case when p.ends_at<now() and not exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id) then 0 else null end) score_percent
      from exam_assignment ea join exam_plan p on p.id=ea.plan_id
      where ea.employee_id=? and p.status='PUBLISHED' and p.score_month=?
      order by p.starts_at,p.id
      """,employeeId,month);
    return automaticAggregate(rows,scheme,"EXAM");
  }

  private SourceAggregate taskAggregate(Long employeeId,LocalDate start,LocalDate end,Map<String,Object> scheme){
    var rows=db.queryForList("""
      select t.id source_id,t.title source_name,
        case when a.status in ('APPROVED','OVERDUE') then coalesce(a.final_score,0) else null end score_percent,
        a.status source_status
      from task_assignment a join challenge_task t on t.id=a.task_id
      where a.employee_id=? and date(t.deadline) between ? and ?
      order by t.deadline,t.id
      """,employeeId,start,end);
    return automaticAggregate(rows,scheme,"TASK");
  }

  private SourceAggregate automaticAggregate(List<Map<String,Object>> rows,Map<String,Object> scheme,String component){
    Map<Long,BigDecimal> configured=new LinkedHashMap<>();
    for(var row:db.queryForList("select source_id,weight from score_scheme_source_weight where scheme_id=? and component_type=?",scheme.get("id"),component))
      configured.put(number(row.get("source_id")).longValue(),decimal(row,"weight"));
    var sources=rows.stream().map(row->new EvaluationRules.SourceScore(number(row.get("source_id")).longValue(),decimal(row,"score_percent"))).toList();
    var weighted=EvaluationRules.allocateSourceWeights(sources,configured);
    Map<Long,EvaluationRules.WeightedSource> byId=new LinkedHashMap<>();
    weighted.forEach(item->byId.put(item.sourceId(),item));
    var items=new ArrayList<Map<String,Object>>();
    for(var row:rows){
      Long id=number(row.get("source_id")).longValue();var value=byId.get(id);var item=new LinkedHashMap<String,Object>();
      item.put("sourceId",id);item.put("name",row.get("source_name"));item.put("scorePercent",value.scorePercent());item.put("weight",value.weight().setScale(2,RoundingMode.HALF_UP));
      item.put("contribution",value.contribution()==null?null:value.contribution().setScale(2,RoundingMode.HALF_UP));
      item.put("status",value.weight().compareTo(BigDecimal.ZERO)==0?"IGNORED":value.scorePercent()==null?"PENDING":"READY");items.add(item);
    }
    BigDecimal percent=EvaluationRules.weightedSourceScore(weighted);
    return new SourceAggregate(scalePercent(percent,maxScore(scheme,component)),items);
  }

  private ManualAggregate mentorAggregate(Long employeeId,List<Map<String,Object>> rows){
    Map<String,Object> employee=db.queryForMap("""
      select e.mentor_user_id,e.skill_mentor_user_id,m.display_name mentor_name,sm.display_name skill_mentor_name
      from employee e left join sys_user m on m.id=e.mentor_user_id left join sys_user sm on sm.id=e.skill_mentor_user_id where e.id=?
      """,employeeId);
    Map<Long,String> expected=new LinkedHashMap<>();
    if(employee.get("mentor_user_id")!=null)expected.put(number(employee.get("mentor_user_id")).longValue(),Objects.toString(employee.get("mentor_name"),"导师"));
    if(employee.get("skill_mentor_user_id")!=null)expected.put(number(employee.get("skill_mentor_user_id")).longValue(),Objects.toString(employee.get("skill_mentor_name"),"技能导师"));
    Map<Long,Map<String,Object>> submittedRows=new LinkedHashMap<>();
    for(var row:rows)submittedRows.put(number(row.get("evaluator_user_id")).longValue(),row);
    var entries=new ArrayList<Map<String,Object>>();var submittedScores=new ArrayList<BigDecimal>();
    for(var expectedEntry:expected.entrySet()){
      var row=submittedRows.get(expectedEntry.getKey());var item=new LinkedHashMap<String,Object>();
      item.put("evaluatorId",expectedEntry.getKey());item.put("evaluatorName",expectedEntry.getValue());item.put("score",row==null?null:decimal(row,"score"));item.put("comment",row==null?null:row.get("comment"));item.put("submittedAt",row==null?null:row.get("submitted_at"));item.put("status",row==null?"PENDING":"SUBMITTED");
      item.put("canEvaluate","MENTOR".equals(SecurityUtils.current().role())&&SecurityUtils.current().id().equals(expectedEntry.getKey()));entries.add(item);if(row!=null)submittedScores.add(decimal(row,"score"));
    }
    BigDecimal partial=average(submittedScores);BigDecimal score=!expected.isEmpty()&&submittedScores.size()==expected.size()?partial:null;
    return new ManualAggregate(score,partial,entries,expected.size(),submittedScores.size());
  }

  private ManualAggregate singleManualAggregate(List<Map<String,Object>> rows){
    Map<String,Object> row=rows.isEmpty()?null:rows.get(rows.size()-1);var entries=new ArrayList<Map<String,Object>>();
    if(row!=null){var item=manualEntry(row);item.put("canEvaluate","TRAINING_ADMIN".equals(SecurityUtils.current().role()));entries.add(item);}
    return new ManualAggregate(row==null?null:decimal(row,"score"),row==null?null:decimal(row,"score"),entries,1,row==null?0:1);
  }

  private StationAggregate stationAggregate(Long employeeId,YearMonth month,Map<String,Object> scheme,List<Map<String,Object>> rows){
    List<StationPeriod> periods=stationPeriods(employeeId,month);Map<Long,BigDecimal> weights=new LinkedHashMap<>();
    var manualWeights=db.queryForList("select station_id,weight from monthly_station_weight where employee_id=? and period_month=?",employeeId,month.atDay(1));
    String mode=manualWeights.isEmpty()?Objects.toString(scheme.get("station_aggregation_mode"),"AUTO_BY_DAYS"):"MANUAL";
    if(!manualWeights.isEmpty())for(var row:manualWeights)weights.put(number(row.get("station_id")).longValue(),decimal(row,"weight"));
    else if("PRIMARY_STATION".equals(mode)&&!periods.isEmpty()){
      StationPeriod primary=periods.stream().max(Comparator.comparingLong(StationPeriod::days)).orElseThrow();for(var period:periods)weights.put(period.stationId(),period.stationId().equals(primary.stationId())?new BigDecimal("100"):BigDecimal.ZERO);
    }else{
      long totalDays=periods.stream().mapToLong(StationPeriod::days).sum();for(var period:periods)weights.put(period.stationId(),totalDays==0?BigDecimal.ZERO:new BigDecimal(period.days()).multiply(new BigDecimal("100")).divide(new BigDecimal(totalDays),8,RoundingMode.HALF_UP));
    }
    Map<Long,List<Map<String,Object>>> byStation=new LinkedHashMap<>();for(var row:rows)byStation.computeIfAbsent(number(row.get("scope_id")).longValue(),ignored->new ArrayList<>()).add(row);
    var entries=new ArrayList<Map<String,Object>>();BigDecimal total=BigDecimal.ZERO;int required=0,submitted=0;boolean complete=true;
    for(var period:periods){BigDecimal weight=weights.getOrDefault(period.stationId(),BigDecimal.ZERO);List<Map<String,Object>> ratings=byStation.getOrDefault(period.stationId(),List.of());BigDecimal stationScore=average(ratings.stream().map(x->decimal(x,"score")).toList());if(weight.compareTo(BigDecimal.ZERO)>0){required++;if(stationScore==null)complete=false;else{submitted++;total=total.add(stationScore.multiply(weight).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP));}}
      var item=new LinkedHashMap<String,Object>();item.put("stationId",period.stationId());item.put("stationName",period.name());item.put("days",period.days());item.put("weight",weight.setScale(2,RoundingMode.HALF_UP));item.put("score",stationScore);item.put("status",weight.compareTo(BigDecimal.ZERO)==0?"IGNORED":stationScore==null?"PENDING":"SUBMITTED");item.put("evaluations",ratings.stream().map(this::manualEntry).toList());
      Integer managed=db.queryForObject("select count(*) from station_manager_scope where station_id=? and user_id=?",Integer.class,period.stationId(),SecurityUtils.current().id());item.put("canEvaluate","STATION_MANAGER".equals(SecurityUtils.current().role())&&managed!=null&&managed>0);entries.add(item);
    }
    return new StationAggregate(complete&&required>0?total.setScale(2,RoundingMode.HALF_UP):null,entries,mode,required,submitted);
  }

  List<StationPeriod> stationPeriods(Long employeeId,YearMonth month){
    Map<String,Object> employee=db.queryForMap("select station_id,onboard_date from employee where id=?",employeeId);LocalDate start=month.atDay(1),endExclusive=month.plusMonths(1).atDay(1);Object onboard=employee.get("onboard_date");if(onboard!=null&&toDate(onboard).isAfter(start))start=toDate(onboard);if(!start.isBefore(endExclusive))return List.of();
    var changes=db.queryForList("select current_station_id,requested_station_id,reviewed_at from station_change_request where employee_id=? and status='APPROVED' and reviewed_at is not null order by reviewed_at,id",employeeId);
    Long stationAtStart=null;for(var change:changes){LocalDate day=toDate(change.get("reviewed_at"));if(!day.isBefore(start)){stationAtStart=change.get("current_station_id")==null?null:number(change.get("current_station_id")).longValue();break;}stationAtStart=change.get("requested_station_id")==null?null:number(change.get("requested_station_id")).longValue();}
    if(stationAtStart==null&&employee.get("station_id")!=null)stationAtStart=number(employee.get("station_id")).longValue();
    Map<Long,Long> days=new LinkedHashMap<>();LocalDate cursor=start;Long current=stationAtStart;
    for(var change:changes){LocalDate day=toDate(change.get("reviewed_at"));if(day.isBefore(start)||!day.isBefore(endExclusive))continue;if(day.isAfter(cursor)&&current!=null)days.merge(current,ChronoUnit.DAYS.between(cursor,day),Long::sum);cursor=day;current=change.get("requested_station_id")==null?null:number(change.get("requested_station_id")).longValue();}
    if(cursor.isBefore(endExclusive)&&current!=null)days.merge(current,ChronoUnit.DAYS.between(cursor,endExclusive),Long::sum);
    var result=new ArrayList<StationPeriod>();for(var entry:days.entrySet()){String name=db.queryForObject("select name from service_station where id=?",String.class,entry.getKey());result.add(new StationPeriod(entry.getKey(),name,entry.getValue()));}return result;
  }

  @Transactional
  public void replaceStationWeights(Long employeeId,YearMonth month,Map<Long,BigDecimal> weights,Long operatorId){
    Set<Long> available=new LinkedHashSet<>();stationPeriods(employeeId,month).forEach(x->available.add(x.stationId()));
    if(weights.isEmpty()||!available.equals(weights.keySet()))throw new BusinessException(400,"手动站点权重必须覆盖该员工当月全部在站记录");
    if(weights.values().stream().anyMatch(value->value==null||value.compareTo(BigDecimal.ZERO)<0))throw new BusinessException(400,"手动站点权重不能小于0");BigDecimal total=weights.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);if(total.compareTo(new BigDecimal("100"))!=0)throw new BusinessException(400,"手动站点权重之和必须为100%");
    db.update("delete from monthly_station_weight where employee_id=? and period_month=?",employeeId,month.atDay(1));for(var entry:weights.entrySet())db.update("insert into monthly_station_weight(employee_id,period_month,station_id,weight,updated_by) values(?,?,?,?,?)",employeeId,month.atDay(1),entry.getKey(),entry.getValue(),operatorId);refreshDraftIfPresent(employeeId,month);
  }

  @Transactional public void clearStationWeights(Long employeeId,YearMonth month){db.update("delete from monthly_station_weight where employee_id=? and period_month=?",employeeId,month.atDay(1));refreshDraftIfPresent(employeeId,month);}

  public boolean stationApplies(Long employeeId,YearMonth month,Long stationId){return stationPeriods(employeeId,month).stream().anyMatch(x->x.stationId().equals(stationId));}

  private Map<String,Object> manualEntry(Map<String,Object> row){var item=new LinkedHashMap<String,Object>();item.put("evaluatorId",row.get("evaluator_user_id"));item.put("evaluatorName",row.get("evaluator_name"));item.put("score",row.get("score"));item.put("comment",row.get("comment"));item.put("submittedAt",row.get("submitted_at"));return item;}
  private BigDecimal average(List<BigDecimal> values){if(values.isEmpty())return null;return values.stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(values.size()),2,RoundingMode.HALF_UP);}
  private LocalDate toDate(Object value){if(value instanceof LocalDate d)return d;if(value instanceof LocalDateTime d)return d.toLocalDate();if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().toLocalDate();if(value instanceof java.sql.Date d)return d.toLocalDate();return LocalDate.parse(String.valueOf(value).substring(0,10));}

  private Map<String,Map<String,Object>> overrideScores(Long employeeId, LocalDate month) {
    Map<String,Map<String,Object>> result = new HashMap<>();
    for (var row : db.queryForList("select o.*,u.display_name operator_name from score_component_override o join sys_user u on u.id=o.created_by where o.employee_id=? and o.period_month=?", employeeId, month)) result.put(String.valueOf(row.get("component_type")), row);
    return result;
  }

  private Integer nextOrDraftVersion(Long employeeId, String type, String key) {
    return db.queryForObject("select coalesce(max(version),0)+1 from score_summary where employee_id=? and summary_type=? and period_key=?", Integer.class, employeeId, type, key);
  }

  private BigDecimal sumAdjustment(Long employeeId, LocalDate month, String type) { BigDecimal value = db.queryForObject("select coalesce(sum(points),0) from score_adjustment where employee_id=? and period_month=? and adjustment_type=?", BigDecimal.class, employeeId, month, type); return value == null ? BigDecimal.ZERO : value; }
  private BigDecimal maxScore(Map<String,Object> scheme,String component){BigDecimal value=decimal(scheme,component.toLowerCase()+"_max_score");return value==null?new BigDecimal("100"):value;}
  private BigDecimal scalePercent(BigDecimal percent,BigDecimal fullScore){return percent==null?null:percent.multiply(fullScore).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);}
  static BigDecimal decimal(Map<String,Object> row, String key) { Object value = row.get(key); return value == null ? null : new BigDecimal(String.valueOf(value)); }
  private static Number number(Object value) { return (Number)value; }
  private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0 || "true".equalsIgnoreCase(String.valueOf(value)); }
  private String toJson(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }

  record StationPeriod(Long stationId,String name,long days) {}
  private record SourceAggregate(BigDecimal score,List<Map<String,Object>> items) {}
  private record ManualAggregate(BigDecimal score,BigDecimal partialScore,List<Map<String,Object>> entries,int required,int submitted) {}
  private record StationAggregate(BigDecimal score,List<Map<String,Object>> entries,String mode,int required,int submitted) {}
  private record MonthlyCalculation(Long schemeId, Map<String,Object> detail, Map<String,BigDecimal> scores, BigDecimal bonus, BigDecimal deduction, BigDecimal finalScore, List<String> missing, String snapshot) {}
}
