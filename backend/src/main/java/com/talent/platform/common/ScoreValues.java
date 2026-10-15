package com.talent.platform.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ScoreValues {
  private ScoreValues() {}
  public static BigDecimal require(BigDecimal value, BigDecimal maximum) {
    if (value == null || value.signum() < 0 || value.compareTo(maximum) > 0)
      throw new BusinessException(400, "分数必须在0至" + maximum.toPlainString() + "之间");
    if (value.stripTrailingZeros().scale() > 2) throw new BusinessException(400, "分数最多保留两位小数");
    return value.setScale(2, RoundingMode.UNNECESSARY);
  }
}
