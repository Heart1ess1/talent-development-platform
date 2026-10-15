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
  public static final List<String> COMPONENTS = List.of("EXAM", "TASK", "MENTOR", "STATION", "TRAINING", "DEFENSE");
  private static final Set<String> AUTO_COMPONENTS = Set.of("EXAM", "TASK", "DEFENSE");
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
  public int generateQuarter(int year,int quarter){
    if(quarter<1||quarter>4)throw new BusinessException(400,"季度必须为1到4");
    String key=year+"-Q"+quarter;int count=0;
    for(var employee:db.queryForList("select id from employee where status='ACTIVE' and batch_id is not null")){
      Long id=number(employee.get("id")).longValue();
      try{writeQuarter(id,key,quarterSchemeFor(id,key),null);count++;}catch(BusinessException ignored){/* 缺方案保留待配置 */}
    }return count;
  }

  public static YearMonth quarterEnd(String key){
    if(key==null||!key.matches("[0-9]{4}-Q[1-4]"))throw new BusinessException(400,"季度格式应为2026-Q1至Q4");
    return YearMonth.of(Integer.parseInt(key.substring(0,4)),Integer.parseInt(key.substring(6))*3);
  }

  public Map<String,Object> quarterSchemeFor(Long employee,String key){
    var old=db.queryForList("select scheme_id from score_summary where employee_id=? and summary_type='QUARTER' and period_key=? and status='PUBLISHED' and scheme_id is not null",employee,key);
    return old.isEmpty()?schemeFor(employee,quarterEnd(key)):schemeById(number(old.get(0).get("scheme_id")).longValue());
  }

  public record QuarterCalculation(BigDecimal finalScore,BigDecimal defenseScore,List<String> missing,String snapshot){}

  public QuarterCalculation calculateQuarter(Long employee,String key,Map<String,Object> scheme,Long group,Map<String,Map<String,Object>> projections){
    YearMonth end=quarterEnd(key);var months=new ArrayList<Map<String,Object>>();var missing=new ArrayList<String>();BigDecimal total=BigDecimal.ZERO;
    boolean defenseEnabled="QUARTER".equals(scheme.get("defense_scope"))&&bool(scheme.get("defense_enabled"));
    BigDecimal defenseWeight=defenseEnabled?decimal(scheme,"defense_weight"):BigDecimal.ZERO;
    BigDecimal monthlyWeight=new BigDecimal("100").subtract(defenseWeight);
    for(int i=0;i<3;i++){
      String month=end.minusMonths(2-i).toString();Map<String,Object> projected=projections==null?null:projections.get(employee+":"+month);
      var rows=projected==null?db.queryForList("select id,final_score,version from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and (status='PUBLISHED' or (status='DRAFT' and revision_group_id=?)) order by (status='DRAFT' and revision_group_id=?) desc,version desc limit 1",employee,month,group,group):List.<Map<String,Object>>of();
      BigDecimal score=projected!=null?(projected.get("newScore")==null?null:new BigDecimal(projected.get("newScore").toString())):rows.isEmpty()?null:decimal(rows.get(0),"final_score");
      BigDecimal weight=decimal(scheme,"quarter_month"+(i+1)+"_weight");BigDecimal contribution=score==null?null:score.multiply(weight).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP);
      if(score==null&&monthlyWeight.signum()>0)missing.add(month);if(contribution!=null)total=total.add(contribution);
      var item=new LinkedHashMap<String,Object>();item.put("month",month);item.put("score",score);item.put("weight",weight);item.put("contribution",contribution==null?null:contribution.setScale(2,RoundingMode.HALF_UP));item.put("monthlySummaryId",rows.isEmpty()?null:rows.get(0).get("id"));months.add(item);
    }
    BigDecimal monthlyContribution=total.multiply(monthlyWeight).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP);
    var defense=new LinkedHashMap<String,Object>();defense.put("enabled",defenseEnabled);defense.put("weight",defenseWeight);
    BigDecimal defenseScore=null,defenseContribution=BigDecimal.ZERO;
    if(defenseEnabled){
      var grades=db.queryForList("select d.id,d.score,d.full_score,d.version,d.session_id,s.stage_no from defense_result d join defense_session s on s.id=d.session_id join employee e on e.id=d.employee_id where d.employee_id=? and s.talent_batch_id=e.batch_id and s.period_key=? and d.status='PUBLISHED'",employee,key);
      if(grades.isEmpty())missing.add("DEFENSE");else{
        var grade=grades.get(0);defenseScore=decimal(grade,"score");BigDecimal normalized=defenseScore.multiply(new BigDecimal("100")).divide(decimal(grade,"full_score"),8,RoundingMode.HALF_UP);
        defenseContribution=normalized.multiply(defenseWeight).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP);
        defense.put("score",defenseScore);defense.put("fullScore",grade.get("full_score"));defense.put("normalizedScore",normalized.setScale(2,RoundingMode.HALF_UP));defense.put("sourceId",grade.get("id"));defense.put("version",grade.get("version"));defense.put("sessionId",grade.get("session_id"));defense.put("stageNo",grade.get("stage_no"));defense.put("contribution",defenseContribution.setScale(2,RoundingMode.HALF_UP));
      }
    }
    var snapshot=new LinkedHashMap<String,Object>();snapshot.put("schemeId",scheme.get("id"));snapshot.put("months",months);snapshot.put("monthlyBaseScore",total.setScale(2,RoundingMode.HALF_UP));snapshot.put("monthlyWeight",monthlyWeight);snapshot.put("monthlyContribution",monthlyContribution.setScale(2,RoundingMode.HALF_UP));snapshot.put("defense",defense);
    BigDecimal finalScore=missing.isEmpty()?monthlyContribution.add(defenseContribution).max(BigDecimal.ZERO).min(new BigDecimal("100")).setScale(2,RoundingMode.HALF_UP):null;
    return new QuarterCalculation(finalScore,defenseScore,missing,toJson(snapshot));
  }

  public Long writeQuarter(Long employee,String key,Map<String,Object> scheme,Long group){
    var value=calculateQuarter(employee,key,scheme,group,null);
    var draft=db.queryForList("select id from score_summary where employee_id=? and summary_type='QUARTER' and period_key=? and status='DRAFT' order by version desc limit 1",employee,key);Long id;
    if(draft.isEmpty()){
      int version=nextOrDraftVersion(employee,"QUARTER",key);
      db.update("insert into score_summary(employee_id,summary_type,period_key,version,scheme_id,revision_group_id) values(?,'QUARTER',?,?,?,?)",employee,key,version,scheme.get("id"),group);id=db.queryForObject("select last_insert_id()",Long.class);
    }else id=number(draft.get(0).get("id")).longValue();
    updateQuarter(id,scheme,value,group);return id;
  }
  private void updateQuarter(Long id,Map<String,Object> scheme,QuarterCalculation value,Long group){
    db.update("update score_summary set scheme_id=?,defense_score=?,final_score=coalesce(revision_override_score,?),missing_items=?,quarter_snapshot=cast(? as json),revision_group_id=?,generated_at=now() where id=? and status='DRAFT'",scheme.get("id"),value.defenseScore(),value.finalScore(),String.join(",",value.missing()),value.snapshot(),group,id);
  }
  @Transactional public void refreshQuarterDraft(Long id){
    var row=db.queryForMap("select * from score_summary where id=? and summary_type='QUARTER' and status='DRAFT'",id);var scheme=schemeById(number(row.get("scheme_id")).longValue());Long group=row.get("revision_group_id")==null?null:number(row.get("revision_group_id")).longValue();
    updateQuarter(id,scheme,calculateQuarter(number(row.get("employee_id")).longValue(),row.get("period_key").toString(),scheme,group,null),group);
  }
  @Transactional public void refreshQuarterIfPresent(Long employee,String key){for(Long id:db.queryForList("select id from score_summary where employee_id=? and summary_type='QUARTER' and period_key=? and status='DRAFT'",Long.class,employee,key))refreshQuarterDraft(id);}

  public Map<String,Object> schemeFor(Long employeeId, YearMonth month) {
    var published=db.queryForList("select scheme_id from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED' and scheme_id is not null order by version desc limit 1",employeeId,month.toString());
    if(!published.isEmpty())return schemeById(((Number)published.get(0).get("scheme_id")).longValue());
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
    db.update("insert into score_summary(employee_id,summary_type,period_key,version,scheme_id,exam_score,task_score,mentor_score,station_score,training_score,defense_score,bonus,deduction,component_snapshot,final_score,missing_items,reopen_reason) values(?,'MONTH',?,?,?,?,?,?,?,?,?,?,?,cast(? as json),?,?,?)",
      employeeId, month.toString(), version, scheme.get("id"), scores.get("EXAM"), scores.get("TASK"), scores.get("MENTOR"), scores.get("STATION"), scores.get("TRAINING"), scores.get("DEFENSE"), calculation.bonus(), calculation.deduction(), calculation.snapshot(), calculation.finalScore(), String.join(",", calculation.missing()), reopenReason);
    return db.queryForObject("select last_insert_id()", Long.class);
  }

  private void updateMonthly(Long id, MonthlyCalculation c) {
    Map<String,BigDecimal> scores = c.scores();
    db.update("update score_summary set scheme_id=?,exam_score=?,task_score=?,mentor_score=?,station_score=?,training_score=?,defense_score=?,bonus=?,deduction=?,component_snapshot=cast(? as json),final_score=?,missing_items=?,generated_at=now() where id=? and status='DRAFT'",
      c.schemeId(), scores.get("EXAM"), scores.get("TASK"), scores.get("MENTOR"), scores.get("STATION"), scores.get("TRAINING"), scores.get("DEFENSE"), c.bonus(), c.deduction(), c.snapshot(), c.finalScore(), String.join(",", c.missing()), id);
  }

  private MonthlyCalculation calculate(Long employeeId, YearMonth month, Map<String,Object> scheme) {
    return calculate(employeeId,month,scheme,null);
  }

  /** Read-only projection of the current sources after moving one task/exam to a new month. */
  public Map<String,Object> previewMonthly(Long employeeId,YearMonth month,String type,Long resource,LocalDateTime endsAt){
    return calculate(employeeId,month,schemeFor(employeeId,month),new SourceMove(type,resource,endsAt)).detail();
  }
  private record SourceMove(String type,Long resource,LocalDateTime endsAt){}

  private MonthlyCalculation calculate(Long employeeId, YearMonth month, Map<String,Object> scheme,SourceMove move) {
    LocalDate start = month.atDay(1), end = month.atEndOfMonth();
    SourceAggregate exam = examAggregate(employeeId,start,scheme,move);
    SourceAggregate task = taskAggregate(employeeId,start,end,scheme,move);
    var defenseRows=db.queryForList("select score,full_score from defense_result where employee_id=? and period_month=? and session_id is null and status='PUBLISHED'",employeeId,start);
    BigDecimal defense=defenseRows.isEmpty()?null:decimal(defenseRows.get(0),"score").multiply(maxScore(scheme,"DEFENSE")).divide(decimal(defenseRows.get(0),"full_score"),8,RoundingMode.HALF_UP);
    Map<String,List<Map<String,Object>>> manual = manualScores(employeeId, start);
    ManualAggregate mentor=mentorAggregate(employeeId,month,manual.getOrDefault("MENTOR",List.of()));
    StationAggregate station=stationAggregate(employeeId,month,scheme,manual.getOrDefault("STATION",List.of()));
    ManualAggregate training=trainingAggregate(employeeId,month,manual.getOrDefault("TRAINING",List.of()));
    Map<String,Map<String,Object>> overrides = overrideScores(employeeId, start);
    Map<String,BigDecimal> sourceScores = new LinkedHashMap<>();
    sourceScores.put("DEFENSE",defense); sourceScores.put("EXAM", exam.score()); sourceScores.put("TASK", task.score());
    sourceScores.put("MENTOR",mentor.score()); sourceScores.put("STATION",station.score()); sourceScores.put("TRAINING",training.score());

    Set<String> personalComponents=new HashSet<>();for(var opportunity:db.queryForList("select distinct t.component_type from submission_opportunity o join evaluation_rating_task t on t.id=o.resource_id where o.resource_type='EVALUATION' and o.participant_id=? and o.status='ACTIVE' and o.starts_at<=now() and o.ends_at>now() and t.employee_id=? and t.period_month=?",SecurityUtils.current().id(),employeeId,start))personalComponents.add(opportunity.get("component_type").toString());
    List<Map<String,Object>> components = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    List<EvaluationRules.WeightedScore> weightedScores = new ArrayList<>();
    Map<String,BigDecimal> effectiveScores = new LinkedHashMap<>();
    for (String code : COMPONENTS) {
      if("DEFENSE".equals(code)&&"QUARTER".equals(scheme.get("defense_scope")))continue;
      boolean enabled = bool(scheme.get(code.toLowerCase() + "_enabled"));
      BigDecimal weight = decimal(scheme, code.toLowerCase() + "_weight");
      BigDecimal fullScore = maxScore(scheme,code);
      BigDecimal source = sourceScores.get(code);
      Map<String,Object> override = overrides.get(code);
      BigDecimal overrideScore = override == null ? null : decimal(override, "override_score");
      BigDecimal effective = overrideScore != null ? overrideScore : source;
      BigDecimal normalized = effective == null ? null : effective.multiply(new BigDecimal("100")).divide(fullScore,8,RoundingMode.HALF_UP);
      BigDecimal contribution = enabled && normalized != null ? normalized.multiply(weight).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP) : null;
      if (enabled && effective == null) missing.add(code);
      weightedScores.add(new EvaluationRules.WeightedScore(enabled,weight,normalized));
      effectiveScores.put(code, effective);
      Map<String,Object> item = new LinkedHashMap<>();
      item.put("personalOpportunity",personalComponents.contains(code)); item.put("code", code); item.put("enabled", enabled); item.put("weight", weight); item.put("fullScore", fullScore); item.put("sourceType", AUTO_COMPONENTS.contains(code) ? "AUTO" : "MANUAL");
      item.put("sourceScore", source); item.put("overrideScore", overrideScore); item.put("effectiveScore", effective); item.put("weightedScore", contribution == null ? null : contribution.setScale(2, RoundingMode.HALF_UP));
      item.put("status", !enabled ? "DISABLED" : effective == null ? "PENDING" : overrideScore != null ? "OVERRIDDEN" : AUTO_COMPONENTS.contains(code) ? "AUTOMATIC" : "SUBMITTED");
      if("EXAM".equals(code))item.put("breakdown",exam.items());
      if("TASK".equals(code))item.put("breakdown",task.items());
      if("MENTOR".equals(code)){item.put("breakdown",mentor.entries());item.put("submittedCount",mentor.submitted());item.put("requiredCount",mentor.required());item.put("partialScore",mentor.partialScore());}
      if("STATION".equals(code)){item.put("breakdown",station.entries());item.put("aggregationMode",station.mode());item.put("submittedCount",station.submitted());item.put("requiredCount",station.required());}
      if("TRAINING".equals(code)){item.put("breakdown",training.entries());item.put("submittedCount",training.submitted());item.put("requiredCount",training.required());item.put("partialScore",training.partialScore());item.put("canEvaluate",training.entries().stream().anyMatch(x->Boolean.TRUE.equals(x.get("canEvaluate"))));}
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
    boolean pendingRevision=db.queryForObject("select count(*) from evaluation_revision_member m join evaluation_revision_group g on g.id=m.group_id where m.employee_id=? and m.period_key=? and m.summary_type='MONTH' and g.status='DRAFT'",Integer.class,employeeId,month.toString())>0;
    boolean locked = isLocked(employeeId, month) && !pendingRevision;
    if(pendingRevision){
      Set<String> allowed=new HashSet<>();for(var permit:db.queryForList("select p.component_type,p.scope_id from evaluation_revision_permission p join evaluation_revision_group g on g.id=p.group_id where p.employee_id=? and p.period_month=? and p.reviewer_id=? and g.status='DRAFT'",employeeId,start,SecurityUtils.current().id()))allowed.add(permit.get("component_type")+":"+permit.get("scope_id"));
      for(var component:components){String code=component.get("code").toString();if(!List.of("MENTOR","STATION","TRAINING").contains(code))continue;
        if(!allowed.contains(code+":0"))component.put("canEvaluate",false);
        Object breakdown=component.get("breakdown");if(breakdown instanceof List<?> entries)for(Object raw:entries)if(raw instanceof Map<?,?>){@SuppressWarnings("unchecked") var entry=(Map<String,Object>)raw;long scope="STATION".equals(code)?((Number)entry.get("stationId")).longValue():0;if(!allowed.contains(code+":"+scope))entry.put("canEvaluate",false);}
      }
    }
    Map<String,Object> detail = new LinkedHashMap<>();
    detail.put("employeeId", employeeId); detail.put("month", month.toString()); detail.put("schemeId", scheme.get("id")); detail.put("schemeVersion", scheme.get("version")); detail.put("templateId",scheme.get("template_id"));detail.put("templateName",scheme.get("template_name"));detail.put("locked", locked);
    detail.put("pendingRevision",pendingRevision); detail.put("components", components); detail.put("bonus", bonus); detail.put("deduction", deduction); detail.put("finalScore", finalScore); detail.put("missingItems", missing);
    String snapshot = toJson(Map.of("schemeId", scheme.get("id"), "schemeVersion", scheme.get("version"), "components", components));
    return new MonthlyCalculation(number(scheme.get("id")).longValue(), detail, effectiveScores, bonus, deduction, finalScore, missing, snapshot);
  }

  private Map<String,List<Map<String,Object>>> manualScores(Long employeeId, LocalDate month) {
    Map<String,List<Map<String,Object>>> result = new HashMap<>();
    for (var row : db.queryForList("select m.*,u.display_name evaluator_name from monthly_evaluation m join sys_user u on u.id=m.evaluator_user_id where m.employee_id=? and m.period_month=? order by m.submitted_at", employeeId, month))
      result.computeIfAbsent(String.valueOf(row.get("evaluator_type")),ignored->new ArrayList<>()).add(row);
    return result;
  }

  private SourceAggregate examAggregate(Long employeeId,LocalDate month,Map<String,Object> scheme,SourceMove move){
    Long moved=move!=null&&"EXAM".equals(move.type())?move.resource():null;
    LocalDate target=moved==null?null:YearMonth.from(move.endsAt()).atDay(1);
    var rows=db.queryForList("""
      select p.id source_id,p.name source_name,
        coalesce((select max(a.total_score*100/nullif(coalesce(
          (select sum(aq.score) from exam_attempt_question aq where aq.attempt_id=a.id),
          (select sum(pq.score) from exam_paper_question pq where pq.paper_id=p.paper_id)),0))
          from exam_attempt a where a.plan_id=p.id and a.employee_id=ea.employee_id and a.published=true),
          case when (case when p.id=? then ? else coalesce(ea.window_ends_at,p.ends_at) end)<now() and not exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id) then 0 else null end) score_percent
      from exam_assignment ea join exam_plan p on p.id=ea.plan_id
      where ea.employee_id=? and p.status='PUBLISHED' and (case when p.id=? then ? else coalesce(ea.score_month,p.score_month) end)=?
      order by p.starts_at,p.id
      """,moved,moved==null?null:move.endsAt(),employeeId,moved,target,month);
    return automaticAggregate(rows,scheme,"EXAM");
  }

  private SourceAggregate taskAggregate(Long employeeId,LocalDate start,LocalDate end,Map<String,Object> scheme,SourceMove move){
    Long moved=move!=null&&"TASK".equals(move.type())?move.resource():null;
    LocalDate target=moved==null?null:YearMonth.from(move.endsAt()).atDay(1);
    var rows=db.queryForList("""
      select t.id source_id,t.title source_name,
        coalesce((select s.score from task_submission s where s.assignment_id=a.id and s.status='APPROVED' order by s.submission_version desc limit 1),case when a.status='OVERDUE' and (? is null or t.id<>?) then 0 else null end) score_percent,
        a.status source_status
      from task_assignment a join challenge_task t on t.id=a.task_id
      where a.employee_id=? and (case when t.id=? then ? else coalesce(a.score_month,date(t.deadline)) end) between ? and ?
      order by t.deadline,t.id
      """,moved,moved,employeeId,moved,target,start,end);
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

  private ManualAggregate mentorAggregate(Long employeeId,YearMonth month,List<Map<String,Object>> rows){
    Map<String,Object> employee=db.queryForMap("""
      select e.mentor_user_id,e.skill_mentor_user_id,m.display_name mentor_name,sm.display_name skill_mentor_name
      from employee e left join sys_user m on m.id=e.mentor_user_id left join sys_user sm on sm.id=e.skill_mentor_user_id where e.id=?
      """,employeeId);
    ReviewerPlan plan=reviewerPlan(employeeId,month,"MENTOR",0);Map<Long,String> expected=new LinkedHashMap<>(plan.reviewers());
    if(!plan.explicit()){
      if(employee.get("mentor_user_id")!=null)expected.put(number(employee.get("mentor_user_id")).longValue(),Objects.toString(employee.get("mentor_name"),"导师"));
      if(employee.get("skill_mentor_user_id")!=null)expected.put(number(employee.get("skill_mentor_user_id")).longValue(),Objects.toString(employee.get("skill_mentor_name"),"技能导师"));
    }
    Map<Long,Map<String,Object>> submittedRows=new LinkedHashMap<>();
    for(var row:rows)submittedRows.put(number(row.get("evaluator_user_id")).longValue(),row);
    var entries=new ArrayList<Map<String,Object>>();var submittedScores=new ArrayList<BigDecimal>();
    for(var expectedEntry:expected.entrySet()){
      var row=submittedRows.get(expectedEntry.getKey());var item=new LinkedHashMap<String,Object>();
      item.put("evaluatorId",expectedEntry.getKey());item.put("evaluatorName",expectedEntry.getValue());item.put("score",row==null?null:decimal(row,"score"));item.put("comment",row==null?null:row.get("comment"));item.put("submittedAt",row==null?null:row.get("submitted_at"));item.put("status",row==null?"PENDING":"SUBMITTED");
      item.put("canEvaluate","MENTOR".equals(SecurityUtils.current().role())&&SecurityUtils.current().id().equals(expectedEntry.getKey()));entries.add(item);if(row!=null)submittedScores.add(decimal(row,"score"));
    }
    BigDecimal partial=EvaluationRules.average(submittedScores);BigDecimal score=EvaluationRules.completedAverage(submittedScores,expected.size());
    return new ManualAggregate(score,partial,entries,expected.size(),submittedScores.size());
  }

  private ManualAggregate trainingAggregate(Long employeeId,YearMonth month,List<Map<String,Object>> rows){
    ReviewerPlan plan=reviewerPlan(employeeId,month,"TRAINING",0);
    if(!plan.explicit()){
      Map<String,Object> row=rows.isEmpty()?null:rows.get(rows.size()-1);var entries=new ArrayList<Map<String,Object>>();
      if(row!=null){var item=manualEntry(row);item.put("canEvaluate","TRAINING_ADMIN".equals(SecurityUtils.current().role()));entries.add(item);}
      return new ManualAggregate(row==null?null:decimal(row,"score"),row==null?null:decimal(row,"score"),entries,1,row==null?0:1);
    }
    return assignedAggregate(plan,rows,"TRAINING_ADMIN");
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
    for(var period:periods){BigDecimal weight=weights.getOrDefault(period.stationId(),BigDecimal.ZERO);List<Map<String,Object>> ratings=byStation.getOrDefault(period.stationId(),List.of());ReviewerPlan plan=reviewerPlan(employeeId,month,"STATION",period.stationId());BigDecimal stationScore;List<Map<String,Object>> evaluationEntries;boolean canEvaluate;
      if(plan.explicit()){ManualAggregate assigned=assignedAggregate(plan,ratings,"STATION_MANAGER");stationScore=assigned.score();evaluationEntries=assigned.entries();canEvaluate=evaluationEntries.stream().anyMatch(x->Boolean.TRUE.equals(x.get("canEvaluate")));}
      else{stationScore=EvaluationRules.average(ratings.stream().map(x->decimal(x,"score")).toList());evaluationEntries=ratings.stream().map(this::manualEntry).toList();Integer managed=db.queryForObject("select count(*) from station_manager_scope where station_id=? and user_id=?",Integer.class,period.stationId(),SecurityUtils.current().id());canEvaluate="STATION_MANAGER".equals(SecurityUtils.current().role())&&managed!=null&&managed>0;}
      if(weight.compareTo(BigDecimal.ZERO)>0){required++;if(stationScore==null)complete=false;else{submitted++;total=total.add(stationScore.multiply(weight).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP));}}
      var item=new LinkedHashMap<String,Object>();item.put("stationId",period.stationId());item.put("stationName",period.name());item.put("days",period.days());item.put("weight",weight.setScale(2,RoundingMode.HALF_UP));item.put("score",stationScore);item.put("status",weight.compareTo(BigDecimal.ZERO)==0?"IGNORED":stationScore==null?"PENDING":"SUBMITTED");item.put("evaluations",evaluationEntries);item.put("reviewerCount",evaluationEntries.size());item.put("submittedCount",evaluationEntries.stream().filter(x->x.get("score")!=null).count());item.put("canEvaluate",canEvaluate);entries.add(item);
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
  private ReviewerPlan reviewerPlan(Long employeeId,YearMonth month,String component,long scopeId){
    var tasks=db.queryForList("select id from evaluation_rating_task where employee_id=? and period_month=? and component_type=? and scope_id=? and status='ACTIVE'",employeeId,month.atDay(1),component,scopeId);
    if(tasks.isEmpty())return new ReviewerPlan(false,new LinkedHashMap<>());Long taskId=number(tasks.get(0).get("id")).longValue();Map<Long,String> reviewers=new LinkedHashMap<>();
    for(var row:db.queryForList("select u.id,u.display_name from evaluation_rating_reviewer r join sys_user u on u.id=r.reviewer_user_id where r.task_id=? and r.status='ACTIVE' order by u.display_name,u.id",taskId))reviewers.put(number(row.get("id")).longValue(),Objects.toString(row.get("display_name"),"评分人"));
    return new ReviewerPlan(true,reviewers);
  }
  private ManualAggregate assignedAggregate(ReviewerPlan plan,List<Map<String,Object>> rows,String role){
    Map<Long,Map<String,Object>> submittedRows=new LinkedHashMap<>();for(var row:rows)submittedRows.put(number(row.get("evaluator_user_id")).longValue(),row);
    var entries=new ArrayList<Map<String,Object>>();var scores=new ArrayList<BigDecimal>();for(var reviewer:plan.reviewers().entrySet()){Map<String,Object> row=submittedRows.get(reviewer.getKey());var item=new LinkedHashMap<String,Object>();item.put("evaluatorId",reviewer.getKey());item.put("evaluatorName",reviewer.getValue());item.put("score",row==null?null:decimal(row,"score"));item.put("comment",row==null?null:row.get("comment"));item.put("submittedAt",row==null?null:row.get("submitted_at"));item.put("status",row==null?"PENDING":"SUBMITTED");item.put("canEvaluate",role.equals(SecurityUtils.current().role())&&SecurityUtils.current().id().equals(reviewer.getKey()));entries.add(item);if(row!=null)scores.add(decimal(row,"score"));}
    BigDecimal partial=EvaluationRules.average(scores),score=EvaluationRules.completedAverage(scores,plan.reviewers().size());return new ManualAggregate(score,partial,entries,plan.reviewers().size(),scores.size());
  }
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
  private record ReviewerPlan(boolean explicit,Map<Long,String> reviewers) {}
  private record SourceAggregate(BigDecimal score,List<Map<String,Object>> items) {}
  private record ManualAggregate(BigDecimal score,BigDecimal partialScore,List<Map<String,Object>> entries,int required,int submitted) {}
  private record StationAggregate(BigDecimal score,List<Map<String,Object>> entries,String mode,int required,int submitted) {}
  private record MonthlyCalculation(Long schemeId, Map<String,Object> detail, Map<String,BigDecimal> scores, BigDecimal bonus, BigDecimal deduction, BigDecimal finalScore, List<String> missing, String snapshot) {}
}
