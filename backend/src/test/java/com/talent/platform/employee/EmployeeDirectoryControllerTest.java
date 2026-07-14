package com.talent.platform.employee;

import com.talent.platform.security.*;
import org.junit.jupiter.api.Test;import org.mockito.ArgumentCaptor;import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;import static org.assertj.core.api.Assertions.assertThat;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;

class EmployeeDirectoryControllerTest {
  @Test void directoryQueriesApplyEmployeeScope(){
    var db=mock(JdbcTemplate.class);var permissions=mock(PermissionService.class);
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter(" and e.mentor_user_id=?",List.of(7L)));
    when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
    when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
    var controller=new EmployeeDirectoryController(db,permissions,mock(AuditService.class));

    controller.list(1,20,null,null,null,null,null,null);

    var sql=ArgumentCaptor.forClass(String.class);verify(db).queryForObject(sql.capture(),eq(Long.class),any(Object[].class));
    assertThat(sql.getValue()).contains("e.mentor_user_id=?");
  }
}
