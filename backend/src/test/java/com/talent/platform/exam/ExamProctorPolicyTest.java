package com.talent.platform.exam;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ExamProctorPolicyTest {
  @Test void onlyActualFocusAndFullscreenEventsCountAsViolations() {
    assertTrue(ExamProctorPolicy.isViolation("BLUR"));
    assertTrue(ExamProctorPolicy.isViolation("HIDDEN"));
    assertTrue(ExamProctorPolicy.isViolation("EXIT_FULLSCREEN"));
    assertFalse(ExamProctorPolicy.isViolation("RECONNECT"));
  }

  @Test void fourthViolationTriggersAutomaticSubmission() {
    assertFalse(ExamProctorPolicy.shouldAutoSubmit(3));
    assertTrue(ExamProctorPolicy.shouldAutoSubmit(4));
  }

  @Test void timeoutCanOnlyBeSettledAtOrAfterTheServerDeadline() {
    var deadline = LocalDateTime.of(2026, 8, 13, 15, 0);
    assertFalse(ExamProctorPolicy.deadlineReached(deadline, deadline.minusNanos(1)));
    assertTrue(ExamProctorPolicy.deadlineReached(deadline, deadline));
    assertTrue(ExamProctorPolicy.deadlineReached(deadline, deadline.plusSeconds(1)));
  }

  @Test void timeoutSettlementIsIdempotentForCompletedAttempts() {
    var deadline = LocalDateTime.of(2026, 8, 13, 15, 0);
    assertTrue(ExamProctorPolicy.shouldSettleTimeout("IN_PROGRESS", deadline, deadline));
    assertFalse(ExamProctorPolicy.shouldSettleTimeout("GRADED", deadline, deadline.plusSeconds(1)));
    assertFalse(ExamProctorPolicy.shouldSettleTimeout("PENDING_REVIEW", deadline, deadline.plusSeconds(1)));
  }
}
