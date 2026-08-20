package com.talent.platform.security;

import java.util.regex.Pattern;

public final class EmployeeInitialPassword {
  private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[0-9Xx]");

  private EmployeeInitialPassword() {}

  public static boolean supports(String idCard) {
    return idCard != null && ID_CARD_PATTERN.matcher(idCard.trim()).matches();
  }

  public static String fromIdCard(String idCard) {
    if (!supports(idCard)) {
      throw new IllegalArgumentException("身份证号码必须为18位，末位仅支持数字或X/x");
    }
    String normalized = idCard.trim();
    return normalized.substring(normalized.length() - 6).replace('x', 'X');
  }
}
