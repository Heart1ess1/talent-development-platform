package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import com.talent.platform.storage.FileStorageService;
import com.talent.platform.storage.UploadTicketService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskControllerTest {
  private JdbcTemplate db;
  private FileStorageService storage;
  private PermissionService permissions;
  private TaskStatusService taskStatus;
  private TaskScoringService scoring;
  private TaskReviewerScopeService reviewerScopes;
  private TaskController controller;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    storage = mock(FileStorageService.class);
    permissions = mock(PermissionService.class);
    taskStatus = mock(TaskStatusService.class);
    scoring = mock(TaskScoringService.class);
    reviewerScopes = mock(TaskReviewerScopeService.class);
    controller = new TaskController(
        db, storage, permissions, mock(AuditService.class), taskStatus,
        mock(TaskAttachmentService.class), mock(UploadTicketService.class), scoring, reviewerScopes);
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
        .contains("select a.id,a.status,a.assigned_at,a.final_score")
        .contains("s.id submission_id")
        .contains("file_count");
  }

  @Test
  void exportsTaskProgressAsSpreadsheet() throws Exception {
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("", List.of()));
    when(db.queryForList(contains("from task_assignment a join challenge_task t"), aryEq(new Object[]{5L})))
        .thenReturn(List.of(new HashMap<>(Map.of(
            "employee_name", "新员工1",
            "employee_no", "employee",
            "status", "APPROVED",
            "final_score", 90,
            "file_count", 2))));
    when(db.queryForList(startsWith("select * from challenge_task"), eq(5L)))
        .thenReturn(List.of(Map.of("id", 5L, "title", "月报提交")));
    var response = new MockHttpServletResponse();

    controller.exportTaskProgress(5L, response);

    assertThat(response.getContentType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(response.getHeader("Content-Disposition")).contains(".xlsx");
    assertThat(response.getContentAsByteArray()).isNotEmpty();
  }

  @Test
  void archivesAllSubmissionNotesUsingEmployeeAndVersionFolders() throws Exception {
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("", List.of()));
    when(db.queryForList(contains("from task_submission s"), aryEq(new Object[]{5L})))
        .thenReturn(List.of(Map.of(
            "id", 99L,
            "submission_version", 2,
            "content", "提交说明",
            "employee_name", "新员工1",
            "employee_no", "employee")));
    when(db.queryForList(startsWith("select id,original_name,storage_key"), eq(99L)))
        .thenReturn(List.of());
    when(db.queryForList(startsWith("select * from challenge_task"), eq(5L)))
        .thenReturn(List.of(Map.of("id", 5L, "title", "月报提交")));
    var response = new MockHttpServletResponse();

    controller.exportTaskSubmissionArchive(5L, response);

    assertThat(response.getContentType()).isEqualTo("application/zip");
    assertThat(response.getHeader("Content-Disposition")).contains(".zip");
    try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
      var entry = zip.getNextEntry();
      assertThat(entry).isNotNull();
      assertThat(entry.getName()).isEqualTo("新员工1（employee）/第2版/提交说明.txt");
      assertThat(new String(zip.readAllBytes())).isEqualTo("提交说明");
    }
  }

  @Test
  void marksMissingPhysicalFilesInsideSubmissionArchive() throws Exception {
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("", List.of()));
    when(db.queryForList(contains("from task_submission s"), aryEq(new Object[]{5L})))
        .thenReturn(List.of(Map.of(
            "id", 99L,
            "submission_version", 1,
            "employee_name", "新员工1",
            "employee_no", "employee")));
    when(db.queryForList(startsWith("select id,original_name,storage_key"), eq(99L)))
        .thenReturn(List.of(Map.of(
            "id", 12L,
            "original_name", "月报.docx",
            "storage_key", "missing.docx")));
    when(storage.load("missing.docx")).thenThrow(new BusinessException(404, "文件不存在"));
    when(db.queryForList(startsWith("select * from challenge_task"), eq(5L)))
        .thenReturn(List.of(Map.of("id", 5L, "title", "月报提交")));
    var response = new MockHttpServletResponse();

    controller.exportTaskSubmissionArchive(5L, response);

    try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
      var entry = zip.getNextEntry();
      assertThat(entry.getName()).isEqualTo("新员工1（employee）/第1版/12-月报.docx.缺失说明.txt");
      assertThat(new String(zip.readAllBytes())).contains("物理文件已不存在");
    }
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
    controller.review(99L, new TaskController.ReviewRequest("APPROVE", null, 80));

    verify(scoring).submitReview(99L, "APPROVE", null, 80);
  }

  @Test
  void rejectsMissingOrInvalidReviewDecision() {
    controller.review(99L, new TaskController.ReviewRequest(null, null, null));
    verify(scoring).submitReview(99L, null, null, null);
  }

  @Test
  void previewsPlanDispatchBeforeCreatingTasks() {
    when(db.queryForList(startsWith("select id,name,enabled"), eq(4L)))
        .thenReturn(List.of(Map.of("id", 4L, "name", "基础培养", "enabled", true)));
    when(db.queryForList(startsWith("select id,title,description,requirements"), any(Object[].class)))
        .thenReturn(List.of(
            Map.of("id", 11L, "title", "安全规范", "description", "学习", "requirements", "通过"),
            Map.of("id", 12L, "title", "工具使用", "description", "实操", "requirements", "提交")));
    when(db.queryForList(startsWith("select e.id from employee"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(21L, 22L, 23L));
    when(db.queryForObject(startsWith("select count(*) from challenge_task"), eq(Integer.class), any(Object[].class)))
        .thenReturn(1);
    var request = new TaskController.PlanDispatchRequest(
        4L,
        List.of(11L, 12L),
        null,
        "OFFSET",
        LocalDate.of(2026, 7, 28),
        7,
        null,
        2L,
        null,
        null,
        3L,
        null,
        List.of(),
        null);

    var result = controller.previewPlanDispatch(request).data();

    verify(permissions).require(Permissions.TASK_MANAGE);
    assertThat(result.targetEmployees()).isEqualTo(3);
    assertThat(result.selectedTasks()).isEqualTo(2);
    assertThat(result.reusedTasks()).isEqualTo(1);
    assertThat(result.deadline()).isEqualTo(LocalDateTime.of(2026, 8, 4, 23, 59, 59));
    verify(db).queryForList(
        argThat(sql -> sql.contains("e.batch_id=?")
            && sql.contains("e.business_unit_id=?")
            && !sql.contains(" or ")),
        eq(Long.class),
        aryEq(new Object[]{2L, 3L}));
    assertThat(result.taskTitles()).containsExactly("安全规范", "工具使用");
    assertThat(result.reviewerIds()).isEmpty();
    verify(db, never()).update(
        startsWith("insert into challenge_task"),
        any(), any(), any(), any(), any(), any(), any(), any());
  }
}
