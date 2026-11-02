package com.talent.platform.user;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.SecurityUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AccountDeletionService {
  private final JdbcTemplate db;
  private final AuditService audit;

  public AccountDeletionService(JdbcTemplate db, AuditService audit) {
    this.db=db;
    this.audit=audit;
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void deleteEmployee(long employeeId) {
    requireSuperAdmin();
    lockSuperAdmins();
    var employee=employee(employeeId);
    Long userId=employee.get("user_id") instanceof Number n?n.longValue():null;
    var user=userId==null?null:user(userId);
    delete(employee,user,"DELETE_EMPLOYEE");
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void deleteAccount(long userId) {
    requireSuperAdmin();
    lockSuperAdmins();
    // Keep the same employee -> account lock order used by leave submission.
    var employees=db.queryForList("select id from employee where user_id=?",Long.class,userId);
    var employee=employees.isEmpty()?null:employee(employees.get(0));
    var user=user(userId);
    if(employee==null&&!db.queryForList("select id from employee where user_id=?",Long.class,userId).isEmpty())
      throw new BusinessException(409,"账号已关联新员工，请刷新后重新操作");
    delete(employee,user,"DELETE_USER");
  }

  private void requireSuperAdmin() {
    if(!"SUPER_ADMIN".equals(SecurityUtils.current().role()))throw new AccessDeniedException("仅超级管理员可删除");
  }

  private void lockSuperAdmins() {
    db.queryForList("select id from sys_user where role='SUPER_ADMIN' and enabled=true order by id for update");
  }

  private Map<String,Object> employee(long id) {
    var rows=db.queryForList("select id,user_id,employee_no,name from employee where id=? for update",id);
    if(rows.isEmpty())throw new BusinessException(404,"新员工不存在");
    return rows.get(0);
  }

  private Map<String,Object> user(long id) {
    if(id==SecurityUtils.current().id())throw new BusinessException(400,"不能删除当前登录账号");
    var rows=db.queryForList("select id,username,display_name,role,enabled from sys_user where id=? for update",id);
    if(rows.isEmpty())throw new BusinessException(404,"账号不存在");
    var user=rows.get(0);
    if("SUPER_ADMIN".equals(user.get("role"))&&Boolean.TRUE.equals(user.get("enabled")))
      block("select count(*) from sys_user where role='SUPER_ADMIN' and enabled=true and id<>?",id,
          "必须保留至少一个启用的超级管理员",true);
    return user;
  }

  private void delete(Map<String,Object> employee,Map<String,Object> user,String action) {
    Long userId=user==null?null:((Number)user.get("id")).longValue();
    Long employeeId=employee==null?null:((Number)employee.get("id")).longValue();
    if(userId!=null)checkResponsibilities(userId);
    if(employeeId!=null) {
      block("select count(*) from leave_request r join leave_request_version v on v.request_id=r.id where r.employee_id=? and (v.status='PENDING' or r.status='APPROVED')",
          employeeId,"该员工仍有待审批或未销假的请假，请先处理");
      block("select count(*) from station_change_request where employee_id=? and status='PENDING'",employeeId,
          "该员工仍有待审批的调站申请，请先处理");
    }
    try {
      if(employeeId!=null)clearEmployee(employeeId,String.valueOf(employee.get("employee_no")));
      if(userId!=null) {
        db.update("delete from course_material_view_session where user_id=?",userId);
        db.update("delete from leave_submission_key where user_id=?",userId);
        db.update("delete from object_upload_ticket where created_by=?",userId);
        db.update("delete from sys_user where id=?",userId);
      }
      // Keep the operator and deleted identities in the audit trail, without credentials.
      audit.log(action,employeeId==null?"USER":"EMPLOYEE",employeeId==null?userId:employeeId,
          employee==null?Map.of("account",user):user==null?Map.of("employee",employee):Map.of("employee",employee,"account",user),null);
    } catch(DataIntegrityViolationException error) {
      // The transaction rolls back all personal records if another business still references this account.
      throw new BusinessException(409,"该账号仍被其他业务记录或管理职责引用，请先解除关联或转交；本次删除未生效");
    }
  }

  private void checkResponsibilities(long id) {
    block("select count(*) from station_manager_scope where user_id=?",id,"该账号仍负责服务站，请先解除管理范围");
    block("select count(*) from area_manager_scope where user_id=?",id,"该账号仍负责区域，请先解除管理范围");
    block("select count(*) from employee where mentor_user_id=? or skill_mentor_user_id=?",new Object[]{id,id},"该账号仍担任导师，请先转交学员");
    block("select count(*) from leave_request_version v join leave_request r on r.id=v.request_id where v.approver_user_id=? and (v.status='PENDING' or (v.version_no=r.current_version and r.status='APPROVED'))",id,"该账号仍有请假审批或未销假责任，请先转交");
    block("select count(*) from task_reviewer_scope_member m join task_reviewer_scope s on s.id=m.scope_id where m.reviewer_user_id=? and s.status='ACTIVE'",id,"该账号仍有任务评分职责，请先转交");
    block("select count(*) from task_reviewer where reviewer_user_id=?",id,"该账号仍有任务评分职责，请先转交");
    block("select count(*) from task_submission_review where reviewer_user_id=? and status='PENDING'",id,"该账号仍有待办任务评分，请先处理");
    block("select count(*) from evaluation_rating_reviewer r join evaluation_rating_task t on t.id=r.task_id where r.reviewer_user_id=? and r.status='ACTIVE' and t.status='ACTIVE'",id,"该账号仍有评价评分职责，请先转交");
    block("select count(*) from evaluation_reviewer_scope_member where reviewer_user_id=?",id,"该账号仍有评价分组职责，请先解除");
    for(String table:List.of("course","course_session","challenge_task","training_plan","task_template","exam_plan",
        "exam_paper","question_bank","exam_question_bank","score_scheme","defense_import_batch","defense_session",
        "evaluation_revision_group","training_plan_folder","question_bank_folder"))
      block("select count(*) from "+table+" where created_by=?",id,"该账号仍关联其他业务管理记录，请先处理");
    // Some older actor columns have no foreign key. Protect other employees' history explicitly.
    for(var reference:List.of(
        List.of("monthly_evaluation","evaluator_user_id"),List.of("attendance","operator_user_id"),
        List.of("task_assignment","assigned_by"),List.of("course_enrollment","assigned_by"),
        List.of("exam_assignment","assigned_by"),List.of("defense_result","created_by"),
        List.of("evaluation_revision_permission","reviewer_id")))
      block("select count(*) from "+reference.get(0)+" where "+reference.get(1)+"=? and employee_id not in (select id from employee where user_id=?)",
          new Object[]{id,id},"该账号仍被其他员工的业务历史引用，请先处理");
    block("select count(*) from task_submission s join task_assignment a on a.id=s.assignment_id where s.reviewed_by=? and a.employee_id not in (select id from employee where user_id=?)",
        new Object[]{id,id},"该账号仍被其他员工的任务评分历史引用，请先处理");
    block("select count(*) from exam_answer a join exam_attempt t on t.id=a.attempt_id where a.reviewed_by=? and t.employee_id not in (select id from employee where user_id=?)",
        new Object[]{id,id},"该账号仍被其他员工的考试评分历史引用，请先处理");
  }

  private void clearEmployee(long id,String employeeNo) {
    db.update("delete from score_adjustment where employee_id=?",id);
    db.update("delete f from stored_file f join task_submission s on s.id=f.submission_id join task_assignment a on a.id=s.assignment_id where a.employee_id=?",id);
    db.update("delete s from task_submission s join task_assignment a on a.id=s.assignment_id where a.employee_id=?",id);
    db.update("delete from task_assignment where employee_id=?",id);
    db.update("delete a from exam_answer a join exam_attempt t on t.id=a.attempt_id where t.employee_id=?",id);
    db.update("delete p from exam_proctor_event p join exam_attempt t on t.id=p.attempt_id where t.employee_id=?",id);
    db.update("delete from exam_attempt where employee_id=?",id);
    db.update("delete from exam_assignment where employee_id=?",id);
    db.update("delete from submission_opportunity where resource_type in ('TASK','EXAM') and participant_id=?",id);
    db.update("delete o from submission_opportunity o join evaluation_rating_task t on t.id=o.resource_id where o.resource_type='EVALUATION' and t.employee_id=?",id);
    db.update("delete h from monthly_evaluation_history h join monthly_evaluation e on e.id=h.evaluation_id where e.employee_id=?",id);
    for(String table:List.of("evaluation_revision_permission","evaluation_revision_member","evaluation_rating_task",
        "monthly_evaluation","monthly_station_weight","score_component_override","score_summary","legacy_exam_result",
        "legacy_evaluation","attendance","course_enrollment","course_material_view_session","employee_location_report",
        "station_change_request","leave_draft"))
      db.update("delete from "+table+" where employee_id=?",id);
    db.update("delete from defense_result where employee_id=? or employee_no=?",id,employeeNo);
    db.update("delete from history_import_row where employee_id=? or employee_no=?",id,employeeNo);
    for(String table:List.of("leave_reminder","leave_action","leave_request_version"))
      db.update("delete d from "+table+" d join leave_request r on r.id=d.request_id where r.employee_id=?",id);
    db.update("delete k from leave_submission_key k join leave_request r on r.id=k.request_id where r.employee_id=?",id);
    db.update("delete from leave_request where employee_id=?",id);
    db.update("delete from employee where id=?",id);
  }

  private void block(String sql,Object id,String message) {block(sql,new Object[]{id},message);}
  private void block(String sql,Object[] args,String message) {
    Integer count=db.queryForObject(sql,Integer.class,args);
    if(count!=null&&count>0)throw new BusinessException(409,message);
  }
  private void block(String sql,Object id,String message,boolean requireAny) {
    Integer count=db.queryForObject(sql,Integer.class,id);
    if(requireAny&&(count==null||count==0))throw new BusinessException(400,message);
  }
}
