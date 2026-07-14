package com.talent.platform.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.*;
import com.talent.platform.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;
  private final EvaluationService service;
  private final ObjectMapper json;

  public EvaluationController(JdbcTemplate db, PermissionService permissions, AuditService audit, EvaluationService service, ObjectMapper json) {
    this.db = db; this.permissions = permissions; this.audit = audit; this.service = service; this.json = json;
  }

  public record SchemeRequest(
    @NotNull Long batchId, @NotNull YearMonth effectiveMonth,
    boolean examEnabled, @NotNull @DecimalMin("0") BigDecimal examWeight,
    boolean taskEnabled, @NotNull @DecimalMin("0") BigDecimal taskWeight,
    boolean mentorEnabled, @NotNull @DecimalMin("0") BigDecimal mentorWeight,
    boolean stationEnabled, @NotNull @DecimalMin("0") BigDecimal stationWeight,
    boolean trainingEnabled, @NotNull @DecimalMin("0") BigDecimal trainingWeight,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth1Weight,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth2Weight,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth3Weight,
    @NotNull @DecimalMin("0") BigDecimal bonusCap, @NotNull @DecimalMin("0") BigDecimal deductionCap) {}
  public record EvaluationRequest(@NotNull Long employeeId, @NotNull YearMonth month, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score, @NotBlank String comment) {}
  public record ComponentEvaluationRequest(@NotNull Long employeeId, @NotNull YearMonth month, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score, @NotBlank String comment) {}
  public record OverrideRequest(@NotNull Long employeeId, @NotNull YearMonth month, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score, @NotBlank String reason) {}
  public record AdjustmentRequest(@NotNull Long employeeId, @NotNull YearMonth month, @Pattern(regexp="BONUS|DEDUCTION") String type, @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal points, @NotBlank String reason, Long evidenceFileId) {}
  public record PublishRequest(String waiverReason, @DecimalMin("0") @DecimalMax("100") BigDecimal overrideScore) {}
  public record ReopenRequest(@NotBlank String reason) {}

  @GetMapping("/schemes")
  public ApiResponse<List<Map<String,Object>>> schemes(@RequestParam(required=false) Long batchId) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    return ApiResponse.ok(batchId == null ? db.queryForList("select * from score_scheme where status<>'DELETED' order by batch_id,effective_month desc,version desc") : db.queryForList("select * from score_scheme where batch_id=? and status<>'DELETED' order by effective_month desc,version desc", batchId));
  }

  @PostMapping("/schemes")
  public ApiResponse<Long> scheme(@Valid @RequestBody SchemeRequest q) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    validateScheme(q);
    Integer version = db.queryForObject("select coalesce(max(version),0)+1 from score_scheme where batch_id=?", Integer.class, q.batchId());
    db.update("insert into score_scheme(batch_id,version,effective_month,exam_enabled,exam_weight,task_enabled,task_weight,mentor_enabled,mentor_weight,station_enabled,station_weight,training_enabled,training_weight,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      q.batchId(),version,q.effectiveMonth().atDay(1),q.examEnabled(),q.examWeight(),q.taskEnabled(),q.taskWeight(),q.mentorEnabled(),q.mentorWeight(),q.stationEnabled(),q.stationWeight(),q.trainingEnabled(),q.trainingWeight(),q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight(),q.bonusCap(),q.deductionCap(),SecurityUtils.current().id());
    Long id = db.queryForObject("select last_insert_id()",Long.class);
    audit.log("CREATE_SCORE_SCHEME","SCORE_SCHEME",id,null,q);
    return ApiResponse.ok(id);
  }

  @PutMapping("/schemes/{id}")
  public ApiResponse<Void> updateScheme(@PathVariable Long id,@Valid @RequestBody SchemeRequest q){
    permissions.require(Permissions.EVALUATION_MANAGE);validateScheme(q);
    Map<String,Object> before=db.queryForMap("select * from score_scheme where id=? and status='DRAFT'",id);
    if(((Number)before.get("batch_id")).longValue()!=q.batchId())throw new BusinessException(400,"评分方案草稿不能更换培养批次");
    db.update("update score_scheme set effective_month=?,exam_enabled=?,exam_weight=?,task_enabled=?,task_weight=?,mentor_enabled=?,mentor_weight=?,station_enabled=?,station_weight=?,training_enabled=?,training_weight=?,quarter_month1_weight=?,quarter_month2_weight=?,quarter_month3_weight=?,bonus_cap=?,deduction_cap=? where id=? and status='DRAFT'",
      q.effectiveMonth().atDay(1),q.examEnabled(),q.examWeight(),q.taskEnabled(),q.taskWeight(),q.mentorEnabled(),q.mentorWeight(),q.stationEnabled(),q.stationWeight(),q.trainingEnabled(),q.trainingWeight(),q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight(),q.bonusCap(),q.deductionCap(),id);
    audit.log("UPDATE_SCORE_SCHEME","SCORE_SCHEME",id,before,q);return ApiResponse.ok(null);
  }

  @PostMapping("/schemes/{id}/draft")
  public ApiResponse<Long> createDraftFromPublished(@PathVariable Long id){
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> source=db.queryForMap("select * from score_scheme where id=? and status in ('PUBLISHED','RETIRED')",id);
    Long batch=((Number)source.get("batch_id")).longValue();
    Integer version=db.queryForObject("select coalesce(max(version),0)+1 from score_scheme where batch_id=?",Integer.class,batch);
    db.update("insert into score_scheme(batch_id,version,effective_month,exam_enabled,exam_weight,task_enabled,task_weight,mentor_enabled,mentor_weight,station_enabled,station_weight,training_enabled,training_weight,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,created_by) select batch_id,?,effective_month,exam_enabled,exam_weight,task_enabled,task_weight,mentor_enabled,mentor_weight,station_enabled,station_weight,training_enabled,training_weight,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,? from score_scheme where id=?",version,SecurityUtils.current().id(),id);
    Long created=db.queryForObject("select last_insert_id()",Long.class);audit.log("COPY_SCORE_SCHEME_TO_DRAFT","SCORE_SCHEME",created,source,null);return ApiResponse.ok(created);
  }

  @DeleteMapping("/schemes/{id}")
  @Transactional
  public ApiResponse<Void> deleteScheme(@PathVariable Long id){
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> before=db.queryForMap("select * from score_scheme where id=? and status<>'DELETED'",id);
    if("DRAFT".equals(before.get("status")))db.update("delete from score_scheme where id=?",id);
    else db.update("update score_scheme set status='DELETED' where id=?",id);
    audit.log("DELETE_SCORE_SCHEME","SCORE_SCHEME",id,before,null);return ApiResponse.ok(null);
  }

  @PostMapping("/schemes/{id}/publish")
  @Transactional
  public ApiResponse<Void> publishScheme(@PathVariable Long id) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> scheme = db.queryForMap("select * from score_scheme where id=?",id);
    if (!"DRAFT".equals(scheme.get("status"))) throw new BusinessException(400,"仅草稿方案可以发布");
    db.update("update score_scheme set status='RETIRED' where batch_id=? and status='PUBLISHED'",scheme.get("batch_id"));
    db.update("update score_scheme set status='PUBLISHED',published_at=now() where id=?",id);
    audit.log("PUBLISH_SCORE_SCHEME","SCORE_SCHEME",id,scheme,null);
    return ApiResponse.ok(null);
  }

  @GetMapping("/monthly/detail")
  public ApiResponse<Map<String,Object>> monthlyDetail(@RequestParam Long employeeId, @RequestParam YearMonth month) {
    permissions.require(Permissions.EVALUATION_VIEW); permissions.requireEmployee(employeeId);
    if ("EMPLOYEE".equals(SecurityUtils.current().role())) throw new BusinessException(403,"员工只能查看已发布评价结果");
    return ApiResponse.ok(service.monthlyDetail(employeeId,month));
  }

  @PutMapping("/monthly/components/{component}")
  public ApiResponse<Void> submitComponent(@PathVariable String component, @Valid @RequestBody ComponentEvaluationRequest q) {
    submitManual(component.toUpperCase(Locale.ROOT),q.employeeId(),q.month(),q.score(),q.comment());
    return ApiResponse.ok(null);
  }

  /** 兼容旧客户端：评分项仍由登录角色安全推导。 */
  @PostMapping("/monthly")
  public ApiResponse<Void> evaluate(@Valid @RequestBody EvaluationRequest q) {
    submitManual(componentForRole(SecurityUtils.current().role()),q.employeeId(),q.month(),q.score(),q.comment());
    return ApiResponse.ok(null);
  }

  private void submitManual(String component, Long employeeId, YearMonth month, BigDecimal score, String comment) {
    permissions.require(Permissions.EVALUATION_SUBMIT); permissions.requireEmployee(employeeId);
    String allowed = componentForRole(SecurityUtils.current().role());
    if (!allowed.equals(component) || !Set.of("MENTOR","STATION","TRAINING").contains(component)) throw new BusinessException(403,"当前角色不能提交该评分项");
    ensureEnabled(employeeId,month,component);
    ensureUnlocked(employeeId,month);
    db.update("insert into monthly_evaluation(employee_id,period_month,evaluator_type,evaluator_user_id,score,comment) values(?,?,?,?,?,?) on duplicate key update evaluator_user_id=values(evaluator_user_id),score=values(score),comment=values(comment),submitted_at=now()", employeeId,month.atDay(1),component,SecurityUtils.current().id(),score,comment);
    service.refreshDraftIfPresent(employeeId,month);
    audit.log("SUBMIT_MONTHLY_COMPONENT","EMPLOYEE",employeeId,null,Map.of("month",month,"component",component,"score",score,"comment",comment));
  }

  @PutMapping("/monthly/overrides/{component}")
  public ApiResponse<Void> override(@PathVariable String component, @Valid @RequestBody OverrideRequest q) {
    requireAdmin(); permissions.requireEmployee(q.employeeId());
    component = component.toUpperCase(Locale.ROOT);
    if (!Set.copyOf(EvaluationService.COMPONENTS).contains(component)) throw new BusinessException(400,"不支持的评分项");
    ensureEnabled(q.employeeId(),q.month(),component); ensureUnlocked(q.employeeId(),q.month());
    Map<String,Object> detail = service.monthlyDetail(q.employeeId(),q.month());
    Object original = component(detail,component).get("sourceScore");
    var before = db.queryForList("select * from score_component_override where employee_id=? and period_month=? and component_type=?",q.employeeId(),q.month().atDay(1),component);
    db.update("insert into score_component_override(employee_id,period_month,component_type,original_score,override_score,reason,created_by) values(?,?,?,?,?,?,?) on duplicate key update original_score=values(original_score),override_score=values(override_score),reason=values(reason),created_by=values(created_by),updated_at=now()",q.employeeId(),q.month().atDay(1),component,original,q.score(),q.reason(),SecurityUtils.current().id());
    service.refreshDraftIfPresent(q.employeeId(),q.month());
    audit.log("OVERRIDE_SCORE_COMPONENT","EMPLOYEE",q.employeeId(),before.isEmpty()?null:before.get(0),q);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/monthly/overrides/{component}")
  public ApiResponse<Void> deleteOverride(@PathVariable String component, @RequestParam Long employeeId, @RequestParam YearMonth month) {
    requireAdmin(); permissions.requireEmployee(employeeId); component=component.toUpperCase(Locale.ROOT);
    if (!Set.copyOf(EvaluationService.COMPONENTS).contains(component)) throw new BusinessException(400,"不支持的评分项");
    ensureUnlocked(employeeId,month);
    var before=db.queryForList("select * from score_component_override where employee_id=? and period_month=? and component_type=?",employeeId,month.atDay(1),component);
    if (before.isEmpty()) throw new BusinessException(404,"覆盖记录不存在");
    db.update("delete from score_component_override where id=?",before.get(0).get("id"));
    service.refreshDraftIfPresent(employeeId,month);
    audit.log("DELETE_SCORE_COMPONENT_OVERRIDE","EMPLOYEE",employeeId,before.get(0),null);
    return ApiResponse.ok(null);
  }

  @GetMapping("/monthly")
  public ApiResponse<List<Map<String,Object>>> monthly(@RequestParam Long employeeId) {
    permissions.require(Permissions.EVALUATION_VIEW); permissions.requireEmployee(employeeId);
    String publishedOnly = "EMPLOYEE".equals(SecurityUtils.current().role()) ? " and exists(select 1 from score_summary s where s.employee_id=m.employee_id and s.summary_type='MONTH' and s.period_key=date_format(m.period_month,'%Y-%m') and s.status='PUBLISHED')" : "";
    return ApiResponse.ok(db.queryForList("select m.id,m.period_month,m.evaluator_type,m.score,m.comment,m.submitted_at,u.display_name evaluator_name from monthly_evaluation m join sys_user u on u.id=m.evaluator_user_id where m.employee_id=?"+publishedOnly+" order by m.period_month desc,m.evaluator_type",employeeId));
  }

  @PostMapping("/adjustments")
  public ApiResponse<Long> adjustment(@Valid @RequestBody AdjustmentRequest q) {
    permissions.require(Permissions.EVALUATION_MANAGE); ensureUnlocked(q.employeeId(),q.month());
    db.update("insert into score_adjustment(employee_id,period_month,adjustment_type,points,reason,evidence_file_id,created_by) values(?,?,?,?,?,?,?)",q.employeeId(),q.month().atDay(1),q.type(),q.points(),q.reason(),q.evidenceFileId(),SecurityUtils.current().id());
    Long id=db.queryForObject("select last_insert_id()",Long.class); service.refreshDraftIfPresent(q.employeeId(),q.month()); audit.log("CREATE_SCORE_ADJUSTMENT","SCORE_ADJUSTMENT",id,null,q); return ApiResponse.ok(id);
  }

  @PostMapping("/summaries/generate-month") public ApiResponse<Integer> generateMonth(@RequestParam YearMonth month){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.generateMonth(month));}
  @PostMapping("/summaries/generate-quarter") public ApiResponse<Integer> generateQuarter(@RequestParam int year,@RequestParam int quarter){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.generateQuarter(year,quarter));}

  @GetMapping("/summaries")
  public ApiResponse<List<Map<String,Object>>> summaries(@RequestParam Long employeeId) {
    permissions.require(Permissions.EVALUATION_VIEW); permissions.requireEmployee(employeeId);
    boolean manage=SecurityUtils.current().can(Permissions.EVALUATION_MANAGE);
    List<Map<String,Object>> rows=db.queryForList("select * from score_summary where employee_id=?"+(manage?"":" and status='PUBLISHED'")+" order by generated_at desc",employeeId);
    for(Map<String,Object> row:rows){parseJson(row,"component_snapshot");parseJson(row,"quarter_snapshot");}
    return ApiResponse.ok(rows);
  }

  @PostMapping("/summaries/{id}/publish")
  public ApiResponse<Void> publish(@PathVariable Long id,@RequestBody PublishRequest q) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> summary=db.queryForMap("select * from score_summary where id=? and status='DRAFT'",id);
    if("MONTH".equals(summary.get("summary_type"))) service.refreshDraft(id);
    summary=db.queryForMap("select * from score_summary where id=? and status='DRAFT'",id);
    String missing=Objects.toString(summary.get("missing_items"),"");
    if(!missing.isBlank()&&(q.waiverReason()==null||q.waiverReason().isBlank()||q.overrideScore()==null)) throw new BusinessException(400,"存在缺失评分，必须填写豁免原因和人工核定总分");
    if(!missing.isBlank()) requireAdmin();
    db.update("update score_summary set status='PUBLISHED',waiver_reason=?,final_score=coalesce(?,final_score),published_at=now() where id=?",q.waiverReason(),q.overrideScore(),id);
    audit.log("PUBLISH_SCORE_SUMMARY","SCORE_SUMMARY",id,summary,q); return ApiResponse.ok(null);
  }

  @PostMapping("/summaries/{id}/reopen")
  public ApiResponse<Long> reopen(@PathVariable Long id,@Valid @RequestBody ReopenRequest q) {
    requireAdmin();
    Map<String,Object> old=db.queryForMap("select * from score_summary where id=? and status='PUBLISHED'",id);
    if(!"MONTH".equals(old.get("summary_type"))) throw new BusinessException(400,"季度汇总请重新生成");
    Long employeeId=((Number)old.get("employee_id")).longValue();
    Long created=service.generateMonth(employeeId,YearMonth.parse(String.valueOf(old.get("period_key"))),service.schemeById(((Number)old.get("scheme_id")).longValue()),q.reason());
    audit.log("REOPEN_SCORE_SUMMARY","SCORE_SUMMARY",created,old,q); return ApiResponse.ok(created);
  }

  private void ensureEnabled(Long employeeId,YearMonth month,String component){Map<String,Object> scheme=service.schemeFor(employeeId,month);Object value=scheme.get(component.toLowerCase(Locale.ROOT)+"_enabled");if(!(Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0))throw new BusinessException(400,"该评分项在当前方案中未启用");}
  private void validateScheme(SchemeRequest q){EvaluationRules.validateComponentWeights(List.of(new EvaluationRules.WeightedItem(q.examEnabled(),q.examWeight()),new EvaluationRules.WeightedItem(q.taskEnabled(),q.taskWeight()),new EvaluationRules.WeightedItem(q.mentorEnabled(),q.mentorWeight()),new EvaluationRules.WeightedItem(q.stationEnabled(),q.stationWeight()),new EvaluationRules.WeightedItem(q.trainingEnabled(),q.trainingWeight())));EvaluationRules.validateQuarterWeights(q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight());}
  private void ensureUnlocked(Long employeeId,YearMonth month){if(service.isLocked(employeeId,month))throw new BusinessException(409,"月度评价已发布，请由管理员重开后修改");}
  private void requireAdmin(){if(!List.of("ADMIN","SUPER_ADMIN").contains(SecurityUtils.current().role()))throw new BusinessException(403,"仅管理员可执行此操作");}
  private String componentForRole(String role){return switch(role){case "MENTOR"->"MENTOR";case "STATION_MANAGER"->"STATION";case "TRAINING_ADMIN"->"TRAINING";default->throw new BusinessException(403,"当前角色不能提交人工评分");};}
  @SuppressWarnings("unchecked") private Map<String,Object> component(Map<String,Object> detail,String code){return ((List<Map<String,Object>>)detail.get("components")).stream().filter(x->code.equals(x.get("code"))).findFirst().orElseThrow();}
  private void parseJson(Map<String,Object> row,String key){Object raw=row.get(key);if(raw instanceof String s){try{row.put(key,json.readValue(s,new TypeReference<Map<String,Object>>(){}));}catch(Exception ignored){}}}
}
