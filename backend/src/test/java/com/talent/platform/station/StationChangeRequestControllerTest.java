package com.talent.platform.station;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationChangeRequestControllerTest {
  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

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

  @Test
  void rejectionRequiresActionableReason() {
    var controller = new StationChangeRequestController(
        mock(JdbcTemplate.class), mock(AuditService.class), mock(PermissionService.class));
    authenticateAdmin();

    assertThatThrownBy(() -> controller.reject(
        8L, new StationChangeRequestController.ReviewRequest("  ")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("必须填写原因");
  }

  @Test
  void approvalRejectsStaleStationSnapshot() {
    var db = mock(JdbcTemplate.class);
    var controller = new StationChangeRequestController(
        db, mock(AuditService.class), mock(PermissionService.class));
    authenticateAdmin();
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of(
            "id", 8L,
            "employee_id", 42L,
            "current_station_id", 3L,
            "requested_station_id", 5L,
            "status", "PENDING")))
        .thenReturn(List.of(Map.of("station_id", 4L)));

    assertThatThrownBy(() -> controller.approve(8L, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("当前服务站已发生变化");
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void summaryReturnsApprovalWorkload() {
    var db = mock(JdbcTemplate.class);
    var permissions = mock(PermissionService.class);
    var controller = new StationChangeRequestController(
        db, mock(AuditService.class), permissions);
    when(db.queryForMap(anyString())).thenReturn(Map.of(
        "total", 12L,
        "pending", 3L,
        "approved_today", 2L,
        "rejected_today", 1L,
        "average_pending_hours", 6.5));

    var response = controller.summary();

    verify(permissions).require(Permissions.MASTER_MANAGE);
    assertThat(response.data().pending()).isEqualTo(3);
    assertThat(response.data().averagePendingHours()).isEqualTo(6.5);
  }

  private void authenticateAdmin() {
    var user = new CurrentUser(
        7L, "admin", "管理员", "ADMIN", false, 1,
        Set.of(Permissions.MASTER_MANAGE), "ALL");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }
}
