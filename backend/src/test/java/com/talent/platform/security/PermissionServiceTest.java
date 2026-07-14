package com.talent.platform.security;

import org.junit.jupiter.api.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.context.SecurityContextHolder;import java.util.*;import static org.assertj.core.api.Assertions.assertThat;import static org.mockito.Mockito.mock;

class PermissionServiceTest {
  private final PermissionService service=new PermissionService(mock(JdbcTemplate.class));
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void fixedRolePermissionMatrix(){
    assertThat(service.permissions("EMPLOYEE")).containsExactly(Permissions.EMPLOYEE_READ,Permissions.EVALUATION_VIEW);
    assertThat(service.permissions("MENTOR")).contains(Permissions.EMPLOYEE_READ,Permissions.EVALUATION_SUBMIT).doesNotContain(Permissions.TASK_MANAGE,Permissions.TASK_REVIEW);
    assertThat(service.permissions("STATION_MANAGER")).contains(Permissions.EVALUATION_SUBMIT).doesNotContain(Permissions.ATTENDANCE_MANAGE);
    assertThat(service.permissions("TRAINING_ADMIN")).contains(Permissions.COURSE_MANAGE,Permissions.TASK_MANAGE,Permissions.TASK_REVIEW,Permissions.EXAM_MANAGE).doesNotContain(Permissions.EMPLOYEE_WRITE,Permissions.USER_EMPLOYEE_MANAGE);
    assertThat(service.permissions("ADMIN")).contains(Permissions.EMPLOYEE_WRITE,Permissions.USER_EMPLOYEE_MANAGE,Permissions.MASTER_MANAGE,Permissions.EVALUATION_VIEW).doesNotContain(Permissions.USER_ADMIN_MANAGE,Permissions.EVALUATION_SUBMIT);
    assertThat(service.permissions("SUPER_ADMIN")).contains(Permissions.USER_ADMIN_MANAGE,Permissions.AUDIT_READ);
  }
  @Test void scopeFiltersAreDerivedFromAuthenticatedContext(){
    authenticate("EMPLOYEE","SELF");assertThat(service.employeeFilter("e").sql()).contains("e.user_id=?");
    authenticate("MENTOR","MENTORED");assertThat(service.employeeFilter("e").sql()).contains("e.mentor_user_id=?");
    authenticate("STATION_MANAGER","STATION");assertThat(service.employeeFilter("e").sql()).contains("station_manager_scope");
    authenticate("ADMIN","ALL");assertThat(service.employeeFilter("e").sql()).isEmpty();
  }
  private void authenticate(String role,String scope){var u=new CurrentUser(7L,"u","U",role,false,1,service.permissions(role),scope);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));}
}
