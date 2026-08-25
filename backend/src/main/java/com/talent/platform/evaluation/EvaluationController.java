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
  private final EvaluationAssignmentService assignments;
  private final ObjectMapper json;

  public EvaluationController(JdbcTemplate db, PermissionService permissions, AuditService audit, EvaluationService service, EvaluationAssignmentService assignments, ObjectMapper json) {
    this.db = db; this.permissions = permissions; this.audit = audit; this.service = service; this.assignments=assignments;this.json = json;
  }

  public record SchemeRequest(
    @NotNull Long batchId, @NotNull YearMonth effectiveMonth,
    boolean examEnabled, @NotNull @DecimalMin("0") BigDecimal examWeight, @DecimalMin(value="0", inclusive=false) BigDecimal examMaxScore,
    boolean taskEnabled, @NotNull @DecimalMin("0") BigDecimal taskWeight, @DecimalMin(value="0", inclusive=false) BigDecimal taskMaxScore,
    boolean mentorEnabled, @NotNull @DecimalMin("0") BigDecimal mentorWeight, @DecimalMin(value="0", inclusive=false) BigDecimal mentorMaxScore,
    boolean stationEnabled, @NotNull @DecimalMin("0") BigDecimal stationWeight, @DecimalMin(value="0", inclusive=false) BigDecimal stationMaxScore,
    @Pattern(regexp="AUTO_BY_DAYS|PRIMARY_STATION") String stationAggregationMode,
    boolean trainingEnabled, @NotNull @DecimalMin("0") BigDecimal trainingWeight, @DecimalMin(value="0", inclusive=false) BigDecimal trainingMaxScore,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth1Weight,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth2Weight,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth3Weight,
    @NotNull @DecimalMin("0") BigDecimal bonusCap, @NotNull @DecimalMin("0") BigDecimal deductionCap) {}
  public record TemplateRequest(
    @NotBlank @Size(max=128) String name, @Size(max=500) String description,
    boolean examEnabled, @NotNull @DecimalMin("0") BigDecimal examWeight, @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal examMaxScore,
    boolean taskEnabled, @NotNull @DecimalMin("0") BigDecimal taskWeight, @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal taskMaxScore,
    boolean mentorEnabled, @NotNull @DecimalMin("0") BigDecimal mentorWeight, @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal mentorMaxScore,
    boolean stationEnabled, @NotNull @DecimalMin("0") BigDecimal stationWeight, @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal stationMaxScore,
    @Pattern(regexp="AUTO_BY_DAYS|PRIMARY_STATION") String stationAggregationMode,
    boolean trainingEnabled, @NotNull @DecimalMin("0") BigDecimal trainingWeight, @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal trainingMaxScore,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth1Weight,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth2Weight,
    @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal quarterMonth3Weight,
    @NotNull @DecimalMin("0") BigDecimal bonusCap, @NotNull @DecimalMin("0") BigDecimal deductionCap,
    List<@Valid SourceWeightRequest> examSourceWeights,List<@Valid SourceWeightRequest> taskSourceWeights) {}
  public record SourceWeightRequest(@NotNull Long sourceId,@NotNull @DecimalMin(value="0",inclusive=false) BigDecimal weight) {}
  public record ApplyTemplateRequest(@NotNull Long templateId, @NotNull Long batchId, @NotNull YearMonth effectiveMonth) {}
  public record EvaluationRequest(@NotNull Long employeeId, @NotNull YearMonth month, @NotNull @DecimalMin("0") BigDecimal score, @NotBlank String comment) {}
  public record ComponentEvaluationRequest(@NotNull Long employeeId, @NotNull YearMonth month, Long scopeId, @NotNull @DecimalMin("0") BigDecimal score, @NotBlank String comment) {}
  public record StationWeightItem(@NotNull Long stationId,@NotNull @DecimalMin("0") BigDecimal weight) {}
  public record StationWeightRequest(@NotNull Long employeeId,@NotNull YearMonth month,@NotEmpty List<@Valid StationWeightItem> stations) {}
  public record OverrideRequest(@NotNull Long employeeId, @NotNull YearMonth month, @NotNull @DecimalMin("0") BigDecimal score, @NotBlank String reason) {}
  public record AdjustmentRequest(@NotNull Long employeeId, @NotNull YearMonth month, @Pattern(regexp="BONUS|DEDUCTION") String type, @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal points, @NotBlank String reason, Long evidenceFileId) {}
  public record PublishRequest(String waiverReason, @DecimalMin("0") @DecimalMax("100") BigDecimal overrideScore) {}
  public record ReopenRequest(@NotBlank String reason) {}

  @GetMapping("/templates")
  public ApiResponse<List<Map<String,Object>>> templates() {
    permissions.require(Permissions.EVALUATION_MANAGE);
    var templates=db.queryForList("""
      select t.*,creator.display_name creator_name,updater.display_name updater_name,
        (select count(*) from score_scheme s where s.template_id=t.id and s.status<>'DELETED') applied_count
      from evaluation_template t
      join sys_user creator on creator.id=t.created_by
      join sys_user updater on updater.id=t.updated_by
      where t.status<>'DELETED'
      order by t.status='ACTIVE' desc,t.updated_at desc,t.id desc
      """);
    for(var template:templates){Long id=((Number)template.get("id")).longValue();template.put("examSourceWeights",sourceWeights("evaluation_template_source_weight","template_id",id,"EXAM"));template.put("taskSourceWeights",sourceWeights("evaluation_template_source_weight","template_id",id,"TASK"));}
    return ApiResponse.ok(templates);
  }

  @PostMapping("/templates")
  @Transactional
  public ApiResponse<Long> createTemplate(@Valid @RequestBody TemplateRequest q) {
    permissions.require(Permissions.EVALUATION_MANAGE); validateTemplate(q);
    Long userId=SecurityUtils.current().id();
    db.update("insert into evaluation_template(name,description,exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      q.name().trim(),blankToNull(q.description()),q.examEnabled(),q.examWeight(),q.examMaxScore(),q.taskEnabled(),q.taskWeight(),q.taskMaxScore(),q.mentorEnabled(),q.mentorWeight(),q.mentorMaxScore(),q.stationEnabled(),q.stationWeight(),q.stationMaxScore(),stationMode(q.stationAggregationMode()),q.trainingEnabled(),q.trainingWeight(),q.trainingMaxScore(),q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight(),q.bonusCap(),q.deductionCap(),userId,userId);
    Long id=db.queryForObject("select last_insert_id()",Long.class);replaceSourceWeights("evaluation_template_source_weight","template_id",id,q.examSourceWeights(),q.taskSourceWeights());audit.log("CREATE_EVALUATION_TEMPLATE","EVALUATION_TEMPLATE",id,null,q);return ApiResponse.ok(id);
  }

  @PutMapping("/templates/{id}")
  @Transactional
  public ApiResponse<Void> updateTemplate(@PathVariable Long id,@Valid @RequestBody TemplateRequest q) {
    permissions.require(Permissions.EVALUATION_MANAGE);validateTemplate(q);
    Map<String,Object> before=db.queryForMap("select * from evaluation_template where id=? and status<>'DELETED'",id);
    db.update("update evaluation_template set name=?,description=?,exam_enabled=?,exam_weight=?,exam_max_score=?,task_enabled=?,task_weight=?,task_max_score=?,mentor_enabled=?,mentor_weight=?,mentor_max_score=?,station_enabled=?,station_weight=?,station_max_score=?,station_aggregation_mode=?,training_enabled=?,training_weight=?,training_max_score=?,quarter_month1_weight=?,quarter_month2_weight=?,quarter_month3_weight=?,bonus_cap=?,deduction_cap=?,updated_by=? where id=? and status<>'DELETED'",
      q.name().trim(),blankToNull(q.description()),q.examEnabled(),q.examWeight(),q.examMaxScore(),q.taskEnabled(),q.taskWeight(),q.taskMaxScore(),q.mentorEnabled(),q.mentorWeight(),q.mentorMaxScore(),q.stationEnabled(),q.stationWeight(),q.stationMaxScore(),stationMode(q.stationAggregationMode()),q.trainingEnabled(),q.trainingWeight(),q.trainingMaxScore(),q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight(),q.bonusCap(),q.deductionCap(),SecurityUtils.current().id(),id);
    replaceSourceWeights("evaluation_template_source_weight","template_id",id,q.examSourceWeights(),q.taskSourceWeights());
    audit.log("UPDATE_EVALUATION_TEMPLATE","EVALUATION_TEMPLATE",id,before,q);return ApiResponse.ok(null);
  }

  @PostMapping("/templates/{id}/copy")
  @Transactional
  public ApiResponse<Long> copyTemplate(@PathVariable Long id) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> source=db.queryForMap("select * from evaluation_template where id=? and status<>'DELETED'",id);
    Long userId=SecurityUtils.current().id();
    db.update("insert into evaluation_template(name,description,status,exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,created_by,updated_by) select concat(name,' - 副本'),description,'ACTIVE',exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,?,? from evaluation_template where id=?",userId,userId,id);
    Long created=db.queryForObject("select last_insert_id()",Long.class);db.update("insert into evaluation_template_source_weight(template_id,component_type,source_id,weight) select ?,component_type,source_id,weight from evaluation_template_source_weight where template_id=?",created,id);audit.log("COPY_EVALUATION_TEMPLATE","EVALUATION_TEMPLATE",created,source,null);return ApiResponse.ok(created);
  }

  @DeleteMapping("/templates/{id}")
  public ApiResponse<Void> deleteTemplate(@PathVariable Long id) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> before=db.queryForMap("select * from evaluation_template where id=? and status<>'DELETED'",id);
    db.update("update evaluation_template set status='DELETED',updated_by=? where id=?",SecurityUtils.current().id(),id);
    audit.log("DELETE_EVALUATION_TEMPLATE","EVALUATION_TEMPLATE",id,before,null);return ApiResponse.ok(null);
  }

  @PostMapping("/templates/apply")
  @Transactional
  public ApiResponse<Long> applyTemplate(@Valid @RequestBody ApplyTemplateRequest q) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> template=db.queryForMap("select * from evaluation_template where id=? and status='ACTIVE'",q.templateId());
    Integer version=db.queryForObject("select coalesce(max(version),0)+1 from score_scheme where batch_id=?",Integer.class,q.batchId());
    db.update("insert into score_scheme(batch_id,template_id,version,effective_month,exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,created_by) select ?,id,?,?,exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,? from evaluation_template where id=?",
      q.batchId(),version,q.effectiveMonth().atDay(1),SecurityUtils.current().id(),q.templateId());
    Long id=db.queryForObject("select last_insert_id()",Long.class);db.update("insert into score_scheme_source_weight(scheme_id,component_type,source_id,weight) select ?,component_type,source_id,weight from evaluation_template_source_weight where template_id=?",id,q.templateId());audit.log("APPLY_EVALUATION_TEMPLATE","SCORE_SCHEME",id,template,q);return ApiResponse.ok(id);
  }

  @GetMapping("/source-options")
  public ApiResponse<Map<String,Object>> sourceOptions(){permissions.require(Permissions.EVALUATION_MANAGE);Map<String,Object> result=new LinkedHashMap<>();result.put("tasks",db.queryForList("select id,title name,deadline from challenge_task order by deadline desc,id desc"));result.put("exams",db.queryForList("select id,name,score_month from exam_plan where status='PUBLISHED' order by score_month desc,id desc"));return ApiResponse.ok(result);}

  @GetMapping("/overview")
  public ApiResponse<Map<String,Object>> overview(@RequestParam YearMonth month) {
    permissions.require(Permissions.EVALUATION_VIEW);
    var filter=permissions.employeeFilter("e");
    var employeeArgs=new ArrayList<Object>(filter.args());employeeArgs.add(month.atDay(1));
    Integer total=db.queryForObject("select count(*) from employee e where e.status='ACTIVE'"+filter.sql(),Integer.class,filter.args().toArray());
    Integer covered=db.queryForObject("select count(*) from employee e where e.status='ACTIVE'"+filter.sql()+" and exists(select 1 from score_scheme sc where sc.batch_id=e.batch_id and sc.status='PUBLISHED' and sc.effective_month<=?)",Integer.class,employeeArgs.toArray());
    var summaryArgs=new ArrayList<Object>();summaryArgs.add(month.toString());summaryArgs.addAll(filter.args());
    List<Map<String,Object>> summaries=db.queryForList("select s.status,s.missing_items from score_summary s join employee e on e.id=s.employee_id where s.summary_type='MONTH' and s.period_key=?"+filter.sql()+" and s.version=(select max(x.version) from score_summary x where x.employee_id=s.employee_id and x.summary_type=s.summary_type and x.period_key=s.period_key)",summaryArgs.toArray());
    int draft=(int)summaries.stream().filter(x->"DRAFT".equals(x.get("status"))).count();
    int published=(int)summaries.stream().filter(x->"PUBLISHED".equals(x.get("status"))).count();
    int missing=(int)summaries.stream().filter(x->!Objects.toString(x.get("missing_items"),"").isBlank()).count();
    Map<String,Object> result=new LinkedHashMap<>();result.put("month",month.toString());result.put("totalEmployees",total);result.put("schemeCoveredEmployees",covered);result.put("draftSummaries",draft);result.put("publishedSummaries",published);result.put("missingSummaries",missing);
    result.put("pendingManualScores",pendingManualScores(month));
    result.put("unassignedRatingTasks",SecurityUtils.current().can(Permissions.EVALUATION_MANAGE)?db.queryForObject("select count(*) from evaluation_rating_task t where t.period_month=? and t.status='ACTIVE' and not exists(select 1 from evaluation_rating_reviewer r where r.task_id=t.id and r.status='ACTIVE')",Integer.class,month.atDay(1)):0);
    result.put("pendingTaskReviews",pendingTaskReviews());result.put("pendingExamReviews",pendingExamReviews());result.put("unpublishedExamResults",unpublishedExamResults());
    return ApiResponse.ok(result);
  }

  @GetMapping("/schemes")
  public ApiResponse<List<Map<String,Object>>> schemes(@RequestParam(required=false) Long batchId) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    String select="select s.*,t.name template_name,b.name batch_name from score_scheme s left join evaluation_template t on t.id=s.template_id join talent_batch b on b.id=s.batch_id where s.status<>'DELETED'";
    return ApiResponse.ok(batchId == null ? db.queryForList(select+" order by s.effective_month desc,s.batch_id,s.version desc") : db.queryForList(select+" and s.batch_id=? order by s.effective_month desc,s.version desc", batchId));
  }

  @PostMapping("/schemes")
  public ApiResponse<Long> scheme(@Valid @RequestBody SchemeRequest q) {
    permissions.require(Permissions.EVALUATION_MANAGE);
    validateScheme(q);
    Integer version = db.queryForObject("select coalesce(max(version),0)+1 from score_scheme where batch_id=?", Integer.class, q.batchId());
    db.update("insert into score_scheme(batch_id,version,effective_month,exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      q.batchId(),version,q.effectiveMonth().atDay(1),q.examEnabled(),q.examWeight(),maxScore(q.examMaxScore()),q.taskEnabled(),q.taskWeight(),maxScore(q.taskMaxScore()),q.mentorEnabled(),q.mentorWeight(),maxScore(q.mentorMaxScore()),q.stationEnabled(),q.stationWeight(),maxScore(q.stationMaxScore()),stationMode(q.stationAggregationMode()),q.trainingEnabled(),q.trainingWeight(),maxScore(q.trainingMaxScore()),q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight(),q.bonusCap(),q.deductionCap(),SecurityUtils.current().id());
    Long id = db.queryForObject("select last_insert_id()",Long.class);
    audit.log("CREATE_SCORE_SCHEME","SCORE_SCHEME",id,null,q);
    return ApiResponse.ok(id);
  }

  @PutMapping("/schemes/{id}")
  public ApiResponse<Void> updateScheme(@PathVariable Long id,@Valid @RequestBody SchemeRequest q){
    permissions.require(Permissions.EVALUATION_MANAGE);validateScheme(q);
    Map<String,Object> before=db.queryForMap("select * from score_scheme where id=? and status='DRAFT'",id);
    if(((Number)before.get("batch_id")).longValue()!=q.batchId())throw new BusinessException(400,"评分方案草稿不能更换培养批次");
    db.update("update score_scheme set effective_month=?,exam_enabled=?,exam_weight=?,exam_max_score=?,task_enabled=?,task_weight=?,task_max_score=?,mentor_enabled=?,mentor_weight=?,mentor_max_score=?,station_enabled=?,station_weight=?,station_max_score=?,station_aggregation_mode=?,training_enabled=?,training_weight=?,training_max_score=?,quarter_month1_weight=?,quarter_month2_weight=?,quarter_month3_weight=?,bonus_cap=?,deduction_cap=? where id=? and status='DRAFT'",
      q.effectiveMonth().atDay(1),q.examEnabled(),q.examWeight(),maxScore(q.examMaxScore()),q.taskEnabled(),q.taskWeight(),maxScore(q.taskMaxScore()),q.mentorEnabled(),q.mentorWeight(),maxScore(q.mentorMaxScore()),q.stationEnabled(),q.stationWeight(),maxScore(q.stationMaxScore()),stationMode(q.stationAggregationMode()),q.trainingEnabled(),q.trainingWeight(),maxScore(q.trainingMaxScore()),q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight(),q.bonusCap(),q.deductionCap(),id);
    audit.log("UPDATE_SCORE_SCHEME","SCORE_SCHEME",id,before,q);return ApiResponse.ok(null);
  }

  @PostMapping("/schemes/{id}/draft")
  public ApiResponse<Long> createDraftFromPublished(@PathVariable Long id){
    permissions.require(Permissions.EVALUATION_MANAGE);
    Map<String,Object> source=db.queryForMap("select * from score_scheme where id=? and status in ('PUBLISHED','RETIRED')",id);
    Long batch=((Number)source.get("batch_id")).longValue();
    Integer version=db.queryForObject("select coalesce(max(version),0)+1 from score_scheme where batch_id=?",Integer.class,batch);
    db.update("insert into score_scheme(batch_id,template_id,version,effective_month,exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,created_by) select batch_id,template_id,?,effective_month,exam_enabled,exam_weight,exam_max_score,task_enabled,task_weight,task_max_score,mentor_enabled,mentor_weight,mentor_max_score,station_enabled,station_weight,station_max_score,station_aggregation_mode,training_enabled,training_weight,training_max_score,quarter_month1_weight,quarter_month2_weight,quarter_month3_weight,bonus_cap,deduction_cap,? from score_scheme where id=?",version,SecurityUtils.current().id(),id);
    Long created=db.queryForObject("select last_insert_id()",Long.class);db.update("insert into score_scheme_source_weight(scheme_id,component_type,source_id,weight) select ?,component_type,source_id,weight from score_scheme_source_weight where scheme_id=?",created,id);audit.log("COPY_SCORE_SCHEME_TO_DRAFT","SCORE_SCHEME",created,source,null);return ApiResponse.ok(created);
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
    permissions.require(Permissions.EVALUATION_VIEW); requireEvaluationEmployee(employeeId,month);
    if ("EMPLOYEE".equals(SecurityUtils.current().role())) throw new BusinessException(403,"员工只能查看已发布评价结果");
    return ApiResponse.ok(service.monthlyDetail(employeeId,month));
  }

  @GetMapping("/monthly/candidates")
  public ApiResponse<List<Map<String,Object>>> monthlyCandidates(@RequestParam YearMonth month,@RequestParam(required=false)Long classId,@RequestParam(required=false)Long classPositionId,@RequestParam(required=false)String keyword,@RequestParam(required=false)String status){
    permissions.require(Permissions.EVALUATION_VIEW);var user=SecurityUtils.current();String sql="select e.id,e.employee_no,e.name,e.class_id,e.class_position_id,b.name batch_name,cls.label class_name,cp.label class_position_name,s.name station_name from employee e left join talent_batch b on b.id=e.batch_id left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS' left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION' left join service_station s on s.id=e.station_id where e.status='ACTIVE'";var args=new ArrayList<Object>();
    if("MENTORED".equals(user.dataScope())){sql+=" and (e.mentor_user_id=? or e.skill_mentor_user_id=? or exists(select 1 from evaluation_rating_task rt join evaluation_rating_reviewer rr on rr.task_id=rt.id and rr.status='ACTIVE' where rt.employee_id=e.id and rt.period_month=? and rt.status='ACTIVE' and rr.reviewer_user_id=?))";args.add(user.id());args.add(user.id());args.add(month.atDay(1));args.add(user.id());}
    if(classId!=null){sql+=" and e.class_id=?";args.add(classId);}
    if(classPositionId!=null){sql+=" and e.class_position_id=?";args.add(classPositionId);}
    if(keyword!=null&&!keyword.isBlank()){sql+=" and (e.name like ? or e.employee_no like ?)";String value="%"+keyword.trim()+"%";args.add(value);args.add(value);}
    sql+=" order by b.name,e.employee_no,e.id";var result=new ArrayList<Map<String,Object>>();
    for(var employee:db.queryForList(sql,args.toArray())){Long employeeId=((Number)employee.get("id")).longValue();if("STATION_MANAGER".equals(user.role())&&!managesHistoricalStation(employeeId,month,user.id())&&!assignments.hasAnyAssignment(employeeId,month,user.id()))continue;var item=new LinkedHashMap<String,Object>(employee);try{Map<String,Object> detail=service.monthlyDetail(employeeId,month);@SuppressWarnings("unchecked") List<Map<String,Object>> components=(List<Map<String,Object>>)detail.get("components");long enabled=components.stream().filter(x->Boolean.TRUE.equals(x.get("enabled"))).count();long complete=components.stream().filter(x->Boolean.TRUE.equals(x.get("enabled"))&&!"PENDING".equals(x.get("status"))).count();boolean myPending=components.stream().filter(x->Boolean.TRUE.equals(x.get("enabled"))).anyMatch(this::canCurrentUserEvaluatePending);String state=Boolean.TRUE.equals(detail.get("locked"))?"PUBLISHED":complete==enabled?"READY":myPending?"MY_PENDING":"IN_PROGRESS";item.put("status",state);item.put("completedCount",complete);item.put("enabledCount",enabled);item.put("finalScore",detail.get("finalScore"));item.put("missingItems",detail.get("missingItems"));}catch(BusinessException error){item.put("status","NO_SCHEME");item.put("completedCount",0);item.put("enabledCount",0);item.put("finalScore",null);item.put("missingItems",List.of());}
      if(status==null||status.isBlank()||status.equals(item.get("status")))result.add(item);
    }return ApiResponse.ok(result);
  }

  @PutMapping("/monthly/components/{component}")
  public ApiResponse<Void> submitComponent(@PathVariable String component, @Valid @RequestBody ComponentEvaluationRequest q) {
    submitManual(component.toUpperCase(Locale.ROOT),q.employeeId(),q.month(),q.scopeId(),q.score(),q.comment());
    return ApiResponse.ok(null);
  }

  /** 兼容旧客户端：评分项仍由登录角色安全推导。 */
  @PostMapping("/monthly")
  public ApiResponse<Void> evaluate(@Valid @RequestBody EvaluationRequest q) {
    submitManual(componentForRole(SecurityUtils.current().role()),q.employeeId(),q.month(),null,q.score(),q.comment());
    return ApiResponse.ok(null);
  }

  private void submitManual(String component, Long employeeId, YearMonth month,Long scopeId, BigDecimal score, String comment) {
    permissions.require(Permissions.EVALUATION_SUBMIT);
    String allowed = componentForRole(SecurityUtils.current().role());
    if (!allowed.equals(component) || !Set.of("MENTOR","STATION","TRAINING").contains(component)) throw new BusinessException(403,"当前角色不能提交该评分项");
    long effectiveScope=0;
    if("STATION".equals(component)){if(scopeId==null||!service.stationApplies(employeeId,month,scopeId))throw new BusinessException(400,"请选择该员工当月实际所在站点");effectiveScope=scopeId;}
    boolean explicitTask=assignments.hasTask(employeeId,month,component,effectiveScope);
    if(explicitTask){if(!assignments.isAssigned(employeeId,month,component,effectiveScope,SecurityUtils.current().id()))throw new BusinessException(403,"该评分任务未分配给当前用户");}
    else if("STATION".equals(component)){Integer managed=db.queryForObject("select count(*) from station_manager_scope where user_id=? and station_id=?",Integer.class,SecurityUtils.current().id(),scopeId);if(managed==null||managed==0)throw new BusinessException(403,"只能评价本人负责的站点");}
    else permissions.requireEmployee(employeeId);
    ensureEnabled(employeeId,month,component);
    ensureWithinFullScore(employeeId,month,component,score);
    ensureUnlocked(employeeId,month);
    if("TRAINING".equals(component)&&!explicitTask)db.update("delete from monthly_evaluation where employee_id=? and period_month=? and evaluator_type='TRAINING'",employeeId,month.atDay(1));
    db.update("insert into monthly_evaluation(employee_id,period_month,evaluator_type,evaluator_user_id,scope_id,score,comment) values(?,?,?,?,?,?,?) on duplicate key update score=values(score),comment=values(comment),submitted_at=now()", employeeId,month.atDay(1),component,SecurityUtils.current().id(),effectiveScope,score,comment);
    service.refreshDraftIfPresent(employeeId,month);
    audit.log("SUBMIT_MONTHLY_COMPONENT","EMPLOYEE",employeeId,null,Map.of("month",month,"component",component,"scopeId",effectiveScope,"score",score,"comment",comment));
  }

  @PutMapping("/monthly/station-weights")
  public ApiResponse<Void> stationWeights(@Valid @RequestBody StationWeightRequest q){requireAdmin();permissions.requireEmployee(q.employeeId());ensureUnlocked(q.employeeId(),q.month());Map<Long,BigDecimal> weights=new LinkedHashMap<>();for(var item:q.stations())if(weights.put(item.stationId(),item.weight())!=null)throw new BusinessException(400,"站点不能重复");service.replaceStationWeights(q.employeeId(),q.month(),weights,SecurityUtils.current().id());audit.log("UPDATE_MONTHLY_STATION_WEIGHTS","EMPLOYEE",q.employeeId(),null,q);return ApiResponse.ok(null);}

  @DeleteMapping("/monthly/station-weights")
  public ApiResponse<Void> clearStationWeights(@RequestParam Long employeeId,@RequestParam YearMonth month){requireAdmin();permissions.requireEmployee(employeeId);ensureUnlocked(employeeId,month);service.clearStationWeights(employeeId,month);audit.log("CLEAR_MONTHLY_STATION_WEIGHTS","EMPLOYEE",employeeId,null,Map.of("month",month));return ApiResponse.ok(null);}

  @PutMapping("/monthly/overrides/{component}")
  public ApiResponse<Void> override(@PathVariable String component, @Valid @RequestBody OverrideRequest q) {
    requireAdmin(); permissions.requireEmployee(q.employeeId());
    component = component.toUpperCase(Locale.ROOT);
    if (!Set.copyOf(EvaluationService.COMPONENTS).contains(component)) throw new BusinessException(400,"不支持的评分项");
    ensureEnabled(q.employeeId(),q.month(),component); ensureWithinFullScore(q.employeeId(),q.month(),component,q.score()); ensureUnlocked(q.employeeId(),q.month());
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
    return ApiResponse.ok(db.queryForList("select m.id,m.period_month,m.evaluator_type,m.scope_id,m.score,m.comment,m.submitted_at,u.display_name evaluator_name from monthly_evaluation m join sys_user u on u.id=m.evaluator_user_id where m.employee_id=?"+publishedOnly+" order by m.period_month desc,m.evaluator_type,m.scope_id,m.evaluator_user_id",employeeId));
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
  private void validateScheme(SchemeRequest q){EvaluationRules.validateComponentWeights(List.of(new EvaluationRules.WeightedItem(q.examEnabled(),q.examWeight()),new EvaluationRules.WeightedItem(q.taskEnabled(),q.taskWeight()),new EvaluationRules.WeightedItem(q.mentorEnabled(),q.mentorWeight()),new EvaluationRules.WeightedItem(q.stationEnabled(),q.stationWeight()),new EvaluationRules.WeightedItem(q.trainingEnabled(),q.trainingWeight())));EvaluationRules.validateQuarterWeights(q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight());validateMaxScores(List.of(maxScore(q.examMaxScore()),maxScore(q.taskMaxScore()),maxScore(q.mentorMaxScore()),maxScore(q.stationMaxScore()),maxScore(q.trainingMaxScore())));stationMode(q.stationAggregationMode());}
  private void validateTemplate(TemplateRequest q){EvaluationRules.validateComponentWeights(List.of(new EvaluationRules.WeightedItem(q.examEnabled(),q.examWeight()),new EvaluationRules.WeightedItem(q.taskEnabled(),q.taskWeight()),new EvaluationRules.WeightedItem(q.mentorEnabled(),q.mentorWeight()),new EvaluationRules.WeightedItem(q.stationEnabled(),q.stationWeight()),new EvaluationRules.WeightedItem(q.trainingEnabled(),q.trainingWeight())));EvaluationRules.validateQuarterWeights(q.quarterMonth1Weight(),q.quarterMonth2Weight(),q.quarterMonth3Weight());validateMaxScores(List.of(q.examMaxScore(),q.taskMaxScore(),q.mentorMaxScore(),q.stationMaxScore(),q.trainingMaxScore()));stationMode(q.stationAggregationMode());sourceWeightMap(q.examSourceWeights(),"exam_plan");sourceWeightMap(q.taskSourceWeights(),"challenge_task");}
  private void validateMaxScores(List<BigDecimal> values){if(values.stream().anyMatch(x->x==null||x.compareTo(BigDecimal.ZERO)<=0||x.compareTo(new BigDecimal("999.99"))>0))throw new BusinessException(400,"各评分项满分必须在0到999.99之间");}
  private void ensureWithinFullScore(Long employeeId,YearMonth month,String component,BigDecimal score){BigDecimal max=service.componentMaxScore(employeeId,month,component);if(score.compareTo(max)>0)throw new BusinessException(400,"评分不能超过该项满分 "+max.stripTrailingZeros().toPlainString());}
  private int pendingManualScores(YearMonth month){String role=SecurityUtils.current().role();if(!List.of("MENTOR","STATION_MANAGER","TRAINING_ADMIN").contains(role))return 0;return monthlyCandidates(month,null,null,null,"MY_PENDING").data().size();}
  private int pendingTaskReviews(){if(!SecurityUtils.current().can(Permissions.TASK_REVIEW))return 0;var filter=permissions.employeeFilter("e");Integer value=db.queryForObject("select count(*) from task_submission s join task_assignment a on a.id=s.assignment_id join employee e on e.id=a.employee_id where s.status='PENDING_REVIEW'"+filter.sql(),Integer.class,filter.args().toArray());return value==null?0:value;}
  private int pendingExamReviews(){if(!SecurityUtils.current().can(Permissions.EXAM_MANAGE))return 0;var filter=permissions.employeeFilter("e");Integer value=db.queryForObject("select count(*) from exam_attempt a join employee e on e.id=a.employee_id where a.status='PENDING_REVIEW'"+filter.sql(),Integer.class,filter.args().toArray());return value==null?0:value;}
  private int unpublishedExamResults(){if(!SecurityUtils.current().can(Permissions.EXAM_MANAGE))return 0;var filter=permissions.employeeFilter("e");Integer value=db.queryForObject("select count(*) from exam_attempt a join employee e on e.id=a.employee_id where a.status='GRADED' and a.published=false"+filter.sql(),Integer.class,filter.args().toArray());return value==null?0:value;}
  private static BigDecimal maxScore(BigDecimal value){return value==null?new BigDecimal("100"):value;}
  private String stationMode(String value){String mode=value==null||value.isBlank()?"AUTO_BY_DAYS":value;if(!Set.of("AUTO_BY_DAYS","PRIMARY_STATION").contains(mode))throw new BusinessException(400,"不支持的站点评价汇总方式");return mode;}
  private List<Map<String,Object>> sourceWeights(String table,String ownerColumn,Long ownerId,String component){return db.queryForList("select source_id sourceId,weight from "+table+" where "+ownerColumn+"=? and component_type=? order by weight desc,source_id",ownerId,component);}
  private Map<Long,BigDecimal> sourceWeightMap(List<SourceWeightRequest> values,String sourceTable){Map<Long,BigDecimal> result=new LinkedHashMap<>();for(var value:values==null?List.<SourceWeightRequest>of():values){if(result.put(value.sourceId(),value.weight())!=null)throw new BusinessException(400,"同一任务或考试不能重复配置权重");Integer exists=db.queryForObject("select count(*) from "+sourceTable+" where id=?",Integer.class,value.sourceId());if(exists==null||exists==0)throw new BusinessException(400,"权重配置引用的任务或考试不存在");}EvaluationRules.validateSourceWeights(result);return result;}
  private void replaceSourceWeights(String table,String ownerColumn,Long ownerId,List<SourceWeightRequest> exams,List<SourceWeightRequest> tasks){db.update("delete from "+table+" where "+ownerColumn+"=?",ownerId);for(var entry:sourceWeightMap(exams,"exam_plan").entrySet())db.update("insert into "+table+"("+ownerColumn+",component_type,source_id,weight) values(?,'EXAM',?,?)",ownerId,entry.getKey(),entry.getValue());for(var entry:sourceWeightMap(tasks,"challenge_task").entrySet())db.update("insert into "+table+"("+ownerColumn+",component_type,source_id,weight) values(?,'TASK',?,?)",ownerId,entry.getKey(),entry.getValue());}
  private boolean managesHistoricalStation(Long employeeId,YearMonth month,Long userId){for(var period:service.stationPeriods(employeeId,month)){Integer count=db.queryForObject("select count(*) from station_manager_scope where station_id=? and user_id=?",Integer.class,period.stationId(),userId);if(count!=null&&count>0)return true;}return false;}
  private void requireEvaluationEmployee(Long employeeId,YearMonth month){if(assignments.hasAnyAssignment(employeeId,month,SecurityUtils.current().id()))return;if("STATION_MANAGER".equals(SecurityUtils.current().role())){if(!managesHistoricalStation(employeeId,month,SecurityUtils.current().id()))throw new BusinessException(403,"无权访问该员工当月站点评价");}else permissions.requireEmployee(employeeId);}
  @SuppressWarnings("unchecked") private boolean canCurrentUserEvaluatePending(Map<String,Object> component){if(!"PENDING".equals(component.get("status")))return false;String code=Objects.toString(component.get("code"),"");Object raw=component.get("breakdown");List<?> entries=raw instanceof List<?> list?list:List.of();if("TRAINING".equals(code)){if(Boolean.TRUE.equals(component.get("canEvaluate")))return true;return "TRAINING_ADMIN".equals(SecurityUtils.current().role())&&numberOrZero(component.get("requiredCount"))==1&&entries.isEmpty();}return entries.stream().filter(Map.class::isInstance).map(x->(Map<String,Object>)x).anyMatch(x->Boolean.TRUE.equals(x.get("canEvaluate"))&&"PENDING".equals(x.get("status")));}
  private long numberOrZero(Object value){return value instanceof Number number?number.longValue():0;}
  private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
  private void ensureUnlocked(Long employeeId,YearMonth month){if(service.isLocked(employeeId,month))throw new BusinessException(409,"月度评价已发布，请由管理员重开后修改");}
  private void requireAdmin(){if(!List.of("ADMIN","SUPER_ADMIN").contains(SecurityUtils.current().role()))throw new BusinessException(403,"仅管理员可执行此操作");}
  private String componentForRole(String role){return switch(role){case "MENTOR"->"MENTOR";case "STATION_MANAGER"->"STATION";case "TRAINING_ADMIN"->"TRAINING";default->throw new BusinessException(403,"当前角色不能提交人工评分");};}
  @SuppressWarnings("unchecked") private Map<String,Object> component(Map<String,Object> detail,String code){return ((List<Map<String,Object>>)detail.get("components")).stream().filter(x->code.equals(x.get("code"))).findFirst().orElseThrow();}
  private void parseJson(Map<String,Object> row,String key){Object raw=row.get(key);if(raw instanceof String s){try{row.put(key,json.readValue(s,new TypeReference<Map<String,Object>>(){}));}catch(Exception ignored){}}}
}
