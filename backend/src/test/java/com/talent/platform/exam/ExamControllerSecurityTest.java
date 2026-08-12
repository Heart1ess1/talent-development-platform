package com.talent.platform.exam;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExamControllerSecurityTest {
  @Test
  void onlyEmployeeUnpublishedViewUsesScoreRedaction() {
    assertThat(ExamController.shouldHideScores(false, false)).isTrue();
    assertThat(ExamController.shouldHideScores(false, true)).isFalse();
    assertThat(ExamController.shouldHideScores(true, false)).isFalse();
  }

  @Test
  void hidesScoresAndReviewerFeedbackUntilAttemptIsPublished() {
    var attempt = new LinkedHashMap<String, Object>();
    attempt.put("objective_score", new BigDecimal("80"));
    attempt.put("total_score", new BigDecimal("80"));
    var question = new LinkedHashMap<String, Object>();
    question.put("id", 1L);
    question.put("saved_answer", "A");
    question.put("answer_score", new BigDecimal("5"));
    question.put("reviewer_comment", "correct");
    List<Map<String, Object>> questions = new ArrayList<>(List.of(question));

    ExamController.hideUnpublishedScores(attempt, questions);

    assertThat(attempt).doesNotContainKeys("objective_score", "total_score");
    assertThat(question).containsEntry("saved_answer", "A")
        .doesNotContainKeys("answer_score", "reviewer_comment");
  }
}
