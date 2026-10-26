package com.talent.platform.course;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionAssignmentServiceTest {
  private JdbcTemplate db;
  private PermissionService permissions;
  private AuditService audit;
  private SessionAssignmentService service;

  @BeforeEach void setup() {
    db=mock(JdbcTemplate.class);permissions=mock(PermissionService.class);audit=mock(AuditService.class);
    service=new SessionAssignmentService(db,permissions,audit);
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("",List.of()));
    when(db.queryForList("select id from course_session where id=? for update",9L)).thenReturn(List.of(Map.of("id",9L)));
    when(db.queryForList("select id from course_session where id=?",9L)).thenReturn(List.of(Map.of("id",9L)));
    var user=new CurrentUser(7L,"training","培训管理员","TRAINING_ADMIN",false,1,Set.of(Permissions.COURSE_MANAGE),"ALL");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  private Map<String,Object> person(long id,String attendance) {
    var row=new HashMap<String,Object>();row.put("employee_id",id);row.put("employee_no","E"+id);
    row.put("employee_name","员工"+id);row.put("assigned_at","2026-10-26 09:00:00");row.put("attendance_status",attendance);
    return row;
  }
  private void roster(List<Map<String,Object>> rows) {
    when(db.queryForList(startsWith("select e.id employee_id"),any(Object[].class))).thenReturn(rows);
  }
  private SessionAssignmentService.Filters filters() {
    return new SessionAssignmentService.Filters("张%_!",List.of(1L,2L),List.of(3L),List.of(4L),List.of(5L),List.of(6L),List.of(7L),List.of(8L),List.of("本科","硕士"),"交大","车辆");
  }

  @Test void allFiltersAreParameterizedAndGroupedUnderTheSameScope() {
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter(" and e.station_id in (?)",List.of(88L)));
    var query=service.candidateQuery(9L,filters());
    assertThat(query.sql()).contains("e.status='ACTIVE'","not exists","e.batch_id in (?,?)","e.education in (?,?)","e.station_id in (?)");
    assertThat(query.sql()).doesNotContain("张","交大","车辆");
    assertThat(query.args()).containsExactly(9L,88L,"%张!%!_!!%","%张!%!_!!%",1L,2L,3L,4L,5L,6L,7L,8L,"本科","硕士","%交大%","%车辆%");
  }
  @Test void paginationCanReachEmployeesAfterTheFirst100() {
    when(db.queryForObject(startsWith("select count(*) from employee e"),eq(Long.class),any(Object[].class))).thenReturn(235L);
    roster(List.of(person(101,null)));
    var result=service.candidates(9L,filters(),6,20);
    assertThat(result.total()).isEqualTo(235);assertThat(result.records().get(0).get("employee_id")).isEqualTo(101L);
    var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(db).queryForList(contains("limit ? offset ?"),args.capture());
    assertThat(args.getValue()).endsWith(20,100L);
  }
  @Test void resolveUsesTheSameFiltersWithoutALimitOrSensitiveFields() {
    roster(List.of(person(101,null)));
    service.resolve(9L,filters());
    var sql=org.mockito.ArgumentCaptor.forClass(String.class);
    verify(db,times(2)).queryForList(sql.capture(),any(Object[].class));
    assertThat(sql.getAllValues().get(1)).contains(service.candidateQuery(9L,filters()).sql()).doesNotContain("limit","id_card","phone","email");
  }
  @Test void fingerprintIgnoresDisplayOrderButDetectsSignInAndMembershipChanges() {
    var one=person(1,null);var two=person(2,null);
    assertThat(SessionAssignmentService.fingerprint(List.of(one,two))).isEqualTo(SessionAssignmentService.fingerprint(List.of(two,one)));
    assertThat(SessionAssignmentService.fingerprint(List.of(one))).isNotEqualTo(SessionAssignmentService.fingerprint(List.of(one,two)));
    assertThat(SessionAssignmentService.fingerprint(List.of(one,two))).isNotEqualTo(SessionAssignmentService.fingerprint(List.of(one,person(2,"MANUAL"))));
  }
  @Test void staleRosterIsRejectedBeforeAnyWrite() {
    roster(List.of(person(1,null)));
    assertThatThrownBy(()->service.change(9L,new SessionAssignmentService.Changes(List.of(2L),List.of(1L),"stale")))
        .isInstanceOf(BusinessException.class).hasMessageContaining("已变化");
    verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(audit);
  }
  @Test void inactiveAdditionRejectsTheEntireBatchBeforeRemovingAnyone() {
    var rows=List.of(person(1,null));roster(rows);
    when(db.queryForList(startsWith("select id,status from employee"),any(Object[].class)))
        .thenReturn(List.of(Map.of("id",1L,"status","ACTIVE"),Map.of("id",2L,"status","INACTIVE")));
    assertThatThrownBy(()->service.change(9L,new SessionAssignmentService.Changes(List.of(2L),List.of(1L),SessionAssignmentService.fingerprint(rows))))
        .isInstanceOf(BusinessException.class).hasMessageContaining("停用");
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void checkedInRemovalRejectsTheEntireBatchBeforeAnyAddition() {
    var rows=List.of(person(1,"CHECKED_IN"));roster(rows);
    when(db.queryForList(startsWith("select id,status from employee"),any(Object[].class)))
        .thenReturn(List.of(Map.of("id",1L,"status","ACTIVE"),Map.of("id",2L,"status","ACTIVE")));
    assertThatThrownBy(()->service.change(9L,new SessionAssignmentService.Changes(List.of(2L),List.of(1L),SessionAssignmentService.fingerprint(rows))))
        .isInstanceOf(BusinessException.class).hasMessageContaining("签到记录");
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void missingOrOutOfScopeEmployeesNeverReachWrites() {
    roster(List.of());
    when(db.queryForList(startsWith("select id,status from employee"),any(Object[].class))).thenReturn(List.of());
    assertThatThrownBy(()->service.change(9L,new SessionAssignmentService.Changes(List.of(200L),List.of(),SessionAssignmentService.fingerprint(List.of()))))
        .hasMessageContaining("人员不存在");
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void batchAppliesOnlyTheDeltaDeduplicatesAndAuditsCounts() {
    var before=List.of(person(1,null),person(8,"MANUAL"));var after=List.of(person(2,null),person(8,"MANUAL"));
    when(db.queryForList(startsWith("select e.id employee_id"),any(Object[].class))).thenReturn(before,after);
    when(db.queryForList(startsWith("select id,status from employee"),any(Object[].class)))
        .thenReturn(List.of(Map.of("id",1L,"status","INACTIVE"),Map.of("id",2L,"status","ACTIVE")));
    when(db.update(anyString(),any(Object[].class))).thenReturn(1);
    var result=service.change(9L,new SessionAssignmentService.Changes(List.of(2L,2L),List.of(1L),SessionAssignmentService.fingerprint(before)));
    assertThat(result.added()).isEqualTo(1);assertThat(result.removed()).isEqualTo(1);
    assertThat(result.state().attendanceCount()).isEqualTo(1);assertThat(result.state().enrolledCount()).isEqualTo(2);
    verify(db).update("delete from course_enrollment where session_id=? and employee_id=?",9L,1L);
    verify(db).update("insert into course_enrollment(session_id,employee_id,assigned_by) values(?,?,?)",9L,2L,7L);
    verify(audit).log(eq("UPDATE_SESSION_ENROLLMENTS"),eq("SESSION"),eq(9L),any(),any());
  }
  @Test void overlapAndDeletedSessionAreRejected() {
    roster(List.of());
    assertThatThrownBy(()->service.change(9L,new SessionAssignmentService.Changes(List.of(1L),List.of(1L),SessionAssignmentService.fingerprint(List.of())))).hasMessageContaining("同时");
    assertThatThrownBy(()->service.change(99L,new SessionAssignmentService.Changes(List.of(),List.of(),"hash"))).hasMessageContaining("已被删除");
    verify(db,never()).update(anyString(),any(Object[].class));
  }
}
