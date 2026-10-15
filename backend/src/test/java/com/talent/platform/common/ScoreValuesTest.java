package com.talent.platform.common;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class ScoreValuesTest {
  private BigDecimal value(String value){return new BigDecimal(value);}
  @Test void integerAndDecimalInputsPreserveTwoPlaces(){for(String input:new String[]{"0","100","85.2","85.25"})assertEquals(2,ScoreValues.require(value(input),value("100")).scale());}
  @Test void precisionAndRangeErrorsAreNotSilentlyRounded(){for(String input:new String[]{"85.251","100.01","-0.01"})assertThrows(BusinessException.class,()->ScoreValues.require(value(input),value("100")));}
}
