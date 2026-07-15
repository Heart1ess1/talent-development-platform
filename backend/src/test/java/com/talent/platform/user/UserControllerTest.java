package com.talent.platform.user;

import com.talent.platform.common.BusinessException;import com.talent.platform.security.*;
import org.junit.jupiter.api.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.security.access.AccessDeniedException;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;import static org.assertj.core.api.Assertions.*;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;

class UserControllerTest {
  private JdbcTemplate db;private UserController controller;private final PermissionService permissionService=new PermissionService(mock(JdbcTemplate.class));
  @BeforeEach void setUp(){db=mock(JdbcTemplate.class);var encoder=mock(PasswordEncoder.class);when(encoder.encode(anyString())).thenReturn("hash");when(db.queryForObject(eq("select last_insert_id()"),eq(Long.class))).thenReturn(10L);controller=new UserController(db,encoder,permissionService,mock(AuditService.class));}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void adminCanCreateOperationalRoles(){
    authenticate("ADMIN");
    var result=controller.create(new UserController.UserRequest("mentor1","导师一","MENTOR",null));
    assertThat(result.data()).containsEntry("id",10L).containsKey("temporaryPassword");
  }

  @Test void adminCannotCreateSystemAdminRoles(){
    authenticate("ADMIN");
    assertThatThrownBy(()->controller.create(new UserController.UserRequest("admin1","管理员","ADMIN",null))).isInstanceOf(AccessDeniedException.class);
  }

  @Test void stationManagerMustHaveStationScope(){
    authenticate("ADMIN");
    assertThatThrownBy(()->controller.create(new UserController.UserRequest("station1","站点负责人","STATION_MANAGER",List.of()))).isInstanceOf(BusinessException.class).hasMessageContaining("站长至少需要绑定一个服务站");
  }

  @Test void superAdminCanChangeLinkedAccountToEmployeeRole(){
    authenticate("SUPER_ADMIN");
    when(db.queryForObject(eq("select role from sys_user where id=?"),eq(String.class),eq(2L))).thenReturn("MENTOR");
    when(db.queryForObject(eq("select count(*) from employee where user_id=?"),eq(Integer.class),eq(2L))).thenReturn(1);
    controller.role(2L,Map.of("role","EMPLOYEE"));
    verify(db).update(eq("update sys_user set role=?,version=version+1,security_version=security_version+1 where id=?"),eq("EMPLOYEE"),eq(2L));
  }

  @Test void accountWithoutEmployeeProfileCannotBecomeEmployeeRole(){
    authenticate("SUPER_ADMIN");
    when(db.queryForObject(eq("select role from sys_user where id=?"),eq(String.class),eq(2L))).thenReturn("MENTOR");
    when(db.queryForObject(eq("select count(*) from employee where user_id=?"),eq(Integer.class),eq(2L))).thenReturn(0);
    assertThatThrownBy(()->controller.role(2L,Map.of("role","EMPLOYEE"))).isInstanceOf(BusinessException.class).hasMessageContaining("未关联员工档案");
  }

  @Test void linkedEmployeeAccountCannotBecomeOperationalRole(){
    authenticate("SUPER_ADMIN");
    when(db.queryForObject(eq("select role from sys_user where id=?"),eq(String.class),eq(2L))).thenReturn("EMPLOYEE");
    when(db.queryForObject(eq("select count(*) from employee where user_id=?"),eq(Integer.class),eq(2L))).thenReturn(1);
    assertThatThrownBy(()->controller.role(2L,Map.of("role","MENTOR"))).isInstanceOf(BusinessException.class).hasMessageContaining("员工账号角色不可修改");
  }

  @Test void superAdminCanChangeNonEmployeeDisplayName(){
    authenticate("SUPER_ADMIN");
    when(db.queryForObject(eq("select count(*) from employee where user_id=?"),eq(Integer.class),eq(3L))).thenReturn(0);
    when(db.queryForMap(eq("select id,display_name from sys_user where id=?"),eq(3L))).thenReturn(Map.of("id",3L,"display_name","旧姓名"));
    controller.displayName(3L,new UserController.DisplayNameRequest("新姓名"));
    verify(db).update(eq("update sys_user set display_name=?,version=version+1 where id=?"),eq("新姓名"),eq(3L));
  }

  @Test void employeeDisplayNameUpdatesLinkedEmployeeName(){
    authenticate("SUPER_ADMIN");
    when(db.queryForObject(eq("select count(*) from employee where user_id=?"),eq(Integer.class),eq(2L))).thenReturn(1);
    when(db.queryForMap(eq("select id,display_name from sys_user where id=?"),eq(2L))).thenReturn(Map.of("id",2L,"display_name","旧姓名"));
    controller.displayName(2L,new UserController.DisplayNameRequest("新姓名"));
    verify(db).update(eq("update sys_user set display_name=?,version=version+1 where id=?"),eq("新姓名"),eq(2L));
    verify(db).update(eq("update employee set name=?,version=version+1 where user_id=?"),eq("新姓名"),eq(2L));
  }

  @Test void superAdminCanChangeUsernameAndRevokeTheTargetSession(){
    authenticate("SUPER_ADMIN");
    when(db.queryForMap(eq("select id,username,role from sys_user where id=?"),eq(3L))).thenReturn(Map.of("id",3L,"username","mentor-old","role","MENTOR"));
    when(db.queryForObject(eq("select count(*) from employee where user_id=?"),eq(Integer.class),eq(3L))).thenReturn(0);
    controller.username(3L,new UserController.UsernameRequest("mentor-new"));
    verify(db).update(eq("update sys_user set username=?,version=version+1,security_version=security_version+1 where id=?"),eq("mentor-new"),eq(3L));
  }

  @Test void employeeUsernameAlsoUpdatesEmployeeNumber(){
    authenticate("SUPER_ADMIN");
    when(db.queryForMap(eq("select id,username,role from sys_user where id=?"),eq(2L))).thenReturn(Map.of("id",2L,"username","010200001111","role","EMPLOYEE"));
    when(db.queryForObject(eq("select count(*) from employee where user_id=?"),eq(Integer.class),eq(2L))).thenReturn(1);
    controller.username(2L,new UserController.UsernameRequest("010200001112"));
    verify(db).update(eq("update employee set employee_no=?,version=version+1 where user_id=?"),eq("010200001112"),eq(2L));
  }

  @Test void superAdminUsernameCannotBeChanged(){
    authenticate("SUPER_ADMIN");
    when(db.queryForMap(eq("select id,username,role from sys_user where id=?"),eq(1L))).thenReturn(Map.of("id",1L,"username","superadmin","role","SUPER_ADMIN"));
    assertThatThrownBy(()->controller.username(1L,new UserController.UsernameRequest("another-admin"))).isInstanceOf(BusinessException.class).hasMessageContaining("不能修改超级管理员");
  }

  private void authenticate(String role){var u=new CurrentUser(7L,"u","U",role,false,1,permissionService.permissions(role),"ALL");SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));}
}
