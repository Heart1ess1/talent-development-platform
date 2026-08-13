package com.talent.platform.exam;

import java.time.LocalDateTime;
import java.util.Set;

final class ExamProctorPolicy {
  static final int ALLOWED_VIOLATIONS = 3;
  private static final Set<String> VIOLATION_TYPES = Set.of("BLUR", "HIDDEN", "EXIT_FULLSCREEN");

  private ExamProctorPolicy() {}

  static boolean isViolation(String type) {
    return VIOLATION_TYPES.contains(type);
  }

  static boolean shouldAutoSubmit(int violationCount) {
    return violationCount > ALLOWED_VIOLATIONS;
  }

  static boolean deadlineReached(LocalDateTime deadline, LocalDateTime now) {
    return !deadline.isAfter(now);
  }

  static boolean shouldSettleTimeout(String status, LocalDateTime deadline, LocalDateTime now) {
    return "IN_PROGRESS".equals(status) && deadlineReached(deadline, now);
  }
}
