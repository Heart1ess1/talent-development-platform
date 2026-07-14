package com.talent.platform.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
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
    var rows = db.queryForList("select * from score_scheme where batch_id=? and status in ('PUBLISHED','RETIRED') and effective_month<=? order by effective_month desc,version desc limit 1", batchId, month.atDay(1));
    if (rows.isEmpty()) throw new BusinessException(400, "该月份没有已发布的评分方案");
    return rows.get(0);
  }

  public Map<String,Object> schemeById(Long id) { return db.queryForMap("select * from score_scheme where id=?", id); }

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
    BigDecimal exam = avg("select avg(total_score) from exam_attempt a join exam_plan p on p.id=a.plan_id where a.employee_id=? and a.published=true and p.score_month=?", employeeId, start);
    BigDecimal task = avg("select avg(a.final_score) from task_assignment a join challenge_task t on t.id=a.task_id where a.employee_id=? and a.status='APPROVED' and date(t.deadline) between ? and ?", employeeId, start, end);
    Map<String,Map<String,Object>> manual = manualScores(employeeId, start);
    Map<String,Map<String,Object>> overrides = overrideScores(employeeId, start);
    Map<String,BigDecimal> sourceScores = new LinkedHashMap<>();
    sourceScores.put("EXAM", exam); sourceScores.put("TASK", task);
    sourceScores.put("MENTOR", scoreOf(manual.get("MENTOR"))); sourceScores.put("STATION", scoreOf(manual.get("STATION"))); sourceScores.put("TRAINING", scoreOf(manual.get("TRAINING")));

    List<Map<String,Object>> components = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    List<EvaluationRules.WeightedScore> weightedScores = new ArrayList<>();
    Map<String,BigDecimal> effectiveScores = new LinkedHashMap<>();
    for (String code : COMPONENTS) {
      boolean enabled = bool(scheme.get(code.toLowerCase() + "_enabled"));
      BigDecimal weight = decimal(scheme, code.toLowerCase() + "_weight");
      BigDecimal source = sourceScores.get(code);
      Map<String,Object> override = overrides.get(code);
      BigDecimal overrideScore = override == null ? null : decimal(override, "override_score");
      BigDecimal effective = overrideScore != null ? overrideScore : source;
      BigDecimal contribution = enabled && effective != null ? effective.multiply(weight).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP) : null;
      if (enabled && effective == null) missing.add(code);
      weightedScores.add(new EvaluationRules.WeightedScore(enabled,weight,effective));
      effectiveScores.put(code, effective);
      Map<String,Object> item = new LinkedHashMap<>();
      item.put("code", code); item.put("enabled", enabled); item.put("weight", weight); item.put("sourceType", AUTO_COMPONENTS.contains(code) ? "AUTO" : "MANUAL");
      item.put("sourceScore", source); item.put("overrideScore", overrideScore); item.put("effectiveScore", effective); item.put("weightedScore", contribution == null ? null : contribution.setScale(2, RoundingMode.HALF_UP));
      item.put("status", !enabled ? "DISABLED" : effective == null ? "PENDING" : overrideScore != null ? "OVERRIDDEN" : AUTO_COMPONENTS.contains(code) ? "AUTOMATIC" : "SUBMITTED");
      if (manual.containsKey(code)) { item.put("comment", manual.get(code).get("comment")); item.put("evaluatorName", manual.get(code).get("evaluator_name")); item.put("submittedAt", manual.get(code).get("submitted_at")); }
      if (override != null) { item.put("overrideReason", override.get("reason")); item.put("overrideBy", override.get("operator_name")); item.put("overrideAt", override.get("updated_at")); }
      components.add(item);
    }
    BigDecimal bonus = sumAdjustment(employeeId, start, "BONUS").min(decimal(scheme, "bonus_cap"));
    BigDecimal deduction = sumAdjustment(employeeId, start, "DEDUCTION").min(decimal(scheme, "deduction_cap"));
    BigDecimal finalScore = EvaluationRules.finalScore(weightedScores,bonus,deduction);
    boolean locked = isLocked(employeeId, month);
    Map<String,Object> detail = new LinkedHashMap<>();
    detail.put("employeeId", employeeId); detail.put("month", month.toString()); detail.put("schemeId", scheme.get("id")); detail.put("schemeVersion", scheme.get("version")); detail.put("locked", locked);
    detail.put("components", components); detail.put("bonus", bonus); detail.put("deduction", deduction); detail.put("finalScore", finalScore); detail.put("missingItems", missing);
    String snapshot = toJson(Map.of("schemeId", scheme.get("id"), "schemeVersion", scheme.get("version"), "components", components));
    return new MonthlyCalculation(number(scheme.get("id")).longValue(), detail, effectiveScores, bonus, deduction, finalScore, missing, snapshot);
  }

  private Map<String,Map<String,Object>> manualScores(Long employeeId, LocalDate month) {
    Map<String,Map<String,Object>> result = new HashMap<>();
    for (var row : db.queryForList("select m.*,u.display_name evaluator_name from monthly_evaluation m join sys_user u on u.id=m.evaluator_user_id where m.employee_id=? and m.period_month=?", employeeId, month)) result.put(String.valueOf(row.get("evaluator_type")), row);
    return result;
  }

  private Map<String,Map<String,Object>> overrideScores(Long employeeId, LocalDate month) {
    Map<String,Map<String,Object>> result = new HashMap<>();
    for (var row : db.queryForList("select o.*,u.display_name operator_name from score_component_override o join sys_user u on u.id=o.created_by where o.employee_id=? and o.period_month=?", employeeId, month)) result.put(String.valueOf(row.get("component_type")), row);
    return result;
  }

  private Integer nextOrDraftVersion(Long employeeId, String type, String key) {
    return db.queryForObject("select coalesce(max(version),0)+1 from score_summary where employee_id=? and summary_type=? and period_key=?", Integer.class, employeeId, type, key);
  }

  private BigDecimal scoreOf(Map<String,Object> row) { return row == null ? null : decimal(row, "score"); }
  private BigDecimal sumAdjustment(Long employeeId, LocalDate month, String type) { BigDecimal value = db.queryForObject("select coalesce(sum(points),0) from score_adjustment where employee_id=? and period_month=? and adjustment_type=?", BigDecimal.class, employeeId, month, type); return value == null ? BigDecimal.ZERO : value; }
  private BigDecimal avg(String sql, Object... args) { return db.queryForObject(sql, BigDecimal.class, args); }
  static BigDecimal decimal(Map<String,Object> row, String key) { Object value = row.get(key); return value == null ? null : new BigDecimal(String.valueOf(value)); }
  private static Number number(Object value) { return (Number)value; }
  private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0 || "true".equalsIgnoreCase(String.valueOf(value)); }
  private String toJson(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }

  private record MonthlyCalculation(Long schemeId, Map<String,Object> detail, Map<String,BigDecimal> scores, BigDecimal bonus, BigDecimal deduction, BigDecimal finalScore, List<String> missing, String snapshot) {}
}
