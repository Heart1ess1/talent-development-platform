package com.talent.platform.security;

public final class InitialPassword {
  private InitialPassword() {}

  public static String fromEmployeeNo(String employeeNo) {
    if (employeeNo == null || employeeNo.trim().isEmpty()) {
      throw new IllegalArgumentException("工号不能为空");
    }
    String normalized = employeeNo.trim();
    return normalized.length() <= 6 ? normalized : normalized.substring(normalized.length() - 6);
  }
}
