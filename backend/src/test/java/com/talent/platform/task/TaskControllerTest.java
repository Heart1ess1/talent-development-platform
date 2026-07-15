package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import com.talent.platform.storage.FileStorageService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskControllerTest {
  private JdbcTemplate db;
  private PermissionService permissions;
  private TaskStatusService taskStatus;
  private TaskController controller;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    permissions = mock(PermissionService.class);
    taskStatus = mock(TaskStatusService.class);
    controller = new TaskController(db, mock(FileStorageService.class), permissions, mock(AuditService.class), taskStatus);
    var user = new CurrentUser(7L, "admin", "Admin", "TRAINING_ADMIN", false, 1,
        Set.of(Permissions.TASK_MANAGE), "ALL");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void refusesToDeleteTaskWithSubmissions() {
    when(db.queryForList(startsWith("select * from challenge_task"), eq(9L)))
        .thenReturn(List.of(Map.of("id", 9L, "title", "任务")));
    when(db.queryForObject(startsWith("select count(*) from task_submission"), eq(Integer.class), eq(9L))).thenReturn(1);

    assertThatThrownBy(() -> controller.deleteTask(9L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("提交记录");
    verify(db, never()).update(eq("delete from task_assignment where task_id=?"), eq(9L));
    verify(db, never()).update(eq("delete from challenge_task where id=?"), eq(9L));
  }

  @Test
  void acceptsJdbcLocalDateTimeWhenSubmittingTask() {
    var employee = new CurrentUser(7L, "employee", "Employee", "EMPLOYEE", false, 1,
        Set.of(), "SELF");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(employee, null, List.of()));
    when(db.queryForObject(startsWith("select employee_id from task_assignment"), eq(Long.class), eq(5L))).thenReturn(12L);
    when(db.queryForMap(startsWith("select a.status,t.deadline"), eq(5L)))
        .thenReturn(Map.of("status", "NOT_SUBMITTED", "deadline", LocalDateTime.now().plusHours(1)));
    when(db.queryForObject(startsWith("select coalesce(max(submission_version)"), eq(Integer.class), eq(5L))).thenReturn(1);
    when(db.queryForObject(eq("select last_insert_id()"), eq(Long.class))).thenReturn(99L);

    controller.submit(5L, "成果说明", null);

    verify(db).update("insert into task_submission(assignment_id,submission_version,content) values(?,?,?)", 5L, 1, "成果说明");
    verify(db).update("update task_assignment set status='PENDING_REVIEW',version=version+1 where id=?", 5L);
  }

  @Test
  void replacesPendingSubmissionWhenEmployeeResubmits() {
    var employee = new CurrentUser(7L, "employee", "Employee", "EMPLOYEE", false, 1, Set.of(), "SELF");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(employee, null, List.of()));
    when(db.queryForObject(startsWith("select employee_id from task_assignment"), eq(Long.class), eq(5L))).thenReturn(12L);
    when(db.queryForMap(startsWith("select a.status,t.deadline"), eq(5L)))
        .thenReturn(Map.of("status", "PENDING_REVIEW", "deadline", LocalDateTime.now().plusHours(1)));
    when(db.queryForObject(startsWith("select coalesce(max(submission_version)"), eq(Integer.class), eq(5L))).thenReturn(2);
    when(db.queryForObject(eq("select last_insert_id()"), eq(Long.class))).thenReturn(100L);

    controller.submit(5L, "修订后的成果", null);

    verify(db).update("update task_submission set status='SUPERSEDED' where assignment_id=? and status='PENDING_REVIEW'", 5L);
    verify(db).update("insert into task_submission(assignment_id,submission_version,content) values(?,?,?)", 5L, 2, "修订后的成果");
  }

  @Test
  void allowsMentorToReadMentoredSubmissionFiles() {
    var mentor = new CurrentUser(7L, "mentor", "Mentor", "MENTOR", false, 1, Set.of(), "MENTORED");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(mentor, null, List.of()));
    var submission = new HashMap<String, Object>();
    submission.put("id", 99L);
    var file = Map.<String, Object>of("id", 15L, "original_name", "report.docx", "size", 1024L);
    when(db.queryForObject(startsWith("select employee_id from task_assignment"), eq(Long.class), eq(5L))).thenReturn(12L);
    when(db.queryForList(startsWith("select s.*,u.display_name"), eq(5L))).thenReturn(List.of(submission));
    when(db.queryForList(startsWith("select id,original_name,size,content_type"), eq(99L))).thenReturn(List.of(file));

    var result = controller.submissions(5L);

    verify(permissions).requireEmployee(12L);
    assertThat(result.data()).singleElement().satisfies(row -> assertThat(row.get("files")).isEqualTo(List.of(file)));
  }

  @Test
  void limitsMentorTaskProgressToMentoredEmployees() {
    var mentor = new CurrentUser(7L, "mentor", "Mentor", "MENTOR", false, 1, Set.of(), "MENTORED");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(mentor, null, List.of()));
    when(permissions.employeeFilter("e"))
        .thenReturn(new PermissionService.ScopeFilter(" and e.mentor_user_id=?", List.of(7L)));
    when(db.queryForObject(startsWith("select count(*) from task_assignment"), eq(Integer.class), aryEq(new Object[]{5L, 7L})))
        .thenReturn(1);
    when(db.queryForList(contains("where a.task_id=? and e.mentor_user_id=?"), aryEq(new Object[]{5L, 7L})))
        .thenReturn(List.of());

    var result = controller.taskProgress(5L);

    assertThat(result.data()).isEmpty();
  }

  @Test
  void returnsEveryAssignedEmployeeAfterRefreshingOverdueAssignments() {
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("", List.of()));
    when(db.queryForList(anyString(), aryEq(new Object[]{5L}))).thenReturn(List.of());

    controller.taskProgress(5L);

    var sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), aryEq(new Object[]{5L}));
    verify(taskStatus).refreshOverdueAssignments();
    assertThat(sql.getValue())
        .contains("from task_assignment a join challenge_task t")
        .contains("left join task_submission")
        .contains("select a.id,a.status,a.assigned_at,a.final_score");
  }

  @Test
  void returnsPersistedOverdueStatusInEmployeeAssignments() {
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("", List.of()));
    when(db.queryForList(anyString(), aryEq(new Object[]{}))).thenReturn(List.of());

    controller.assignments(null);

    var sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), aryEq(new Object[]{}));
    verify(taskStatus).refreshOverdueAssignments();
    assertThat(sql.getValue())
        .contains("a.status,a.final_score");
  }

  @Test
  void restrictsReviewToTheSubmittedEmployeesScope() {
    var reviewer = new CurrentUser(7L, "reviewer", "Reviewer", "CUSTOM", false, 1,
        Set.of(Permissions.TASK_REVIEW), "MENTORED");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(reviewer, null, List.of()));
    when(db.queryForMap(startsWith("select s.assignment_id,s.status,a.employee_id"), eq(99L)))
        .thenReturn(Map.of("assignment_id", 5L, "employee_id", 12L, "status", "PENDING_REVIEW"));

    controller.review(99L, new TaskController.ReviewRequest("APPROVE", null, 80));

    verify(permissions).requireEmployee(12L);
    verify(db).update("update task_assignment set status=?,final_score=?,version=version+1 where id=?", "APPROVED", 80, 5L);
  }

  @Test
  void rejectsMissingOrInvalidReviewDecision() {
    when(db.queryForMap(startsWith("select s.assignment_id,s.status,a.employee_id"), eq(99L)))
        .thenReturn(Map.of("assignment_id", 5L, "employee_id", 12L, "status", "PENDING_REVIEW"));

    assertThatThrownBy(() -> controller.review(99L, new TaskController.ReviewRequest(null, null, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("审核结论");
    verify(db, never()).update(startsWith("update task_submission"), any(), any(), any(), any(), any());
  }
}
