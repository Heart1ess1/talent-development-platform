package com.talent.platform.task;

import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskReviewerScopeServiceTest {
  private JdbcTemplate db;
  private TaskReviewerScopeService service;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    service = new TaskReviewerScopeService(db, mock(PermissionService.class), mock(AuditService.class));
    when(db.queryForObject(contains("enabled=true and role<>'EMPLOYEE'"), eq(Integer.class), any(Object[].class)))
        .thenAnswer(invocation -> invocation.getArguments().length - 2);
    when(db.queryForList(startsWith("select name from talent_batch"), eq(String.class), anyLong()))
        .thenReturn(List.of("2026届"));
    when(db.queryForList(startsWith("select name from business_unit"), eq(String.class), eq(3L)))
        .thenReturn(List.of("城轨"));
    when(db.queryForList(startsWith("select name from business_unit"), eq(String.class), eq(4L)))
        .thenReturn(List.of("机车"));
    when(db.queryForList(startsWith("select label from dictionary_item"), eq(String.class), anyLong(), eq("CLASS")))
        .thenAnswer(invocation -> List.of(invocation.getArgument(2).equals(1L) ? "一班" : "二班"));
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked") RowMapper<Object> mapper = invocation.getArgument(1);
      return List.of(
          mapper.mapRow(resultSet(Map.of(
              "employee_id", 11L, "employee_name", "甲", "employee_no", "001",
              "batch_id", 2026L, "batch_name", "2026届", "business_unit_id", 3L,
              "business_unit_name", "城轨", "class_id", 1L, "class_name", "一班")), 0),
          mapper.mapRow(resultSet(Map.of(
              "employee_id", 12L, "employee_name", "乙", "employee_no", "002",
              "batch_id", 2026L, "batch_name", "2026届", "business_unit_id", 4L,
              "business_unit_name", "机车", "class_id", 2L, "class_name", "二班")), 1));
    }).when(db).query(contains("from employee e"), any(RowMapper.class), any(Object[].class));
  }

  @Test
  void combinedConditionsUseIntersectionAndCanFullyCoverEmployees() {
    var preview = service.previewEmployees(List.of(11L, 12L), List.of(
        new TaskReviewerScopeService.ScopeRequest(null, 2026L, 3L, null, List.of(7L)),
        new TaskReviewerScopeService.ScopeRequest(null, 2026L, 4L, 2L, List.of(8L))));

    assertThat(preview.valid()).isTrue();
    assertThat(preview.coveredEmployees()).isEqualTo(2);
    assertThat(preview.scopes()).extracting(row -> row.get("coveredEmployees")).containsExactly(1L, 1L);
  }

  @Test
  void reportsOverlappingEmployeesWithTheirIdentity() {
    var preview = service.previewEmployees(List.of(11L, 12L), List.of(
        new TaskReviewerScopeService.ScopeRequest(null, 2026L, null, null, List.of(7L)),
        new TaskReviewerScopeService.ScopeRequest(null, null, 3L, null, List.of(8L))));

    assertThat(preview.valid()).isFalse();
    assertThat(preview.overlappingEmployees()).isEqualTo(1);
    assertThat(preview.overlapping()).hasSize(1);
    assertThat(preview.overlapping().get(0)).containsEntry("employeeName", "甲").containsEntry("employeeNo", "001");
  }

  @Test
  void reportsEmployeesNotCoveredByAnyScope() {
    var preview = service.previewEmployees(List.of(11L, 12L), List.of(
        new TaskReviewerScopeService.ScopeRequest(null, null, 3L, null, List.of(7L))));

    assertThat(preview.valid()).isFalse();
    assertThat(preview.uncoveredEmployees()).isEqualTo(1);
    assertThat(preview.uncovered()).hasSize(1);
    assertThat(preview.uncovered().get(0)).containsEntry("employeeName", "乙").containsEntry("employeeNo", "002");
  }

  @Test
  void permitsLeavingTheWholeTaskTemporarilyUnconfigured() {
    var preview = service.previewEmployees(List.of(11L, 12L), List.of());

    assertThat(preview.valid()).isTrue();
    assertThat(preview.targetEmployees()).isEqualTo(2);
    assertThat(preview.coveredEmployees()).isZero();
  }

  private ResultSet resultSet(Map<String, Object> values) throws Exception {
    ResultSet result = mock(ResultSet.class);
    AtomicBoolean wasNull = new AtomicBoolean(false);
    when(result.getLong(anyString())).thenAnswer(invocation -> {
      Object value = values.get(invocation.getArgument(0));
      wasNull.set(value == null);
      return value == null ? 0L : ((Number) value).longValue();
    });
    when(result.getString(anyString())).thenAnswer(invocation -> {
      Object value = values.get(invocation.getArgument(0));
      wasNull.set(value == null);
      return value == null ? null : String.valueOf(value);
    });
    when(result.wasNull()).thenAnswer(invocation -> wasNull.get());
    return result;
  }
}
