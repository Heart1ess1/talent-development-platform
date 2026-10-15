package com.talent.platform.exam;

import com.fasterxml.jackson.databind.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import java.math.BigDecimal;import java.util.*;

@Service
public class ExamScoringService {
  @org.springframework.beans.factory.annotation.Autowired private com.talent.platform.evaluation.EvaluationRevisionService revisions;
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
    db.update("update exam_attempt set status=?,submitted_at=now(),objective_score=?,total_score=?,active_violation_key=null,violation_deadline_at=null where id=? and status='IN_PROGRESS'",
            status,objective,subjective?null:objective,id);
    return Map.of("status",status,"objectiveScore",objective,"submission",submittedStatus);
  }
  @Transactional public int scoreExpired(){var rows=db.queryForList("select id,(deadline_at<=now()) exam_expired from exam_attempt where status='IN_PROGRESS' and (deadline_at<=now() or violation_deadline_at<=now())");for(var row:rows)try{Long id=((Number)row.get("id")).longValue();Object expired=row.get("exam_expired");boolean examExpired=Boolean.TRUE.equals(expired)||(expired instanceof Number number&&number.intValue()!=0);score(id,examExpired?"TIMEOUT":"ANTI_CHEAT_TIMEOUT");}catch(Exception e){throw new IllegalStateException(e);}return rows.size();}
  @Transactional public int publishEndedResults(){
    var rows=db.queryForList("select distinct a.employee_id,coalesce(ea.score_month,p.score_month) score_month from exam_attempt a join exam_plan p on p.id=a.plan_id join exam_assignment ea on ea.plan_id=p.id and ea.employee_id=a.employee_id where a.status='GRADED' and a.published=false and p.status='PUBLISHED' and coalesce(ea.window_ends_at,p.ends_at)<=now()");
    int count=db.update("""
      update exam_attempt a join exam_plan p on p.id=a.plan_id
      join exam_assignment ea on ea.plan_id=p.id and ea.employee_id=a.employee_id
      set a.published=true,a.version=a.version+1
      where a.status='GRADED' and a.published=false and p.status='PUBLISHED' and coalesce(ea.window_ends_at,p.ends_at)<=now()
      """);
    if(count>0&&revisions!=null){Map<Long,Set<java.time.YearMonth>> affected=new LinkedHashMap<>();for(var row:rows)affected.computeIfAbsent(((Number)row.get("employee_id")).longValue(),k->new LinkedHashSet<>()).add(java.time.YearMonth.parse(row.get("score_month").toString().substring(0,7)));revisions.changed(affected,"发布考试成绩");}return count;
  }
  static boolean answersMatch(String type,JsonNode expected,JsonNode actual){if(!"MULTIPLE".equals(type))return Objects.equals(expected,actual);if(expected==null||actual==null||!expected.isArray()||!actual.isArray()||expected.size()!=actual.size())return false;var expectedValues=new HashSet<String>();var actualValues=new HashSet<String>();expected.forEach(x->expectedValues.add(x.toString()));actual.forEach(x->actualValues.add(x.toString()));return expectedValues.size()==expected.size()&&actualValues.size()==actual.size()&&expectedValues.equals(actualValues);}
  private JsonNode tree(Object x)throws Exception{return x==null?null:mapper.readTree(String.valueOf(x));}
}
