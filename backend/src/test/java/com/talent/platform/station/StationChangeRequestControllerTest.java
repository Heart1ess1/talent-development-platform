package com.talent.platform.station;

import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationChangeRequestControllerTest {
  @Test
  void employeeHistoryUsesDataScopeAndApprovalTime() {
    var db = mock(JdbcTemplate.class);
    var permissions = mock(PermissionService.class);
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    var controller = new StationChangeRequestController(db, mock(AuditService.class), permissions);

    controller.employeeHistory(42L);

    verify(permissions).require(Permissions.EMPLOYEE_READ);
    verify(permissions).requireEmployee(42L);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), any(Object[].class));
    assertThat(sql.getValue())
        .contains("coalesce(r.reviewed_at,r.updated_at) effective_at")
        .contains("r.status='APPROVED'")
        .contains("order by coalesce(r.reviewed_at,r.updated_at) desc");
  }
}
