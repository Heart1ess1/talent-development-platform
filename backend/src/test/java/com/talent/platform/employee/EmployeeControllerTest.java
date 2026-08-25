package com.talent.platform.employee;

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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeControllerTest {
  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void directStationEditCreatesEffectiveHistory() {
    var db = mock(JdbcTemplate.class);
    var permissions = mock(PermissionService.class);
    when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
        "user_id", 7L,
        "station_id", 10L,
        "status", "ACTIVE"));
    var currentUser = new CurrentUser(
        5L, "admin", "管理员", "ADMIN", false, 0, Set.of(), "ALL");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(currentUser, null, List.of()));
    var controller = new EmployeeController(
        db,
        mock(PasswordEncoder.class),
        permissions,
        mock(AuditService.class));
    var request = new EmployeeController.EmployeeRequest(
        "employee",
        "新员工",
        "男",
        1L,
        5L,
        6L,
        2L,
        20L,
        3L,
        4L,
        "学校",
        "专业",
        "本科",
        null,
        "籍贯",
        "公司住址",
        "13800000000",
        "employee@example.com",
        null,
        "群众",
        "阅读",
        "沟通",
        "110101200001010000",
        "这是一条较长的人员备注",
        "ACTIVE");

    controller.update(1L, request);

    verify(permissions).require(Permissions.EMPLOYEE_UPDATE);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(db, atLeastOnce()).update(sql.capture(), any(Object[].class));
    assertThat(sql.getAllValues()).anySatisfy(value ->
        assertThat(value)
            .contains("insert into station_change_request")
            .contains("reviewed_at"));
  }
}
