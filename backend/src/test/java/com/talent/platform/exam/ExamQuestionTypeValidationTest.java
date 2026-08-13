package com.talent.platform.exam;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExamQuestionTypeValidationTest {
  @Test
  void newQuestionsOnlyAcceptObjectiveTypes() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      for (var type : List.of("SINGLE", "MULTIPLE", "TRUE_FALSE")) {
        var request = request(type);
        assertThat(validator.validate(request)).isEmpty();
      }
      assertThat(validator.validate(request("SHORT")))
          .anyMatch(violation -> violation.getPropertyPath().toString().equals("type"));
    }
  }

  private ExamController.QuestionRequest request(String type) {
    return new ExamController.QuestionRequest(
        1L, type, "题干", JsonNodeFactory.instance.arrayNode().add("A").add("B"),
        JsonNodeFactory.instance.textNode("A"), null, BigDecimal.ONE, List.of());
  }
}
