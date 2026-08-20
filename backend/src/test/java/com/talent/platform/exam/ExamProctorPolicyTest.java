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

  @Test void mobileCompatibleModeOnlyCountsLeavingThePage() {
    assertTrue(ExamProctorPolicy.isViolation(ExamProctorPolicy.MOBILE_COMPATIBLE, "HIDDEN"));
    assertFalse(ExamProctorPolicy.isViolation(ExamProctorPolicy.MOBILE_COMPATIBLE, "BLUR"));
    assertFalse(ExamProctorPolicy.isViolation(ExamProctorPolicy.MOBILE_COMPATIBLE, "EXIT_FULLSCREEN"));
  }

  @Test void anAttemptThatDowngradesToCompatibleModeStaysCompatibleWhenResumed() {
    assertEquals(ExamProctorPolicy.MOBILE_COMPATIBLE,
        ExamProctorPolicy.resolveAttemptMode(ExamProctorPolicy.FULLSCREEN_STRICT, ExamProctorPolicy.MOBILE_COMPATIBLE));
    assertEquals(ExamProctorPolicy.MOBILE_COMPATIBLE,
        ExamProctorPolicy.resolveAttemptMode(ExamProctorPolicy.MOBILE_COMPATIBLE, ExamProctorPolicy.FULLSCREEN_STRICT));
    assertEquals(ExamProctorPolicy.FULLSCREEN_STRICT,
        ExamProctorPolicy.resolveAttemptMode(ExamProctorPolicy.FULLSCREEN_STRICT, ExamProctorPolicy.FULLSCREEN_STRICT));
  }

  @Test void configuredViolationLimitTriggersAutomaticSubmission() {
    assertFalse(ExamProctorPolicy.shouldAutoSubmit(2, 3));
    assertTrue(ExamProctorPolicy.shouldAutoSubmit(3, 3));
    assertTrue(ExamProctorPolicy.shouldAutoSubmit(4, 3));
  }

  @Test void violationGraceUsesServerTimeAndExpiresAtTheDeadline() {
    var now = LocalDateTime.of(2026, 8, 20, 15, 0);
    var deadline = ExamProctorPolicy.violationDeadline(now, 15);
    assertEquals(now.plusSeconds(15), deadline);
    assertFalse(ExamProctorPolicy.violationGraceExpired(deadline, deadline.minusNanos(1)));
    assertTrue(ExamProctorPolicy.violationGraceExpired(deadline, deadline));
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
