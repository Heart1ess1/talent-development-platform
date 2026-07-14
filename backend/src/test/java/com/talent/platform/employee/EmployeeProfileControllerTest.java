package com.talent.platform.employee;

import com.talent.platform.common.BusinessException;import com.talent.platform.security.*;
import org.junit.jupiter.api.*;import org.mockito.ArgumentCaptor;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.context.SecurityContextHolder;
import java.time.LocalDate;import java.util.*;import static org.assertj.core.api.Assertions.*;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;

class EmployeeProfileControllerTest {
  private JdbcTemplate db;private EmployeeProfileController controller;
  @BeforeEach void setUp(){db=mock(JdbcTemplate.class);controller=new EmployeeProfileController(db,mock(AuditService.class));when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(new HashMap<>(Map.of("id",3L,"phone","13800000000"))));}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void employeeCanUpdateOnlyProfileFields(){
    authenticate("EMPLOYEE");controller.update(new EmployeeProfileController.ProfileRequest("13900000000","a@example.com",LocalDate.of(2000,1,1),"籍贯","常住地","学校","专业","本科"));
    var sql=ArgumentCaptor.forClass(String.class);verify(db).update(sql.capture(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    assertThat(sql.getValue()).contains("phone=?","email=?","birth_date=?").doesNotContain("station_id","mentor_user_id","batch_id","status");
  }

  @Test void nonEmployeeCannotUpdateEmployeeProfile(){
    authenticate("MENTOR");
    assertThatThrownBy(()->controller.update(new EmployeeProfileController.ProfileRequest("13900000000",null,null,null,null,null,null,null))).isInstanceOf(BusinessException.class);
  }

  private void authenticate(String role){var permissions=new PermissionService(mock(JdbcTemplate.class));var scope="EMPLOYEE".equals(role)?"SELF":"MENTORED";var u=new CurrentUser(7L,"u","U",role,false,1,permissions.permissions(role),scope);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));}
}
