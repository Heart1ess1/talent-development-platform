package com.talent.platform.exam;

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
}
