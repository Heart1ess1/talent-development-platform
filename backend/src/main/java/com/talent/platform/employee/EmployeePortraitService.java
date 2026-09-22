package com.talent.platform.employee;

import com.talent.platform.common.PageResult;
import com.talent.platform.security.*;
import org.springframework.stereotype.Service;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.talent.platform.employee.EmployeePortraitDtos.*;

@Service
public class EmployeePortraitService {
  private final EmployeePortraitQuery query;
  public EmployeePortraitService(EmployeePortraitQuery query){this.query=query;}

  public Overview overview(long employeeId){
    var employee=employee(query.employee(employeeId));var task=query.taskMetrics(employeeId);
    var material=query.materialMetrics(employeeId);var exam=query.examMetrics(employeeId);
    var exams=query.publishedExamTrend(employeeId);var month=query.evaluationTrend(employeeId,"MONTH");
    var quarter=query.evaluationTrend(employeeId,"QUARTER");
    long taskTotal=n(task,"total"),taskApproved=n(task,"approved"),materialTotal=n(material,"total"),materialViewed=n(material,"viewed");
    long examTotal=n(exam,"total"),examCompleted=n(exam,"completed");
    var validScores=exams.stream().filter(x->decimal(x,"total_score")!=null&&positive(decimal(x,"max_score"))).toList();
    BigDecimal avg=validScores.isEmpty()?null:validScores.stream().map(x->decimal(x,"total_score").multiply(BigDecimal.valueOf(100)).divide(decimal(x,"max_score"),2,RoundingMode.HALF_UP)).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(validScores.size()),2,RoundingMode.HALF_UP);
    var metrics=List.of(
      ratio("taskCompletion","任务完成率",taskApproved,taskTotal,"已通过任务"),
      ratio("materialCoverage","课件阅读覆盖率",materialViewed,materialTotal,"有阅读记录的课件"),
      ratio("examCompletion","考试完成情况",examCompleted,examTotal,"已交卷考试计划"),
      value("examAverage","已发布考试平均分",avg,(long)validScores.size(),"分",validScores.isEmpty()?"EMPTY":"VALUE",validScores.isEmpty()?"暂无已发布成绩":"按每个考试计划最近一次正式成绩折算为百分制"),
      latest("latestMonth","最新月度评价",month,"MONTH"),latest("latestQuarter","最新季度评价",quarter,"QUARTER"));
    return new Overview(employee,location(query.latestLocation(employeeId)),metrics,
      taskChart(task),chart(query.courseParticipation(employeeId),"state","count"),examChart(exams),
      chart(month,"period_key","final_score"),chart(quarter,"period_key","final_score"),LocalDateTime.now());
  }

  public Courses courses(long employeeId,String type,int page,int size){return courses(employeeId,type,page,size,null,null);}
  public Courses courses(long employeeId,String type,int page,int size,LocalDate dateFrom,LocalDate dateTo){
    requireDateRange(dateFrom,dateTo);
    int limit=limit(size),offset=offset(page,limit);String selected=normalize(type,"SESSIONS",Set.of("SESSIONS","MATERIALS"));
    if("MATERIALS".equals(selected)){
      var rangeArgs=new ArrayList<Object>();String range=dateFilter("max(v.last_seen_at)",dateFrom,dateTo,rangeArgs);
      var countArgs=new ArrayList<Object>();countArgs.add(employeeId);countArgs.add(employeeId);countArgs.addAll(rangeArgs);
      long total=query.count("select count(*) from (select m.id from course_material m join (select distinct cs.course_id from course_enrollment ce join course_session cs on cs.id=ce.session_id where ce.employee_id=?) assigned on assigned.course_id=m.course_id left join course_material_view_session v on v.material_id=m.id and v.employee_id=? group by m.id having 1=1"+range+") portrait_materials",countArgs.toArray());
      var args=new ArrayList<Object>();args.add(employeeId);args.add(employeeId);args.addAll(rangeArgs);args.add(limit);args.add(offset);
      var rows=query.rows("""
        select m.id,c.name course_name,m.original_name,
          count(distinct v.id) view_count,coalesce(sum(v.duration_seconds),0) duration_seconds,max(v.last_seen_at) last_viewed_at
        from course_material m join course c on c.id=m.course_id
        join (select distinct cs.course_id from course_enrollment ce join course_session cs on cs.id=ce.session_id where ce.employee_id=?) assigned on assigned.course_id=m.course_id
        left join course_material_view_session v on v.material_id=m.id and v.employee_id=?
        group by m.id,c.name,m.original_name having 1=1
        """+range+" order by c.name,m.id limit ? offset ?",args.toArray());
      var data=rows.stream().map(x->new CourseMaterial(nl(x,"id"),s(x,"course_name"),s(x,"original_name"),n(x,"view_count")>0,n(x,"view_count"),n(x,"duration_seconds"),dt(x,"last_viewed_at"))).toList();
      return new Courses(new PageResult<>(data,total,page,limit),selected,LocalDateTime.now());
    }
    var args=new ArrayList<Object>();args.add(employeeId);String range=dateFilter("cs.starts_at",dateFrom,dateTo,args);
    long total=query.count("select count(*) from course_enrollment ce join course_session cs on cs.id=ce.session_id where ce.employee_id=?"+range,args.toArray());args.add(limit);args.add(offset);
    var rows=query.rows("""
      select cs.id,c.name course_name,cs.title,cs.starts_at,cs.ends_at,cs.location,
        a.status attendance_status,a.checked_at,a.source,
        case when cs.starts_at>now() then 'UPCOMING' when cs.ends_at>=now() then 'IN_PROGRESS' when a.id is not null then 'ATTENDED' else 'ABSENT' end participation_status
      from course_enrollment ce join course_session cs on cs.id=ce.session_id join course c on c.id=cs.course_id
      left join attendance a on a.session_id=cs.id and a.employee_id=ce.employee_id
      where ce.employee_id=?
      """+range+" order by cs.starts_at desc,cs.id desc limit ? offset ?",args.toArray());
    var data=rows.stream().map(x->new CourseSession(nl(x,"id"),s(x,"course_name"),s(x,"title"),dt(x,"starts_at"),dt(x,"ends_at"),s(x,"location"),s(x,"participation_status"),s(x,"attendance_status"),dt(x,"checked_at"),s(x,"source"))).toList();
    return new Courses(new PageResult<>(data,total,page,limit),selected,LocalDateTime.now());
  }

  public PageResult<Task> tasks(long employeeId,String status,int page,int size){return tasks(employeeId,status,page,size,null,null);}
  public PageResult<Task> tasks(long employeeId,String status,int page,int size,LocalDate dateFrom,LocalDate dateTo){
    requireDateRange(dateFrom,dateTo);
    int limit=limit(size),offset=offset(page,limit);var args=new ArrayList<Object>();args.add(employeeId);
    String filter="";
    if(status!=null&&!status.isBlank()){
      String selected=normalize(status,status,Set.of("NOT_SUBMITTED","PENDING_REVIEW","RETURNED","APPROVED","OVERDUE"));
      String overdue="(a.status='OVERDUE' or (a.status<>'APPROVED' and t.deadline is not null and t.deadline<now()))";
      if("OVERDUE".equals(selected))filter=" and "+overdue;
      else if(!"APPROVED".equals(selected)) {filter=" and a.status=? and not "+overdue;args.add(selected);}
      else {filter=" and a.status=?";args.add(selected);}
    }
    filter+=dateFilter("t.deadline",dateFrom,dateTo,args);
    long total=query.count("select count(*) from task_assignment a join challenge_task t on t.id=a.task_id where a.employee_id=?"+filter,args.toArray());args.add(limit);args.add(offset);
    var rows=query.rows("""
      select a.id,t.title,tp.name training_plan_name,a.assigned_at,t.deadline,a.status,a.final_score,
        a.batch_name_snapshot,a.business_unit_name_snapshot,a.class_name_snapshot,
        s.submission_version,s.submitted_at,s.reviewed_at,
        case when rs.id is null then null else concat(coalesce(rs.batch_name,'全部批次'),' / ',coalesce(rs.business_unit_name,'全部板块'),' / ',coalesce(rs.class_name,'全部班级')) end scoring_scope_label,
        (select group_concat(u.display_name order by u.display_name,u.id separator '、') from task_reviewer_scope_member m join sys_user u on u.id=m.reviewer_user_id where m.scope_id=a.scoring_scope_id) reviewer_names,
        case when s.id is null then (select count(*) from task_reviewer_scope_member m where m.scope_id=a.scoring_scope_id)
             else (select count(*) from task_submission_review r where r.submission_id=s.id) end reviewer_count,
        (select count(*) from task_submission_review r where r.submission_id=s.id and r.status='SUBMITTED') reviewed_count
      from task_assignment a join challenge_task t on t.id=a.task_id left join training_plan tp on tp.id=t.training_plan_id
      left join task_reviewer_scope rs on rs.id=a.scoring_scope_id
      left join task_submission s on s.id=(select x.id from task_submission x where x.assignment_id=a.id order by x.submission_version desc,x.id desc limit 1)
      where a.employee_id=?
      """+filter+" order by t.deadline desc,a.id desc limit ? offset ?",args.toArray());
    var assignmentIds=rows.stream().map(x->x.get("id")).toArray();
    Map<Long,List<TaskSubmission>> histories=assignmentIds.length==0?Map.of():taskSubmissionHistories(assignmentIds);
    var data=rows.stream().map(x->{var deadline=dt(x,"deadline");var reviewed=dt(x,"reviewed_at");boolean approved="APPROVED".equals(s(x,"status"));return new Task(nl(x,"id"),s(x,"title"),or(s(x,"training_plan_name"),"独立任务"),dt(x,"assigned_at"),deadline,s(x,"status"),integer(x,"submission_version"),dt(x,"submitted_at"),n(x,"reviewer_count"),n(x,"reviewed_count"),decimal(x,"final_score"),reviewed,s(x,"scoring_scope_label"),s(x,"reviewer_names"),!approved&&deadline!=null&&deadline.isBefore(LocalDateTime.now()),approved&&reviewed!=null&&deadline!=null&&reviewed.isAfter(deadline),s(x,"batch_name_snapshot"),s(x,"business_unit_name_snapshot"),s(x,"class_name_snapshot"),histories.getOrDefault(nl(x,"id"),List.of()));}).toList();
    return new PageResult<>(data,total,page,limit);
  }

  public PageResult<Exam> exams(long employeeId,int page,int size){return exams(employeeId,page,size,null,null);}
  public PageResult<Exam> exams(long employeeId,int page,int size,LocalDate dateFrom,LocalDate dateTo){
    requireDateRange(dateFrom,dateTo);int limit=limit(size),offset=offset(page,limit);var args=new ArrayList<Object>();args.add(employeeId);String range=dateFilter("p.starts_at",dateFrom,dateTo,args);
    long total=query.count("select count(*) from exam_assignment ea join exam_plan p on p.id=ea.plan_id where ea.employee_id=?"+range,args.toArray());args.add(limit);args.add(offset);
    var plans=query.rows("""
      select p.id,p.name,p.starts_at,p.ends_at,p.max_attempts,
        case when p.status='DRAFT' then 'DRAFT' when p.ends_at<now() then 'ENDED' when p.starts_at>now() then 'UPCOMING' else 'OPEN' end plan_phase
      from exam_assignment ea join exam_plan p on p.id=ea.plan_id where ea.employee_id=?
      """+range+" order by p.starts_at desc,p.id desc limit ? offset ?",args.toArray());
    if(plans.isEmpty())return new PageResult<>(List.of(),total,page,limit);
    String marks=String.join(",",Collections.nCopies(plans.size(),"?"));var ids=plans.stream().map(x->x.get("id")).toArray();
    boolean manage=SecurityUtils.current().can(Permissions.EXAM_MANAGE);
    var attemptRows=query.rows("""
      select a.id,a.plan_id,a.attempt_no,a.status,a.started_at,a.submitted_at,
        case when ? or a.published=true then a.total_score else null end total_score,
        case when ? or a.published=true then a.published else false end published,
        coalesce((select sum(q.score) from exam_attempt_question q where q.attempt_id=a.id),
                 (select sum(q.score) from exam_paper_question q where q.paper_id=p.paper_id)) max_score
      from exam_attempt a join exam_plan p on p.id=a.plan_id where a.employee_id=? and a.plan_id in ("""+marks+") order by a.plan_id,a.attempt_no",prepend(new Object[]{manage,manage,employeeId},ids));
    var grouped=attemptRows.stream().collect(Collectors.groupingBy(x->nl(x,"plan_id"),LinkedHashMap::new,Collectors.toList()));
    var data=plans.stream().map(p->{var attempts=grouped.getOrDefault(nl(p,"id"),List.of()).stream().map(a->new ExamAttempt(nl(a,"id"),integer(a,"attempt_no"),s(a,"status"),dt(a,"started_at"),dt(a,"submitted_at"),decimal(a,"total_score"),decimal(a,"max_score"),bool(a,"published"))).toList();var end=dt(p,"ends_at");String participation=attempts.isEmpty()?(end!=null&&end.isBefore(LocalDateTime.now())?"ABSENT":"NOT_STARTED"):attempts.stream().anyMatch(a->"IN_PROGRESS".equals(a.status()))?"IN_PROGRESS":attempts.stream().anyMatch(a->"PENDING_REVIEW".equals(a.status()))?"PENDING_REVIEW":"COMPLETED";return new Exam(nl(p,"id"),s(p,"name"),dt(p,"starts_at"),end,s(p,"plan_phase"),participation,integer(p,"max_attempts"),attempts);}).toList();
    return new PageResult<>(data,total,page,limit);
  }

  public PageResult<Evaluation> evaluations(long employeeId,String type,int page,int size){return evaluations(employeeId,type,page,size,null,null);}
  public PageResult<Evaluation> evaluations(long employeeId,String type,int page,int size,LocalDate dateFrom,LocalDate dateTo){
    requireDateRange(dateFrom,dateTo);int limit=limit(size),offset=offset(page,limit);String selected=normalize(type,"MONTH",Set.of("MONTH","QUARTER"));boolean manage=SecurityUtils.current().can(Permissions.EVALUATION_MANAGE);
    String visibility=manage?"":" and status='PUBLISHED'";var args=new ArrayList<Object>();args.add(employeeId);args.add(selected);visibility+=dateFilter("generated_at",dateFrom,dateTo,args);long total=query.count("select count(*) from score_summary where employee_id=? and summary_type=?"+visibility,args.toArray());args.add(limit);args.add(offset);
    var rows=query.rows("""
      select id,summary_type,period_key,version,status,exam_score,task_score,mentor_score,station_score,training_score,
        bonus,deduction,final_score,missing_items,cast(component_snapshot as char) component_snapshot,
        cast(quarter_snapshot as char) quarter_snapshot,generated_at,published_at
      from score_summary where employee_id=? and summary_type=?
      """+visibility+" order by period_key desc,version desc limit ? offset ?",args.toArray());
    var data=rows.stream().map(x->new Evaluation(nl(x,"id"),s(x,"summary_type"),s(x,"period_key"),integer(x,"version"),s(x,"status"),decimal(x,"exam_score"),decimal(x,"task_score"),decimal(x,"mentor_score"),decimal(x,"station_score"),decimal(x,"training_score"),decimal(x,"bonus"),decimal(x,"deduction"),decimal(x,"final_score"),s(x,"missing_items"),s(x,"component_snapshot"),s(x,"quarter_snapshot"),dt(x,"generated_at"),dt(x,"published_at"),!"PUBLISHED".equals(s(x,"status")))).toList();
    return new PageResult<>(data,total,page,limit);
  }

  public Stations stations(long employeeId){
    var emp=employee(query.employee(employeeId));var rows=query.rows("""
      select r.id,cs.name current_station_name,ns.name requested_station_name,r.reviewed_at,r.review_comment
      from station_change_request r left join service_station cs on cs.id=r.current_station_id
      left join service_station ns on ns.id=r.requested_station_id where r.employee_id=? and r.status='APPROVED'
      order by r.reviewed_at desc,r.id desc
      """,employeeId);
    var history=rows.stream().map(x->new StationHistory(nl(x,"id"),s(x,"current_station_name"),s(x,"requested_station_name"),dt(x,"reviewed_at"),"APPROVED_CHANGE",s(x,"review_comment"))).toList();
    return new Stations(emp,history,LocalDateTime.now());
  }

  public Locations locations(long employeeId,int page,int size){return locations(employeeId,page,size,null,null);}
  public Locations locations(long employeeId,int page,int size,LocalDate dateFrom,LocalDate dateTo){
    requireDateRange(dateFrom,dateTo);int limit=limit(size),offset=offset(page,limit);var args=new ArrayList<Object>();args.add(employeeId);String range=dateFilter("occurred_at",dateFrom,dateTo,args);long total=query.count("select count(*) from employee_location_report where employee_id=?"+range,args.toArray());args.add(limit);args.add(offset);
    var rows=query.rows("select id,from_location,to_location,reason,occurred_at,expected_return_at,report_source,created_at from employee_location_report where employee_id=?"+range+" order by occurred_at desc,id desc limit ? offset ?",args.toArray());
    var data=rows.stream().map(x->new Location(nl(x,"id"),s(x,"from_location"),s(x,"to_location"),s(x,"reason"),dt(x,"occurred_at"),dt(x,"expected_return_at"),s(x,"report_source"),dt(x,"created_at"))).toList();
    return new Locations(location(query.latestLocation(employeeId)),new PageResult<>(data,total,page,limit),LocalDateTime.now());
  }

  public PageResult<TimelineEvent> timeline(long employeeId,String type,int page,int size){return timeline(employeeId,type,page,size,null,null);}
  public PageResult<TimelineEvent> timeline(long employeeId,String type,int page,int size,LocalDate dateFrom,LocalDate dateTo){
    requireDateRange(dateFrom,dateTo);
    var events=new ArrayList<TimelineEvent>();var emp=query.employee(employeeId);var onboard=date(emp,"onboard_date");
    if(onboard!=null)events.add(new TimelineEvent("ONBOARD-"+employeeId,"ONBOARD",onboard.atStartOfDay(),"员工入职","COMPLETED",employeeId,false));
    query.rows("select a.id,a.status,a.checked_at,c.name,cs.title from attendance a join course_session cs on cs.id=a.session_id join course c on c.id=cs.course_id where a.employee_id=?",employeeId).forEach(x->events.add(event("ATTENDANCE",x,"checked_at",s(x,"name")+" · "+s(x,"title"),s(x,"status"))));
    query.rows("select s.id,s.status,s.submitted_at,t.title from task_submission s join task_assignment a on a.id=s.assignment_id join challenge_task t on t.id=a.task_id where a.employee_id=?",employeeId).forEach(x->events.add(event("TASK",x,"submitted_at",s(x,"title")+" · 提交任务",s(x,"status"))));
    query.rows("select s.id,s.status,s.reviewed_at,t.title from task_submission s join task_assignment a on a.id=s.assignment_id join challenge_task t on t.id=a.task_id where a.employee_id=? and s.reviewed_at is not null",employeeId).forEach(x->events.add(event("TASK_REVIEW",x,"reviewed_at",s(x,"title")+("APPROVED".equals(s(x,"status"))?" · 审核通过":" · 审核退回"),s(x,"status"))));
    query.rows("select a.id,a.status,a.submitted_at,p.name from exam_attempt a join exam_plan p on p.id=a.plan_id where a.employee_id=? and a.submitted_at is not null",employeeId).forEach(x->events.add(event("EXAM",x,"submitted_at",s(x,"name")+" · 考试交卷",s(x,"status"))));
    query.rows("select id,status,published_at,period_key,summary_type from score_summary where employee_id=? and status='PUBLISHED' and published_at is not null",employeeId).forEach(x->events.add(event("EVALUATION",x,"published_at",s(x,"period_key")+("MONTH".equals(s(x,"summary_type"))?"月度":"季度")+"评价发布",s(x,"status"))));
    query.rows("select id,reviewed_at,'APPROVED' status from station_change_request where employee_id=? and status='APPROVED' and reviewed_at is not null",employeeId).forEach(x->events.add(event("STATION",x,"reviewed_at","服务站调整生效",s(x,"status"))));
    query.rows("select id,occurred_at,'RECORDED' status,to_location from employee_location_report where employee_id=?",employeeId).forEach(x->events.add(event("LOCATION",x,"occurred_at","位置报备："+s(x,"to_location"),s(x,"status"))));
    if(type!=null&&!type.isBlank())events.removeIf(x->!x.type().equals(type));
    if(dateFrom!=null)events.removeIf(x->x.occurredAt()==null||x.occurredAt().toLocalDate().isBefore(dateFrom));
    if(dateTo!=null)events.removeIf(x->x.occurredAt()==null||x.occurredAt().toLocalDate().isAfter(dateTo));
    events.sort(Comparator.comparing(TimelineEvent::timeMissing).thenComparing(TimelineEvent::occurredAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(TimelineEvent::id));
    int limit=limit(size),start=Math.min(offset(page,limit),events.size()),end=Math.min(start+limit,events.size());
    return new PageResult<>(events.subList(start,end),events.size(),page,limit);
  }

  private TimelineEvent event(String type,Map<String,Object>x,String time,String title,String status){var at=dt(x,time);return new TimelineEvent(type+"-"+nl(x,"id")+"-"+time,type,at,title,status,nl(x,"id"),at==null);}
  private Map<Long,List<TaskSubmission>> taskSubmissionHistories(Object[] assignmentIds){
    var current=SecurityUtils.current();
    boolean reviewAll=Set.of("TRAINING_ADMIN","ADMIN","SUPER_ADMIN").contains(current.role());
    String marks=String.join(",",Collections.nCopies(assignmentIds.length,"?"));
    var submissions=query.rows("select id,assignment_id,submission_version,status,submitted_at,reviewed_at,score,review_comment from task_submission where assignment_id in ("+marks+") order by assignment_id,submission_version desc,id desc",assignmentIds);
    if(submissions.isEmpty())return Map.of();
    var submissionIds=submissions.stream().map(x->x.get("id")).toArray();
    String reviewMarks=String.join(",",Collections.nCopies(submissionIds.length,"?"));
    var reviews=query.rows("select r.submission_id,r.reviewer_user_id,u.display_name,r.status,r.decision,r.score,r.comment,r.submitted_at from task_submission_review r join sys_user u on u.id=r.reviewer_user_id where r.submission_id in ("+reviewMarks+") order by r.submission_id,u.display_name,r.reviewer_user_id",submissionIds);
    var groupedReviews=reviews.stream().collect(Collectors.groupingBy(x->nl(x,"submission_id"),LinkedHashMap::new,Collectors.toList()));
    var files=query.rows("select id,submission_id,original_name,content_type,size from stored_file where submission_id in ("+reviewMarks+") order by submission_id,id",submissionIds);
    var groupedFiles=files.stream().collect(Collectors.groupingBy(x->nl(x,"submission_id"),LinkedHashMap::new,Collectors.mapping(x->new SubmissionFile(nl(x,"id"),s(x,"original_name"),s(x,"content_type"),n(x,"size")),Collectors.toList())));
    return submissions.stream().collect(Collectors.groupingBy(x->nl(x,"assignment_id"),LinkedHashMap::new,Collectors.mapping(x->{
      var rawReviews=groupedReviews.getOrDefault(nl(x,"id"),List.of());
      boolean finished=Set.of("APPROVED","RETURNED").contains(s(x,"status"));
      var visibleReviews=rawReviews.stream().map(r->{boolean detailsVisible=reviewAll||finished||Objects.equals(current.id(),nl(r,"reviewer_user_id"));return new TaskReview(nl(r,"reviewer_user_id"),s(r,"display_name"),s(r,"status"),detailsVisible?s(r,"decision"):null,detailsVisible?decimal(r,"score"):null,detailsVisible?s(r,"comment"):null,dt(r,"submitted_at"));}).toList();
      long reviewed=rawReviews.stream().filter(r->"SUBMITTED".equals(s(r,"status"))).count();
      return new TaskSubmission(nl(x,"id"),integer(x,"submission_version"),s(x,"status"),dt(x,"submitted_at"),dt(x,"reviewed_at"),decimal(x,"score"),reviewAll?s(x,"review_comment"):null,rawReviews.size(),reviewed,groupedFiles.getOrDefault(nl(x,"id"),List.of()),visibleReviews);
    },Collectors.toList())));
  }
  private Metric ratio(String key,String label,long numerator,long denominator,String note){return new Metric(key,label,denominator==0?null:BigDecimal.valueOf(numerator*100).divide(BigDecimal.valueOf(denominator),1,RoundingMode.HALF_UP),numerator,denominator,"%",denominator==0?"UNASSIGNED":"VALUE",note);}
  private Metric value(String key,String label,BigDecimal value,Long numerator,String unit,String state,String note){return new Metric(key,label,value,numerator,null,unit,state,note);}
  private Metric latest(String key,String label,List<Map<String,Object>> rows,String type){if(rows.isEmpty())return value(key,label,null,0L,"分","EMPTY","暂无已发布评价");var x=rows.get(rows.size()-1);return value(key,label,decimal(x,"final_score"),1L,"分","VALUE",s(x,"period_key")+("MONTH".equals(type)?" 月度":" 季度"));}
  private List<ChartItem> taskChart(Map<String,Object>x){return List.of(item("NOT_SUBMITTED","待提交",n(x,"not_submitted")),item("PENDING_REVIEW","审核中",n(x,"pending_review")),item("RETURNED","已退回",n(x,"returned_count")),item("APPROVED","已通过",n(x,"approved")),new ChartItem("OVERDUE","逾期标记",BigDecimal.valueOf(n(x,"overdue")),null,"附加标记，不计入互斥状态合计"));}
  private List<ChartItem> examChart(List<Map<String,Object>> rows){return rows.stream().map(x->new ChartItem(String.valueOf(x.get("plan_id")),s(x,"name"),decimal(x,"total_score"),decimal(x,"max_score"),"第"+integer(x,"attempt_no")+"次 · "+s(x,"score_month"))).toList();}
  private List<ChartItem> chart(List<Map<String,Object>> rows,String key,String value){return rows.stream().map(x->new ChartItem(s(x,key),label(s(x,key)),decimal(x,value),null,null)).toList();}
  private ChartItem item(String key,String label,long value){return new ChartItem(key,label,BigDecimal.valueOf(value),null,null);}
  private EmployeeSummary employee(Map<String,Object>x){return new EmployeeSummary(nl(x,"id"),s(x,"employee_no"),s(x,"name"),s(x,"avatar_token"),s(x,"batch_name"),s(x,"class_name"),s(x,"business_unit_name"),s(x,"station_name"),s(x,"technical_mentor_name"),s(x,"skill_mentor_name"),date(x,"onboard_date"));}
  private LatestLocation location(Map<String,Object>x){return x.isEmpty()?null:new LatestLocation(s(x,"to_location"),dt(x,"occurred_at"),dt(x,"created_at"));}
  private static int limit(int size){return Math.min(Math.max(size,1),100);}private static int offset(int page,int size){return Math.max(0,page-1)*size;}
  private static void requireDateRange(LocalDate from,LocalDate to){if(from!=null&&to!=null&&from.isAfter(to))throw new com.talent.platform.common.BusinessException(400,"开始日期不能晚于结束日期");}
  private static String dateFilter(String column,LocalDate from,LocalDate to,List<Object> args){var sql=new StringBuilder();if(from!=null){sql.append(" and ").append(column).append(">=?");args.add(from.atStartOfDay());}if(to!=null){sql.append(" and ").append(column).append("<?");args.add(to.plusDays(1).atStartOfDay());}return sql.toString();}
  private static String normalize(String value,String fallback,Set<String> allowed){String x=value==null?fallback:value.trim().toUpperCase(Locale.ROOT);if(!allowed.contains(x))throw new com.talent.platform.common.BusinessException(400,"不支持的筛选值");return x;}
  private static Object[] prepend(Object[] first,Object[] rest){var out=Arrays.copyOf(first,first.length+rest.length);System.arraycopy(rest,0,out,first.length,rest.length);return out;}
  private static String label(String x){return switch(or(x,"")){case "UPCOMING"->"未开始";case "IN_PROGRESS"->"进行中";case "ATTENDED"->"已签到";case "ABSENT"->"未签到";default->or(x,"-");};}
  private static String s(Map<String,Object>x,String k){Object v=x.get(k);return v==null?null:String.valueOf(v);}private static String or(String x,String y){return x==null||x.isBlank()?y:x;}
  private static long n(Map<String,Object>x,String k){Object v=x.get(k);return v instanceof Number z?z.longValue():0;}private static Long nl(Map<String,Object>x,String k){Object v=x.get(k);return v instanceof Number z?z.longValue():null;}private static int integer(Map<String,Object>x,String k){Object v=x.get(k);return v instanceof Number z?z.intValue():0;}
  private static boolean bool(Map<String,Object>x,String k){Object v=x.get(k);return Boolean.TRUE.equals(v)||(v instanceof Number z&&z.intValue()!=0);}
  private static BigDecimal decimal(Map<String,Object>x,String k){Object v=x.get(k);return v instanceof BigDecimal z?z:v instanceof Number z?new BigDecimal(z.toString()):null;}private static boolean positive(BigDecimal x){return x!=null&&x.compareTo(BigDecimal.ZERO)>0;}
  private static LocalDate date(Map<String,Object>x,String k){Object v=x.get(k);return v instanceof java.sql.Date z?z.toLocalDate():v instanceof LocalDate z?z:null;}private static LocalDateTime dt(Map<String,Object>x,String k){Object v=x.get(k);return v instanceof Timestamp z?z.toLocalDateTime():v instanceof LocalDateTime z?z:v instanceof java.sql.Date z?z.toLocalDate().atStartOfDay():null;}
}
