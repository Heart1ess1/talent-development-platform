package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskScoringServiceTest {
  private JdbcTemplate db;
  private PermissionService permissions;
  private TaskReviewerScopeService reviewerScopes;
  private TaskScoringService service;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    permissions = mock(PermissionService.class);
    reviewerScopes = mock(TaskReviewerScopeService.class);
    service = new TaskScoringService(db, permissions, mock(AuditService.class), reviewerScopes);
    authenticate("MENTOR", Set.of(Permissions.TASK_SCORE));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void rejectsScoringByAnUnassignedAdministrator() {
    authenticate("ADMIN", Set.of(Permissions.TASK_SCORE, Permissions.TASK_MANAGE));
    when(db.queryForList(contains("where s.id=? for update"), eq(99L)))
        .thenReturn(List.of(Map.of("assignment_id", 5L, "status", "PENDING_REVIEW", "task_id", 3L)));
    when(reviewerScopes.isReviewerForAssignment(5L, 7L)).thenReturn(false);

    assertThatThrownBy(() -> service.submitReview(99L, "APPROVE", null, 90))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("自己范围内");
  }

  @Test
  void averagesAllApprovalsToOneDecimal() {
    prepareAssignedPendingSubmission();
    when(db.update(startsWith("update task_submission_review"), eq("APPROVE"), eq(90), isNull(), eq(99L), eq(7L)))
        .thenReturn(1);
    when(db.queryForObject(startsWith("select count(*) from task_submission_review where"), eq(Integer.class), eq(99L)))
        .thenReturn(0);
    when(db.queryForObject(contains("round(avg(score),1)"), eq(BigDecimal.class), eq(99L)))
        .thenReturn(new BigDecimal("85.5"));

    service.submitReview(99L, "APPROVE", null, 90);

    verify(db).update("update task_assignment set status='APPROVED',final_score=?,version=version+1 where id=?",
        new BigDecimal("85.5"), 5L);
  }

  @Test
  void oneReturnImmediatelyEndsTheRound() {
    prepareAssignedPendingSubmission();
    when(db.update(startsWith("update task_submission_review"), eq("RETURN"), isNull(), eq("请补材料"), eq(99L), eq(7L)))
        .thenReturn(1);

    service.submitReview(99L, "RETURN", "请补材料", null);

    verify(db).update("update task_submission_review set status='VOIDED' where submission_id=? and status='PENDING'", 99L);
    verify(db).update("update task_assignment set status='RETURNED',final_score=null,version=version+1 where id=?", 5L);
  }

  @Test
  void hidesPeerScoresUntilTheRoundFinishes() {
    authenticate("ADMIN", Set.of(Permissions.TASK_SCORE, Permissions.TASK_MANAGE));
    var submission = new HashMap<String, Object>();
    submission.put("id", 99L);
    submission.put("task_id", 3L);
    submission.put("employee_id", 12L);
    submission.put("status", "PENDING_REVIEW");
    when(db.queryForList(startsWith("select s.id,s.assignment_id"), eq(99L))).thenReturn(List.of(submission));
    when(db.queryForList(startsWith("select a.id assignment_id"), eq(99L)))
        .thenReturn(List.of(Map.of("assignment_id", 5L, "task_id", 3L, "employee_id", 12L)));
    when(db.queryForList(startsWith("select id,original_name"), eq(99L))).thenReturn(List.of());
    var peer = new HashMap<String, Object>();
    peer.put("reviewer_user_id", 8L);
    peer.put("reviewer_name", "Peer");
    peer.put("role", "MENTOR");
    peer.put("status", "SUBMITTED");
    peer.put("decision", "APPROVE");
    peer.put("score", 88);
    peer.put("comment", "很好");
    peer.put("submitted_at", LocalDateTime.now());
    when(db.queryForList(startsWith("select r.reviewer_user_id"), eq(99L))).thenReturn(List.of(peer));
    when(db.queryForObject(contains("r.submission_id=?"), eq(Integer.class), eq(99L), eq(7L))).thenReturn(0);

    var result = service.submissionDetail(99L);
    @SuppressWarnings("unchecked") var reviews = (List<Map<String, Object>>) result.get("reviews");

    assertThat(reviews).singleElement().satisfies(review -> {
      assertThat(review.get("score")).isNull();
      assertThat(review.get("comment")).isNull();
      assertThat(review.get("decision")).isNull();
    });
  }

  @Test
  void refusesReviewerChangesAfterScoringStarts() {
    authenticate("ADMIN", Set.of(Permissions.TASK_SCORE, Permissions.TASK_MANAGE));
    doThrow(new BusinessException(409,"评分范围已开始评分，不能更换评分人"))
        .when(reviewerScopes).setUniformReviewers(3L,List.of(7L));

    assertThatThrownBy(() -> service.setReviewers(3L, List.of(7L)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能更换评分人");
  }

  @Test
  void rejectsResetAfterMonthlyEvaluationIsPublished() {
    authenticate("ADMIN", Set.of(Permissions.TASK_SCORE, Permissions.TASK_MANAGE));
    when(db.queryForList(contains("where s.id=? for update"), eq(99L))).thenReturn(List.of(Map.of(
        "assignment_id", 5L,
        "employee_id", 12L,
        "deadline", LocalDateTime.of(2026, 8, 31, 23, 59),
        "status", "APPROVED")));
    when(db.queryForObject(contains("summary_type='MONTH'"), eq(Integer.class), eq(12L), eq("2026-08")))
        .thenReturn(1);

    assertThatThrownBy(() -> service.resetReview(99L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("评价已发布");
  }

  private void prepareAssignedPendingSubmission() {
    when(db.queryForList(contains("where s.id=? for update"), eq(99L)))
        .thenReturn(List.of(Map.of("assignment_id", 5L, "status", "PENDING_REVIEW", "task_id", 3L)));
    when(reviewerScopes.isReviewerForAssignment(5L, 7L)).thenReturn(true);
  }

  private void authenticate(String role, Set<String> granted) {
    var user = new CurrentUser(7L, "reviewer", "Reviewer", role, false, 1, granted, "ALL");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }
}
