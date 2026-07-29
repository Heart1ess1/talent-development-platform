package com.talent.platform.exam;

import org.junit.jupiter.api.Test;

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
}
