package com.talent.platform.evaluation;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class EvaluationAssignmentService {
  public static final Set<String> MANUAL_COMPONENTS=Set.of("MENTOR","STATION","TRAINING");
  private static final List<String> COMPONENT_ORDER=List.of("MENTOR","STATION","TRAINING");
  private static final Set<String> TARGET_TYPES=Set.of("ALL","BATCH","BUSINESS_UNIT");
  private final JdbcTemplate db;
  private final EvaluationService evaluation;

  public EvaluationAssignmentService(JdbcTemplate db,EvaluationService evaluation){this.db=db;this.evaluation=evaluation;}

  @Transactional
  public int generateMonth(YearMonth month,LocalDateTime dueAt){
    LocalDateTime effectiveDue=dueAt==null?month.atEndOfMonth().atTime(23,59,59):dueAt;
    long operator=SecurityUtils.current().id();int created=0;
    for(var employee:db.queryForList("select id,mentor_user_id,skill_mentor_user_id from employee where status='ACTIVE' and batch_id is not null order by id")){
      long employeeId=number(employee.get("id"));Map<String,Object> scheme;
      try{scheme=evaluation.schemeFor(employeeId,month);}catch(BusinessException ignored){continue;}
      if(enabled(scheme,"MENTOR")){
        List<Long> defaults=new ArrayList<>();addId(defaults,employee.get("mentor_user_id"));addId(defaults,employee.get("skill_mentor_user_id"));
        created+=createTask(employeeId,month,"MENTOR",0,effectiveDue,operator,defaults);
      }
      if(enabled(scheme,"STATION"))for(var station:evaluation.stationPeriods(employeeId,month)){
        List<Long> defaults=db.queryForList("select u.id from station_manager_scope s join sys_user u on u.id=s.user_id where s.station_id=? and u.enabled=true and u.role='STATION_MANAGER' order by u.display_name,u.id",Long.class,station.stationId());
        created+=createTask(employeeId,month,"STATION",station.stationId(),effectiveDue,operator,defaults);
      }
      if(enabled(scheme,"TRAINING"))created+=createTask(employeeId,month,"TRAINING",0,effectiveDue,operator,List.of());
    }
    for(String component:COMPONENT_ORDER)reapplyScopeRules(month,component,operator);
    return created;
  }

  public List<Map<String,Object>> overview(YearMonth month){
    List<Map<String,Object>> result=new ArrayList<>();
    for(String component:COMPONENT_ORDER){
      List<Map<String,Object>> tasks=list(month,component,null,null,null);var item=new LinkedHashMap<String,Object>();
      item.put("component",component);item.put("taskCount",tasks.size());item.put("employeeCount",tasks.stream().map(x->x.get("employee_id")).distinct().count());
      item.put("unassignedCount",tasks.stream().filter(x->"UNASSIGNED".equals(x.get("status"))).count());
      item.put("pendingCount",tasks.stream().filter(x->Set.of("PENDING","IN_PROGRESS","OVERDUE").contains(String.valueOf(x.get("status")))).count());
      item.put("completedCount",tasks.stream().filter(x->Set.of("COMPLETED","CLOSED").contains(String.valueOf(x.get("status")))).count());
      item.put("coveredTaskCount",tasks.stream().filter(x->number(x.get("reviewerCount"))>0).count());
      item.put("ruleCount",db.queryForObject("select count(*) from evaluation_reviewer_scope_rule where period_month=? and component_type=? and status='ACTIVE'",Integer.class,month.atDay(1),component));
      item.put("reviewerCount",db.queryForObject("select count(distinct m.reviewer_user_id) from evaluation_reviewer_scope_rule r join evaluation_reviewer_scope_member m on m.rule_id=r.id where r.period_month=? and r.component_type=? and r.status='ACTIVE'",Integer.class,month.atDay(1),component));
      result.add(item);
    }
    return result;
  }

  public List<Map<String,Object>> list(YearMonth month,String component,String status,Long reviewerId,String keyword){
    StringBuilder sql=new StringBuilder("""
      select t.id,t.employee_id,t.period_month,t.component_type,t.scope_id,t.due_at,t.note,t.status task_status,
        e.employee_no,e.name employee_name,e.class_id,cls.label class_name,
        e.class_position_id,cp.label class_position_name,
        b.name batch_name,bu.name business_unit_name,s.name station_name,scope.name scope_name,
        exists(select 1 from score_summary ss where ss.employee_id=t.employee_id and ss.summary_type='MONTH' and ss.period_key=date_format(t.period_month,'%Y-%m') and ss.status='PUBLISHED') locked
      from evaluation_rating_task t join employee e on e.id=t.employee_id
      left join talent_batch b on b.id=e.batch_id left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS' left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION' left join business_unit bu on bu.id=e.business_unit_id left join service_station s on s.id=e.station_id
      left join service_station scope on scope.id=nullif(t.scope_id,0)
      where t.period_month=?
      """);
    List<Object> args=new ArrayList<>();args.add(month.atDay(1));
    if(component!=null&&!component.isBlank()){String code=component.toUpperCase(Locale.ROOT);requireComponent(code);sql.append(" and t.component_type=?");args.add(code);}
    if(reviewerId!=null){sql.append(" and exists(select 1 from evaluation_rating_reviewer rr where rr.task_id=t.id and rr.reviewer_user_id=? and rr.status='ACTIVE')");args.add(reviewerId);}
    if(keyword!=null&&!keyword.isBlank()){sql.append(" and (e.name like ? or e.employee_no like ? or b.name like ? or bu.name like ? or s.name like ?)");String q="%"+keyword.trim()+"%";args.addAll(List.of(q,q,q,q,q));}
    sql.append(" order by t.due_at is null,t.due_at,b.name,e.employee_no,t.component_type,t.scope_id");
    List<Map<String,Object>> result=new ArrayList<>();
    for(var row:db.queryForList(sql.toString(),args.toArray())){
      var item=decorate(new LinkedHashMap<>(row));
      if(status==null||status.isBlank()||status.equals(item.get("status")))result.add(item);
    }
    return result;
  }

  public Map<String,Object> detail(Long taskId){return decorate(taskRow(taskId));}

  public List<Map<String,Object>> reviewerOptions(String component){
    String role=roleFor(requireComponent(component.toUpperCase(Locale.ROOT)));
    return db.queryForList("select id,username,display_name,role from sys_user where enabled=true and role=? order by display_name,username,id",role);
  }

  public List<Map<String,Object>> scopeRules(YearMonth month,String component){
    String code=requireComponent(component.toUpperCase(Locale.ROOT));
    List<Map<String,Object>> result=new ArrayList<>();
    for(var row:db.queryForList("select * from evaluation_reviewer_scope_rule where period_month=? and component_type=? and status='ACTIVE' order by case target_type when 'BUSINESS_UNIT' then 1 when 'BATCH' then 2 else 3 end,target_id,id",month.atDay(1),code)){
      var item=new LinkedHashMap<>(row);long ruleId=number(row.get("id"));String type=String.valueOf(row.get("target_type"));long targetId=number(row.get("target_id"));
      item.put("targetName",scopeTargetName(type,targetId));
      item.put("reviewers",db.queryForList("select u.id,u.username,u.display_name,u.role from evaluation_reviewer_scope_member m join sys_user u on u.id=m.reviewer_user_id where m.rule_id=? order by u.display_name,u.id",ruleId));
      item.put("matchedEmployeeCount",matchedCount(month,code,type,targetId,true));item.put("matchedTaskCount",matchedCount(month,code,type,targetId,false));
      result.add(item);
    }
    return result;
  }

  @Transactional
  public Long saveScopeRule(YearMonth month,String component,String targetType,Long requestedTargetId,Collection<Long> requestedReviewerIds,LocalDateTime dueAt,String note){
    String code=requireComponent(component.toUpperCase(Locale.ROOT));String type=requireTargetType(targetType.toUpperCase(Locale.ROOT));
    if(!"ALL".equals(type)&&requestedTargetId==null)throw new BusinessException(400,"请选择批次或板块");
    long targetId="ALL".equals(type)?0:requestedTargetId;
    validateTarget(type,targetId);LinkedHashSet<Long> reviewers=new LinkedHashSet<>(requestedReviewerIds==null?List.of():requestedReviewerIds);if(reviewers.isEmpty())throw new BusinessException(400,"请至少选择一名评分人");validateReviewers(code,reviewers);
    long operator=SecurityUtils.current().id();String normalizedNote=note==null||note.isBlank()?null:note.trim();
    List<Long> existing=db.queryForList("select id from evaluation_reviewer_scope_rule where period_month=? and component_type=? and target_type=? and target_id=?",Long.class,month.atDay(1),code,type,targetId);Long id;
    if(existing.isEmpty()){db.update("insert into evaluation_reviewer_scope_rule(period_month,component_type,target_type,target_id,due_at,note,created_by,updated_by) values(?,?,?,?,?,?,?,?)",month.atDay(1),code,type,targetId,dueAt,normalizedNote,operator,operator);id=db.queryForObject("select last_insert_id()",Long.class);}
    else{id=existing.get(0);db.update("update evaluation_reviewer_scope_rule set due_at=?,note=?,status='ACTIVE',updated_by=? where id=?",dueAt,normalizedNote,operator,id);db.update("delete from evaluation_reviewer_scope_member where rule_id=?",id);}
    for(Long reviewerId:reviewers)db.update("insert into evaluation_reviewer_scope_member(rule_id,reviewer_user_id) values(?,?)",id,reviewerId);
    reapplyScopeRules(month,code,operator);return id;
  }

  @Transactional
  public Map<String,Object> deleteScopeRule(Long id){
    var rows=db.queryForList("select * from evaluation_reviewer_scope_rule where id=?",id);if(rows.isEmpty())throw new BusinessException(404,"评分范围配置不存在");var before=new LinkedHashMap<>(rows.get(0));
    YearMonth month=YearMonth.from(toDate(before.get("period_month")));String component=String.valueOf(before.get("component_type"));db.update("delete from evaluation_reviewer_scope_rule where id=?",id);reapplyScopeRules(month,component,SecurityUtils.current().id());return before;
  }

  @Transactional
  public void assign(Collection<Long> taskIds,Collection<Long> requestedReviewerIds,String mode,LocalDateTime dueAt,String note){
    if(taskIds==null||taskIds.isEmpty())throw new BusinessException(400,"请选择评分任务");
    String effectiveMode=mode==null||mode.isBlank()?"REPLACE":mode.toUpperCase(Locale.ROOT);
    if(!Set.of("REPLACE","ADD").contains(effectiveMode))throw new BusinessException(400,"不支持的分配方式");
    LinkedHashSet<Long> reviewers=new LinkedHashSet<>(requestedReviewerIds==null?List.of():requestedReviewerIds);
    long operator=SecurityUtils.current().id();
    for(Long taskId:new LinkedHashSet<>(taskIds)){
      Map<String,Object> task=taskRow(taskId);if(locked(task))throw new BusinessException(409,"已发布月度结果的评分任务不能调整");
      String component=String.valueOf(task.get("component_type"));validateReviewers(component,reviewers);
      if("REPLACE".equals(effectiveMode))db.update("update evaluation_rating_reviewer set status='REMOVED',removed_at=now() where task_id=? and status='ACTIVE'",taskId);
      for(Long reviewerId:reviewers)db.update("insert into evaluation_rating_reviewer(task_id,reviewer_user_id,status,assigned_by) values(?,?,'ACTIVE',?) on duplicate key update status='ACTIVE',assigned_by=values(assigned_by),assigned_at=now(),removed_at=null",taskId,reviewerId,operator);
      String normalizedNote=note==null||note.isBlank()?null:note.trim();
      db.update("update evaluation_rating_task set due_at=coalesce(?,due_at),note=coalesce(?,note),updated_by=?,status='ACTIVE' where id=?",dueAt,normalizedNote,operator,taskId);
      evaluation.refreshDraftIfPresent(number(task.get("employee_id")),YearMonth.from(toDate(task.get("period_month"))));
    }
  }

  public boolean hasTask(Long employeeId,YearMonth month,String component,long scopeId){
    Integer count=db.queryForObject("select count(*) from evaluation_rating_task where employee_id=? and period_month=? and component_type=? and scope_id=? and status='ACTIVE'",Integer.class,employeeId,month.atDay(1),component,scopeId);return count!=null&&count>0;
  }

  public boolean isAssigned(Long employeeId,YearMonth month,String component,long scopeId,Long userId){
    Integer count=db.queryForObject("select count(*) from evaluation_rating_task t join evaluation_rating_reviewer r on r.task_id=t.id and r.status='ACTIVE' where t.employee_id=? and t.period_month=? and t.component_type=? and t.scope_id=? and t.status='ACTIVE' and r.reviewer_user_id=?",Integer.class,employeeId,month.atDay(1),component,scopeId,userId);return count!=null&&count>0;
  }

  public boolean hasAnyAssignment(Long employeeId,YearMonth month,Long userId){
    Integer count=db.queryForObject("select count(*) from evaluation_rating_task t join evaluation_rating_reviewer r on r.task_id=t.id and r.status='ACTIVE' where t.employee_id=? and t.period_month=? and t.status='ACTIVE' and r.reviewer_user_id=?",Integer.class,employeeId,month.atDay(1),userId);return count!=null&&count>0;
  }

  private int createTask(long employeeId,YearMonth month,String component,long scopeId,LocalDateTime dueAt,long operator,List<Long> defaults){
    int inserted=db.update("insert ignore into evaluation_rating_task(employee_id,period_month,component_type,scope_id,due_at,created_by,updated_by) values(?,?,?,?,?,?,?)",employeeId,month.atDay(1),component,scopeId,dueAt,operator,operator);
    if(inserted==0)return 0;
    Long taskId=db.queryForObject("select id from evaluation_rating_task where employee_id=? and period_month=? and component_type=? and scope_id=?",Long.class,employeeId,month.atDay(1),component,scopeId);
    for(Long reviewerId:new LinkedHashSet<>(defaults))if(eligibleReviewer(component,reviewerId))
      db.update("insert into evaluation_rating_reviewer(task_id,reviewer_user_id,status,assigned_by) values(?,?,'ACTIVE',?)",taskId,reviewerId,operator);
    return 1;
  }

  private Map<String,Object> taskRow(Long taskId){
    var rows=db.queryForList("""
      select t.*,e.employee_no,e.name employee_name,b.name batch_name,bu.name business_unit_name,s.name station_name,scope.name scope_name,
        exists(select 1 from score_summary ss where ss.employee_id=t.employee_id and ss.summary_type='MONTH' and ss.period_key=date_format(t.period_month,'%Y-%m') and ss.status='PUBLISHED') locked
      from evaluation_rating_task t join employee e on e.id=t.employee_id
      left join talent_batch b on b.id=e.batch_id left join business_unit bu on bu.id=e.business_unit_id left join service_station s on s.id=e.station_id
      left join service_station scope on scope.id=nullif(t.scope_id,0) where t.id=?
      """,taskId);
    if(rows.isEmpty())throw new BusinessException(404,"评分任务不存在");return new LinkedHashMap<>(rows.get(0));
  }

  private Map<String,Object> decorate(Map<String,Object> task){
    long taskId=number(task.get("id")),employeeId=number(task.get("employee_id")),scopeId=number(task.get("scope_id"));
    String component=String.valueOf(task.get("component_type"));LocalDate month=toDate(task.get("period_month"));
    List<Map<String,Object>> reviewers=db.queryForList("""
      select r.reviewer_user_id reviewerId,u.display_name reviewerName,u.username,u.role,r.assigned_at assignedAt,
        r.assignment_source assignmentSource,r.scope_rule_id scopeRuleId,
        m.score,m.comment,m.submitted_at submittedAt
      from evaluation_rating_reviewer r join sys_user u on u.id=r.reviewer_user_id
      left join monthly_evaluation m on m.employee_id=? and m.period_month=? and m.evaluator_type=? and m.scope_id=? and m.evaluator_user_id=r.reviewer_user_id
      where r.task_id=? and r.status='ACTIVE' order by u.display_name,u.id
      """,employeeId,month,component,scopeId,taskId);
    int submitted=(int)reviewers.stream().filter(x->x.get("score")!=null).count();
    List<BigDecimal> scores=reviewers.stream().filter(x->x.get("score")!=null).map(x->new BigDecimal(String.valueOf(x.get("score")))).toList();
    BigDecimal average=EvaluationRules.average(scores);
    boolean complete=!reviewers.isEmpty()&&submitted==reviewers.size();
    String state;if(locked(task)||"CLOSED".equals(task.get("task_status"))||"CLOSED".equals(task.get("status")))state="CLOSED";else if(reviewers.isEmpty())state="UNASSIGNED";else if(complete)state="COMPLETED";else if(isOverdue(task.get("due_at")))state="OVERDUE";else if(submitted>0)state="IN_PROGRESS";else state="PENDING";
    task.put("reviewers",reviewers);task.put("reviewerCount",reviewers.size());task.put("submittedCount",submitted);task.put("averageScore",average);task.put("finalAverageScore",complete?average:null);task.put("status",state);task.put("locked",locked(task));return task;
  }

  private void reapplyScopeRules(YearMonth month,String component,long operator){
    List<Map<String,Object>> rules=loadScopeRules(month,component);
    List<Map<String,Object>> tasks=db.queryForList("""
      select t.id,t.employee_id,t.due_at,t.note,e.batch_id,e.business_unit_id,
        exists(select 1 from score_summary s where s.employee_id=t.employee_id and s.summary_type='MONTH' and s.period_key=date_format(t.period_month,'%Y-%m') and s.status='PUBLISHED') locked
      from evaluation_rating_task t join employee e on e.id=t.employee_id
      where t.period_month=? and t.component_type=? and t.status='ACTIVE'
      """,month.atDay(1),component);
    if(rules.isEmpty()){
      db.update("update evaluation_rating_reviewer r join evaluation_rating_task t on t.id=r.task_id set r.status='REMOVED',r.removed_at=now() where t.period_month=? and t.component_type=? and r.status='ACTIVE' and r.assignment_source='SCOPE_RULE'",month.atDay(1),component);return;
    }
    for(var task:tasks){if(locked(task))continue;long taskId=number(task.get("id"));Map<String,Object> rule=bestRule(rules,numberNullable(task.get("batch_id")),numberNullable(task.get("business_unit_id")));
      db.update("update evaluation_rating_reviewer set status='REMOVED',removed_at=now() where task_id=? and status='ACTIVE'",taskId);
      if(rule==null)continue;long ruleId=number(rule.get("id"));
      for(Long reviewerId:db.queryForList("select reviewer_user_id from evaluation_reviewer_scope_member where rule_id=?",Long.class,ruleId))
        db.update("insert into evaluation_rating_reviewer(task_id,reviewer_user_id,status,assigned_by,assignment_source,scope_rule_id) values(?,?,'ACTIVE',?,'SCOPE_RULE',?) on duplicate key update status='ACTIVE',assigned_by=values(assigned_by),assignment_source='SCOPE_RULE',scope_rule_id=values(scope_rule_id),assigned_at=now(),removed_at=null",taskId,reviewerId,operator,ruleId);
      db.update("update evaluation_rating_task set due_at=coalesce(?,due_at),note=coalesce(?,note),updated_by=? where id=?",rule.get("due_at"),rule.get("note"),operator,taskId);
      evaluation.refreshDraftIfPresent(number(task.get("employee_id")),month);
    }
  }

  private List<Map<String,Object>> loadScopeRules(YearMonth month,String component){return db.queryForList("select * from evaluation_reviewer_scope_rule where period_month=? and component_type=? and status='ACTIVE' order by case target_type when 'BUSINESS_UNIT' then 3 when 'BATCH' then 2 else 1 end desc,id",month.atDay(1),component);}
  private Map<String,Object> bestRule(List<Map<String,Object>> rules,Long batchId,Long businessUnitId){for(var rule:rules)if(scopeMatches(String.valueOf(rule.get("target_type")),number(rule.get("target_id")),batchId,businessUnitId))return rule;return null;}
  static boolean scopeMatches(String type,long targetId,Long batchId,Long businessUnitId){return switch(type){case "ALL"->true;case "BATCH"->Objects.equals(batchId,targetId);case "BUSINESS_UNIT"->Objects.equals(businessUnitId,targetId);default->false;};}

  private int matchedCount(YearMonth month,String component,String type,long targetId,boolean employees){
    String filter=switch(type){case "ALL"->"";case "BATCH"->" and e.batch_id=?";case "BUSINESS_UNIT"->" and e.business_unit_id=?";default->throw new BusinessException(400,"不支持的适用范围");};
    List<Object> args=new ArrayList<>();args.add(month.atDay(1));args.add(component);if(!"ALL".equals(type))args.add(targetId);
    String aggregation=employees?"count(distinct t.employee_id)":"count(*)";
    String sql="select "+aggregation+" from evaluation_rating_task t join employee e on e.id=t.employee_id where t.period_month=? and t.component_type=? and t.status='ACTIVE'"+filter;
    Integer count=db.queryForObject(sql,Integer.class,args.toArray());return count==null?0:count;
  }

  private String scopeTargetName(String type,long targetId){return switch(type){case "ALL"->"全员";case "BATCH"->Objects.toString(db.queryForObject("select name from talent_batch where id=?",String.class,targetId),"批次");case "BUSINESS_UNIT"->Objects.toString(db.queryForObject("select name from business_unit where id=?",String.class,targetId),"板块");default->"未知范围";};}
  private void validateTarget(String type,long targetId){if("ALL".equals(type))return;String table="BATCH".equals(type)?"talent_batch":"business_unit";Integer count=db.queryForObject("select count(*) from "+table+" where id=? and enabled=true",Integer.class,targetId);if(count==null||count==0)throw new BusinessException(400,"所选批次或板块不存在或已停用");}
  private String requireTargetType(String type){if(!TARGET_TYPES.contains(type))throw new BusinessException(400,"不支持的适用范围");return type;}

  private void validateReviewers(String component,Set<Long> reviewerIds){
    if(reviewerIds.isEmpty())return;String role=roleFor(component);
    String placeholders=String.join(",",Collections.nCopies(reviewerIds.size(),"?"));List<Object> args=new ArrayList<>(reviewerIds);args.add(role);
    Integer count=db.queryForObject("select count(*) from sys_user where id in ("+placeholders+") and enabled=true and role=?",Integer.class,args.toArray());
    if(count==null||count!=reviewerIds.size())throw new BusinessException(400,"评分人账号不存在、已停用或角色与评分项不匹配");
  }

  private boolean eligibleReviewer(String component,Long reviewerId){
    Integer count=db.queryForObject("select count(*) from sys_user where id=? and enabled=true and role=?",Integer.class,reviewerId,roleFor(component));
    return count!=null&&count>0;
  }

  private String requireComponent(String component){if(!MANUAL_COMPONENTS.contains(component))throw new BusinessException(400,"仅人工评分项需要分配评分人");return component;}
  private String roleFor(String component){return switch(component){case "MENTOR"->"MENTOR";case "STATION"->"STATION_MANAGER";case "TRAINING"->"TRAINING_ADMIN";default->throw new BusinessException(400,"不支持的评分项");};}
  private boolean enabled(Map<String,Object> scheme,String component){Object value=scheme.get(component.toLowerCase(Locale.ROOT)+"_enabled");return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
  private boolean locked(Map<String,Object> task){Object value=task.get("locked");return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
  private boolean isOverdue(Object value){return value!=null&&toDateTime(value).isBefore(LocalDateTime.now());}
  private LocalDate toDate(Object value){if(value instanceof LocalDate d)return d;if(value instanceof java.sql.Date d)return d.toLocalDate();if(value instanceof Timestamp t)return t.toLocalDateTime().toLocalDate();return LocalDate.parse(String.valueOf(value).substring(0,10));}
  private LocalDateTime toDateTime(Object value){if(value instanceof LocalDateTime d)return d;if(value instanceof Timestamp t)return t.toLocalDateTime();return LocalDateTime.parse(String.valueOf(value).replace(' ','T')) ;}
  private long number(Object value){return ((Number)value).longValue();}
  private Long numberNullable(Object value){return value==null?null:((Number)value).longValue();}
  private void addId(List<Long> values,Object value){if(value!=null)values.add(number(value));}
}
