package com.talent.platform.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

  @Test void dynamicAttemptQuestionsAreScored()throws Exception{
    JdbcTemplate db=mock(JdbcTemplate.class);
    when(db.queryForList(anyString(),eq(42L),eq(42L))).thenReturn(List.of(Map.of(
            "id",7L,
            "question_type","SINGLE",
            "answer_json","\"A\"",
            "score",new BigDecimal("50")
    )));
    when(db.queryForList("select answer_json from exam_answer where attempt_id=? and question_id=?",42L,7L))
            .thenReturn(List.of(Map.of("answer_json","\"A\"")));

    var result=new ExamScoringService(db,mapper).score(42L,"MANUAL");

    assertEquals("GRADED",result.get("status"));
    assertEquals(new BigDecimal("50"),result.get("objectiveScore"));
    verify(db).update(contains("insert into exam_answer"),eq(42L),eq(7L),eq(new BigDecimal("50")));
    verify(db).update(contains("update exam_attempt"),eq("GRADED"),eq(new BigDecimal("50")),
            eq(new BigDecimal("50")),eq(42L));
  }

  @Test void scheduledSettlementIncludesViolationCountdownDeadlines(){
    JdbcTemplate db=mock(JdbcTemplate.class);
    when(db.queryForList(contains("violation_deadline_at<=now()"))).thenReturn(List.of());

    assertEquals(0,new ExamScoringService(db,mapper).scoreExpired());

    verify(db).queryForList(contains("deadline_at<=now() or violation_deadline_at<=now()"));
  }

  @Test void endedObjectiveResultsArePublishedInOneServerSideUpdate(){
    JdbcTemplate db=mock(JdbcTemplate.class);
    when(db.update(contains("p.ends_at<=now()"))).thenReturn(3);

    int published=new ExamScoringService(db,mapper).publishEndedResults();

    assertEquals(3,published);
    verify(db).update(contains("a.status='GRADED'"));
  }
}
