package com.talent.platform.evaluation;

import com.talent.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRulesTest {
  @Test void enabledWeightsMustTotalOneHundred() {
    assertDoesNotThrow(() -> EvaluationRules.validateComponentWeights(List.of(
      item(true,"40"),item(false,"0"),item(true,"60"))));
    assertThrows(BusinessException.class, () -> EvaluationRules.validateComponentWeights(List.of(
      item(true,"40"),item(false,"10"),item(true,"50"))));
    assertThrows(BusinessException.class, () -> EvaluationRules.validateComponentWeights(List.of(
      item(false,"0"),item(false,"0"))));
  }

  @Test void enabledItemCannotHaveZeroWeight() {
    BusinessException error=assertThrows(BusinessException.class, () -> EvaluationRules.validateComponentWeights(List.of(
      item(true,"0"),item(true,"100"))));
    assertEquals(400,error.getCode());
  }

  @Test void quarterWeightsArePositiveAndTotalOneHundred() {
    assertDoesNotThrow(() -> EvaluationRules.validateQuarterWeights(d("33.33"),d("33.33"),d("33.34")));
    assertThrows(BusinessException.class, () -> EvaluationRules.validateQuarterWeights(d("0"),d("50"),d("50")));
    assertThrows(BusinessException.class, () -> EvaluationRules.validateQuarterWeights(d("30"),d("30"),d("30")));
  }

  @Test void disabledMissingItemIsIgnoredAndEnabledMissingItemBlocksResult() {
    assertEquals(d("85.00"),EvaluationRules.finalScore(List.of(
      score(true,"100","80"),score(false,"0",null)),d("10"),d("5")));
    assertNull(EvaluationRules.finalScore(List.of(
      score(true,"50","80"),score(true,"50",null)),d("0"),d("0")));
  }

  @Test void adjustmentsAreClampedToScoreBounds() {
    assertEquals(d("100.00"),EvaluationRules.finalScore(List.of(score(true,"100","98")),d("10"),d("0")));
    assertEquals(d("0.00"),EvaluationRules.finalScore(List.of(score(true,"100","2")),d("0"),d("10")));
  }

  private static EvaluationRules.WeightedItem item(boolean enabled,String weight){return new EvaluationRules.WeightedItem(enabled,d(weight));}
  private static EvaluationRules.WeightedScore score(boolean enabled,String weight,String score){return new EvaluationRules.WeightedScore(enabled,d(weight),score==null?null:d(score));}
  private static BigDecimal d(String value){return new BigDecimal(value);}
}
