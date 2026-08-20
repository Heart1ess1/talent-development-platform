package com.talent.platform.exam;

import java.time.LocalDateTime;
import java.util.Set;

final class ExamProctorPolicy {
  static final int DEFAULT_VIOLATION_LIMIT = 4;
  static final int DEFAULT_VIOLATION_GRACE_SECONDS = 15;
  static final String FULLSCREEN_STRICT = "FULLSCREEN_STRICT";
  static final String MOBILE_COMPATIBLE = "MOBILE_COMPATIBLE";
  private static final Set<String> STRICT_VIOLATION_TYPES = Set.of("BLUR", "HIDDEN", "EXIT_FULLSCREEN");

  private ExamProctorPolicy() {}

  static boolean isViolation(String type) {
    return isViolation(FULLSCREEN_STRICT, type);
  }

  static boolean isViolation(String proctorMode, String type) {
    if ("HIDDEN".equals(type)) return true;
    return FULLSCREEN_STRICT.equals(normalizeMode(proctorMode)) && STRICT_VIOLATION_TYPES.contains(type);
  }

  static String normalizeMode(String mode) {
    return MOBILE_COMPATIBLE.equals(mode) ? MOBILE_COMPATIBLE : FULLSCREEN_STRICT;
  }

  static String resolveAttemptMode(String existingMode, String requestedMode) {
    return MOBILE_COMPATIBLE.equals(normalizeMode(existingMode)) || MOBILE_COMPATIBLE.equals(normalizeMode(requestedMode))
        ? MOBILE_COMPATIBLE : FULLSCREEN_STRICT;
  }

  static boolean shouldAutoSubmit(int violationCount, int violationLimit) {
    return violationCount >= violationLimit;
  }

  static LocalDateTime violationDeadline(LocalDateTime now, int graceSeconds) {
    return now.plusSeconds(graceSeconds);
  }

  static boolean violationGraceExpired(LocalDateTime deadline, LocalDateTime now) {
    return deadline != null && !deadline.isAfter(now);
  }

  static boolean deadlineReached(LocalDateTime deadline, LocalDateTime now) {
    return !deadline.isAfter(now);
  }

  static boolean shouldSettleTimeout(String status, LocalDateTime deadline, LocalDateTime now) {
    return "IN_PROGRESS".equals(status) && deadlineReached(deadline, now);
  }
}
