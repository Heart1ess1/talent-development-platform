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
    return created;
  }

  public List<Map<String,Object>> list(YearMonth month,String component,String status,Long reviewerId,String keyword){
    StringBuilder sql=new StringBuilder("""
      select t.id,t.employee_id,t.period_month,t.component_type,t.scope_id,t.due_at,t.note,t.status task_status,
        e.employee_no,e.name employee_name,b.name batch_name,s.name station_name,scope.name scope_name,
        exists(select 1 from score_summary ss where ss.employee_id=t.employee_id and ss.summary_type='MONTH' and ss.period_key=date_format(t.period_month,'%Y-%m') and ss.status='PUBLISHED') locked
      from evaluation_rating_task t join employee e on e.id=t.employee_id
      left join talent_batch b on b.id=e.batch_id left join service_station s on s.id=e.station_id
      left join service_station scope on scope.id=nullif(t.scope_id,0)
      where t.period_month=?
      """);
    List<Object> args=new ArrayList<>();args.add(month.atDay(1));
    if(component!=null&&!component.isBlank()){String code=component.toUpperCase(Locale.ROOT);requireComponent(code);sql.append(" and t.component_type=?");args.add(code);}
    if(reviewerId!=null){sql.append(" and exists(select 1 from evaluation_rating_reviewer rr where rr.task_id=t.id and rr.reviewer_user_id=? and rr.status='ACTIVE')");args.add(reviewerId);}
    if(keyword!=null&&!keyword.isBlank()){sql.append(" and (e.name like ? or e.employee_no like ? or b.name like ? or s.name like ?)");String q="%"+keyword.trim()+"%";args.addAll(List.of(q,q,q,q));}
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
      select t.*,e.employee_no,e.name employee_name,b.name batch_name,s.name station_name,scope.name scope_name,
        exists(select 1 from score_summary ss where ss.employee_id=t.employee_id and ss.summary_type='MONTH' and ss.period_key=date_format(t.period_month,'%Y-%m') and ss.status='PUBLISHED') locked
      from evaluation_rating_task t join employee e on e.id=t.employee_id
      left join talent_batch b on b.id=e.batch_id left join service_station s on s.id=e.station_id
      left join service_station scope on scope.id=nullif(t.scope_id,0) where t.id=?
      """,taskId);
    if(rows.isEmpty())throw new BusinessException(404,"评分任务不存在");return new LinkedHashMap<>(rows.get(0));
  }

  private Map<String,Object> decorate(Map<String,Object> task){
    long taskId=number(task.get("id")),employeeId=number(task.get("employee_id")),scopeId=number(task.get("scope_id"));
    String component=String.valueOf(task.get("component_type"));LocalDate month=toDate(task.get("period_month"));
    List<Map<String,Object>> reviewers=db.queryForList("""
      select r.reviewer_user_id reviewerId,u.display_name reviewerName,u.username,u.role,r.assigned_at assignedAt,
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
  private void addId(List<Long> values,Object value){if(value!=null)values.add(number(value));}
}
