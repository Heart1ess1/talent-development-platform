package com.talent.platform.movement;

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

import java.sql.Timestamp;
import java.time.LocalDateTime;
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

class LocationReportControllerTest {
  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void employeeFirstReportStartsFromAssignedStation() {
    var db = mock(JdbcTemplate.class);
    var audit = mock(AuditService.class);
    var controller = new LocationReportController(db, mock(PermissionService.class), audit);
    authenticate("EMPLOYEE", "SELF");
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 31L, "name", "张明", "station_name", "华东服务站")))
        .thenReturn(List.of());
    when(db.update(anyString(), any(Object[].class))).thenReturn(1);
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(88L);
    var occurredAt = LocalDateTime.now().minusMinutes(10);

    var response = controller.submit(new LocationReportController.SubmitRequest(
        "南京客户现场", "配合设备调试", occurredAt, occurredAt.plusDays(2)));

    assertThat(response.data()).isEqualTo(88L);
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(sql.capture(), args.capture());
    assertThat(sql.getValue()).contains("insert into employee_location_report");
    assertThat(args.getValue())
        .containsExactly(31L, "华东服务站", "南京客户现场", "配合设备调试",
            occurredAt, occurredAt.plusDays(2), 7L);
    verify(audit).log(
        org.mockito.ArgumentMatchers.eq("SUBMIT_LOCATION_REPORT"),
        org.mockito.ArgumentMatchers.eq("EMPLOYEE"),
        org.mockito.ArgumentMatchers.eq(31L),
        org.mockito.ArgumentMatchers.isNull(),
        any());
  }

  @Test
  void rejectsReportEarlierThanLatestMovement() {
    var db = mock(JdbcTemplate.class);
    var controller = new LocationReportController(
        db, mock(PermissionService.class), mock(AuditService.class));
    authenticate("EMPLOYEE", "SELF");
    var latestTime = LocalDateTime.now().minusHours(1);
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 31L, "name", "张明", "station_name", "华东服务站")))
        .thenReturn(List.of(Map.of(
            "to_location", "苏州客户现场",
            "occurred_at", Timestamp.valueOf(latestTime))));

    assertThatThrownBy(() -> controller.submit(new LocationReportController.SubmitRequest(
        "南京客户现场", "补录", latestTime.minusMinutes(1), null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能早于上一条");
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void employeeCannotOpenManagementList() {
    var controller = new LocationReportController(
        mock(JdbcTemplate.class), mock(PermissionService.class), mock(AuditService.class));
    authenticate("EMPLOYEE", "SELF");

    assertThatThrownBy(() -> controller.list(1, 20, null, null, null, null, true, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("只能查看本人");
  }

  @Test
  void mentorListUsesPermissionDataScope() {
    var db = mock(JdbcTemplate.class);
    var permissions = mock(PermissionService.class);
    var controller = new LocationReportController(db, permissions, mock(AuditService.class));
    authenticate("MENTOR", "MENTORED");
    when(permissions.employeeFilter("e"))
        .thenReturn(new PermissionService.ScopeFilter(" and e.mentor_user_id=?", List.of(7L)));
    when(db.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(0L);
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

    controller.list(1, 20, null, null, null, null, true, null, null);

    verify(permissions).require(Permissions.EMPLOYEE_READ);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), any(Object[].class));
    assertThat(sql.getValue()).contains("e.mentor_user_id=?").contains("not exists");
  }

  private void authenticate(String role, String scope) {
    var user = new CurrentUser(
        7L, "user", "用户", role, false, 1,
        Set.of(Permissions.EMPLOYEE_READ), scope);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }
}
