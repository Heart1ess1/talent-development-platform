package com.talent.platform.evaluation;

import com.talent.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceGradingTest {
  private List<BigDecimal> ratios(String... values){return Arrays.stream(values).map(BigDecimal::new).toList();}
  private PerformanceGrading.Distribution grade(List<BigDecimal> ratio,String... values){var rows=new ArrayList<PerformanceGrading.Score>();for(int i=0;i<values.length;i++)rows.add(new PerformanceGrading.Score(i+1,new BigDecimal(values[i])));return PerformanceGrading.allocate(rows,ratio);}
  @Test void largestRemaindersAllocateExactlyTheWholeCohort(){
    assertEquals(List.of(1,5,1),grade(ratios("20","70","10"),"100","90","80","70","60","50","0").targets());
    assertEquals(List.of(0,1,0),grade(ratios("20","70","10"),"0").targets());
    assertEquals(List.of(1,0,1),grade(ratios("33.33","33.33","33.34"),"100","0").targets());
    assertEquals(List.of(1,0,0),grade(ratios("50","50","0"),"0").targets());
  }
  @Test void tiesAtBothBoundariesMoveTogetherIntoTheHigherGrade(){
    var d=grade(ratios("20","70","10"),"100","90","90","80","70","60","50","40","30","30");
    assertEquals(List.of(2,7,1),d.targets());assertEquals(List.of(3,7,0),d.actual());
    assertEquals(2,d.placements().get(2).rank());assertEquals("A",d.placements().get(2).grade());assertEquals("B",d.placements().get(9).grade());
  }
  @Test void allTiedAndZeroQuotaGrades(){
    assertEquals(List.of(4,0,0),grade(ratios("25","50","25"),"80","80","80","80").actual());
    assertEquals(List.of(0,4,0),grade(ratios("0","75","25"),"80","80","80","80").actual());
    assertEquals(List.of(0,0,2),grade(ratios("0","0","100"),"100","0").actual());
  }
  @Test void percentagesAndMissingScoresAreValidated(){
    for(var r:List.of(ratios("20","70","9.99"),ratios("-1","100","1"),ratios("20.001","69.999","10")))assertThrows(BusinessException.class,()->PerformanceGrading.validate(r));
    assertThrows(BusinessException.class,()->PerformanceGrading.allocate(List.of(new PerformanceGrading.Score(1,null)),ratios("20","70","10")));
    assertEquals(List.of(0,0,0),PerformanceGrading.allocate(List.of(),ratios("20","70","10")).targets());
  }
  @Test void displayedScorePrecisionDeterminesTies(){
    var d=grade(ratios("50","50","0"),"90.004","90.00");assertEquals(List.of(2,0,0),d.actual());
  }
}
