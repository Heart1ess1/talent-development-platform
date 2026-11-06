package com.talent.platform.evaluation;

import com.talent.platform.common.BusinessException;
import java.math.*;
import java.util.*;

/** Pure allocation rules shared by preview and publication. */
public final class PerformanceGrading {
  private PerformanceGrading() {}
  public static final List<String> GRADES=List.of("A","B","C");
  public record Score(long employeeId,BigDecimal score) {}
  public record Placement(long employeeId,BigDecimal score,int rank,String grade) {}
  public record Distribution(List<Integer> targets,List<Integer> actual,List<Placement> placements) {}

  public static void validate(List<BigDecimal> ratios) {
    if(ratios==null||ratios.size()!=3||ratios.stream().anyMatch(x->x==null||x.signum()<0||x.compareTo(new BigDecimal("100"))>0||x.stripTrailingZeros().scale()>2)
        ||ratios.stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(new BigDecimal("100"))!=0)
      throw new BusinessException(400,"ABC 比例须为 0 至 100，最多两位小数，且合计为 100%");
  }

  public static Distribution allocate(List<Score> scores,List<BigDecimal> ratios) {
    validate(ratios);
    if(scores.stream().map(Score::employeeId).distinct().count()!=scores.size())throw new BusinessException(400,"参评人员重复");
    if(scores.stream().anyMatch(x->x.score()==null||x.score().signum()<0||x.score().compareTo(new BigDecimal("100"))>0))throw new BusinessException(400,"参评人员须具有有效综合分");
    int n=scores.size();int[] quotas=new int[3];var remainders=new BigDecimal[3];int allocated=0;
    for(int i=0;i<3;i++){
      BigDecimal raw=ratios.get(i).multiply(BigDecimal.valueOf(n)).movePointLeft(2);
      quotas[i]=raw.intValue();allocated+=quotas[i];remainders[i]=raw.subtract(BigDecimal.valueOf(quotas[i]));
    }
    var order=new ArrayList<>(List.of(0,1,2));order.sort(Comparator.<Integer,BigDecimal>comparing(i->remainders[i]).reversed().thenComparingInt(i->i));
    for(int i=0;i<n-allocated;i++)quotas[order.get(i)]++;
    var sorted=scores.stream().map(x->new Score(x.employeeId(),x.score().setScale(2,RoundingMode.HALF_UP)))
        .sorted(Comparator.comparing(Score::score).reversed().thenComparingLong(Score::employeeId)).toList();
    var placements=new ArrayList<Placement>();int[] actual=new int[3];int rank=0;BigDecimal previous=null;
    for(int i=0;i<sorted.size();i++){
      Score row=sorted.get(i);if(previous==null||row.score().compareTo(previous)!=0)rank=i+1;previous=row.score();
      int grade=rank<=quotas[0]?0:rank<=quotas[0]+quotas[1]?1:2;
      actual[grade]++;placements.add(new Placement(row.employeeId(),row.score(),rank,GRADES.get(grade)));
    }
    return new Distribution(Arrays.stream(quotas).boxed().toList(),Arrays.stream(actual).boxed().toList(),placements);
  }
}
