package com.talent.platform.employee;

import com.talent.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class EmployeePortraitQuery {
  private final JdbcTemplate db;
  public EmployeePortraitQuery(JdbcTemplate db){this.db=db;}

  public Map<String,Object> employee(long id){
    var rows=db.queryForList("""
      select e.id,e.employee_no,e.name,e.onboard_date,u.avatar_token,b.name batch_name,
        cls.label class_name,bu.name business_unit_name,s.name station_name,
        tm.display_name technical_mentor_name,sm.display_name skill_mentor_name
      from employee e left join sys_user u on u.id=e.user_id
      left join talent_batch b on b.id=e.batch_id
      left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
      left join business_unit bu on bu.id=e.business_unit_id
      left join service_station s on s.id=e.station_id
      left join sys_user tm on tm.id=e.mentor_user_id
      left join sys_user sm on sm.id=e.skill_mentor_user_id where e.id=?
      """,id);
    if(rows.isEmpty())throw new BusinessException(404,"员工不存在");
    return rows.get(0);
  }
  public Map<String,Object> latestLocation(long id){return first("select to_location,occurred_at,created_at from employee_location_report where employee_id=? order by occurred_at desc,id desc limit 1",id);}
  public Map<String,Object> taskMetrics(long id){return db.queryForMap("""
    select count(*) total,count(case when status='APPROVED' then 1 end) approved,
      count(case when status in ('NOT_SUBMITTED','OVERDUE') then 1 end) not_submitted,
      count(case when status='PENDING_REVIEW' then 1 end) pending_review,
      count(case when status='RETURNED' then 1 end) returned_count,
      count(case when status='OVERDUE' or (status<>'APPROVED' and t.deadline<now()) then 1 end) overdue
    from task_assignment a join challenge_task t on t.id=a.task_id where a.employee_id=?
    """,id);}
  public Map<String,Object> materialMetrics(long id){return db.queryForMap("""
    select count(*) total,count(case when x.view_count>0 then 1 end) viewed from (
      select m.id,count(v.id) view_count from course_material m
      join (select distinct cs.course_id from course_enrollment ce join course_session cs on cs.id=ce.session_id where ce.employee_id=?) assigned on assigned.course_id=m.course_id
      left join course_material_view_session v on v.material_id=m.id and v.employee_id=? group by m.id
    ) x
    """,id,id);}
  public Map<String,Object> examMetrics(long id){return db.queryForMap("""
    select count(*) total,count(case when exists(select 1 from exam_attempt a where a.plan_id=p.id and a.employee_id=ea.employee_id and a.submitted_at is not null) then 1 end) completed,
      count(case when p.ends_at<now() and not exists(select 1 from exam_attempt a where a.plan_id=p.id and a.employee_id=ea.employee_id) then 1 end) absent
    from exam_assignment ea join exam_plan p on p.id=ea.plan_id where ea.employee_id=? and p.status='PUBLISHED'
    """,id);}
  public List<Map<String,Object>> publishedExamTrend(long id){return db.queryForList("""
    select a.id,p.id plan_id,p.name,p.score_month,a.attempt_no,a.total_score,
      coalesce((select sum(q.score) from exam_attempt_question q where q.attempt_id=a.id),
               (select sum(q.score) from exam_paper_question q where q.paper_id=p.paper_id)) max_score
    from exam_attempt a join exam_plan p on p.id=a.plan_id
    where a.employee_id=? and a.status='GRADED' and a.published=true
      and a.id=(select x.id from exam_attempt x where x.employee_id=a.employee_id and x.plan_id=a.plan_id and x.status='GRADED' and x.published=true order by x.attempt_no desc,x.id desc limit 1)
    order by p.score_month,p.id
    """,id);}
  public List<Map<String,Object>> evaluationTrend(long id,String type){return db.queryForList("""
    select s.period_key,s.final_score from score_summary s where s.employee_id=? and s.summary_type=? and s.status='PUBLISHED'
      and s.version=(select max(x.version) from score_summary x where x.employee_id=s.employee_id and x.summary_type=s.summary_type and x.period_key=s.period_key and x.status='PUBLISHED')
    order by s.period_key
    """,id,type);}
  public List<Map<String,Object>> courseParticipation(long id){return db.queryForList("""
    select case when cs.starts_at>now() then 'UPCOMING' when cs.ends_at>=now() then 'IN_PROGRESS'
      when a.id is not null then 'ATTENDED' else 'ABSENT' end state,count(*) count
    from course_enrollment ce join course_session cs on cs.id=ce.session_id
    left join attendance a on a.session_id=ce.session_id and a.employee_id=ce.employee_id
    where ce.employee_id=? group by state
    """,id);}
  public long count(String sql,Object...args){Long n=db.queryForObject(sql,Long.class,args);return n==null?0:n;}
  public List<Map<String,Object>> rows(String sql,Object...args){return db.queryForList(sql,args);}
  public Map<String,Object> first(String sql,Object...args){var rows=db.queryForList(sql,args);return rows.isEmpty()?Map.of():rows.get(0);}
}
