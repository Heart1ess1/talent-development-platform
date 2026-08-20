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

    controller.list(1,20,null,null,null,null,null,null,null,null,null,false);

    var sql=ArgumentCaptor.forClass(String.class);verify(db).queryForObject(sql.capture(),eq(Long.class),any(Object[].class));
    assertThat(sql.getValue()).contains("e.mentor_user_id=?");
    var listSql=ArgumentCaptor.forClass(String.class);verify(db).queryForList(listSql.capture(),any(Object[].class));
    assertThat(listSql.getValue())
      .contains(
        "e.political_status",
        "class_name",
        "e.notes",
        "business_unit_name",
        "skill_mentor_name",
        "station_change_count",
        "last_station_change_at");
  }

  @Test void allRowsRequestDoesNotAddPaginationClause(){
    var db=mock(JdbcTemplate.class);var permissions=mock(PermissionService.class);
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("",List.of()));
    when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(2L);
    when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(
      Map.of("id",2L,"name","李四"),Map.of("id",1L,"name","张三")));
    var controller=new EmployeeDirectoryController(db,permissions,mock(AuditService.class));

    var result=controller.list(1,20,null,null,null,null,null,null,null,null,null,true);

    var listSql=ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(listSql.capture(),any(Object[].class));
    assertThat(listSql.getValue()).doesNotContain("limit ? offset ?");
    assertThat(result.data().records()).hasSize(2);
    assertThat(result.data().total()).isEqualTo(2L);
  }

  @Test void summaryUsesScopeAndReturnsManagementCoverage(){
    var db=mock(JdbcTemplate.class);var permissions=mock(PermissionService.class);
    when(permissions.employeeFilter("e"))
      .thenReturn(new PermissionService.ScopeFilter(" and e.business_unit_id=?",List.of(3L)));
    when(db.queryForMap(anyString(),any(Object[].class))).thenReturn(Map.of(
      "totalEmployees",5L,
      "activeEmployees",4L,
      "inactiveEmployees",1L,
      "stationAssigned",3L,
      "mentorReady",2L));
    var controller=new EmployeeDirectoryController(db,permissions,mock(AuditService.class));

    var result=controller.summary(null,null,null,null,null,null,null,null);

    var sql=ArgumentCaptor.forClass(String.class);
    verify(db).queryForMap(sql.capture(),any(Object[].class));
    assertThat(sql.getValue()).contains(
      "e.business_unit_id=?",
      "stationAssigned",
      "mentorReady");
    assertThat(result.data()).containsEntry("totalEmployees",5L);
  }
}
