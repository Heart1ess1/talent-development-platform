package com.talent.platform.schedule;

import com.talent.platform.common.BusinessException;
import com.talent.platform.evaluation.EvaluationRevisionService;
import com.talent.platform.security.*;
import com.talent.platform.task.TaskStatusService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

@Service
public class ScheduleService {
  private final JdbcTemplate db;private final PermissionService permissions;private final AuditService audit;
  private final EvaluationRevisionService revisions;private final TaskStatusService taskStatus;
  public ScheduleService(JdbcTemplate db,PermissionService permissions,AuditService audit,EvaluationRevisionService revisions,TaskStatusService taskStatus){this.db=db;this.permissions=permissions;this.audit=audit;this.revisions=revisions;this.taskStatus=taskStatus;}
  public record Request(@Pattern(regexp="IMMEDIATE|OFFSET|MANUAL") String scheduleMode,LocalDateTime startsAt,@NotNull LocalDateTime endsAt,LocalDate baseDate,@Min(0) Integer offsetDays,@NotNull Integer version,@NotBlank @Size(max=1000) String reason,Long participantId){}
  private String type(String type){String value=type.toUpperCase(Locale.ROOT);if(!Set.of("TASK","EXAM","EVALUATION").contains(value))throw new BusinessException(400,"不支持的任务类型");return value;}
  private String table(String type){return switch(type){case "TASK"->"challenge_task";case "EXAM"->"exam_plan";default->"evaluation_rating_task";};}
  private String endColumn(String type){return switch(type){case "TASK"->"deadline";case "EXAM"->"ends_at";default->"due_at";};}
  private void require(String type){permissions.require(switch(type){case "TASK"->Permissions.TASK_MANAGE;case "EXAM"->Permissions.EXAM_MANAGE;default->Permissions.EVALUATION_MANAGE;});}
  private Map<String,Object> resource(String type,Long id,boolean lock){var rows=db.queryForList("select * from "+table(type)+" where id=?"+(lock?" for update":""),id);if(rows.isEmpty())throw new BusinessException(404,"任务不存在");return rows.get(0);}
  public Map<String,Object> detail(String requested,Long id){String type=type(requested);require(type);var row=new LinkedHashMap<>(resource(type,id,false));row.put("endsAt",row.get(endColumn(type)));row.put("participants",participants(type,id));row.put("opportunities",db.queryForList("select * from submission_opportunity where resource_type=? and resource_id=? order by id desc",type,id));return row;}
  public List<Map<String,Object>> participants(String type,Long id){
    if(type.equals("EVALUATION"))return db.queryForList("select u.id participant_id,u.display_name name,u.username employee_no,t.employee_id,t.period_month score_month,t.starts_at,t.due_at ends_at from evaluation_rating_reviewer r join evaluation_rating_task t on t.id=r.task_id join sys_user u on u.id=r.reviewer_user_id where t.id=? and r.status='ACTIVE'",id);
    String assignment=type.equals("TASK")?"task_assignment":"exam_assignment",fk=type.equals("TASK")?"task_id":"plan_id";
    return db.queryForList("select a.*,e.id participant_id,e.name,e.employee_no from "+assignment+" a join employee e on e.id=a.employee_id where a."+fk+"=? order by e.employee_no",id);
  }
  public Map<String,Object> preview(String requested,Long id,Request request){
    String type=type(requested);require(type);var row=resource(type,id,false);
    if(((Number)row.get("schedule_version")).intValue()!=request.version())throw new BusinessException(409,"时限已被其他人修改，请刷新");
    TimeWindow window=TimeWindow.resolve(request.scheduleMode(),request.startsAt(),request.endsAt(),request.baseDate(),request.offsetDays(),TimeWindow.now());
    if(!window.endsAt().isAfter(TimeWindow.now()))throw new BusinessException(400,"新截止时间必须晚于当前时间");
    var people=participants(type,id);if(request.participantId()!=null)people=people.stream().filter(x->Objects.equals(((Number)x.get("participant_id")).longValue(),request.participantId())).toList();
    if(people.isEmpty())throw new BusinessException(400,"未找到对应的任务参与人员");
    List<Map<String,Object>> impacts=new ArrayList<>();
    for(var person:people){var p=new LinkedHashMap<>(person);p.put("oldMonth",Objects.toString(person.get("score_month"),"").substring(0,Math.min(7,Objects.toString(person.get("score_month"),"").length())));p.put("newMonth",type.equals("EVALUATION")?p.get("oldMonth"):YearMonth.from(window.endsAt()).toString());impacts.add(p);}
    var result=new LinkedHashMap<String,Object>();result.put("window",window);result.put("version",request.version());result.put("participants",impacts);result.put("activePersonalCount",db.queryForObject("select count(*) from submission_opportunity where resource_type=? and resource_id=? and status='ACTIVE'",Integer.class,type,id));
    var summaries=new ArrayList<Map<String,Object>>();for(var p:impacts){Long employee=((Number)(type.equals("EVALUATION")?p.get("employee_id"):p.get("participant_id"))).longValue();for(String month:new LinkedHashSet<>(List.of(p.get("oldMonth").toString(),p.get("newMonth").toString())))if(!month.isBlank())summaries.addAll(db.queryForList("select id,employee_id,period_key,summary_type,version,final_score from score_summary where employee_id=? and status='PUBLISHED' and (period_key=? or period_key=concat(left(?,4),'-Q',quarter(cast(concat(?,'-01') as date))))",employee,month,month,month));}result.put("publishedSummaries",summaries);
    result.put("scoreImpacts",revisions.previewSchedule(type,id,window.endsAt(),impacts));return result;
  }
  @Transactional
  public Map<String,Object> change(String requested,Long id,Request request){
    String type=type(requested);require(type);var before=resource(type,id,true);var preview=preview(type,id,request);var window=(TimeWindow)preview.get("window");
    if(request.participantId()!=null)throw new BusinessException(400,"个人调整请使用开放一次补交入口");
    if(type.equals("EXAM")){
      Integer active=db.queryForObject("select count(*) from exam_attempt where plan_id=? and status='IN_PROGRESS' and deadline_at>now() and (deadline_at>? or ?>now())",Integer.class,id,window.endsAt(),window.startsAt());
      if(active>0)throw new BusinessException(409,"新窗口会影响正在作答人员，请保留其已获得的答题时间");
    }
    var affected=affected(type,id,null);
    db.update("update "+table(type)+" set starts_at=?,"+endColumn(type)+"=?,schedule_mode=?,base_date=?,offset_days=?,schedule_version=schedule_version+1 where id=?",window.startsAt(),window.endsAt(),window.mode(),request.baseDate(),request.offsetDays(),id);
    if(type.equals("EVALUATION"))db.update("update submission_opportunity set status='ARCHIVED' where resource_type='EVALUATION' and resource_id=? and status='CONSUMED'",id);
    db.update("update submission_opportunity set starts_at=?,ends_at=? where resource_type=? and resource_id=? and status='ACTIVE'",window.startsAt(),window.endsAt(),type,id);
    if(!type.equals("EVALUATION")){
      String assignment=type.equals("TASK")?"task_assignment":"exam_assignment",fk=type.equals("TASK")?"task_id":"plan_id";
      db.update("update "+assignment+" set window_starts_at=null,window_ends_at=null,score_month=? where "+fk+"=?",YearMonth.from(window.endsAt()).atDay(1),id);
      if(type.equals("EXAM"))db.update("update exam_plan set score_month=? where id=?",YearMonth.from(window.endsAt()).atDay(1),id);
    }
    if(type.equals("TASK"))restoreTask(id,null);
    merge(affected,affected(type,id,null));Long group=revisions.changed(affected,request.reason());
    allowEvaluation(type,id,null,group);
    audit.log("ADJUST_TIME_WINDOW",type,id,before,request);taskStatus.rescheduleNextDeadline();
    var result=new LinkedHashMap<>(preview);result.put("revisionGroupId",group);return result;
  }
  @Transactional
  public Map<String,Object> grant(String requested,Long id,Request request){
    String type=type(requested);require(type);resource(type,id,true);var preview=preview(type,id,request);
    if(request.participantId()==null)throw new BusinessException(400,"请选择个人补交对象");
    long participant=request.participantId();
    if(type.equals("TASK")&&db.queryForObject("select count(*) from task_assignment where task_id=? and employee_id=? and status='PENDING_REVIEW'",Integer.class,id,participant)>0)throw new BusinessException(409,"当前成果正在评分，请先完成本轮评分");
    if(type.equals("EXAM")&&db.queryForObject("select count(*) from exam_attempt where plan_id=? and employee_id=? and status='IN_PROGRESS' and deadline_at>now()",Integer.class,id,participant)>0)throw new BusinessException(409,"该员工仍在考试中，请继续当前答卷");
    if(db.queryForObject("select count(*) from submission_opportunity where resource_type=? and resource_id=? and participant_id=? and status='ACTIVE'",Integer.class,type,id,participant)>0)throw new BusinessException(409,"该人员已有未使用的个人机会，请先撤销原机会");
    TimeWindow window=(TimeWindow)preview.get("window");var affected=affected(type,id,participant);
    db.update("insert into submission_opportunity(resource_type,resource_id,participant_id,starts_at,ends_at,reason,created_by) values(?,?,?,?,?,?,?)",type,id,participant,window.startsAt(),window.endsAt(),request.reason(),SecurityUtils.current().id());
    db.update("update "+table(type)+" set schedule_version=schedule_version+1 where id=?",id);
    Long grant=db.queryForObject("select last_insert_id()",Long.class);
    if(!type.equals("EVALUATION")){
      String assignment=type.equals("TASK")?"task_assignment":"exam_assignment",fk=type.equals("TASK")?"task_id":"plan_id";
      db.update("update "+assignment+" set window_starts_at=?,window_ends_at=?,score_month=? where "+fk+"=? and employee_id=?",window.startsAt(),window.endsAt(),YearMonth.from(window.endsAt()).atDay(1),id,participant);
    }
    if(type.equals("TASK"))restoreTask(id,participant);
    merge(affected,affected(type,id,participant));Long group=revisions.changed(affected,request.reason());
    allowEvaluation(type,id,participant,group);
    audit.log("GRANT_PERSONAL_OPPORTUNITY",type,id,null,request);taskStatus.rescheduleNextDeadline();
    var result=new LinkedHashMap<String,Object>();result.put("id",grant);result.put("revisionGroupId",group);return result;
  }
  @Transactional
  public void revoke(String requested,Long id,Long opportunityId){
    String type=type(requested);require(type);var row=resource(type,id,true);
    var found=db.queryForList("select * from submission_opportunity where id=? and resource_type=? and resource_id=? and status='ACTIVE' for update",opportunityId,type,id);
    if(found.isEmpty())throw new BusinessException(409,"机会已经使用或撤销");long participant=((Number)found.get(0).get("participant_id")).longValue();var affected=affected(type,id,participant);
    db.update("update submission_opportunity set status='REVOKED' where id=?",opportunityId);
    db.update("update "+table(type)+" set schedule_version=schedule_version+1 where id=?",id);
    if(!type.equals("EVALUATION")){
      String assignment=type.equals("TASK")?"task_assignment":"exam_assignment",fk=type.equals("TASK")?"task_id":"plan_id";
      LocalDate month=type.equals("EXAM")?LocalDate.parse(row.get("score_month").toString()):YearMonth.from(TimeWindow.time(row.get("deadline"))).atDay(1);
      db.update("update "+assignment+" set window_starts_at=null,window_ends_at=null,score_month=? where "+fk+"=? and employee_id=?",month,id,participant);
    }
    merge(affected,affected(type,id,participant));revisions.changed(affected,"撤销个人补交 #"+opportunityId);audit.log("REVOKE_PERSONAL_OPPORTUNITY",type,id,found.get(0),null);taskStatus.rescheduleNextDeadline();
  }
  private void allowEvaluation(String type,Long id,Long reviewer,Long group){
    if(!type.equals("EVALUATION"))return;
    db.update("update evaluation_rating_task set status='ACTIVE' where id=?",id);
    if(group!=null)db.update("insert ignore into evaluation_revision_permission(group_id,employee_id,period_month,component_type,scope_id,reviewer_id) select ?,t.employee_id,t.period_month,t.component_type,t.scope_id,r.reviewer_user_id from evaluation_rating_task t join evaluation_rating_reviewer r on r.task_id=t.id and r.status='ACTIVE' where t.id=?"+(reviewer==null?"":" and r.reviewer_user_id=?"),reviewer==null?new Object[]{group,id}:new Object[]{group,id,reviewer});
  }
  private void restoreTask(Long task,Long employee){db.update("update task_assignment a set status=case when exists(select 1 from task_submission s where s.assignment_id=a.id and s.status='RETURNED') then 'RETURNED' else 'NOT_SUBMITTED' end,final_score=(select s.score from task_submission s where s.assignment_id=a.id and s.status='APPROVED' order by s.submission_version desc limit 1),version=version+1 where a.task_id=? and a.status='OVERDUE'"+(employee==null?"":" and a.employee_id=?"),employee==null?new Object[]{task}:new Object[]{task,employee});}
  private Map<Long,Set<YearMonth>> affected(String type,Long id,Long participant){
    Map<Long,Set<YearMonth>> result=new LinkedHashMap<>();for(var p:participants(type,id)){
      if(participant!=null&&((Number)p.get("participant_id")).longValue()!=participant)continue;
      Object value=p.get("score_month");if(value==null)continue;long employee=((Number)p.get(type.equals("EVALUATION")?"employee_id":"participant_id")).longValue();result.computeIfAbsent(employee,k->new LinkedHashSet<>()).add(YearMonth.parse(value.toString().substring(0,7)));
    }return result;
  }
  private void merge(Map<Long,Set<YearMonth>> a,Map<Long,Set<YearMonth>> b){b.forEach((id,months)->a.computeIfAbsent(id,k->new LinkedHashSet<>()).addAll(months));}
  public Map<String,Object> activeOpportunity(String type,Long id,Long participant){var rows=db.queryForList("select * from submission_opportunity where resource_type=? and resource_id=? and participant_id=? and status='ACTIVE' and starts_at<=now() and ends_at>now() for update",type,id,participant);return rows.isEmpty()?null:rows.get(0);}
  @Transactional
  public Map<String,Object> assertTaskOpen(Long assignment){
    var row=db.queryForMap("select a.*,t.starts_at,t.deadline from task_assignment a join challenge_task t on t.id=a.task_id where a.id=? for update",assignment);
    var opportunity=activeOpportunity("TASK",((Number)row.get("task_id")).longValue(),((Number)row.get("employee_id")).longValue());
    if(opportunity!=null){row.put("opportunityId",opportunity.get("id"));return row;}
    if(row.get("window_ends_at")!=null)throw new BusinessException(400,"个人补交机会已使用、尚未开始或已到期");
    new TimeWindow(TimeWindow.time(row.get("starts_at")),TimeWindow.time(row.get("deadline")),"MANUAL").requireOpen();
    if(!Set.of("NOT_SUBMITTED","RETURNED","PENDING_REVIEW").contains(row.get("status")))throw new BusinessException(400,"当前状态不能提交，请申请个人补交机会");return row;
  }
  public void consume(Object id){if(id!=null&&db.update("update submission_opportunity set status='CONSUMED',consumed_at=now() where id=? and status='ACTIVE' and starts_at<=now() and ends_at>now()",id)!=1)throw new BusinessException(409,"个人机会已使用或已到期，请刷新");}
  public Map<String,Object> evaluationOpportunity(Long employee,YearMonth month,String component,long scope,Long reviewer){
    var tasks=db.queryForList("select id,starts_at,due_at from evaluation_rating_task where employee_id=? and period_month=? and component_type=? and scope_id=? for update",employee,month.atDay(1),component,scope);
    if(tasks.isEmpty())return null;var row=tasks.get(0);var opportunity=activeOpportunity("EVALUATION",((Number)row.get("id")).longValue(),reviewer);if(opportunity!=null)return opportunity;
    if(db.queryForObject("select count(*) from submission_opportunity where resource_type='EVALUATION' and resource_id=? and participant_id=? and status in ('ACTIVE','CONSUMED')",Integer.class,row.get("id"),reviewer)>0)throw new BusinessException(400,"个人补评分机会已使用、尚未开始或已到期");
    if(db.queryForObject("select count(*) from monthly_evaluation where employee_id=? and period_month=? and evaluator_type=? and scope_id=? and evaluator_user_id=? and submitted_at<?",Integer.class,employee,month.atDay(1),component,scope,reviewer,row.get("starts_at"))>0)throw new BusinessException(400,"该评分已完成，如需重评请申请个人补评分机会");
    LocalDateTime start=TimeWindow.time(row.get("starts_at")),end=TimeWindow.time(row.get("due_at"));
    if(start!=null&&TimeWindow.now().isBefore(start)||end!=null&&!TimeWindow.now().isBefore(end))throw new BusinessException(400,"评分任务未开始或已截止，请申请个人补评分");return null;
  }
}
