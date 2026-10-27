package com.talent.platform.leave;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import com.talent.platform.user.UserController;
import com.talent.platform.station.StationResponsibilityService;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Requires a disposable MySQL database, never a production schema. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named="LEAVE_TEST_DB_URL",matches=".+")
class LeaveIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url",()->System.getenv("LEAVE_TEST_DB_URL"));
    properties.add("app.bootstrap.demo-users-enabled",()->false);
  }
  @Autowired JdbcTemplate db;
  @Autowired LeaveService service;
  @Autowired PermissionService permissions;
  @Autowired UserController accounts;
  @Autowired StationResponsibilityService responsibilities;
  @Autowired com.talent.platform.task.TaskScoringService scoring;
  @MockitoSpyBean AuditService audit;
  long employeeUser,employee,station,otherStation,stationManager,areaManager,director,admin,otherArea;
  String tag;
  LocalDate day(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
  long user(String suffix,String role){String username=tag+suffix;db.update("insert into sys_user(username,display_name,password_hash,role,must_change_password) values(?,?,'unused',?,false)",username,username,role);return db.queryForObject("select id from sys_user where username=?",Long.class,username);}
  void login(long id){RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(permissions.load(id),null,List.of()));}
  @BeforeEach void fixture(){
    tag="leave-"+UUID.randomUUID().toString().substring(0,8);
    db.update("insert into service_station(name) values(?),(?)",tag,tag+"-other");
    station=db.queryForObject("select id from service_station where name=?",Long.class,tag);
    otherStation=db.queryForObject("select id from service_station where name=?",Long.class,tag+"-other");
    employeeUser=user("-employee","EMPLOYEE");stationManager=user("-station","STATION_MANAGER");areaManager=user("-area","AREA_MANAGER");director=user("-director","TRAINING_DIRECTOR");admin=user("-admin","ADMIN");otherArea=user("-outsider","AREA_MANAGER");
    db.update("insert into employee(user_id,employee_no,name,station_id,status) values(?,?,?,?,'ACTIVE')",employeeUser,tag,tag,station);
    employee=db.queryForObject("select id from employee where user_id=?",Long.class,employeeUser);
    db.update("insert into station_manager_scope(user_id,station_id) values(?,?)",stationManager,station);
    db.update("insert into area_manager_scope(user_id,station_id) values(?,?),(?,?)",areaManager,station,otherArea,otherStation);
    login(employeeUser);
  }
  @AfterEach void reset(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();clearInvocations(audit);}
  LeaveService.Form form(LocalDateTime start,LocalDateTime end,long approver,Integer expected){return new LeaveService.Form("RETURN_HOME","PERSONAL",start,end,"测试返家地点","测试回段地点","集成测试请假","紧急情况补提说明",approver,true,UUID.randomUUID().toString(),expected);}
  long submitPast(){return service.submit(form(day().minusDays(1).atTime(8,0),day().minusDays(1).atTime(12,0),stationManager,null),null);}
  void approve(long id,int version,long reviewer){login(reviewer);service.review(id,version,new LeaveService.ReviewForm("APPROVE","同意"));}
  int count(String sql,Object... args){return db.queryForObject(sql,Integer.class,args);}

  @Test void approvalPlansReturnAndLateFilingAreDistinct(){
    long id=submitPast();approve(id,1,stationManager);
    assertThat(count("select count(*) from employee_location_report where leave_request_id=?",id)).isZero();
    assertThat(count("select count(*) from leave_reminder where request_id=? and status='OPEN'",id)).isEqualTo(2);
    login(employeeUser);assertThat(service.plans(null)).anyMatch(x->((Number)x.get("request_id")).longValue()==id);
    var returned=new LeaveService.ReturnForm(day().minusDays(1).atTime(11,0),"测试回段地点","",1);
    service.returnFromLeave(id,returned);service.returnFromLeave(id,returned);
    assertThat(count("select count(*) from employee_location_report where leave_request_id=?",id)).isEqualTo(1);
    service.scanOverdue();assertThat(count("select count(*) from leave_reminder where request_id=? and status='OPEN'",id)).isZero();
    @SuppressWarnings("unchecked") var actions=(List<Map<String,Object>>)service.detail(id).get("actions");
    assertThat(actions).anyMatch(x->List.of("true","1").contains(String.valueOf(x.get("late_filing"))));
  }

  @Test void exactApprovalLevelAndScopeAreEnforced(){
    var start=day().plusDays(2).atTime(8,0);var end=start.withHour(18);
    assertThatThrownBy(()->service.submit(form(start,end,stationManager,null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("审批人");
    assertThatThrownBy(()->service.submit(form(start,end,otherArea,null),null)).isInstanceOf(BusinessException.class);
    long id=service.submit(form(start,end,areaManager,null),null);
    login(stationManager);assertThatThrownBy(()->service.review(id,1,new LeaveService.ReviewForm("APPROVE",""))).isInstanceOf(BusinessException.class);
    approve(id,1,areaManager);
    login(employeeUser);assertThatThrownBy(()->service.submit(form(start.plusHours(1),end.plusHours(1),areaManager,null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("时间段");
    assertThat(permissions.permissions("AREA_MANAGER")).doesNotContain(Permissions.TASK_SCORE,Permissions.EVALUATION_MANAGE,Permissions.EXAM_MANAGE,Permissions.COURSE_MANAGE);
    assertThat(permissions.permissions("UNRECOGNIZED")).isEmpty();
  }

  @Test void changeKeepsOldDeadlineUntilApprovalAndRejectDoesNotReplaceIt(){
    long id=submitPast();approve(id,1,stationManager);login(employeeUser);
    service.submit(form(day().minusDays(1).atTime(8,0),day().plusDays(2).atTime(12,0),director,1),id);
    assertThat(count("select current_version from leave_request where id=?",id)).isEqualTo(1);
    assertThat(count("select count(*) from leave_reminder where request_id=? and status='OPEN'",id)).isEqualTo(2);
    login(director);service.review(id,2,new LeaveService.ReviewForm("REJECT","理由不充分"));
    assertThat(count("select current_version from leave_request where id=?",id)).isEqualTo(1);
    login(employeeUser);service.submit(form(day().minusDays(1).atTime(8,0),day().plusDays(2).atTime(12,0),director,1),id);
    approve(id,3,director);
    assertThat(count("select current_version from leave_request where id=?",id)).isEqualTo(3);
    assertThat(count("select count(*) from leave_reminder where request_id=? and status='OPEN'",id)).isZero();
    login(employeeUser);assertThatThrownBy(()->service.returnFromLeave(id,new LeaveService.ReturnForm(day().minusDays(1).atTime(11,0),"测试回段地点","",1))).isInstanceOf(BusinessException.class).hasMessageContaining("版本");
  }

  @Test void returnClosesPendingChange(){
    long id=submitPast();approve(id,1,stationManager);login(employeeUser);
    service.submit(form(day().minusDays(1).atTime(8,0),day().plusDays(2).atTime(12,0),director,1),id);
    service.returnFromLeave(id,new LeaveService.ReturnForm(day().minusDays(1).atTime(13,0),"测试回段地点","延迟返回",1));
    assertThat(db.queryForObject("select status from leave_request_version where request_id=? and version_no=2",String.class,id)).isEqualTo("WITHDRAWN");
    login(director);assertThatThrownBy(()->service.review(id,2,new LeaveService.ReviewForm("APPROVE",""))).isInstanceOf(BusinessException.class);
  }

  @Test void draftAndNoticeAreProtected(){
    service.saveDraft(Map.of("reason","未完成草稿"));assertThat(service.draft()).containsEntry("reason","未完成草稿");
    var original=form(day().plusDays(1).atTime(8,0),day().plusDays(1).atTime(12,0),stationManager,null);
    var invalid=new LeaveService.Form(original.scenario(),original.leaveType(),original.startAt(),original.endAt(),original.destination(),original.returnLocation(),original.reason(),null,stationManager,false,original.requestKey(),null);
    assertThatThrownBy(()->service.submit(invalid,null)).isInstanceOf(BusinessException.class).hasMessageContaining("须知");
    login(otherArea);assertThatThrownBy(()->service.draft()).isInstanceOf(BusinessException.class);
    login(employeeUser);service.deleteDraft();assertThat(service.draft()).isEmpty();
  }

  @Test void concurrentIdenticalSubmissionCreatesOneRequest() throws Exception {
    var f=form(day().plusDays(1).atTime(8,0),day().plusDays(1).atTime(12,0),stationManager,null);
    var executor=Executors.newFixedThreadPool(2);
    try{
      Callable<Long> command=()->{login(employeeUser);return service.submit(f,null);};
      var first=executor.submit(command);var second=executor.submit(command);
      assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
      assertThat(count("select count(*) from leave_request where employee_id=?",employee)).isEqualTo(1);
    }finally{executor.shutdownNow();}
  }

  @Test void responsibilityMustBeTransferredBeforeAccountChange(){
    long id=submitPast();login(admin);
    assertThatThrownBy(()->accounts.enabled(stationManager,new UserController.EnableRequest(false))).isInstanceOf(BusinessException.class).hasMessageContaining("先转交");
    long replacement=user("-replacement","STATION_MANAGER");db.update("insert into station_manager_scope values(?,?)",replacement,station);
    service.transfer(id,1,new LeaveService.TransferForm(replacement,"人员职责调整"));
    accounts.enabled(stationManager,new UserController.EnableRequest(false));
    approve(id,1,replacement);
    assertThat(count("select count(*) from leave_reminder where request_id=? and recipient_user_id=? and status='OPEN'",id,replacement)).isEqualTo(1);
  }

  @Test void outsidersCannotReadAndRejectedHistoryRemainsVisible(){
    long id=submitPast();login(otherArea);
    assertThatThrownBy(()->service.detail(id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(service.list("all",null,null,null,null,null,null,null,null,1,20).total()).isZero();
    login(stationManager);service.review(id,1,new LeaveService.ReviewForm("REJECT","请完善原因"));
    assertThat(service.list("reviewed",tag,null,null,null,null,null,null,null,1,20).total()).isEqualTo(1);
  }

  @Test void auditFailureRollsBackSubmission(){
    doThrow(new IllegalStateException("audit unavailable")).when(audit).log(eq("LEAVE_SUBMIT"),anyString(),any(),any(),any());
    try{
      assertThatThrownBy(this::submitPast).isInstanceOf(IllegalStateException.class);
      assertThat(count("select count(*) from leave_request where employee_id=?",employee)).isZero();
      assertThat(count("select count(*) from leave_submission_key where user_id=?",employeeUser)).isZero();
    }finally{doCallRealMethod().when(audit).log(anyString(),anyString(),any(),any(),any());}
  }

  StationResponsibilityService.Change change(long replacement,Map<String,Long> transfers,String baseline){
    return new StationResponsibilityService.Change(List.of(new StationResponsibilityService.Assignment(station,"STATION_MANAGER",List.of(replacement))),transfers,"服务站站长交接",baseline);
  }

  @Test void maintenanceTransfersEffectiveAndPendingVersionsAtomically(){
    long id=submitPast();approve(id,1,stationManager);login(admin);
    long replacement=user("-replacement","STATION_MANAGER");
    var preview=responsibilities.preview(change(replacement,null,null));
    assertThat(preview.get("affectedVersions")).isEqualTo(1);
    String key="STATION_MANAGER:"+station+":"+stationManager;
    responsibilities.save(change(replacement,Map.of(key,replacement),(String)preview.get("baselineHash")));
    assertThat(count("select count(*) from station_manager_scope where user_id=? and station_id=?",stationManager,station)).isZero();
    assertThat(count("select count(*) from station_manager_scope where user_id=? and station_id=?",replacement,station)).isEqualTo(1);
    assertThat(db.queryForObject("select approver_user_id from leave_request_version where request_id=?",Long.class,id)).isEqualTo(replacement);
    assertThat(count("select count(*) from leave_reminder where request_id=? and recipient_user_id=? and status='OPEN'",id,replacement)).isEqualTo(1);
    assertThat(count("select count(*) from leave_action where request_id=? and actor_user_id=? and action='APPROVED'",id,stationManager)).isEqualTo(1);
    assertThat(count("select count(*) from area_manager_scope where user_id=? and station_id=?",areaManager,station)).isEqualTo(1);
  }

  @Test void maintenanceRejectsMissingSuccessorAndStalePreview(){
    long id=submitPast();login(admin);long replacement=user("-replacement","STATION_MANAGER");
    var preview=responsibilities.preview(change(replacement,null,null));
    String hash=(String)preview.get("baselineHash");
    assertThatThrownBy(()->responsibilities.save(change(replacement,Map.of(),hash))).isInstanceOf(BusinessException.class).hasMessageContaining("负责人");
    assertThat(count("select count(*) from station_manager_scope where user_id=? and station_id=?",stationManager,station)).isEqualTo(1);
    approve(id,1,stationManager);login(admin);
    assertThatThrownBy(()->responsibilities.save(change(replacement,Map.of("STATION_MANAGER:"+station+":"+stationManager,replacement),hash))).isInstanceOf(BusinessException.class).hasMessageContaining("重新预览");
    assertThat(count("select count(*) from station_manager_scope where user_id=?",replacement)).isZero();
  }

  @Test void maintenanceFailureRollsBackScopeAndTransfersAndDeniesOperators(){
    long id=submitPast();login(areaManager);
    assertThatThrownBy(()->responsibilities.context()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    login(admin);long replacement=user("-replacement","STATION_MANAGER");
    var preview=responsibilities.preview(change(replacement,null,null));
    doThrow(new IllegalStateException("audit unavailable")).when(audit).log(eq("UPDATE_STATION_RESPONSIBILITIES"),anyString(),any(),any(),any());
    try{
      assertThatThrownBy(()->responsibilities.save(change(replacement,Map.of("STATION_MANAGER:"+station+":"+stationManager,replacement),(String)preview.get("baselineHash")))).isInstanceOf(IllegalStateException.class);
      assertThat(count("select count(*) from station_manager_scope where user_id=?",replacement)).isZero();
      assertThat(db.queryForObject("select approver_user_id from leave_request_version where request_id=?",Long.class,id)).isEqualTo(stationManager);
      assertThat(count("select count(*) from leave_action where request_id=? and action='TRANSFER'",id)).isZero();
    }finally{doCallRealMethod().when(audit).log(anyString(),anyString(),any(),any(),any());}
  }

  @Test void bulkRoleChangeMovesScopeToCorrectTableAndExcludesNewScoringRoles(){
    long superAdmin=user("-super","SUPER_ADMIN");login(superAdmin);
    var selection=new UserController.BulkSelection("IDS",List.of(stationManager),Map.of(),List.of());
    accounts.bulkExecute(new UserController.BulkRequest(selection,Map.of("role","AREA_MANAGER","stationIds",List.of(station)),false,null,null,UUID.randomUUID().toString()));
    assertThat(count("select count(*) from station_manager_scope where user_id=?",stationManager)).isZero();
    assertThat(count("select count(*) from area_manager_scope where user_id=? and station_id=?",stationManager,station)).isEqualTo(1);
    assertThat(scoring.reviewerOptions()).noneMatch(x->List.of("AREA_MANAGER","TRAINING_DIRECTOR").contains(x.get("role")));
  }

  @Test void concurrentScanAndReturnNeverReopenClosedLeave() throws Exception {
    long id=submitPast();approve(id,1,stationManager);
    db.update("delete from leave_reminder where request_id=?",id);
    var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{
      var scan=executor.submit(()->{start.await();service.scanOverdue();return true;});
      var returned=executor.submit(()->{start.await();login(employeeUser);service.returnFromLeave(id,new LeaveService.ReturnForm(day().minusDays(1).atTime(11,0),"测试回段地点","",1));return true;});
      start.countDown();assertThat(scan.get(30,TimeUnit.SECONDS)).isTrue();assertThat(returned.get(30,TimeUnit.SECONDS)).isTrue();
      service.scanOverdue();
      assertThat(count("select count(*) from leave_reminder where request_id=? and status='OPEN'",id)).isZero();
      assertThat(count("select count(*) from employee_location_report where leave_request_id=?",id)).isEqualTo(1);
    }finally{executor.shutdownNow();}
  }
}
