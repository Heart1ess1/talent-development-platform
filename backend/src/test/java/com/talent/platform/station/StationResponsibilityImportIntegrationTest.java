package com.talent.platform.station;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.BusinessException;
import com.talent.platform.leave.LeaveService;
import com.talent.platform.security.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** These transaction, lock and rollback assertions require an isolated real MySQL schema. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="STATION_IMPORT_TEST_DB_URL",matches=".+")
class StationResponsibilityImportIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties){properties.add("spring.datasource.url",()->System.getenv("STATION_IMPORT_TEST_DB_URL"));properties.add("app.bootstrap.demo-users-enabled",()->false);}
  @Autowired JdbcTemplate db;
  @Autowired StationResponsibilityImportService imports;
  @Autowired StationResponsibilityService responsibilities;
  @Autowired PermissionService permissions;
  @Autowired PasswordEncoder encoder;
  @Autowired LeaveService leaves;
  @Autowired JwtService jwt;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @MockitoSpyBean AuditService audit;
  String tag,stationName,otherName;
  long admin,otherAdmin,station,otherStation,oldManager,areaManager,employeeUser,employee;
  long user(String no,String name,String role){db.update("insert into sys_user(username,display_name,password_hash,role,must_change_password) values(?,?,?,?,false)",no,name,encoder.encode("Existing123!"),role);long id=db.queryForObject("select id from sys_user where username=?",Long.class,no);db.update("insert into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,true,?)",id,role,id);return id;}
  void login(long id){RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(permissions.load(id),null,List.of()));}
  @BeforeEach void fixture(){
    tag="si"+UUID.randomUUID().toString().replace("-","").substring(0,12);stationName=tag+"服务站";otherName=tag+"另一站";
    db.update("insert into service_station(name) values(?),(?)",stationName,otherName);station=db.queryForObject("select id from service_station where name=?",Long.class,stationName);otherStation=db.queryForObject("select id from service_station where name=?",Long.class,otherName);
    admin=user(tag+"admin","管理员","ADMIN");otherAdmin=user(tag+"admin2","管理员乙","ADMIN");oldManager=user(tag+"old","原站长","STATION_MANAGER");areaManager=user(tag+"area","区域经理","AREA_MANAGER");employeeUser=user(tag+"employee","员工","EMPLOYEE");
    db.update("insert into station_manager_scope(user_id,station_id) values(?,?)",oldManager,station);db.update("insert into area_manager_scope(user_id,station_id) values(?,?)",areaManager,station);
    db.update("insert into employee(user_id,employee_no,name,station_id,status) values(?,?,?,?,'ACTIVE')",employeeUser,tag+"employee","员工",station);employee=db.queryForObject("select id from employee where user_id=?",Long.class,employeeUser);login(admin);
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();reset(audit);}
  MockMultipartFile file(String[]... data)throws Exception{
    try(var book=new XSSFWorkbook()){
      var sheet=book.createSheet("负责人配置");var header=sheet.createRow(0);for(int c=0;c<5;c++)header.createCell(c).setCellValue(StationImportWorkbook.HEADERS.get(c));
      for(int r=0;r<data.length;r++){var row=sheet.createRow(r+1);for(int c=0;c<5;c++)row.createCell(c).setCellValue(data[r][c]);}
      var out=new ByteArrayOutputStream();book.write(out);return StationImportWorkbookTest.file(out.toByteArray());
    }
  }
  String[] row(String name,String no){return new String[]{stationName,name,no,"",""};}
  StationResponsibilityImportService.Preview preview(String[]... rows)throws Exception{return imports.preview(file(rows));}
  Map<String,Object> commit(StationResponsibilityImportService.Preview p){var transfers=new LinkedHashMap<String,String>();for(var impact:p.impacts())transfers.put(impact.key(),impact.candidateRefs().get(0));return imports.commit(p.batchId(),new StationResponsibilityImportService.Commit("批量调整负责人",transfers));}
  int count(String sql,Object... args){return db.queryForObject(sql,Integer.class,args);}
  long id(String no){return db.queryForObject("select id from sys_user where username=?",Long.class,no);}
  long leave(boolean approved){
    login(employeeUser);var start=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(10).atTime(8,0);if(approved)start=start.plusDays(1);
    long request=leaves.submit(new LeaveService.Form("RETURN_HOME","PERSONAL",start,start.withHour(12),"家","回段","请假测试",null,oldManager,true,UUID.randomUUID().toString(),null),null);
    if(approved){login(oldManager);leaves.review(request,1,new LeaveService.ReviewForm("APPROVE","同意"));}login(admin);return request;
  }

  @Test void previewCreatesNoBusinessRowsAndCommitCreatesOnlyAccountWithExpectedPassword()throws Exception{
    String no="0"+(10000+new Random().nextInt(89999));var p=preview(row("新站长",no));
    assertThat(p.errors()).isEmpty();assertThat(p.summary().createdAccounts()).isEqualTo(1);assertThat(count("select count(*) from sys_user where username=?",no)).isZero();
    commit(p);long user=id(no);var account=db.queryForMap("select * from sys_user where id=?",user);
    assertThat(account).containsEntry("must_change_password",true).containsEntry("role","STATION_MANAGER");assertThat(encoder.matches(no,String.valueOf(account.get("password_hash")))).isTrue();
    assertThat(count("select count(*) from employee where user_id=?",user)).isZero();assertThat(count("select count(*) from station_manager_scope where station_id=? and user_id=?",station,user)).isOne();
    assertThat(count("select count(*) from area_manager_scope where station_id=? and user_id=?",station,areaManager)).isOne();
    assertThat(count("select count(*) from sys_user where id=?",oldManager)).isOne();
    assertThat(String.valueOf(db.queryForObject("select after_value from operation_log where action='IMPORT_STATION_RESPONSIBILITIES' and user_id=? order by id desc limit 1",String.class,admin))).doesNotContain("password_hash","temporaryPassword");
  }
  @Test void onePersonManyStationsAndManyPeopleOneStationDeduplicateAndKeepUnmentionedScopes()throws Exception{
    String no=tag+"new";db.update("insert into station_manager_scope(user_id,station_id) values(?,?)",oldManager,otherStation);
    var p=preview(row("新站长",no),row("新站长",no),row("原站长",tag+"old"),new String[]{otherName,"新站长",no,"",""});
    assertThat(p.errors()).isEmpty();assertThat(p.summary().duplicateRelations()).isOne();assertThat(p.summary().createdAccounts()).isOne();commit(p);
    assertThat(count("select count(*) from station_manager_scope where user_id=?",id(no))).isEqualTo(2);
    assertThat(count("select count(*) from station_manager_scope where station_id=?",station)).isEqualTo(2);
    assertThat(count("select count(*) from station_manager_scope where station_id=? and user_id=?",otherStation,oldManager)).isZero();
  }
  @Test void reusePreservesPasswordAndOtherScopesAndSecondImportIsNoop()throws Exception{
    long user=user(tag+"reuse","复用站长","STATION_MANAGER");db.update("insert into station_manager_scope(user_id,station_id) values(?,?)",user,otherStation);
    String hash=db.queryForObject("select password_hash from sys_user where id=?",String.class,user);
    var p=preview(row("复用站长",tag+"reuse"));assertThat(p.summary().reusedAccounts()).isOne();commit(p);
    assertThat(db.queryForObject("select password_hash from sys_user where id=?",String.class,user)).isEqualTo(hash);
    assertThat(count("select count(*) from station_manager_scope where user_id=?",user)).isEqualTo(2);
    var again=preview(row("复用站长",tag+"reuse"));assertThat(again.summary().changedStations()).isZero();assertThat(again.summary().unchangedRelations()).isOne();commit(again);
  }
  @Test void replacesOnlyFilledRoleAndSkipsStationRowsWithoutResponsiblePeople()throws Exception{
    var p=preview(new String[]{stationName,"","","新区域经理",tag+"areaNew"},new String[]{otherName,"","","",""});
    assertThat(p.summary().skippedRows()).isOne();commit(p);
    assertThat(count("select count(*) from station_manager_scope where station_id=? and user_id=?",station,oldManager)).isOne();
  }
  @Test void rejectsHalfPairsNameRoleDisabledAndEmployeeIdentityConflictsWithoutPartialWrites()throws Exception{
    db.update("update sys_user set enabled=false where id=?",oldManager);
    db.update("update employee set employee_no=? where id=?",tag+"different",employee);
    var p=preview(row("有效新站长",tag+"valid"),new String[]{otherName,"缺少工号","","",""},row("错误姓名",tag+"old"),row("员工",tag+"employee"),row("员工",tag+"different"));
    assertThat(p.errors()).extracting(StationResponsibilityImportService.Diagnostic::message).anyMatch(s->s.contains("成对")).anyMatch(s->s.contains("姓名不一致")).anyMatch(s->s.contains("角色冲突")).anyMatch(s->s.contains("停用")).anyMatch(s->s.contains("关联账号不一致"));
    assertThatThrownBy(()->commit(p)).hasMessageContaining("存在错误");assertThat(count("select count(*) from sys_user where username=?",tag+"valid")).isZero();
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(imports.errors(p.batchId())))){assertThat(book.getSheetAt(0).getLastRowNum()).isGreaterThan(0);}
  }
  @Test void rejectsUnknownDisabledStationsDifferentNamesRolesAndCaseVariants()throws Exception{
    db.update("update service_station set enabled=false where id=?",otherStation);
    var p=preview(row("甲",tag+"x"),row("乙",tag+"x"),new String[]{stationName,"","","甲",tag+"x"},new String[]{otherName,"甲",tag+"q","",""},new String[]{"不存在","甲",tag+"q","",""},row("甲",tag.toUpperCase(Locale.ROOT)+"X"));
    assertThat(p.errors()).extracting(StationResponsibilityImportService.Diagnostic::message).anyMatch(s->s.contains("不同姓名")).anyMatch(s->s.contains("不能同时")).anyMatch(s->s.contains("大小写")).anyMatch(s->s.contains("停用"));
  }
  @Test void emptyTemplateCannotCommit()throws Exception{
    var p=preview(new String[]{stationName,"","","",""});assertThat(p.status()).isEqualTo("INVALID");assertThat(p.errors()).anyMatch(e->e.row()==0);assertThatThrownBy(()->commit(p)).hasMessageContaining("错误");
  }
  @Test void newAccountReceivesPendingAndApprovedUnreturnedResponsibilities()throws Exception{
    long pending=leave(false),approved=leave(true);var p=preview(row("接任站长",tag+"new"));assertThat(p.summary().affectedVersions()).isEqualTo(2);commit(p);
    long next=id(tag+"new");assertThat(count("select count(*) from leave_request_version where request_id in (?,?) and approver_user_id=?",pending,approved,next)).isEqualTo(2);
    assertThat(count("select count(*) from leave_action where request_id in (?,?) and action='TRANSFER'",pending,approved)).isEqualTo(2);
    login(next);assertThat(leaves.list("pending",null,null,null,null,null,null,null,null,1,20).records()).anyMatch(x->((Number)x.get("request_id")).longValue()==pending);
    assertThatThrownBy(()->permissions.requireEmployee(employee+999999)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    permissions.requireEmployee(employee);login(oldManager);assertThatThrownBy(()->permissions.requireEmployee(employee)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
  @Test void requiresExplicitValidTransferAndRejectsForgedTargets()throws Exception{
    leave(false);var p=preview(row("接任甲",tag+"new"),row("接任乙",tag+"new2"));
    assertThat(p.impacts().get(0).candidateRefs()).hasSize(2);
    assertThatThrownBy(()->imports.commit(p.batchId(),new StationResponsibilityImportService.Commit("调整",Map.of()))).hasMessageContaining("选择");
    assertThatThrownBy(()->imports.commit(p.batchId(),new StationResponsibilityImportService.Commit("调整",Map.of(p.impacts().get(0).key(),"STATION_MANAGER:forged")))).hasMessageContaining("选择");
    assertThat(count("select count(*) from sys_user where username=?",tag+"new")).isZero();
  }
  @Test void rollbackAfterAccountCreationRestoresAccountScopeLeaveAndAudit()throws Exception{
    long request=leave(false);var p=preview(row("接任站长",tag+"new"));
    doThrow(new IllegalStateException("forced audit failure")).when(audit).log(eq("IMPORT_STATION_RESPONSIBILITIES"),anyString(),isNull(),any(),any());
    assertThatThrownBy(()->commit(p)).isInstanceOf(IllegalStateException.class);
    assertThat(count("select count(*) from sys_user where username=?",tag+"new")).isZero();
    assertThat(count("select count(*) from station_manager_scope where station_id=? and user_id=?",station,oldManager)).isOne();
    assertThat(db.queryForObject("select approver_user_id from leave_request_version where request_id=?",Long.class,request)).isEqualTo(oldManager);
    assertThat(count("select count(*) from leave_action where request_id=? and action='TRANSFER'",request)).isZero();
    assertThat(imports.get(p.batchId()).get("status")).isEqualTo("VALIDATED");
  }
  @Test void previewBecomesStaleAfterIdentityStationOrLeaveChanges()throws Exception{
    var p=preview(row("新站长",tag+"new"));user(tag+"new","新站长","STATION_MANAGER");assertThatThrownBy(()->commit(p)).hasMessageContaining("重新预览");
    var q=preview(row("新站长",tag+"new2"));db.update("update service_station set enabled=false where id=?",station);assertThatThrownBy(()->commit(q)).hasMessageContaining("重新预览");db.update("update service_station set enabled=true where id=?",station);
    var r=preview(row("新站长",tag+"new3"));leave(false);assertThatThrownBy(()->commit(r)).hasMessageContaining("重新预览");
  }
  @Test void expiredBatchAndNonOwnerCannotCommitOrReadOrDownload()throws Exception{
    var p=preview(row("新站长",tag+"new"));login(otherAdmin);assertThatThrownBy(()->imports.get(p.batchId())).isInstanceOf(BusinessException.class).hasMessageContaining("无权");assertThatThrownBy(()->imports.errors(p.batchId())).hasMessageContaining("无权");assertThatThrownBy(()->commit(p)).hasMessageContaining("无权");login(admin);
    db.update("update station_responsibility_import set expires_at=date_sub(now(),interval 1 minute) where id=?",p.batchId());assertThatThrownBy(()->commit(p)).hasMessageContaining("过期");
  }
  @Test void duplicateAndConcurrentCommitsReturnOriginalResultExactlyOnce()throws Exception{
    var p=preview(row("新站长",tag+"new"));var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{
      Callable<Map<String,Object>> task=()->{login(admin);try{start.await();return commit(p);}finally{SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}};
      var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertThat(a.get(30,TimeUnit.SECONDS).get("result")).isEqualTo(b.get(30,TimeUnit.SECONDS).get("result"));
      login(admin);assertThat(commit(p).get("status")).isEqualTo("COMPLETED");
      assertThat(count("select count(*) from sys_user where username=?",tag+"new")).isOne();assertThat(count("select count(*) from operation_log where action='IMPORT_STATION_RESPONSIBILITIES' and user_id=?",admin)).isOne();
    }finally{pool.shutdownNow();}
  }
  @Test void apiEnforcesAdminPermissionOwnerAndAuthentication()throws Exception{
    String adminToken=jwt.create(permissions.load(admin)),employeeToken=jwt.create(permissions.load(employeeUser));SecurityContextHolder.clearContext();
    mvc.perform(get("/api/v1/station-responsibilities/import-template")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/station-responsibilities/import-template").header("Authorization","Bearer "+employeeToken)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/station-responsibilities/import-template").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    mvc.perform(multipart("/api/v1/station-responsibilities/imports/preview").file(file(row("新站长",tag+"new"))).header("Authorization","Bearer "+employeeToken)).andExpect(status().isForbidden());
  }
  @Test void shortInitialPasswordLoginRequiresChangeAndOldTokenIsRevoked()throws Exception{
    String no;do{no=String.valueOf(1000+new Random().nextInt(8999));}while(count("select count(*) from sys_user where username=?",no)>0);
    commit(preview(row("短工号站长",no)));SecurityContextHolder.clearContext();
    var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",no,"password",no)))).andExpect(status().isOk()).andReturn();
    var data=mapper.readTree(login.getResponse().getContentAsString()).get("data");String token=data.get("token").asText();assertThat(data.get("user").get("mustChangePassword").asBoolean()).isTrue();
    mvc.perform(get("/api/v1/employees").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    var changed=mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(Map.of("oldPassword",no,"newPassword","NewPassword123!")))).andExpect(status().isOk()).andReturn();
    var changedData=mapper.readTree(changed.getResponse().getContentAsString()).get("data");assertThat(changedData.get("user").get("mustChangePassword").asBoolean()).isFalse();
    mvc.perform(get("/api/v1/employees").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/employees").header("Authorization","Bearer "+changedData.get("token").asText())).andExpect(status().isOk());
  }
  @Test void cleanupRemovesOldBatchesButRetainsBusinessAudit()throws Exception{
    var p=preview(row("新站长",tag+"new"));commit(p);db.update("update station_responsibility_import set created_at=date_sub(now(),interval 8 day) where id=?",p.batchId());imports.cleanup();
    assertThat(count("select count(*) from station_responsibility_import where id=?",p.batchId())).isZero();assertThat(count("select count(*) from operation_log where action='IMPORT_STATION_RESPONSIBILITIES' and user_id=?",admin)).isOne();
  }
}
