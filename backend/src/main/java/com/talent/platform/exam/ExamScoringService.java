package com.talent.platform.exam;

import com.fasterxml.jackson.databind.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import java.math.BigDecimal;import java.util.*;

@Service
public class ExamScoringService {
  private final JdbcTemplate db;private final ObjectMapper mapper;public ExamScoringService(JdbcTemplate db,ObjectMapper mapper){this.db=db;this.mapper=mapper;}
  @Transactional
  public Map<String,Object> score(Long id,String submittedStatus)throws Exception{
    var questions=db.queryForList("""
        select id,question_type,answer_json,score from (
          select q.id,q.question_type,q.answer_json,pq.score
          from exam_attempt a
          join exam_plan p on p.id=a.plan_id
          join exam_paper ep on ep.id=p.paper_id and ep.dynamic_assembly=false
          join exam_paper_question pq on pq.paper_id=ep.id
          join question_bank q on q.id=pq.question_id
          where a.id=?
          union all
          select q.id,q.question_type,q.answer_json,aq.score
          from exam_attempt_question aq
          join question_bank q on q.id=aq.question_id
          where aq.attempt_id=?
        ) attempt_questions
        """,id,id);
    BigDecimal objective=BigDecimal.ZERO;
    boolean subjective=false;
    for(var question:questions){
      Long questionId=((Number)question.get("id")).longValue();
      String type=String.valueOf(question.get("question_type"));
      if("SHORT".equals(type)){
        subjective=true;
        db.update("insert ignore into exam_answer(attempt_id,question_id) values(?,?)",id,questionId);
        continue;
      }
      var answer=db.queryForList(
              "select answer_json from exam_answer where attempt_id=? and question_id=?",id,questionId);
      BigDecimal point=BigDecimal.ZERO;
      if(!answer.isEmpty()&&answersMatch(type,tree(question.get("answer_json")),tree(answer.get(0).get("answer_json"))))
        point=new BigDecimal(String.valueOf(question.get("score")));
      objective=objective.add(point);
      db.update("insert into exam_answer(attempt_id,question_id,score) values(?,?,?) on duplicate key update score=values(score)",
              id,questionId,point);
    }
    String status=subjective?"PENDING_REVIEW":"GRADED";
    db.update("update exam_attempt set status=?,submitted_at=now(),objective_score=?,total_score=? where id=? and status='IN_PROGRESS'",
            status,objective,subjective?null:objective,id);
    return Map.of("status",status,"objectiveScore",objective,"submission",submittedStatus);
  }
  @Transactional public int scoreExpired(){var ids=db.queryForList("select id from exam_attempt where status='IN_PROGRESS' and deadline_at<now()",Long.class);for(Long id:ids)try{score(id,"TIMEOUT");}catch(Exception e){throw new IllegalStateException(e);}return ids.size();}
  static boolean answersMatch(String type,JsonNode expected,JsonNode actual){if(!"MULTIPLE".equals(type))return Objects.equals(expected,actual);if(expected==null||actual==null||!expected.isArray()||!actual.isArray()||expected.size()!=actual.size())return false;var expectedValues=new HashSet<String>();var actualValues=new HashSet<String>();expected.forEach(x->expectedValues.add(x.toString()));actual.forEach(x->actualValues.add(x.toString()));return expectedValues.size()==expected.size()&&actualValues.size()==actual.size()&&expectedValues.equals(actualValues);}
  private JsonNode tree(Object x)throws Exception{return x==null?null:mapper.readTree(String.valueOf(x));}
}
