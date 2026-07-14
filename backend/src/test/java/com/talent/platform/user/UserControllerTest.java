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

  private void authenticate(String role){var u=new CurrentUser(7L,"u","U",role,false,1,permissionService.permissions(role),"ALL");SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));}
}
