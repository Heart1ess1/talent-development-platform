package com.talent.platform.course;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in: ASSIGNMENT_TEST_DB_URL must point to a disposable MySQL database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named="ASSIGNMENT_TEST_DB_URL",matches=".+")
class SessionAssignmentIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url",()->System.getenv("ASSIGNMENT_TEST_DB_URL"));
    properties.add("app.bootstrap.demo-users-enabled",()->true);
  }
  @Autowired JdbcTemplate db;
  @Autowired SessionAssignmentService assignments;
  @Autowired CourseController courses;
  @Autowired PermissionService permissions;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoSpyBean AuditService audit;
  long sessionId,courseId,first,second,inactive,admin;

  void authenticate() {
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
    var user=permissions.load(admin);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
  }
  long lastId(){return Objects.requireNonNull(db.queryForObject("select last_insert_id()",Long.class));}
  long insertPerson(String tag,String status) {
    db.update("insert into employee(employee_no,name,status) values(?,?,?)",tag,tag,status);
    return Objects.requireNonNull(db.queryForObject("select id from employee where employee_no=?",Long.class,tag));
  }
  @BeforeEach void fixture() {
    admin=Objects.requireNonNull(db.queryForObject("select id from sys_user where username='trainadmin'",Long.class));authenticate();
    String tag="assignment-"+UUID.randomUUID().toString().substring(0,8);
    first=insertPerson(tag+"-1","ACTIVE");second=insertPerson(tag+"-2","ACTIVE");inactive=insertPerson(tag+"-3","INACTIVE");
    db.update("insert into course(name,created_by) values(?,?)",tag,admin);courseId=Objects.requireNonNull(db.queryForObject("select id from course where name=?",Long.class,tag));
    db.update("""
        insert into course_session(course_id,title,starts_at,ends_at,checkin_starts_at,checkin_ends_at,checkin_code,created_by)
        values(?,?,date_sub(now(),interval 1 hour),date_add(now(),interval 1 day),date_sub(now(),interval 1 day),date_add(now(),interval 1 day),?,?)
        """,courseId,tag,tag.substring(tag.length()-8),admin);sessionId=Objects.requireNonNull(db.queryForObject("select id from course_session where title=?",Long.class,tag));
    db.update("insert into course_enrollment(session_id,employee_id,assigned_by) values(?,?,?)",sessionId,first,admin);
  }
  @AfterEach void cleanup() {
    reset(audit);
    db.update("delete from attendance where session_id=?",sessionId);
    db.update("delete from course_enrollment where session_id=?",sessionId);
    db.update("delete from course_session where id=?",sessionId);
    db.update("delete from course where id=?",courseId);
    db.update("delete from employee where id in (?,?,?)",first,second,inactive);
    SecurityContextHolder.clearContext();
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  }
  private SessionAssignmentService.Changes changes(List<Long> add,List<Long> remove,String hash) {
    return new SessionAssignmentService.Changes(add,remove,hash);
  }
  private void checkin(long employeeId) {
    db.update("insert into attendance(session_id,employee_id,status,source,checked_at,operator_user_id) values(?,?,'MANUAL','MANUAL',now(),?)",sessionId,employeeId,admin);
  }

  @Test void savesOnlyDeltaAndKeepsHistoricalInactiveEmployeesVisible() {
    var old=assignments.state(sessionId);
    var result=assignments.change(sessionId,changes(List.of(second,second),List.of(first),old.baselineHash()));
    assertThat(result.added()).isEqualTo(1);assertThat(result.removed()).isEqualTo(1);
    db.update("update employee set status='INACTIVE' where id=?",second);
    assertThat(assignments.state(sessionId).records()).singleElement().satisfies(row->assertThat(row.get("status")).isEqualTo("INACTIVE"));
    assertThat(db.queryForObject("select count(*) from operation_log where action='UPDATE_SESSION_ENROLLMENTS' and target_id=?",Integer.class,sessionId)).isEqualTo(1);
  }
  @Test void rollsBackRealWritesWhenAuditFails() {
    var old=assignments.state(sessionId);
    doThrow(new IllegalStateException("audit unavailable")).when(audit).log(eq("UPDATE_SESSION_ENROLLMENTS"),anyString(),any(),any(),any());
    assertThatThrownBy(()->assignments.change(sessionId,changes(List.of(second),List.of(first),old.baselineHash()))).hasMessageContaining("audit unavailable");
    assertThat(assignments.state(sessionId).baselineHash()).isEqualTo(old.baselineHash());
    assertThat(assignments.state(sessionId).records()).extracting(row->((Number)row.get("employee_id")).longValue()).containsExactly(first);
  }
  @Test void rejectsInactiveOrCheckedInMembersWithoutPartialChanges() {
    var old=assignments.state(sessionId);
    assertThatThrownBy(()->assignments.change(sessionId,changes(List.of(inactive),List.of(first),old.baselineHash()))).hasMessageContaining("停用");
    assertThat(assignments.state(sessionId).baselineHash()).isEqualTo(old.baselineHash());
    checkin(first);var signed=assignments.state(sessionId);
    assertThatThrownBy(()->assignments.change(sessionId,changes(List.of(second),List.of(first),signed.baselineHash()))).hasMessageContaining("签到记录");
    assertThat(assignments.state(sessionId).enrolledCount()).isEqualTo(1);
  }
  @Test void staleAndRepeatedSavesCannotOverwriteOtherChanges() {
    var old=assignments.state(sessionId);var change=changes(List.of(second),List.of(),old.baselineHash());
    assignments.change(sessionId,change);
    assertThatThrownBy(()->assignments.change(sessionId,change)).hasMessageContaining("已变化");
    assertThat(assignments.state(sessionId).enrolledCount()).isEqualTo(2);
    assertThat(courses.enroll(sessionId,new CourseController.EnrollRequest(List.of(second))).data()).isEqualTo(0);
  }
  @Test void locksSerializeWithConcurrentManualCheckin() throws Exception {
    var old=assignments.state(sessionId);
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(2);
    try {
      var signer=pool.submit(()->{
        authenticate();
        try{new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
          assignments.lockSession(sessionId);locked.countDown();
          try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
          courses.manualAttendance(new CourseController.ManualAttendanceRequest(sessionId,first,"concurrent test"));
        });}finally{SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
      });
      assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
      var remover=pool.submit(()->{authenticate();try{return assignments.change(sessionId,changes(List.of(second),List.of(first),old.baselineHash()));}finally{SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}});
      assertThatThrownBy(()->remover.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      release.countDown();signer.get(10,TimeUnit.SECONDS);
      assertThatThrownBy(()->remover.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
      var latest=assignments.state(sessionId);assertThat(latest.enrolledCount()).isEqualTo(1);assertThat(latest.attendanceCount()).isEqualTo(1);
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void permissionDeniedAndDeletedSessionAreRejected() {
    var old=assignments.state(sessionId);
    var user=new CurrentUser(999L,"employee","员工","EMPLOYEE",false,1,Set.of(),"SELF");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
    assertThatThrownBy(()->assignments.change(sessionId,changes(List.of(second),List.of(),old.baselineHash()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    authenticate();
    assertThatThrownBy(()->assignments.change(Long.MAX_VALUE,changes(List.of(),List.of(),"hash"))).hasMessageContaining("已被删除");
  }
}
