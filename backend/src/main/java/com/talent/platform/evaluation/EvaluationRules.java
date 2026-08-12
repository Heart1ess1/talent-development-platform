package com.talent.platform.evaluation;

import com.talent.platform.common.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EvaluationRules {
  private EvaluationRules() {}

  record WeightedItem(boolean enabled, BigDecimal weight) {}
  record WeightedScore(boolean enabled, BigDecimal weight, BigDecimal score) {}
  record SourceScore(Long sourceId, BigDecimal scorePercent) {}
  record WeightedSource(Long sourceId, BigDecimal scorePercent, BigDecimal weight, BigDecimal contribution) {}

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

  static void validateSourceWeights(Map<Long,BigDecimal> weights) {
    BigDecimal total=BigDecimal.ZERO;
    for (var entry:weights.entrySet()) {
      if(entry.getKey()==null||entry.getValue()==null||entry.getValue().compareTo(BigDecimal.ZERO)<=0)
        throw new BusinessException(400,"任务或考试明细权重必须大于0");
      total=total.add(entry.getValue());
    }
    if(total.compareTo(new BigDecimal("100"))>0)
      throw new BusinessException(400,"任务或考试已指定权重不能超过100%");
  }

  static List<WeightedSource> allocateSourceWeights(List<SourceScore> sources, Map<Long,BigDecimal> configured) {
    if(sources.isEmpty())return List.of();
    Map<Long,BigDecimal> matched=new LinkedHashMap<>();
    for(SourceScore source:sources)if(configured.containsKey(source.sourceId()))matched.put(source.sourceId(),configured.get(source.sourceId()));
    BigDecimal explicit=matched.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
    long unspecified=sources.stream().filter(x->!matched.containsKey(x.sourceId())).count();
    BigDecimal fallback=unspecified==0?BigDecimal.ZERO:new BigDecimal("100").subtract(explicit).max(BigDecimal.ZERO)
      .divide(BigDecimal.valueOf(unspecified),8,RoundingMode.HALF_UP);
    var result=new ArrayList<WeightedSource>();
    for(SourceScore source:sources){
      BigDecimal weight=matched.getOrDefault(source.sourceId(),fallback);
      if(unspecified==0&&explicit.compareTo(BigDecimal.ZERO)>0)
        weight=weight.multiply(new BigDecimal("100")).divide(explicit,8,RoundingMode.HALF_UP);
      if(unspecified==0&&explicit.compareTo(BigDecimal.ZERO)==0)
        weight=new BigDecimal("100").divide(BigDecimal.valueOf(sources.size()),8,RoundingMode.HALF_UP);
      BigDecimal contribution=source.scorePercent()==null||weight.compareTo(BigDecimal.ZERO)==0?null:
        source.scorePercent().multiply(weight).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP);
      result.add(new WeightedSource(source.sourceId(),source.scorePercent(),weight,contribution));
    }
    return result;
  }

  static BigDecimal weightedSourceScore(List<WeightedSource> sources) {
    if(sources.isEmpty())return null;
    BigDecimal total=BigDecimal.ZERO;
    for(WeightedSource source:sources){
      if(source.weight().compareTo(BigDecimal.ZERO)>0&&source.scorePercent()==null)return null;
      if(source.contribution()!=null)total=total.add(source.contribution());
    }
    return total.setScale(6,RoundingMode.HALF_UP);
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
