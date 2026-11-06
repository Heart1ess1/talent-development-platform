package com.talent.platform.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.BusinessException;
import com.talent.platform.common.ScoreValues;
import com.talent.platform.security.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.*;

@Service
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class MonthlyPerformanceService {
  private final JdbcTemplate db;
  private final EvaluationService evaluation;
  private final ObjectMapper json;
  public MonthlyPerformanceService(JdbcTemplate db,EvaluationService evaluation,ObjectMapper json){this.db=db;this.evaluation=evaluation;this.json=json;}
  private long number(Object x){return ((Number)x).longValue();}
  private boolean yes(Object x){return Boolean.TRUE.equals(x)||x instanceof Number n&&n.intValue()!=0;}
  private List<BigDecimal> ratios(Map<String,Object> g){return List.of((BigDecimal)g.get("ratio_a"),(BigDecimal)g.get("ratio_b"),(BigDecimal)g.get("ratio_c"));}
  private YearMonth month(Map<String,Object> g){return YearMonth.parse(g.get("period_month").toString().substring(0,7));}
  private long operator(){var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getPrincipal() instanceof com.talent.platform.security.CurrentUser u?u.id():db.queryForObject("select min(u.id) from sys_user u join sys_user_role r on r.user_id=u.id and r.role_code='SUPER_ADMIN'",Long.class);}
  private String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception ex){throw new IllegalStateException(ex);}}
  private String token(Object x){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(x).getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
  private Map<String,Object> group(long id){var rows=db.queryForList("select g.*,b.name batch_name from monthly_performance_group g join talent_batch b on b.id=g.talent_batch_id where g.id=?",id);if(rows.isEmpty())throw new BusinessException(404,"绩效方案不存在");return new LinkedHashMap<>(rows.get(0));}
  private Map<String,Object> draft(long id){var g=group(id);if(!"DRAFT".equals(g.get("status")))throw new BusinessException(409,"请先重开正式绩效方案，或刷新页面");return g;}
  private void lockBatch(long batch){db.queryForMap("select id from talent_batch where id=? for update",batch);}
  private void lockGroup(long id){db.queryForMap("select id from monthly_performance_group where id=? for update",id);}
  private List<Map<String,Object>> members(long id){return db.queryForList("select * from monthly_performance_member where group_id=? order by employee_id",id);}

  public List<Map<String,Object>> list(long batch,YearMonth month){return db.queryForList("select g.*,b.name batch_name from monthly_performance_group g join talent_batch b on b.id=g.talent_batch_id where g.talent_batch_id=? and g.period_month=? order by g.version desc",batch,month.atDay(1));}
  public Map<String,Object> detail(long id){
    var g=group(id);var rows=members(id);
    for(var row:rows){
      var previous=db.queryForList("select m.final_score,m.grade from monthly_performance_member m join monthly_performance_group p on p.id=m.group_id where m.employee_id=? and p.talent_batch_id=? and p.period_month=? and p.version<? and p.status in ('PUBLISHED','SUPERSEDED') order by p.version desc limit 1",row.get("employee_id"),g.get("talent_batch_id"),month(g).atDay(1),g.get("version"));
      row.put("old_score",previous.isEmpty()?null:previous.get(0).get("final_score"));row.put("old_grade",previous.isEmpty()?null:previous.get(0).get("grade"));
    }
    g.put("members",rows);return g;
  }

  public long save(long batch,YearMonth month,BigDecimal a,BigDecimal b,BigDecimal c){
    PerformanceGrading.validate(Arrays.asList(a,b,c));lockBatch(batch);
    var drafts=db.queryForList("select id from monthly_performance_group where talent_batch_id=? and period_month=? and status='DRAFT'",batch,month.atDay(1));
    if(!drafts.isEmpty()){
      long id=number(drafts.get(0).get("id"));db.update("update monthly_performance_group set ratio_a=?,ratio_b=?,ratio_c=?,generation=generation+1 where id=?",a,b,c,id);return id;
    }
    if(!list(batch,month).isEmpty())throw new BusinessException(409,"该月绩效已发布，请由管理员重开");
    if(db.queryForObject("select count(*) from score_summary s join employee e on e.id=s.employee_id where e.batch_id=? and s.summary_type='MONTH' and s.period_key=? and s.status='PUBLISHED'",Integer.class,batch,month.toString())>0)
      throw new BusinessException(409,"该批次月份已有正式成绩，首期不支持补划历史绩效");
    db.update("insert into monthly_performance_group(talent_batch_id,period_month,version,ratio_a,ratio_b,ratio_c,created_by) values(?,?,1,?,?,?,?)",batch,month.atDay(1),a,b,c,operator());
    long id=db.queryForObject("select last_insert_id()",Long.class);refreshRoster(id);return id;
  }

  public void refreshRoster(long id){
    var initial=draft(id);lockBatch(number(initial.get("talent_batch_id")));lockGroup(id);var g=draft(id);
    if(((Number)g.get("version")).intValue()!=1)throw new BusinessException(409,"正式发布后的参评名单固定，不可刷新");
    long batch=number(g.get("talent_batch_id"));var date=month(g).atDay(1);
    var employees=db.queryForList("select id,employee_no,name from employee where batch_id=? and status='ACTIVE' order by id for update",batch);
    for(var e:employees){
      long employee=number(e.get("id"));
      if(db.queryForObject("select count(*) from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED'",Integer.class,employee,month(g).toString())>0)
        throw new BusinessException(409,"名单中已有正式成绩，请先处理后再启用绩效划分");
      db.update("insert ignore into monthly_performance_scope(employee_id,period_month,talent_batch_id) values(?,?,?)",employee,date,batch);
      if(db.queryForObject("select talent_batch_id from monthly_performance_scope where employee_id=? and period_month=?",Long.class,employee,date)!=batch)
        throw new BusinessException(409,"员工 "+e.get("name")+" 已属于该月其他绩效批次，不能重复参评");
      db.update("insert into monthly_performance_member(group_id,employee_id,employee_no,employee_name) values(?,?,?,?) on duplicate key update employee_no=values(employee_no),employee_name=values(employee_name)",id,employee,e.get("employee_no"),e.get("name"));
    }
    db.update("update monthly_performance_member m join employee e on e.id=m.employee_id set m.exclusion_reason=case when m.participating=true then '名单刷新：已不属于该批次在培人员' else m.exclusion_reason end,m.participating=false where m.group_id=? and (e.batch_id is null or e.batch_id<>? or e.status<>'ACTIVE')",id,batch);
    db.update("update monthly_performance_group set generation=generation+1 where id=?",id);
  }

  public void participation(long id,long employee,boolean participating,String reason){
    lockGroup(id);var g=draft(id);
    if(((Number)g.get("version")).intValue()!=1)throw new BusinessException(409,"发布后的参评名单固定，修订仅调整比例和成绩");
    if(!participating&&(reason==null||reason.isBlank()||reason.length()>1000))throw new BusinessException(400,"请填写不超过 1000 字的不参评原因");
    if(db.update("update monthly_performance_member set participating=?,exclusion_reason=? where group_id=? and employee_id=?",participating,participating?null:reason.trim(),id,employee)!=1)throw new BusinessException(404,"参评名单中没有该员工");
    db.update("update monthly_performance_group set generation=generation+1 where id=?",id);
  }

  public long reopen(long id,String reason){
    if(reason==null||reason.isBlank()||reason.length()>1000)throw new BusinessException(400,"请填写不超过 1000 字的重开原因");
    var old=group(id);lockBatch(number(old.get("talent_batch_id")));lockGroup(id);old=group(id);
    if(!"PUBLISHED".equals(old.get("status")))throw new BusinessException(409,"只能重开当前正式绩效版本");
    return ensureRevision(old,reason);
  }

  private long ensureRevision(Map<String,Object> old,String reason){
    long batch=number(old.get("talent_batch_id"));lockBatch(batch);
    var existing=db.queryForList("select id from monthly_performance_group where talent_batch_id=? and period_month=? and status='DRAFT'",batch,month(old).atDay(1));
    if(!existing.isEmpty())return number(existing.get(0).get("id"));
    int version=((Number)old.get("version")).intValue()+1;
    db.update("insert into monthly_performance_group(talent_batch_id,period_month,version,ratio_a,ratio_b,ratio_c,reopen_reason,created_by) values(?,?,?,?,?,?,?,?)",batch,month(old).atDay(1),version,old.get("ratio_a"),old.get("ratio_b"),old.get("ratio_c"),reason,operator());
    long id=db.queryForObject("select last_insert_id()",Long.class);
    db.update("insert into monthly_performance_member(group_id,employee_id,employee_no,employee_name,participating,exclusion_reason) select ?,employee_id,employee_no,employee_name,participating,exclusion_reason from monthly_performance_member where group_id=?",id,old.get("id"));
    for(var person:members(id))if(yes(person.get("participating"))){
      long employee=number(person.get("employee_id"));
      var summary=db.queryForMap("select scheme_id from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED'",employee,month(old).toString());
      evaluation.generateMonth(employee,month(old),evaluation.schemeById(number(summary.get("scheme_id"))),reason);
    }
    return id;
  }

  /** Score changes reopen the entire frozen cohort and expand existing linked corrections. */
  public Map<Long,Set<YearMonth>> expandAffected(Map<Long,Set<YearMonth>> affected,String reason){
    var result=new TreeMap<Long,Set<YearMonth>>();affected.forEach((id,months)->result.put(id,new TreeSet<>(months)));
    var touched=new TreeSet<Long>();
    for(var entry:affected.entrySet())for(var m:entry.getValue())for(var g:db.queryForList("select g.* from monthly_performance_group g join monthly_performance_member p on p.group_id=g.id where p.employee_id=? and p.participating=true and g.period_month=? and g.status='PUBLISHED' order by g.id",entry.getKey(),m.atDay(1))){
      long id=ensureRevision(g,reason);if(!touched.add(id))continue;
      for(var person:members(id))if(yes(person.get("participating")))result.computeIfAbsent(number(person.get("employee_id")),x->new TreeSet<>()).add(m);
    }
    return result;
  }

  public Long grouped(long employee,YearMonth month){
    var rows=db.queryForList("select g.id from monthly_performance_group g join monthly_performance_member m on m.group_id=g.id where m.employee_id=? and m.participating=true and g.period_month=? and g.status in ('DRAFT','PUBLISHED') order by g.version desc limit 1",employee,month.atDay(1));return rows.isEmpty()?null:number(rows.get(0).get("id"));
  }
  public void requireIndividualPublication(long employee,YearMonth month){if(grouped(employee,month)!=null)throw new BusinessException(409,"该月已启用 ABC 绩效，请在月度评价的绩效划分中统一预览和发布");}
  public List<Long> revisionPerformanceGroups(long revision){return db.queryForList("select distinct g.id from evaluation_revision_member r join monthly_performance_member m on m.employee_id=r.employee_id and m.participating=true join monthly_performance_group g on g.id=m.group_id and g.period_month=str_to_date(concat(r.period_key,'-01'),'%Y-%m-%d') and g.status='DRAFT' where r.group_id=? order by g.id",Long.class,revision);}

  private Map<String,Object> refreshMember(Map<String,Object> g,Map<String,Object> member){
    var row=new LinkedHashMap<>(member);long employee=number(member.get("employee_id"));
    var old=db.queryForList("select m.final_score,m.grade from monthly_performance_member m join monthly_performance_group g on g.id=m.group_id where m.employee_id=? and g.talent_batch_id=? and g.period_month=? and g.status='PUBLISHED'",employee,g.get("talent_batch_id"),month(g).atDay(1));
    row.put("old_score",old.isEmpty()?null:old.get(0).get("final_score"));row.put("old_grade",old.isEmpty()?null:old.get(0).get("grade"));
    row.put("grade",null);row.put("rank_no",null);row.put("error",null);row.put("missing_items",null);
    if(!yes(member.get("participating"))){row.put("final_score",null);return row;}
    try{
      var official=db.queryForList("select scheme_id from score_summary where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED'",employee,month(g).toString());
      var scheme=official.isEmpty()?evaluation.schemeFor(employee,month(g)):evaluation.schemeById(number(official.get(0).get("scheme_id")));
      long summary=evaluation.generateMonth(employee,month(g),scheme,"绩效批次 #"+g.get("id"));
      var s=db.queryForMap("select id,version,final_score,missing_items,waiver_reason,revision_override_score,revision_group_id,cast(component_snapshot as char) component_snapshot from score_summary where id=?",summary);
      row.put("summary_id",summary);row.put("summary_version",s.get("version"));row.put("missing_items",s.get("missing_items"));row.put("waiver_reason",s.get("waiver_reason"));row.put("revision_override_score",s.get("revision_override_score"));row.put("revision_group_id",s.get("revision_group_id"));row.put("component_snapshot",s.get("component_snapshot"));
      row.put("final_score",s.get("revision_override_score")!=null?s.get("revision_override_score"):s.get("final_score"));
      if(!Objects.toString(s.get("missing_items"),"").isBlank()&&(s.get("revision_override_score")==null||Objects.toString(s.get("waiver_reason"),"").isBlank()))row.put("error","评分缺项，须补齐或由管理员核定");
      if(Arrays.asList(Objects.toString(s.get("missing_items"),"").split(",")).contains("DEFENSE"))row.put("error","请先发布答辩成绩");
      if(row.get("final_score")==null)row.put("error","缺少有效综合分");
    }catch(BusinessException ex){row.put("summary_id",null);row.put("final_score",null);row.put("error",ex.getMessage());}
    return row;
  }

  /** Traverse linked score revisions so a cross-month correction is never partially published. */
  public Map<String,Object> preview(long id){
    lockGroup(id);draft(id);var groupIds=new TreeSet<Long>();groupIds.add(id);var revisionIds=new TreeSet<Long>();
    var views=new TreeMap<Long,Map<String,Object>>();var extra=new TreeMap<Long,Map<String,Object>>();
    boolean added=true;
    while(added){
      added=false;
      for(long groupId:new TreeSet<>(groupIds))if(!views.containsKey(groupId)){
        lockGroup(groupId);var g=draft(groupId);var rows=new ArrayList<>(members(groupId).stream().map(m->refreshMember(g,m)).toList());g.put("members",rows);views.put(groupId,g);
        for(var row:rows)if(row.get("revision_group_id")!=null)if(revisionIds.add(number(row.get("revision_group_id"))))added=true;
      }
      for(long revision:revisionIds){
        for(var linked:db.queryForList("select employee_id,period_key,summary_type,summary_id,error_message from evaluation_revision_member where group_id=? order by employee_id,period_key",revision)){
          if(!"MONTH".equals(linked.get("summary_type")))throw new BusinessException(409,"关联修订包含已停止的季度评价，请先处理");
          long employee=number(linked.get("employee_id"));YearMonth m=YearMonth.parse(linked.get("period_key").toString());Long related=grouped(employee,m);
          if(related!=null){var relatedGroup=group(related);if("PUBLISHED".equals(relatedGroup.get("status")))related=ensureRevision(relatedGroup,"关联绩效修订");if(groupIds.add(related))added=true;}
          else if(linked.get("summary_id")!=null){
            long summary=number(linked.get("summary_id"));evaluation.refreshDraft(summary);
            extra.put(summary,db.queryForMap("select s.id summary_id,s.employee_id,s.period_key,s.version summary_version,coalesce(s.revision_override_score,s.final_score) final_score,s.missing_items,s.waiver_reason,s.revision_override_score,cast(s.component_snapshot as char) component_snapshot,e.name employee_name,e.employee_no from score_summary s join employee e on e.id=s.employee_id where s.id=?",summary));
          }else{var error=new LinkedHashMap<>(linked);error.put("error",Objects.toString(linked.get("error_message"),"关联月评尚未生成"));extra.put(-employee,error);}
        }
      }
    }
    var blockers=new ArrayList<String>();
    for(var g:views.values()){
      @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)g.get("members");
      var included=rows.stream().filter(m->yes(m.get("participating"))).toList();g.put("participant_count",included.size());
      if(included.isEmpty())blockers.add(g.get("batch_name")+" "+month(g)+" 没有参评人员");
      for(var row:included)if(row.get("error")!=null)blockers.add(row.get("employee_name")+"："+row.get("error"));
      boolean complete=included.stream().allMatch(m->m.get("error")==null&&m.get("final_score")!=null);
      var distribution=PerformanceGrading.allocate(complete?included.stream().map(m->new PerformanceGrading.Score(number(m.get("employee_id")),(BigDecimal)m.get("final_score"))).toList():List.of(),ratios(g));
      var byEmployee=new HashMap<Long,PerformanceGrading.Placement>();distribution.placements().forEach(p->byEmployee.put(p.employeeId(),p));
      for(var row:rows){var placement=byEmployee.get(number(row.get("employee_id")));if(placement!=null){row.put("grade",placement.grade());row.put("rank_no",placement.rank());}}
      // Targets remain meaningful even while some scores are incomplete.
      var target=PerformanceGrading.allocate(included.stream().map(m->new PerformanceGrading.Score(number(m.get("employee_id")),BigDecimal.ZERO)).toList(),ratios(g)).targets();
      g.put("targets",target);g.put("actual",complete?distribution.actual():null);g.put("tie_expanded",complete&&!target.equals(distribution.actual()));
      rows.sort(Comparator.<Map<String,Object>,Boolean>comparing(m->!yes(m.get("participating"))).thenComparing(m->(BigDecimal)m.get("final_score"),Comparator.nullsLast(Comparator.reverseOrder())).thenComparingLong(m->number(m.get("employee_id"))));
    }
    for(var row:extra.values())if(row.get("error")!=null||row.get("final_score")==null||(!Objects.toString(row.get("missing_items"),"").isBlank()&&(row.get("revision_override_score")==null||Objects.toString(row.get("waiver_reason"),"").isBlank())))blockers.add(row.get("employee_name")+"：关联月评缺项");
    var result=new LinkedHashMap<String,Object>();result.put("groups",new ArrayList<>(views.values()));result.put("revisionMembers",new ArrayList<>(extra.values()));result.put("revisionIds",new ArrayList<>(revisionIds));result.put("blockingMessages",blockers);result.put("canPublish",blockers.isEmpty());result.put("reviewToken",token(result));return result;
  }

  public void waive(long id,long employee,String reason,BigDecimal score){
    if(!Set.of("ADMIN","SUPER_ADMIN").contains(SecurityUtils.current().role()))throw new BusinessException(403,"仅管理员可核定缺项结果");
    ScoreValues.require(score,new BigDecimal("100"));if(reason==null||reason.isBlank()||reason.length()>1000)throw new BusinessException(400,"请填写不超过 1000 字的核定原因");
    lockGroup(id);var g=draft(id);var member=members(id).stream().filter(m->number(m.get("employee_id"))==employee&&yes(m.get("participating"))).findFirst().orElseThrow(()->new BusinessException(404,"参评人员不存在"));
    var row=refreshMember(g,member);if(row.get("summary_id")==null)throw new BusinessException(400,"请先配置评分方案");
    if(Arrays.asList(Objects.toString(row.get("missing_items"),"").split(",")).contains("DEFENSE"))throw new BusinessException(400,"请先发布答辩成绩");
    if(Objects.toString(row.get("missing_items"),"").isBlank())throw new BusinessException(400,"无缺项的成绩请通过分项评分调整");
    db.update("update score_summary set revision_override_score=?,waiver_reason=?,final_score=? where id=? and status='DRAFT'",score,reason.trim(),score,row.get("summary_id"));
    db.update("update monthly_performance_group set generation=generation+1 where id=?",id);
  }

  @SuppressWarnings("unchecked")
  public void publish(long id,String reviewToken){
    var first=preview(id);var groups=(List<Map<String,Object>>)first.get("groups");
    for(long batch:groups.stream().map(g->number(g.get("talent_batch_id"))).distinct().sorted().toList())lockBatch(batch);
    var employees=new TreeSet<Long>();for(var g:groups)for(var row:(List<Map<String,Object>>)g.get("members"))employees.add(number(row.get("employee_id")));
    for(var row:(List<Map<String,Object>>)first.get("revisionMembers"))employees.add(number(row.get("employee_id")));
    for(long employee:employees)db.queryForMap("select id from employee where id=? for update",employee);
    for(var g:groups)lockGroup(number(g.get("id")));
    var fresh=preview(id);
    if(reviewToken==null||!reviewToken.equals(fresh.get("reviewToken")))throw new BusinessException(409,"比例、名单或评分来源已变化，请重新预览并核对");
    if(!Boolean.TRUE.equals(fresh.get("canPublish")))throw new BusinessException(400,"仍有缺项或无参评人员，请先处理");
    for(var g:(List<Map<String,Object>>)fresh.get("groups")){
      for(var row:(List<Map<String,Object>>)g.get("members")){
        if(yes(row.get("participating"))){publishSummary(number(row.get("summary_id")));db.update("update monthly_performance_member set summary_id=?,summary_version=?,final_score=?,rank_no=?,grade=? where group_id=? and employee_id=?",row.get("summary_id"),row.get("summary_version"),row.get("final_score"),row.get("rank_no"),row.get("grade"),g.get("id"),row.get("employee_id"));}
      }
      db.update("update monthly_performance_group set status='SUPERSEDED' where talent_batch_id=? and period_month=? and status='PUBLISHED'",g.get("talent_batch_id"),month(g).atDay(1));
      var snapshot=new LinkedHashMap<String,Object>();snapshot.put("targets",g.get("targets"));snapshot.put("actual",g.get("actual"));snapshot.put("tie_expanded",g.get("tie_expanded"));
      if(db.update("update monthly_performance_group set status='PUBLISHED',participant_count=?,distribution_snapshot=cast(? as json),published_at=now() where id=? and status='DRAFT'",g.get("participant_count"),encode(snapshot),g.get("id"))!=1)throw new BusinessException(409,"绩效版本已变化");
    }
    for(var row:(List<Map<String,Object>>)fresh.get("revisionMembers"))publishSummary(number(row.get("summary_id")));
    for(long revision:(List<Long>)fresh.get("revisionIds"))db.update("update evaluation_revision_group set status='PUBLISHED',published_at=now(),reviewed_generation=generation where id=? and status='DRAFT'",revision);
  }

  private void publishSummary(long id){
    var s=db.queryForMap("select * from score_summary where id=? and status='DRAFT' for update",id);
    if("HISTORICAL".equals(s.get("source_type")))throw new BusinessException(409,"历史导入成绩不可通过绩效流程发布");
    db.update("update score_summary set status='SUPERSEDED' where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED'",s.get("employee_id"),s.get("period_key"));
    if(db.update("update score_summary set status='PUBLISHED',final_score=coalesce(revision_override_score,final_score),published_at=now() where id=? and status='DRAFT'",id)!=1)throw new BusinessException(409,"成绩版本已变化");
  }

  public void decorateSummary(Map<String,Object> row){
    var grades=db.queryForList("select g.id performance_group_id,g.version performance_version,m.grade performance_grade,m.participating performance_participating from monthly_performance_member m join monthly_performance_group g on g.id=m.group_id where m.summary_id=? and g.status in ('PUBLISHED','SUPERSEDED') order by g.version desc limit 1",row.get("id"));
    if(!grades.isEmpty())row.putAll(grades.get(0));
    long employee=number(row.get("employee_id"));YearMonth m=YearMonth.parse(row.get("period_key").toString());Long group=grouped(employee,m);row.put("performance_managed",group!=null);
    if(group!=null)row.put("performance_batch_id",group(group).get("talent_batch_id"));
    row.put("performance_pending",db.queryForObject("select count(*) from monthly_performance_group g join monthly_performance_member p on p.group_id=g.id where p.employee_id=? and g.period_month=? and g.status='DRAFT' and g.version>1",Integer.class,employee,m.atDay(1))>0);
  }
}
