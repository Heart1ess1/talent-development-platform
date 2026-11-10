package com.talent.platform.user;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Statement;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Use a disposable MySQL database; every scenario rolls back its account and role changes. */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="ROLE_TEST_DB_URL",matches=".+")
class MultiRoleManagementIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry p){
    p.add("spring.datasource.url",()->System.getenv("ROLE_TEST_DB_URL"));
    p.add("app.bootstrap.demo-users-enabled",()->false);
  }
  @Autowired JdbcTemplate db;
  @Autowired UserController controller;
  @Autowired PermissionService permissions;
  @Autowired com.talent.platform.station.StationResponsibilityService responsibilities;
  long operator,target,a,b,c;
  String tag;

  @BeforeEach void setup(){
    tag="roles-"+UUID.randomUUID().toString().substring(0,8);
    operator=user("operator","SUPER_ADMIN");target=user("target","MENTOR");
    a=insert("insert into service_station(name) values(?)",tag+"A");
    b=insert("insert into service_station(name) values(?)",tag+"B");
    c=insert("insert into service_station(name) values(?)",tag+"C");
    login(operator,"SUPER_ADMIN");
  }
  @AfterEach void cleanup(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
  long insert(String sql,Object...args){
    var key=new GeneratedKeyHolder();
    db.update(connection->{var s=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);
    return key.getKey().longValue();
  }
  long user(String suffix,String role){
    long id=insert("insert into sys_user(username,display_name,password_hash,role,enabled,must_change_password) values(?,?,'initial',?,true,false)",tag+suffix,tag+suffix,role);
    db.update("insert into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,true,?)",id,role,id);return id;
  }
  void addRole(long id,String role){db.update("insert into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,false,?)",id,role,operator);}
  void login(long id,String role){
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(permissions.load(id,role),null,List.of()));
  }
  List<String> roles(){return permissions.roles(target);}
  String primary(){return db.queryForObject("select role from sys_user where id=?",String.class,target);}
  int version(){return db.queryForObject("select security_version from sys_user where id=?",Integer.class,target);}
  UserController.BulkRequest request(Map<String,Object> changes){return new UserController.BulkRequest(new UserController.BulkSelection("IDS",List.of(target),Map.of(),List.of()),changes,false,null,null,UUID.randomUUID().toString());}
  Map<String,Object> change(String action,String role){return controller.bulkExecute(request(Map.of("roleAction",action,"role",role))).data();}
  List<Long> scopes(String table){return db.queryForList("select station_id from "+table+" where user_id=? order by station_id",Long.class,target);}
  void bothScopes(){
    controller.roleSet(target,new UserController.RolesRequest(List.of("MENTOR","STATION_MANAGER","AREA_MANAGER"),"MENTOR",null,List.of(a),List.of(a,b,c)));
  }
  long leave(String level){
    long employeeUser=user("employee","EMPLOYEE");
    long employee=insert("insert into employee(user_id,employee_no,name) values(?,?,?)",employeeUser,tag,tag);
    long leave=insert("insert into leave_request(employee_id,status) values(?,'PENDING')",employee);
    db.update("insert into leave_request_version(request_id,version_no,scenario,leave_type,start_at,end_at,destination,return_location,reason,day_units,approver_level,approver_user_id,status,notice_version,notice_acknowledged_at,created_by,station_id) values(?,1,'RETURN_HOME','PERSONAL','2026-01-01 08:00:00','2026-01-01 12:00:00','home','company','test',1,?,?,'PENDING','V1',now(),?,?)",leave,level,target,employeeUser,a);
    return leave;
  }

  @Test void adminCannotResetSecondarySuperAdminAndPasswordIsUnchanged(){
    addRole(target,"SUPER_ADMIN");long admin=user("admin","ADMIN");login(admin,"ADMIN");
    assertThatThrownBy(()->controller.reset(target)).isInstanceOf(AccessDeniedException.class);
    assertThat(db.queryForObject("select password_hash from sys_user where id=?",String.class,target)).isEqualTo("initial");
  }
  @Test void adminCannotDisableSecondaryAdministrator(){
    addRole(target,"ADMIN");long admin=user("admin","ADMIN");login(admin,"ADMIN");
    assertThatThrownBy(()->controller.enabled(target,new UserController.EnableRequest(false))).isInstanceOf(AccessDeniedException.class);
    assertThat(db.queryForObject("select enabled from sys_user where id=?",Boolean.class,target)).isTrue();
  }
  @Test void superAdminCanResetSecondarySuperAdmin(){addRole(target,"SUPER_ADMIN");assertThat(controller.reset(target).data()).containsKey("temporaryPassword");}
  @Test void accountListExcludesProtectedSecondaryRolesForOrdinaryAdmin(){
    addRole(target,"SUPER_ADMIN");long admin=user("admin","ADMIN");login(admin,"ADMIN");
    assertThat(controller.list(null).data()).noneMatch(x->((Number)x.get("id")).longValue()==target);
  }
  @Test void grantingExistingPrimaryIsUnchanged(){int v=version();assertThat(change("GRANT","MENTOR")).containsEntry("changed",0);assertThat(version()).isEqualTo(v);}
  @Test void grantingExistingSecondaryKeepsPrimaryAndScope(){
    bothScopes();int v=version();var before=roles();
    assertThat(controller.bulkExecute(request(Map.of("roleAction","GRANT","role","STATION_MANAGER","stationIds",List.of(c)))).data()).containsEntry("changed",0);
    assertThat(roles()).isEqualTo(before);assertThat(primary()).isEqualTo("MENTOR");assertThat(scopes("station_manager_scope")).containsExactly(a);assertThat(version()).isEqualTo(v);
  }
  @Test void grantingNewRolePreservesExistingRolesAndPrimary(){
    int v=version();assertThat(change("GRANT","TRAINING_ADMIN")).containsEntry("changed",1);
    assertThat(roles()).containsExactly("MENTOR","TRAINING_ADMIN");assertThat(primary()).isEqualTo("MENTOR");assertThat(version()).isEqualTo(v+1);
  }
  @Test void grantingNewScopedRoleOnlySetsItsOwnScope(){
    bothScopes();change("REVOKE","STATION_MANAGER");
    controller.bulkExecute(request(Map.of("roleAction","GRANT","role","STATION_MANAGER","stationIds",List.of(b))));
    assertThat(scopes("station_manager_scope")).containsExactly(b);assertThat(scopes("area_manager_scope")).containsExactly(a,b,c);
  }
  @Test void revokeMissingRoleIsUnchanged(){int v=version();assertThat(change("REVOKE","TRAINING_ADMIN")).containsEntry("changed",0);assertThat(version()).isEqualTo(v);}
  @Test void revokePrimarySelectsRemainingRoleWithoutDroppingIt(){addRole(target,"TRAINING_ADMIN");change("REVOKE","MENTOR");assertThat(primary()).isEqualTo("TRAINING_ADMIN");assertThat(roles()).containsExactly("TRAINING_ADMIN");}
  @Test void revokeLastRoleIsRejected(){assertThatThrownBy(()->change("REVOKE","MENTOR")).isInstanceOf(BusinessException.class).hasMessageContaining("至少一个身份");}
  @Test void revokeStationKeepsAreaABC(){bothScopes();change("REVOKE","STATION_MANAGER");assertThat(scopes("station_manager_scope")).isEmpty();assertThat(scopes("area_manager_scope")).containsExactly(a,b,c);assertThat(permissions.load(target,"AREA_MANAGER").dataScope()).isEqualTo("AREA");}
  @Test void revokeAreaKeepsStationA(){bothScopes();change("REVOKE","AREA_MANAGER");assertThat(scopes("station_manager_scope")).containsExactly(a);assertThat(scopes("area_manager_scope")).isEmpty();}
  @Test void fullRoleEditorAlsoCleansOnlyRevokedScope(){
    bothScopes();controller.roleSet(target,new UserController.RolesRequest(List.of("MENTOR","AREA_MANAGER"),"MENTOR",null,null,List.of(a,b,c)));
    assertThat(scopes("station_manager_scope")).isEmpty();assertThat(scopes("area_manager_scope")).containsExactly(a,b,c);
  }
  @Test void changingStationScopeDoesNotChangeAreaScope(){bothScopes();controller.stations(target,new UserController.StationScopeRequest("STATION_MANAGER",List.of(b)));assertThat(scopes("station_manager_scope")).containsExactly(b);assertThat(scopes("area_manager_scope")).containsExactly(a,b,c);}
  @Test void ambiguousLegacyScopeCannotOverwriteBothRoles(){bothScopes();assertThatThrownBy(()->controller.stations(target,new UserController.StationScopeRequest(null,List.of(b)))).isInstanceOf(BusinessException.class).hasMessageContaining("请选择");}
  @Test void relevantPendingLeaveBlocksRevocation(){bothScopes();leave("STATION_MANAGER");assertThatThrownBy(()->change("REVOKE","STATION_MANAGER")).isInstanceOf(BusinessException.class).hasMessageContaining("先转交");assertThat(roles()).contains("STATION_MANAGER");}
  @Test void unrelatedPendingLeaveDoesNotBlockOtherRoleRevocation(){bothScopes();leave("AREA_MANAGER");change("REVOKE","STATION_MANAGER");assertThat(scopes("area_manager_scope")).containsExactly(a,b,c);}
  @Test void roleEditorCanRevokeStationWhileAreaLeaveIsPending(){
    bothScopes();leave("AREA_MANAGER");
    controller.roleSet(target,new UserController.RolesRequest(List.of("MENTOR","AREA_MANAGER"),"MENTOR",null,List.of(),List.of(a,b,c)));
    assertThat(scopes("station_manager_scope")).isEmpty();assertThat(scopes("area_manager_scope")).containsExactly(a,b,c);
  }
  @Test void responsibilityContextListsBothIdentities(){
    bothScopes();
    @SuppressWarnings("unchecked") var users=(List<Map<String,Object>>)responsibilities.context().get("users");
    assertThat(users.stream().filter(x->((Number)x.get("id")).longValue()==target).map(x->String.valueOf(x.get("role"))).toList())
      .containsExactlyInAnyOrder("STATION_MANAGER","AREA_MANAGER");
  }
  @Test void accountScopeSaveIsVisibleInResponsibilityManagement(){
    bothScopes();
    controller.stations(target,new UserController.StationScopeRequest("STATION_MANAGER",List.of(b)));
    @SuppressWarnings("unchecked") var visible=(List<Map<String,Object>>)responsibilities.context().get("scopes");
    var mine=visible.stream().filter(x->((Number)x.get("user_id")).longValue()==target).toList();
    assertThat(mine.stream().filter(x->"STATION_MANAGER".equals(x.get("level"))).map(x->((Number)x.get("station_id")).longValue()).toList()).containsExactly(b);
    assertThat(mine.stream().filter(x->"AREA_MANAGER".equals(x.get("level"))).map(x->((Number)x.get("station_id")).longValue()).toList()).containsExactlyInAnyOrder(a,b,c);
  }
  @Test void responsibilitySaveIsVisibleInAccountEditorAndPermissions(){
    bothScopes();
    var assignments=List.of(
      new com.talent.platform.station.StationResponsibilityService.Assignment(a,"STATION_MANAGER",List.of()),
      new com.talent.platform.station.StationResponsibilityService.Assignment(b,"STATION_MANAGER",List.of(target)));
    var initial=new com.talent.platform.station.StationResponsibilityService.Change(assignments,Map.of(),"调整站长范围",null);
    var preview=responsibilities.preview(initial);
    responsibilities.save(new com.talent.platform.station.StationResponsibilityService.Change(assignments,Map.of(),initial.reason(),String.valueOf(preview.get("baselineHash"))));
    var account=controller.list(null).data().stream().filter(x->((Number)x.get("id")).longValue()==target).findFirst().orElseThrow();
    assertThat(String.valueOf(account.get("station_manager_station_ids"))).isEqualTo(String.valueOf(b));
    assertThat(String.valueOf(account.get("area_manager_station_ids"))).isEqualTo(a+","+b+","+c);
    long employeeUser=user("scoped-employee","EMPLOYEE");
    long employee=insert("insert into employee(user_id,employee_no,name,station_id) values(?,?,?,?)",employeeUser,tag+"-scope",tag,b);
    login(target,"STATION_MANAGER");
    assertThatCode(()->permissions.requireEmployee(employee)).doesNotThrowAnyException();
    db.update("update employee set station_id=? where id=?",a,employee);
    assertThatThrownBy(()->permissions.requireEmployee(employee)).isInstanceOf(AccessDeniedException.class);
    login(target,"AREA_MANAGER");
    assertThatCode(()->permissions.requireEmployee(employee)).doesNotThrowAnyException();
  }
  @Test void roleGrantCannotUseAStalePreview(){
    var q=request(Map.of("roleAction","GRANT","role","TRAINING_ADMIN"));var preview=controller.bulkPreview(q).data();
    controller.bulkExecute(request(Map.of("roleAction","GRANT","role","ADMIN")));
    var stale=new UserController.BulkRequest(q.selection(),q.changes(),false,1,String.valueOf(preview.get("selectionHash")),q.requestId());
    assertThatThrownBy(()->controller.bulkExecute(stale)).isInstanceOf(BusinessException.class).hasMessageContaining("重新预览");
    assertThat(roles()).doesNotContain("TRAINING_ADMIN");
  }
  @Test void missingActionCannotInvokeOldDestructiveBulkReplacement(){assertThatThrownBy(()->controller.bulkPreview(request(Map.of("role","TRAINING_ADMIN")))).isInstanceOf(BusinessException.class).hasMessageContaining("授予或撤销");}
  @Test void previewAndExecuteAgreeOnIdempotentAndChangedOperations(){
    var q=request(Map.of("roleAction","GRANT","role","TRAINING_ADMIN"));var preview=controller.bulkPreview(q).data();
    assertThat(preview).containsEntry("changed",1);
    var execute=new UserController.BulkRequest(q.selection(),q.changes(),false,1,String.valueOf(preview.get("selectionHash")),q.requestId());
    assertThat(controller.bulkExecute(execute).data()).containsEntry("changed",1);
    assertThat(controller.bulkPreview(request(q.changes())).data()).containsEntry("changed",0);
  }
}
