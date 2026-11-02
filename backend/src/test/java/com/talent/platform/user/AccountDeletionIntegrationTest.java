package com.talent.platform.user;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Must point at a disposable MySQL database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="LEAVE_TEST_DB_URL",matches=".+")
class AccountDeletionIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url",()->System.getenv("LEAVE_TEST_DB_URL"));
    properties.add("app.bootstrap.demo-users-enabled",()->false);
  }
  @Autowired JdbcTemplate db;
  @Autowired AccountDeletionService service;
  @Autowired PermissionService permissions;
  @Autowired MockMvc mvc;
  long operator,account,employee,otherEmployee,otherAccount,station;
  String tag;

  @BeforeEach void fixture() {
    tag="delete-"+UUID.randomUUID().toString().substring(0,8);
    operator=user("-super","SUPER_ADMIN");
    account=user("-employee","EMPLOYEE");
    otherAccount=user("-other","EMPLOYEE");
    employee=insert("insert into employee(user_id,employee_no,name) values(?,?,?)",account,tag,tag);
    otherEmployee=insert("insert into employee(user_id,employee_no,name) values(?,?,?)",otherAccount,tag+"-other",tag+"-other");
    station=insert("insert into service_station(name) values(?)",tag);
    login(operator);
  }
  @AfterEach void reset() {SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
  long user(String suffix,String role) {
    return insert("insert into sys_user(username,display_name,password_hash,role,must_change_password) values(?,?,'unused',?,false)",tag+suffix,tag+suffix,role);
  }
  long insert(String sql,Object... args) {
    var key=new GeneratedKeyHolder();
    db.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
      for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);
    return key.getKey().longValue();
  }
  void login(long id) {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(permissions.load(id),null,List.of()));
  }
  int count(String sql,Object... args) {return db.queryForObject(sql,Integer.class,args);}
  long completedLeave() {
    long request=insert("insert into leave_request(employee_id,status) values(?,'RETURNED')",employee);
    db.update("insert into leave_request_version(request_id,version_no,scenario,leave_type,start_at,end_at,destination,return_location,reason,day_units,approver_level,approver_user_id,status,notice_version,notice_acknowledged_at,created_by) values(?,1,'RETURN_HOME','PERSONAL','2026-01-01 08:00:00','2026-01-01 12:00:00','home','company','test',1,'TRAINING_DIRECTOR',?,'APPROVED','V1',now(),?)",request,operator,account);
    db.update("insert into leave_action(request_id,version_no,actor_user_id,action) values(?,1,?,'RETURN')",request,account);
    db.update("insert into leave_reminder(request_id,version_no,recipient_user_id,status) values(?,1,?,'CLOSED')",request,account);
    return request;
  }

  @Test void employeeDeletionCleansPersonalRecordsAndPreservesOtherEmployeesAndSharedResources() {
    long task=insert("insert into challenge_task(title,description,deadline,created_by) values(?,'test',now(),?)",tag,operator);
    long assignment=insert("insert into task_assignment(task_id,employee_id,assigned_by) values(?,?,?)",task,employee,operator);
    db.update("insert into task_assignment(task_id,employee_id,assigned_by) values(?,?,?)",task,otherEmployee,operator);
    long opportunity=insert("insert into submission_opportunity(resource_type,resource_id,participant_id,starts_at,ends_at,reason,created_by) values('TASK',?,?,now(),now(),'test',?)",task,employee,operator);
    long submission=insert("insert into task_submission(assignment_id,submission_version,content,opportunity_id) values(?,1,'test',?)",assignment,opportunity);
    db.update("insert into stored_file(submission_id,original_name,size,storage_key,uploader_user_id) values(?,'test',1,?,?)",submission,tag,account);
    db.update("insert into task_submission_review(submission_id,reviewer_user_id) values(?,?)",submission,operator);
    long course=insert("insert into course(name,created_by) values(?,?)",tag,operator);
    long session=insert("insert into course_session(course_id,title,starts_at,ends_at,checkin_starts_at,checkin_ends_at,checkin_code,created_by) values(?,?,now(),now(),now(),now(),?,?)",course,tag,tag.substring(0,12),operator);
    db.update("insert into course_enrollment(session_id,employee_id,assigned_by) values(?,?,?)",session,employee,operator);
    db.update("insert into attendance(session_id,employee_id,status,source,checked_at,operator_user_id) values(?,?,'PRESENT','SELF',now(),?)",session,employee,account);
    long paper=insert("insert into exam_paper(name,created_by) values(?,?)",tag,operator);
    long plan=insert("insert into exam_plan(paper_id,name,starts_at,ends_at,duration_minutes,score_month,created_by) values(?,?,now(),now(),60,'2026-01-01',?)",paper,tag,operator);
    db.update("insert into exam_assignment(plan_id,employee_id,assigned_by) values(?,?,?)",plan,employee,operator);
    long attempt=insert("insert into exam_attempt(plan_id,employee_id,attempt_no,deadline_at) values(?,?,1,now())",plan,employee);
    db.update("insert into exam_proctor_event(attempt_id,event_type) values(?,'TEST')",attempt);
    long rating=insert("insert into evaluation_rating_task(employee_id,period_month,component_type,created_by,updated_by) values(?,'2026-01-01','MENTOR',?,?)",employee,operator,operator);
    db.update("insert into evaluation_rating_reviewer(task_id,reviewer_user_id,assigned_by) values(?,?,?)",rating,operator,operator);
    db.update("insert into submission_opportunity(resource_type,resource_id,participant_id,starts_at,ends_at,reason,created_by) values('EVALUATION',?,?,now(),now(),'test',?)",rating,operator,operator);
    long evaluation=insert("insert into monthly_evaluation(employee_id,period_month,evaluator_type,evaluator_user_id,score,comment) values(?,'2026-01-01','MENTOR',?,80,'test')",employee,operator);
    db.update("insert into monthly_evaluation_history(evaluation_id,score) values(?,70)",evaluation);
    db.update("insert into score_summary(employee_id,summary_type,period_key,version) values(?,'MONTH','2026-01',1)",employee);
    db.update("insert into leave_draft(employee_id,content) values(?,'{}')",employee);
    long leave=completedLeave();
    db.update("insert into leave_submission_key(user_id,request_key,payload_hash,request_id) values(?,'test','test',?)",account,leave);
    service.deleteEmployee(employee);
    for(String table:List.of("employee","sys_user"))assertThat(count("select count(*) from "+table+" where id=?",table.equals("employee")?employee:account)).isZero();
    for(String table:List.of("task_assignment","exam_attempt","exam_assignment","evaluation_rating_task","monthly_evaluation","score_summary","leave_request","leave_draft","attendance","course_enrollment"))
      assertThat(count("select count(*) from "+table+" where employee_id=?",employee)).as(table).isZero();
    assertThat(count("select count(*) from stored_file where storage_key=?",tag)).isZero();
    assertThat(count("select count(*) from task_submission where id=?",submission)).isZero();
    assertThat(count("select count(*) from monthly_evaluation_history where evaluation_id=?",evaluation)).isZero();
    assertThat(count("select count(*) from submission_opportunity where id=? or (resource_type='EVALUATION' and resource_id=?)",opportunity,rating)).isZero();
    assertThat(count("select count(*) from leave_action where request_id=?",leave)).isZero();
    assertThat(count("select count(*) from employee where id=?",otherEmployee)).isEqualTo(1);
    assertThat(count("select count(*) from task_assignment where employee_id=?",otherEmployee)).isEqualTo(1);
    assertThat(count("select count(*) from challenge_task where id=?",task)).isEqualTo(1);
    assertThat(count("select count(*) from exam_plan where id=?",plan)).isEqualTo(1);
    assertThat(count("select count(*) from operation_log where action='DELETE_EMPLOYEE' and target_id=?",employee)).isEqualTo(1);
    assertThatThrownBy(()->permissions.load(account)).isInstanceOf(AccessDeniedException.class);
  }

  @Test void accountEntryAlsoDeletesLinkedEmployee() {
    service.deleteAccount(account);
    assertThat(count("select count(*) from employee where id=?",employee)).isZero();
    assertThat(count("select count(*) from sys_user where id=?",account)).isZero();
  }

  @Test void unlinkedAccountCanBeDeleted() {
    long id=user("-unused","MENTOR");
    service.deleteAccount(id);
    assertThat(count("select count(*) from sys_user where id=?",id)).isZero();
  }

  @Test void ordinaryRolesCannotDeleteEitherResource() {
    for(String role:List.of("ADMIN","TRAINING_ADMIN","TRAINING_DIRECTOR","AREA_MANAGER","STATION_MANAGER","MENTOR","EMPLOYEE")) {
      login(user("-role-"+role,role));
      assertThatThrownBy(()->service.deleteEmployee(employee)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->service.deleteAccount(account)).isInstanceOf(AccessDeniedException.class);
    }
    assertThat(count("select count(*) from employee where id=?",employee)).isEqualTo(1);
  }

  @Test void currentAccountCannotBeDeleted() {
    assertThatThrownBy(()->service.deleteAccount(operator)).isInstanceOf(BusinessException.class).hasMessageContaining("当前登录");
  }

  @Test void missingAccountAndEmployeeReturnBusinessErrors() {
    assertThatThrownBy(()->service.deleteAccount(Long.MAX_VALUE)).isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
    assertThatThrownBy(()->service.deleteEmployee(Long.MAX_VALUE)).isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
  }

  @Test void stationResponsibilitiesBlockDeletion() {
    db.update("insert into station_manager_scope(user_id,station_id) values(?,?)",account,station);
    assertThatThrownBy(()->service.deleteEmployee(employee)).isInstanceOf(BusinessException.class).hasMessageContaining("管理范围");
    assertThat(count("select count(*) from employee where id=?",employee)).isEqualTo(1);
  }

  @Test void mentorResponsibilitiesBlockDeletion() {
    db.update("update employee set mentor_user_id=? where id=?",account,otherEmployee);
    assertThatThrownBy(()->service.deleteAccount(account)).isInstanceOf(BusinessException.class).hasMessageContaining("导师");
  }

  @Test void pendingEmployeeLeaveBlocksDeletion() {
    long leave=completedLeave();
    db.update("update leave_request set status='APPROVED' where id=?",leave);
    assertThatThrownBy(()->service.deleteEmployee(employee)).isInstanceOf(BusinessException.class).hasMessageContaining("未销假");
    assertThat(count("select count(*) from leave_request where id=?",leave)).isEqualTo(1);
  }

  @Test void approverResponsibilitiesBlockDeletion() {
    long leave=completedLeave();
    db.update("update leave_request_version set status='PENDING',approver_user_id=? where request_id=?",account,leave);
    assertThatThrownBy(()->service.deleteAccount(account)).isInstanceOf(BusinessException.class).hasMessageContaining("审批");
  }

  @Test void unexpectedForeignReferenceRollsBackEveryPersonalDeletion() {
    db.update("insert into leave_draft(employee_id,content) values(?,'{}')",employee);
    // Historical reviewer on another employee must never be removed as a side effect.
    db.update("insert into station_change_request(employee_id,requested_station_id,status,reviewed_by) values(?,?,'APPROVED',?)",otherEmployee,station,account);
    assertThatThrownBy(()->service.deleteEmployee(employee)).isInstanceOf(BusinessException.class).hasMessageContaining("本次删除未生效");
    assertThat(count("select count(*) from employee where id=?",employee)).isEqualTo(1);
    assertThat(count("select count(*) from leave_draft where employee_id=?",employee)).isEqualTo(1);
    assertThat(count("select count(*) from sys_user where id=?",account)).isEqualTo(1);
    assertThat(count("select count(*) from operation_log where action='DELETE_EMPLOYEE' and target_id=?",employee)).isZero();
  }

  @Test void deleteEndpointsEnforceRoleAndRemoveLinkedResources() throws Exception {
    var admin=new UsernamePasswordAuthenticationToken(permissions.load(user("-api-admin","ADMIN")),null,List.of());
    mvc.perform(delete("/api/v1/employees/{id}",employee).with(authentication(admin))).andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/users/{id}",account).with(authentication(admin))).andExpect(status().isForbidden());
    var superAdmin=new UsernamePasswordAuthenticationToken(permissions.load(operator),null,List.of());
    mvc.perform(delete("/api/v1/employees/{id}",employee).with(authentication(superAdmin))).andExpect(status().isOk());
    mvc.perform(delete("/api/v1/users/{id}",otherAccount).with(authentication(superAdmin))).andExpect(status().isOk());
    assertThat(count("select count(*) from employee where id in (?,?)",employee,otherEmployee)).isZero();
    assertThat(count("select count(*) from sys_user where id in (?,?)",account,otherAccount)).isZero();
  }

  @Test void historicalEvaluationWithoutForeignKeyStillProtectsOtherEmployees() {
    db.update("insert into monthly_evaluation(employee_id,period_month,evaluator_type,evaluator_user_id,score,comment) values(?,'2026-01-01','MENTOR',?,80,'history')",otherEmployee,account);
    assertThatThrownBy(()->service.deleteAccount(account)).isInstanceOf(BusinessException.class).hasMessageContaining("业务历史");
    assertThat(count("select count(*) from monthly_evaluation where employee_id=?",otherEmployee)).isEqualTo(1);
    assertThat(count("select count(*) from sys_user where id=?",account)).isEqualTo(1);
  }
}
