package com.talent.platform.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExamScoringServiceTest {
  private final ObjectMapper mapper=new ObjectMapper();

  @Test void multipleChoiceIgnoresSelectionOrder()throws Exception{
    assertTrue(ExamScoringService.answersMatch("MULTIPLE",mapper.readTree("[\"A\",\"C\"]"),mapper.readTree("[\"C\",\"A\"]")));
    assertFalse(ExamScoringService.answersMatch("MULTIPLE",mapper.readTree("[\"A\",\"C\"]"),mapper.readTree("[\"A\",\"B\"]")));
  }

  @Test void singleAndTrueFalseRemainExact()throws Exception{
    assertTrue(ExamScoringService.answersMatch("SINGLE",mapper.readTree("\"A\""),mapper.readTree("\"A\"")));
    assertFalse(ExamScoringService.answersMatch("TRUE_FALSE",mapper.readTree("true"),mapper.readTree("false")));
  }
}
