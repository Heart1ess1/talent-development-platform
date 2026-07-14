package com.talent.platform.evaluation;

import com.talent.platform.common.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

final class EvaluationRules {
  private EvaluationRules() {}

  record WeightedItem(boolean enabled, BigDecimal weight) {}
  record WeightedScore(boolean enabled, BigDecimal weight, BigDecimal score) {}

  static void validateComponentWeights(List<WeightedItem> items) {
    if (items.stream().noneMatch(WeightedItem::enabled)) throw new BusinessException(400, "至少启用一个评分项");
    BigDecimal total = BigDecimal.ZERO;
    for (WeightedItem item : items) {
      BigDecimal weight = item.weight() == null ? BigDecimal.ZERO : item.weight();
      if (item.enabled() && weight.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(400, "启用项权重必须大于0");
      if (!item.enabled() && weight.compareTo(BigDecimal.ZERO) != 0) throw new BusinessException(400, "关闭项权重必须为0");
      total = total.add(weight);
    }
    if (total.compareTo(new BigDecimal("100")) != 0) throw new BusinessException(400, "启用项权重之和必须为100");
  }

  static void validateQuarterWeights(BigDecimal... weights) {
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal weight : weights) {
      if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(400, "季度月份权重必须大于0");
      total = total.add(weight);
    }
    if (total.compareTo(new BigDecimal("100")) != 0) throw new BusinessException(400, "季度月份权重之和必须为100");
  }

  static BigDecimal finalScore(List<WeightedScore> items, BigDecimal bonus, BigDecimal deduction) {
    BigDecimal total=BigDecimal.ZERO;
    for(WeightedScore item:items){
      if(!item.enabled()) continue;
      if(item.score()==null) return null;
      total=total.add(item.score().multiply(item.weight()).divide(new BigDecimal("100"),4,RoundingMode.HALF_UP));
    }
    return total.add(bonus).subtract(deduction).max(BigDecimal.ZERO).min(new BigDecimal("100")).setScale(2,RoundingMode.HALF_UP);
  }
}
