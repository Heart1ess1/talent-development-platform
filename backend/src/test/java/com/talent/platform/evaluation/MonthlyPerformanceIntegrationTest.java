package com.talent.platform.evaluation;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs migrations and transactional publication against a disposable MySQL 8 database. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ABC_TEST_DB_URL",matches=".+")
class MonthlyPerformanceIntegrationTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",()->System.getenv("ABC_TEST_DB_URL"));p.add("app.bootstrap.demo-users-enabled",()->false);}
  @MockitoSpyBean JdbcTemplate db;
  @MockitoSpyBean AuditService audit;
  @Autowired MonthlyPerformanceService service;
  @Autowired EvaluationRevisionService revisions;
  @Autowired PermissionService permissions;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  long operator,mentor,batch,scheme;String tag;
  YearMonth month=YearMonth.of(2026,10);
  List<Long> people;
  BigDecimal d(String x){return new BigDecimal(x);}
  long insert(String sql,Object...args){var key=new GeneratedKeyHolder();db.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return key.getKey().longValue();}
  long user(String role){return insert("insert into sys_user(username,display_name,password_hash,role,must_change_password) values(?,?,'unused',?,false)",tag+role+UUID.randomUUID().toString().substring(0,6),tag+role,role);}
  void login(long id){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(permissions.load(id),null,List.of()));}
  @BeforeEach void fixture(){
    tag="abc"+UUID.randomUUID().toString().substring(0,8);operator=user("SUPER_ADMIN");mentor=user("MENTOR");login(operator);
    batch=insert("insert into talent_batch(name) values(?)",tag);
    scheme=insert("insert into score_scheme(batch_id,version,status,effective_month,exam_weight,task_weight,mentor_weight,station_weight,training_weight,exam_enabled,task_enabled,mentor_enabled,station_enabled,training_enabled,created_by) values(?,1,'PUBLISHED','2026-01-01',0,0,100,0,0,false,false,true,false,false,?)",batch,operator);
    people=new ArrayList<>();
  }
  @AfterEach void logout(){SecurityContextHolder.clearContext();}
  long employee(String score){
    long id=insert("insert into employee(employee_no,name,batch_id,mentor_user_id,status) values(?,?,?,?,'ACTIVE')",tag+people.size(),"绩效员工"+people.size(),batch,mentor);people.add(id);
    if(score!=null)db.update("insert into monthly_evaluation(employee_id,period_month,evaluator_type,evaluator_user_id,scope_id,score,comment) values(?,'2026-10-01','MENTOR',?,0,?,'验收评分')",id,mentor,d(score));return id;
  }
  long configure(){return service.save(batch,month,d("20"),d("70"),d("10"));}
  @SuppressWarnings("unchecked") Map<String,Object> primary(Map<String,Object> preview){return ((List<Map<String,Object>>)preview.get("groups")).get(0);}
  @SuppressWarnings("unchecked") List<Map<String,Object>> members(Map<String,Object> preview){return (List<Map<String,Object>>)primary(preview).get("members");}
  String review(Map<String,Object> preview){return preview.get("reviewToken").toString();}
  int count(String sql,Object...args){return db.queryForObject(sql,Integer.class,args);}
  void publish(long id){service.publish(id,review(service.preview(id)));}

  @Test void wholeCohortPublishesTiesSnapshotsAndSpreadsheet()throws Exception{
    for(String score:List.of("100","90","90","80","70","60","50","40","30","0"))employee(score);
    long id=configure();var preview=service.preview(id);
    assertThat(primary(preview).get("targets")).isEqualTo(List.of(2,7,1));assertThat(primary(preview).get("actual")).isEqualTo(List.of(3,6,1));
    service.publish(id,review(preview));assertThat(count("select count(*) from score_summary where employee_id in (select employee_id from monthly_performance_member where group_id=?) and status='PUBLISHED'",id)).isEqualTo(10);
    assertThat(db.queryForObject("select grade from monthly_performance_member where group_id=? and employee_id=?",String.class,id,people.get(9))).isEqualTo("C");
    var auth=SecurityContextHolder.getContext().getAuthentication();
    mvc.perform(get("/api/v1/evaluation/performance/{id}/export",id).with(authentication(auth))).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    mvc.perform(get("/api/v1/employees/{id}/portrait/evaluations",people.get(0)).with(authentication(auth))).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].performanceGrade").value("A")).andExpect(jsonPath("$.data.records[0].performanceVersion").value(1));
    assertThatThrownBy(()->service.publish(id,review(preview))).isInstanceOf(BusinessException.class);
    assertThatThrownBy(()->service.participation(id,people.get(0),false,"test")).isInstanceOf(BusinessException.class);
  }

  @Test void exclusionChangesDenominatorAndMissingScoreIsNotZero(){
    long missing=employee(null);employee("0");long id=configure();
    assertThat(service.preview(id).get("canPublish")).isEqualTo(false);
    assertThatThrownBy(()->service.participation(id,missing,false," ")).isInstanceOf(BusinessException.class);
    service.participation(id,missing,false,"当月未参加培养");var p=service.preview(id);
    assertThat(primary(p).get("participant_count")).isEqualTo(1);assertThat(primary(p).get("actual")).isEqualTo(List.of(0,1,0));publish(id);
    assertThat(count("select count(*) from score_summary where employee_id=? and status='PUBLISHED'",missing)).isZero();
    assertThat(db.queryForObject("select grade from monthly_performance_member where group_id=? and employee_id=?",String.class,id,missing)).isNull();
  }

  @Test void staleScoreRatioAndRosterPreviewsCannotPublish(){
    long employee=employee("80");long id=configure();String stale=review(service.preview(id));
    db.update("update monthly_evaluation set score=90 where employee_id=?",employee);
    assertThatThrownBy(()->service.publish(id,stale)).isInstanceOf(BusinessException.class).hasMessageContaining("重新预览");
    String oldToken=review(service.preview(id));
    service.save(batch,month,d("30"),d("60"),d("10"));assertThatThrownBy(()->service.publish(id,oldToken)).isInstanceOf(BusinessException.class);
    String beforeRoster=review(service.preview(id));employee("75");service.refreshRoster(id);assertThatThrownBy(()->service.publish(id,beforeRoster)).isInstanceOf(BusinessException.class);
    assertThat(count("select count(*) from score_summary where scheme_id=? and status='PUBLISHED'",scheme)).isZero();
  }

  @Test void onlyAdministratorCanWaiveAndZeroIsAValidOverride(){
    long e=employee(null);long id=configure();long trainer=user("TRAINING_ADMIN");login(trainer);
    assertThatThrownBy(()->service.waive(id,e,"缺项核定",d("0"))).isInstanceOf(BusinessException.class).hasMessageContaining("管理员");
    login(operator);service.waive(id,e,"缺项核定",d("0"));var p=service.preview(id);assertThat(p.get("canPublish")).isEqualTo(true);service.publish(id,review(p));
    assertThat(db.queryForObject("select final_score from monthly_performance_member where group_id=? and employee_id=?",BigDecimal.class,id,e)).isEqualByComparingTo("0");
  }

  @Test void requiredDefenseCannotBeBypassedWithAnAdministrativeWaiver(){
    long e=employee("80");
    db.update("update score_scheme set mentor_weight=0,defense_enabled=true,defense_weight=100,monthly_rule_version='TASK_MONTH_V1' where id=?",scheme);
    long id=configure();assertThat(service.preview(id).get("canPublish")).isEqualTo(false);
    assertThatThrownBy(()->service.waive(id,e,"不能代替正式答辩",d("80"))).isInstanceOf(BusinessException.class).hasMessageContaining("请先发布答辩成绩");
  }

  @Test void publicationRollsBackIfItsAuditCannotBeWritten()throws Exception{
    employee("80");long id=configure();String token=review(service.preview(id));
    org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(audit).log(org.mockito.ArgumentMatchers.eq("PUBLISH_MONTHLY_PERFORMANCE"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    var auth=SecurityContextHolder.getContext().getAuthentication();
    mvc.perform(post("/api/v1/evaluation/performance/{id}/publish",id).with(authentication(auth)).contentType("application/json").content("{\"reviewToken\":\""+token+"\"}")).andExpect(status().is5xxServerError());
    assertThat(db.queryForObject("select status from monthly_performance_group where id=?",String.class,id)).isEqualTo("DRAFT");
    assertThat(count("select count(*) from score_summary where employee_id=? and status='PUBLISHED'",people.get(0))).isZero();
  }

  @Test void sourceRevisionRegradesEveryoneWhileOldFormalResultsRemain(){
    long first=employee("100"),second=employee("90");long id=service.save(batch,month,d("50"),d("50"),d("0"));publish(id);
    db.update("update monthly_evaluation set score=80 where employee_id=?",first);
    long revision=revisions.changed(Map.of(first,Set.of(month)),"修订评分");
    assertThat(service.revisionPerformanceGroups(revision)).isNotEmpty();
    long newId=((Number)service.list(batch,month).get(0).get("id")).longValue();
    var p=service.preview(newId);assertThat(members(p)).hasSize(2);
    assertThat(db.queryForObject("select grade from monthly_performance_member where group_id=? and employee_id=?",String.class,id,first)).isEqualTo("A");
    assertThatThrownBy(()->revisions.publish(revision,"unused")).isInstanceOf(BusinessException.class).hasMessageContaining("ABC");
    service.publish(newId,review(p));assertThat(db.queryForObject("select grade from monthly_performance_member where group_id=? and employee_id=?",String.class,newId,second)).isEqualTo("A");
    assertThat(db.queryForObject("select status from monthly_performance_group where id=?",String.class,id)).isEqualTo("SUPERSEDED");
    assertThat(db.queryForObject("select status from evaluation_revision_group where id=?",String.class,revision)).isEqualTo("PUBLISHED");
  }

  @Test void manualReopenPreservesRosterAndIndividualPublicationCannotBypass()throws Exception{
    long e=employee("80");long id=configure();publish(id);long next=service.reopen(id,"调整比例");
    assertThatThrownBy(()->service.refreshRoster(next)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(()->service.participation(next,e,false,"移出")).isInstanceOf(BusinessException.class);
    long summary=db.queryForObject("select id from score_summary where employee_id=? and status='DRAFT'",Long.class,e);
    mvc.perform(post("/api/v1/evaluation/summaries/{id}/publish",summary).contentType("application/json").content("{}").with(authentication(SecurityContextHolder.getContext().getAuthentication()))).andExpect(status().isConflict());
  }

  @Test void permissionsAndEmployeeResultDoNotExposeOtherMembers()throws Exception{
    long e=employee("80"),account=user("EMPLOYEE");db.update("update employee set user_id=? where id=?",account,e);employee("90");long id=configure();publish(id);login(account);
    var auth=SecurityContextHolder.getContext().getAuthentication();
    mvc.perform(get("/api/v1/evaluation/performance").param("batchId",Long.toString(batch)).param("month",month.toString()).with(authentication(auth))).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/evaluation/summaries").param("employeeId",Long.toString(e)).with(authentication(auth))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].performance_grade").isNotEmpty());
    mvc.perform(get("/api/v1/evaluation/summaries").param("employeeId",Long.toString(people.get(1))).with(authentication(auth))).andExpect(status().isForbidden());
  }

  @Test void cannotBackfillHistoricalOrAlreadyPublishedScores(){
    long e=employee("80");db.update("insert into score_summary(employee_id,summary_type,period_key,version,final_score,status,source_type) values(?,'MONTH','2026-10',1,80,'PUBLISHED','HISTORICAL')",e);
    assertThatThrownBy(this::configure).isInstanceOf(BusinessException.class).hasMessageContaining("正式成绩");
    assertThat(service.list(batch,month)).isEmpty();
  }

  @Test void concurrentPublishHasOnlyOneWinner()throws Exception{
    employee("80");employee("90");long id=configure();String review=review(service.preview(id));
    var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    Callable<Boolean> attempt=()->{login(operator);start.await();try{service.publish(id,review);return true;}catch(Exception expected){return false;}finally{SecurityContextHolder.clearContext();}};
    try{var a=pool.submit(attempt);var b=pool.submit(attempt);start.countDown();assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}finally{pool.shutdownNow();}
    assertThat(count("select count(*) from score_summary where scheme_id=? and status='PUBLISHED'",scheme)).isEqualTo(2);
  }

  @Test void databaseFailureRollsBackEveryPublishedMember(){
    employee("80");long fail=employee("90");long id=configure();String review=review(service.preview(id));
    long summary=db.queryForObject("select id from score_summary where employee_id=? and status='DRAFT'",Long.class,fail);
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("ABC rollback verification"))
      .when(db).update(org.mockito.ArgumentMatchers.eq("update score_summary set status='PUBLISHED',final_score=coalesce(revision_override_score,final_score),published_at=now() where id=? and status='DRAFT'"),org.mockito.ArgumentMatchers.eq(summary));
    assertThatThrownBy(()->service.publish(id,review)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(count("select count(*) from score_summary where scheme_id=? and status='PUBLISHED'",scheme)).isZero();
    assertThat(db.queryForObject("select status from monthly_performance_group where id=?",String.class,id)).isEqualTo("DRAFT");
  }

  @Test void crossMonthRevisionPublishesBothGradingGroupsTogether(){
    long e=employee("80");long october=configure();publish(october);
    YearMonth november=month.plusMonths(1);
    db.update("insert into monthly_evaluation(employee_id,period_month,evaluator_type,evaluator_user_id,scope_id,score,comment) values(?,'2026-11-01','MENTOR',?,0,90,'11月评分')",e,mentor);
    long next=service.save(batch,november,d("20"),d("70"),d("10"));publish(next);
    db.update("update monthly_evaluation set score=70 where employee_id=?",e);
    long revision=revisions.changed(Map.of(e,Set.of(month,november)),"跨月份修订");
    List<Long> groups=service.revisionPerformanceGroups(revision);assertThat(groups).hasSize(2);
    var preview=service.preview(groups.get(0));assertThat((List<?>)preview.get("groups")).hasSize(2);
    service.publish(groups.get(0),review(preview));
    assertThat(count("select count(*) from monthly_performance_group where id in (?,?) and status='PUBLISHED'",groups.get(0),groups.get(1))).isEqualTo(2);
    assertThat(count("select count(*) from score_summary where employee_id=? and status='PUBLISHED' and final_score=70",e)).isEqualTo(2);
  }

  @Test void emptyCohortCannotPublishAndNextMonthRequiresNewRatios(){
    long e=employee("80");long id=configure();service.participation(id,e,false,"本月不参评");
    var preview=service.preview(id);assertThat(preview.get("canPublish")).isEqualTo(false);
    assertThatThrownBy(()->service.publish(id,review(preview))).isInstanceOf(BusinessException.class);
    assertThat(service.list(batch,month.plusMonths(1))).isEmpty();
    assertThatThrownBy(()->service.save(batch,month,d("20.001"),d("69.999"),d("10"))).isInstanceOf(BusinessException.class);
  }

  @Test void rosterRefreshPreservesExclusionReasonsAndPreventsDoubleCohorts(){
    long e=employee("80");long id=configure();service.participation(id,e,false,"整月外派不参评");
    long otherBatch=insert("insert into talent_batch(name) values(?)",tag+"other");
    db.update("update employee set batch_id=? where id=?",otherBatch,e);service.refreshRoster(id);
    assertThat(db.queryForObject("select exclusion_reason from monthly_performance_member where group_id=? and employee_id=?",String.class,id,e)).isEqualTo("整月外派不参评");
    assertThatThrownBy(()->service.save(otherBatch,month,d("20"),d("70"),d("10"))).isInstanceOf(BusinessException.class).hasMessageContaining("其他绩效批次");
    assertThat(service.list(otherBatch,month)).isEmpty();
  }

  @Test void mentorAndStationScopesAndManagerReopenPermissions()throws Exception{
    long own=employee("80"),other=employee("90");
    long station=insert("insert into service_station(name) values(?)",tag+"station"),manager=user("STATION_MANAGER");
    db.update("update employee set station_id=? where id=?",station,own);
    db.update("insert into station_manager_scope(user_id,station_id) values(?,?)",manager,station);
    long id=configure();publish(id);
    long otherMentor=user("MENTOR");db.update("update employee set mentor_user_id=? where id=?",otherMentor,other);
    db.update("update monthly_evaluation set evaluator_user_id=? where employee_id=?",otherMentor,other);
    for(long reviewer:List.of(mentor,manager)){
      login(reviewer);var auth=SecurityContextHolder.getContext().getAuthentication();
      mvc.perform(get("/api/v1/evaluation/summaries").param("employeeId",Long.toString(own)).with(authentication(auth))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].performance_grade").isNotEmpty());
      mvc.perform(get("/api/v1/evaluation/summaries").param("employeeId",Long.toString(other)).with(authentication(auth))).andExpect(status().isForbidden());
      mvc.perform(get("/api/v1/evaluation/performance/{id}",id).with(authentication(auth))).andExpect(status().isForbidden());
    }
    login(user("TRAINING_ADMIN"));var trainerAuth=SecurityContextHolder.getContext().getAuthentication();
    mvc.perform(get("/api/v1/evaluation/performance/{id}",id).with(authentication(trainerAuth))).andExpect(status().isOk());
    mvc.perform(post("/api/v1/evaluation/performance/{id}/reopen",id).contentType("application/json").content("{\"reason\":\"调整比例\"}").with(authentication(trainerAuth))).andExpect(status().isForbidden());
    login(user("ADMIN"));var adminAuth=SecurityContextHolder.getContext().getAuthentication();
    mvc.perform(post("/api/v1/evaluation/performance/{id}/reopen",id).contentType("application/json").content("{\"reason\":\"调整比例\"}").with(authentication(adminAuth))).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
  }

  @Test void concurrentScoreTransactionInvalidatesPreviewWithoutPartialPublication()throws Exception{
    long e=employee("80");employee("90");long id=configure();String oldReview=review(service.preview(id));
    var scoreWritten=new CountDownLatch(1);var commitScore=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    try{
      var scoring=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
        db.queryForMap("select id from employee where id=? for update",e);
        db.update("update monthly_evaluation set score=70 where employee_id=?",e);scoreWritten.countDown();
        try{if(!commitScore.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Score commit timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
      }));
      assertThat(scoreWritten.await(20,TimeUnit.SECONDS)).isTrue();
      var publishing=pool.submit(()->{login(operator);try{return catchThrowable(()->service.publish(id,oldReview));}finally{SecurityContextHolder.clearContext();}});
      commitScore.countDown();scoring.get(30,TimeUnit.SECONDS);
      assertThat(publishing.get(30,TimeUnit.SECONDS)).isInstanceOf(BusinessException.class).hasMessageContaining("重新预览");
      assertThat(count("select count(*) from score_summary where scheme_id=? and status='PUBLISHED'",scheme)).isZero();
      assertThat(db.queryForObject("select status from monthly_performance_group where id=?",String.class,id)).isEqualTo("DRAFT");
    }finally{commitScore.countDown();pool.shutdownNow();}
  }
}
