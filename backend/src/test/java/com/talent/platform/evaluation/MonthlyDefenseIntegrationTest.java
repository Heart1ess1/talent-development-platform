package com.talent.platform.evaluation;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named="DEFENSE_TEST_DB_URL",matches=".+")
class MonthlyDefenseIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry p){
    p.add("spring.datasource.url",()->System.getenv("DEFENSE_TEST_DB_URL"));
    p.add("app.bootstrap.demo-users-enabled",()->false);
  }
  @Autowired JdbcTemplate db;
  @Autowired DefenseTaskController tasks;
  @Autowired EvaluationController evaluations;
  @Autowired EvaluationService evaluation;
  @Autowired EvaluationRevisionService revisions;
  @Autowired PermissionService permissions;
  long admin,batch,employee,second,scheme;
  YearMonth month=YearMonth.of(2027,1);
  String tag;
  void login(long id){RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(permissions.load(id),null,List.of()));}
  long last(){return db.queryForObject("select last_insert_id()",Long.class);}
  long person(String suffix){db.update("insert into employee(employee_no,name,batch_id,status) values(?,?,?,'ACTIVE')",tag+suffix,tag+suffix,batch);return last();}
  @BeforeEach void fixture(){
    tag="md-"+UUID.randomUUID().toString().substring(0,8);
    db.update("insert into sys_user(username,display_name,password_hash,role,must_change_password) values(?,?,'unused','ADMIN',false)",tag,tag);admin=last();login(admin);
    db.update("insert into talent_batch(name) values(?)",tag);batch=last();employee=person("-01");second=person("-02");
    db.update("insert into score_scheme(batch_id,version,status,effective_month,exam_enabled,task_enabled,mentor_enabled,station_enabled,training_enabled,defense_enabled,exam_weight,task_weight,mentor_weight,station_weight,training_weight,defense_weight,defense_scope,created_by) values(?,1,'PUBLISHED',?,false,false,false,false,false,true,0,0,0,0,0,100,'MONTH',?)",batch,month.atDay(1),admin);scheme=last();
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
  DefenseTaskController.TaskRequest form(String name,List<Long> people,int version){return new DefenseTaskController.TaskRequest(name,batch,month,new BigDecimal("120.50"),month.atDay(9),null,people,version);}
  long task(){return tasks.create(form(tag,List.of(employee),0)).data();}
  void activate(long id){tasks.action(id,"activate",new DefenseTaskController.VersionRequest(0));}
  long grade(long id,String score,Long prior){return tasks.saveGrade(id,new DefenseTaskController.GradeRequest(employee,new BigDecimal(score),"事实意见",prior,prior==null?null:"修订原因")).data();}
  int count(String sql,Object...a){return db.queryForObject(sql,Integer.class,a);}
  @SuppressWarnings("unchecked") Map<String,Object> component(){return ((List<Map<String,Object>>)evaluation.monthlyDetail(employee,month).get("components")).stream().filter(c->c.get("code").equals("DEFENSE")).findFirst().orElseThrow();}
  @Test void taskLifecycleLocksAndReleasesMonth(){
    long id=task();assertThatThrownBy(()->tasks.create(form("冲突",List.of(employee),0))).isInstanceOf(BusinessException.class).hasMessageContaining("冲突");
    tasks.update(id,form("草稿改名",List.of(employee,second),0));assertThatThrownBy(()->activate(id)).hasMessageContaining("刷新");
    tasks.action(id,"activate",new DefenseTaskController.VersionRequest(1));
    assertThatThrownBy(()->tasks.update(id,form("不可改",List.of(second),2))).hasMessageContaining("草稿");
    long g=grade(id,"0",null);tasks.action(id,"cancel",new DefenseTaskController.VersionRequest(2));
    assertThatThrownBy(()->tasks.publishGrade(g)).hasMessageContaining("启用");
    assertThat(tasks.create(form("释放后重建",List.of(employee),0)).data()).isPositive();
  }
  @Test void deleteDraftRemovesForeignKeyChildren(){long id=task();tasks.delete(id,0);assertThat(count("select count(*) from defense_task_participant where task_id=?",id)).isZero();}
  @Test void candidatesExcludeCurrentTaskButReportOthers(){
    long id=task();var rows=(List<?>)tasks.employees(batch,month,id,null,null,null,null).data();
    assertThat(rows).hasSize(2);assertThat(rows.toString()).doesNotContain("conflict_task_name="+tag);
    var conflict=(List<?>)tasks.employees(batch,month,null,null,null,null,null).data();assertThat(conflict.toString()).contains("conflict_task_name="+tag);
  }
  @Test void concurrentCreationHasOneWinner()throws Exception{
    var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    Callable<Boolean> run=()->{login(admin);start.await();try{tasks.create(form("并发",List.of(employee),0));return true;}catch(Exception ex){return false;}finally{clear();}};
    var a=pool.submit(run);var b=pool.submit(run);start.countDown();int wins=(a.get()?1:0)+(b.get()?1:0);pool.shutdown();
    assertThat(wins).isOne();assertThat(count("select count(*) from defense_task_participant where employee_id=? and active_flag=1",employee)).isOne();
  }
  @Test void missingZeroAndNonHundredFullScore(){
    assertThat(component()).containsEntry("status","PENDING").containsEntry("missingReason","当月未创建答辩任务");
    long id=task();activate(id);long g=grade(id,"0",null);tasks.publishGrade(g);
    assertThat((BigDecimal)evaluation.monthlyDetail(employee,month).get("finalScore")).isEqualByComparingTo("0.00");
    long revision=grade(id,"102.43",g);tasks.publishGrade(revision);
    assertThat((BigDecimal)evaluation.monthlyDetail(employee,month).get("finalScore")).isEqualByComparingTo("85.00");
    db.update("update score_scheme set defense_enabled=false,defense_weight=0,task_enabled=true,task_weight=100 where id=?",scheme);
    assertThat(component()).containsEntry("recordedButDisabled",true).containsEntry("status","DISABLED");
    assertThatThrownBy(()->tasks.action(id,"cancel",new DefenseTaskController.VersionRequest(1))).hasMessageContaining("正式成绩");
  }
  MockMultipartFile excel(List<List<String>> rows)throws Exception{
    var out=new ByteArrayOutputStream();EasyExcel.write(out).head(List.of(List.of("工号"),List.of("姓名"),List.of("答辩成绩"),List.of("评价意见"))).sheet("答辩成绩").doWrite(rows);
    return new MockMultipartFile("file","score.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
  }
  @SuppressWarnings("unchecked") Map<String,Object> upload(long id,List<List<String>> rows)throws Exception{return (Map<String,Object>)tasks.upload(id,false,null,excel(rows)).data();}
  @Test void importCorrectionIdempotencyAndAtomicPublication()throws Exception{
    long id=task();activate(id);var file=excel(List.of(List.of(tag+"-01",tag+"-01","", "空成绩")));
    var imp=(Map<?,?>)tasks.upload(id,false,null,file).data();long b=((Number)imp.get("id")).longValue();
    assertThat(((Map<?,?>)tasks.upload(id,false,null,file).data()).get("id")).isEqualTo(b);
    assertThatThrownBy(()->tasks.publishImport(id,b)).hasMessageContaining("错误");
    var rows=(List<Map<String,Object>>)imp.get("rows");long row=((Number)rows.get(0).get("id")).longValue();
    tasks.editImport(id,b,row,Map.of("employee_no",tag+"-01","employee_name",tag+"-01","raw_score","0","comment","有效零分"));
    tasks.publishImport(id,b);assertThat(count("select count(*) from defense_result where task_id=? and status='PUBLISHED'",id)).isOne();
    assertThatThrownBy(()->tasks.publishImport(id,b)).hasMessageContaining("已发布");
    // Same binary in another task must be independent, not caught by the legacy batch/month index.
    long other=tasks.create(form("另一任务",List.of(second),0)).data();activate(other);
    assertThat(((Map<?,?>)tasks.upload(other,false,null,file).data()).get("id")).isNotEqualTo(b);
  }
  @Test void duplicateEmployeeAndNameRangeValidation()throws Exception{
    long id=task();activate(id);var imp=upload(id,List.of(List.of(tag+"-01","错误姓名","121",""),List.of(tag+"-01",tag+"-01","5",""),List.of("000000","陌生员工","0","")));
    assertThat(imp.toString()).contains("姓名与工号不一致","重复工号","不在任务参与人员中");
    assertThatThrownBy(()->tasks.publishImport(id,((Number)imp.get("id")).longValue())).hasMessageContaining("错误");
    assertThat(count("select count(*) from defense_result where task_id=? and status='PUBLISHED'",id)).isZero();
  }
  @Test void revisionOnlyCreatesMonthlyDraftAndRejectsStaleToken(){
    long id=task();activate(id);long g=grade(id,"60.25",null);tasks.publishGrade(g);
    long summary=evaluation.generateMonth(employee,month,evaluation.schemeById(scheme),null);
    evaluations.publish(summary,new EvaluationController.PublishRequest(null,null));
    long revision=grade(id,"102.43",g);tasks.publishGrade(revision);
    assertThat(db.queryForObject("select final_score from score_summary where id=?",BigDecimal.class,summary)).isEqualByComparingTo("50");
    assertThat(count("select count(*) from evaluation_revision_member where employee_id=? and summary_type='QUARTER'",employee)).isZero();
    long group=db.queryForObject("select group_id from evaluation_revision_member where employee_id=?",Long.class,employee);
    var inspected=revisions.refresh(group);String token=inspected.get("reviewToken").toString();
    long revision2=grade(id,"102.44",revision);tasks.publishGrade(revision2);
    assertThatThrownBy(()->revisions.publish(group,token)).hasMessageContaining("来源已变化");
    revisions.publish(group,revisions.refresh(group).get("reviewToken").toString());
    assertThat(count("select count(*) from score_summary where employee_id=? and status='PUBLISHED'",employee)).isOne();
  }
  @Test void oldPublishedSchemesKeepLegacyInterpretation(){
    db.update("update score_scheme set monthly_rule_version='LEGACY',defense_scope='QUARTER',task_enabled=true,task_weight=100,defense_weight=20 where id=?",scheme);
    assertThat(((List<?>)evaluation.monthlyDetail(employee,month).get("components")).toString()).doesNotContain("code=DEFENSE");
  }
  @Test void taskPermissionsAndLegacyQuarterPublishAreClosed(){
    long id=task();db.update("insert into score_summary(employee_id,summary_type,period_key,version,scheme_id) values(?,'QUARTER','2027-Q1',1,?)",employee,scheme);long summary=last();
    assertThatThrownBy(()->evaluations.publish(summary,new EvaluationController.PublishRequest(null,null))).hasMessageContaining("季度");
    db.update("insert into sys_user(username,display_name,password_hash,role,must_change_password) values(?,?,'unused','MENTOR',false)",tag+"mentor",tag);long mentor=last();login(mentor);
    assertThatThrownBy(()->tasks.grades(id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
  @Test void legacyAndTaskScoresCanCoexistWithoutBeingMixed(){
    db.update("insert into defense_result(employee_id,employee_no,employee_name,period_month,score,full_score,status,created_by) values(?,?,?,?,99,100,'PUBLISHED',?)",employee,tag+"-01",tag+"-01",month.atDay(1),admin);
    long id=task();activate(id);tasks.publishGrade(grade(id,"0",null));
    assertThat(count("select count(*) from defense_result where employee_id=? and status='PUBLISHED'",employee)).isEqualTo(2);
    assertThat((BigDecimal)evaluation.monthlyDetail(employee,month).get("finalScore")).isEqualByComparingTo("0");
  }
  @Test void importRevisionRejectsChangedSourceBeforeBatchPublication()throws Exception{
    long id=task();activate(id);long g=grade(id,"60",null);tasks.publishGrade(g);
    var imp=(Map<?,?>)tasks.upload(id,true,"Excel修订",excel(List.of(List.of(tag+"-01",tag+"-01","75","")))).data();
    tasks.publishGrade(grade(id,"70",g));
    assertThatThrownBy(()->tasks.publishImport(id,((Number)imp.get("id")).longValue())).hasMessageContaining("错误");
    assertThat(db.queryForObject("select score from defense_result where task_id=? and status='PUBLISHED'",BigDecimal.class,id)).isEqualByComparingTo("70");
  }
  @Test void monthTasksAreNotLimitedToFourAndSupportYearBoundary(){
    for(int i=0;i<6;i++)tasks.create(new DefenseTaskController.TaskRequest(tag+i,batch,month.plusMonths(i),new BigDecimal("100"),null,null,List.of(employee),0));
    assertThat(count("select count(*) from defense_task where talent_batch_id=?",batch)).isEqualTo(6);
  }

}
