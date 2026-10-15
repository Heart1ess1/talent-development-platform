package com.talent.platform.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Published snapshots remain authoritative until the entire correction is reviewed. */
@Service
public class EvaluationRevisionService {
  private final JdbcTemplate db;
  private final EvaluationService evaluation;
  private final ObjectMapper json;
  public EvaluationRevisionService(JdbcTemplate db,EvaluationService evaluation,ObjectMapper json){this.db=db;this.evaluation=evaluation;this.json=json;}

  /** No draft or formal result is written while the administrator previews a time change. */
  public List<Map<String,Object>> previewSchedule(String type,Long resource,LocalDateTime endsAt,List<Map<String,Object>> people){
    Map<String,Map<String,Object>> monthly=new LinkedHashMap<>();
    for(var person:people){
      long employee=((Number)person.get(type.equals("EVALUATION")?"employee_id":"participant_id")).longValue();
      for(String key:new LinkedHashSet<>(List.of(person.get("oldMonth").toString(),person.get("newMonth").toString()))){
        if(key.isBlank()||monthly.containsKey(employee+":"+key))continue;
        var item=new LinkedHashMap<String,Object>();item.put("employeeId",employee);item.put("name",person.get("name"));item.put("periodKey",key);item.put("summaryType","MONTH");
        var official=db.queryForList("select final_score from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED'",employee,key);
        item.put("oldScore",official.isEmpty()?null:official.get(0).get("final_score"));
        try{var projected=evaluation.previewMonthly(employee,YearMonth.parse(key),type,resource,endsAt);item.put("missingItems",projected.get("missingItems"));item.put("newScore",((List<?>)projected.get("missingItems")).isEmpty()?projected.get("finalScore"):null);}
        catch(BusinessException error){item.put("error",error.getMessage());item.put("newScore",null);}
        monthly.put(employee+":"+key,item);
      }
    }
    var result=new ArrayList<>(monthly.values());Set<String> quarters=new HashSet<>();
    for(var item:monthly.values()){
      long employee=((Number)item.get("employeeId")).longValue();YearMonth month=YearMonth.parse(item.get("periodKey").toString());int first=(month.getMonthValue()-1)/3*3+1;
      String quarter=month.getYear()+"-Q"+((first-1)/3+1);if(!quarters.add(employee+":"+quarter))continue;
      var official=db.queryForList("select final_score,scheme_id from score_summary where employee_id=? and summary_type='QUARTER' and period_key=? and status='PUBLISHED'",employee,quarter);if(official.isEmpty())continue;
      var projected=new LinkedHashMap<String,Object>();projected.put("employeeId",employee);projected.put("name",item.get("name"));projected.put("periodKey",quarter);projected.put("summaryType","QUARTER");projected.put("oldScore",official.get(0).get("final_score"));
      try{var value=evaluation.calculateQuarter(employee,quarter,evaluation.quarterSchemeFor(employee,quarter),null,monthly);projected.put("missingItems",value.missing());projected.put("newScore",value.finalScore());}
      catch(BusinessException error){projected.put("error",error.getMessage());projected.put("newScore",null);}result.add(projected);
    }
    return result;
  }

  @Transactional
  public Long changed(Map<Long,Set<YearMonth>> affected,String reason){return changed(affected,reason,false);}

  @Transactional
  public Long quarterChanged(Long employee,String key,String reason){
    if(db.queryForObject("select count(*) from score_summary where employee_id=? and summary_type='QUARTER' and period_key=? and status='PUBLISHED'",Integer.class,employee,key)==0){evaluation.refreshQuarterIfPresent(employee,key);return null;}
    return changed(Map.of(employee,Set.of(EvaluationService.quarterEnd(key))),reason,true);
  }

  private Long changed(Map<Long,Set<YearMonth>> affected,String reason,boolean quarterOnly){
    if(affected.isEmpty()) return null;
    for(Long employee:new TreeSet<>(affected.keySet()))db.queryForMap("select id from employee where id=? for update",employee);
    Set<Long> groups=new TreeSet<>();
    boolean published=false;
    for(var entry:affected.entrySet()) for(var month:entry.getValue()) {
      groups.addAll(db.queryForList("select m.group_id from evaluation_revision_member m join evaluation_revision_group g on g.id=m.group_id where m.employee_id=? and g.status='DRAFT' for update",Long.class,entry.getKey()));
      published |= db.queryForObject("select count(*) from score_summary where employee_id=? and summary_type=? and period_key=? and status='PUBLISHED'",Integer.class,entry.getKey(),quarterOnly?"QUARTER":"MONTH",quarterOnly?month.getYear()+"-Q"+((month.getMonthValue()-1)/3+1):month.toString())>0;
    }
    if(!published && groups.isEmpty()) {
      for(var e:affected.entrySet())for(var m:e.getValue())evaluation.refreshDraftIfPresent(e.getKey(),m);
      return null;
    }
    Long id;
    if(groups.isEmpty()) {
      db.update("insert into evaluation_revision_group(reason,created_by) values(?,?)",reason,operator());
      id=db.queryForObject("select last_insert_id()",Long.class);
    }else{id=groups.iterator().next();db.queryForMap("select id from evaluation_revision_group where id=? for update",id);}
    for(Long old:groups)if(!old.equals(id)){
      db.update("insert ignore into evaluation_revision_member(group_id,employee_id,period_key,summary_type,summary_id,error_message) select ?,employee_id,period_key,summary_type,summary_id,error_message from evaluation_revision_member where group_id=?",id,old);
      db.update("insert ignore into evaluation_revision_permission(group_id,employee_id,period_month,component_type,scope_id,reviewer_id) select ?,employee_id,period_month,component_type,scope_id,reviewer_id from evaluation_revision_permission where group_id=?",id,old);
      db.update("update score_summary set revision_group_id=? where revision_group_id=? and status='DRAFT'",id,old);
      db.update("update evaluation_revision_group set status='SUPERSEDED' where id=?",old);
    }
    for(var entry:affected.entrySet())for(var month:entry.getValue()){
      if(!quarterOnly)db.update("insert ignore into evaluation_revision_member(group_id,employee_id,period_key,summary_type) values(?,?,?,'MONTH')",id,entry.getKey(),month.toString());
      String quarter=month.getYear()+"-Q"+((month.getMonthValue()-1)/3+1);
      if(db.queryForObject("select count(*) from score_summary where employee_id=? and period_key=? and summary_type='QUARTER' and status='PUBLISHED'",Integer.class,entry.getKey(),quarter)>0)
        db.update("insert ignore into evaluation_revision_member(group_id,employee_id,period_key,summary_type) values(?,?,?,'QUARTER')",id,entry.getKey(),quarter);
    }
    db.update("update evaluation_revision_group set generation=generation+1,reason=? where id=?",reason,id);
    refresh(id);
    return id;
  }

  @Transactional
  public Map<String,Object> refresh(Long id){
    requireDraft(id);
    for(var member:members(id))if("MONTH".equals(member.get("summary_type"))){YearMonth month=YearMonth.parse(member.get("period_key").toString());String quarter=month.getYear()+"-Q"+((month.getMonthValue()-1)/3+1);if(db.queryForObject("select count(*) from score_summary where employee_id=? and summary_type='QUARTER' and period_key=? and status='PUBLISHED'",Integer.class,member.get("employee_id"),quarter)>0)db.update("insert ignore into evaluation_revision_member(group_id,employee_id,period_key,summary_type) values(?,?,?,'QUARTER')",id,member.get("employee_id"),quarter);}
    for(var member:members(id)) if("MONTH".equals(member.get("summary_type"))){
      Long employee=((Number)member.get("employee_id")).longValue();YearMonth month=YearMonth.parse(member.get("period_key").toString());
      try {
        var old=db.queryForList("select scheme_id from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED' order by version desc limit 1",employee,month.toString());
        Map<String,Object> scheme=old.isEmpty()||old.get(0).get("scheme_id")==null?evaluation.schemeFor(employee,month):evaluation.schemeById(((Number)old.get(0).get("scheme_id")).longValue());
        Long summary=evaluation.generateMonth(employee,month,scheme,"关联修订组 #"+id);
        db.update("update score_summary set revision_group_id=?,final_score=coalesce(revision_override_score,final_score) where id=?",id,summary);
        db.update("update evaluation_revision_member set summary_id=?,error_message=null where group_id=? and employee_id=? and period_key=? and summary_type='MONTH'",summary,id,employee,month.toString());
      }catch(BusinessException ex){db.update("update evaluation_revision_member set error_message=? where group_id=? and employee_id=? and period_key=? and summary_type='MONTH'",ex.getMessage(),id,employee,month.toString());}
    }
    for(var member:members(id))if("QUARTER".equals(member.get("summary_type"))){try{refreshQuarter(id,member);}catch(BusinessException ex){db.update("update evaluation_revision_member set error_message=? where group_id=? and employee_id=? and period_key=? and summary_type='QUARTER'",ex.getMessage(),id,member.get("employee_id"),member.get("period_key"));}}
    return detail(id);
  }

  private void refreshQuarter(Long group,Map<String,Object> member){
    long employee=((Number)member.get("employee_id")).longValue();String key=member.get("period_key").toString();
    var scheme=evaluation.quarterSchemeFor(employee,key);Long summary=evaluation.writeQuarter(employee,key,scheme,group);
    db.update("update evaluation_revision_member set summary_id=?,error_message=null where group_id=? and employee_id=? and period_key=? and summary_type='QUARTER'",summary,group,employee,key);
  }

  public List<Map<String,Object>> list(){return db.queryForList("select g.*,u.display_name creator_name,(select count(*) from evaluation_revision_member m where m.group_id=g.id) member_count from evaluation_revision_group g join sys_user u on u.id=g.created_by where g.status='DRAFT' order by g.id desc");}
  public Map<String,Object> detail(Long id){
    var result=new LinkedHashMap<>(db.queryForMap("select * from evaluation_revision_group where id=?",id));
    var rows=members(id);result.put("members",rows);result.put("reviewToken",token(Map.of("generation",result.get("generation"),"members",rows)));return result;
  }
  private List<Map<String,Object>> members(Long id){return db.queryForList("select m.*,e.name employee_name,e.employee_no,s.final_score,s.missing_items,s.waiver_reason,s.revision_override_score,s.version,s.component_snapshot,s.quarter_snapshot,(select old.final_score from score_summary old where old.employee_id=m.employee_id and old.summary_type=m.summary_type and old.period_key=m.period_key and old.status='PUBLISHED' order by old.version desc limit 1) old_final_score from evaluation_revision_member m join employee e on e.id=m.employee_id left join score_summary s on s.id=m.summary_id where m.group_id=? order by m.employee_id,m.summary_type,m.period_key",id);}
  @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public void publish(Long id,String reviewToken){
    for(Long employee:db.queryForList("select distinct employee_id from evaluation_revision_member where group_id=? order by employee_id",Long.class,id))db.queryForMap("select id from employee where id=? for update",employee);
    requireDraft(id);var fresh=refresh(id);
    if(reviewToken==null||!reviewToken.equals(fresh.get("reviewToken")))throw new BusinessException(409,"评分来源已变化，请刷新并重新核对修订稿");
    for(var row:members(id)){
      if(row.get("error_message")!=null||row.get("summary_id")==null||row.get("final_score")==null||(!Objects.toString(row.get("missing_items"),"").isBlank()&&(row.get("revision_override_score")==null||Objects.toString(row.get("waiver_reason"),"").isBlank())))throw new BusinessException(400,"关联修订仍有缺失方案或评分，请先完善");
      db.update("update score_summary set status='SUPERSEDED' where employee_id=? and summary_type=? and period_key=? and status='PUBLISHED'",row.get("employee_id"),row.get("summary_type"),row.get("period_key"));
      db.update("update score_summary set status='PUBLISHED',published_at=now() where id=? and status='DRAFT'",row.get("summary_id"));
    }
    db.update("update evaluation_revision_group set status='PUBLISHED',published_at=now(),reviewed_generation=generation where id=?",id);
  }
  @Transactional
  public void waive(Long group,Long summary,String reason,BigDecimal score){
    if(!Set.of("ADMIN","SUPER_ADMIN").contains(SecurityUtils.current().role()))throw new BusinessException(403,"仅管理员可核定缺项结果");
    com.talent.platform.common.ScoreValues.require(score,new BigDecimal("100"));if(reason==null||reason.isBlank())throw new BusinessException(400,"请填写缺项豁免原因");requireDraft(group);
    if(db.update("update score_summary set revision_override_score=?,waiver_reason=?,final_score=? where id=? and revision_group_id=? and status='DRAFT'",score,reason,score,summary,group)!=1)throw new BusinessException(404,"关联草稿不存在");
    refresh(group);
  }
  public boolean canEdit(Long employee,YearMonth month,String component,long scope,Long reviewer){return db.queryForObject("select count(*) from evaluation_revision_permission p join evaluation_revision_group g on g.id=p.group_id where p.employee_id=? and p.period_month=? and p.component_type=? and p.scope_id=? and p.reviewer_id=? and g.status='DRAFT'",Integer.class,employee,month.atDay(1),component,scope,reviewer)>0;}
  public boolean pending(Long employee,YearMonth month){return db.queryForObject("select count(*) from evaluation_revision_member m join evaluation_revision_group g on g.id=m.group_id where m.employee_id=? and m.period_key=? and m.summary_type='MONTH' and g.status='DRAFT'",Integer.class,employee,month.toString())>0;}
  private Long operator(){var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getPrincipal() instanceof CurrentUser u?u.id():db.queryForObject("select min(id) from sys_user where role='SUPER_ADMIN'",Long.class);}
  private void requireDraft(Long id){var rows=db.queryForList("select id from evaluation_revision_group where id=? and status='DRAFT' for update",id);if(rows.isEmpty())throw new BusinessException(409,"修订组已发布或已合并，请刷新");}
  private String token(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
