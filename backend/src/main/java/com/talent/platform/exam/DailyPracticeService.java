package com.talent.platform.exam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.BusinessException;
import com.talent.platform.common.PageResult;
import com.talent.platform.schedule.TimeWindow;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.SecurityUtils;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 考试中心「每日随机推题」管理端服务。
 * 阶段二只负责计划侧：计划增删改查、发布校验、参与人快照、暂停恢复、排除日期。
 * 每日题目生成（阶段三）由 DailyPracticeScheduler 调用，本类不实现生成逻辑。
 */
@Service
public class DailyPracticeService {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> QUESTION_TYPES = Set.of("SINGLE", "MULTIPLE", "TRUE_FALSE");
  private static final Set<String> REPEAT_POLICIES = Set.of("ALLOW_REPEAT", "REDUCE_COUNT", "FAIL");
  private static final List<String> DEFAULT_TYPES = List.of("SINGLE", "MULTIPLE", "TRUE_FALSE");
  private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  public record PlanRequest(
      @NotBlank @Size(max = 128) String name,
      @Size(max = 500) String description,
      List<Long> bankIds,
      List<String> questionTypes,
      List<@Size(max = 32) String> tags,
      @NotNull @Min(1) @Max(200) Integer dailyCount,
      @NotNull @DecimalMin(value = "0.01") @Digits(integer = 3, fraction = 2) BigDecimal scorePerQuestion,
      @DecimalMin(value = "0") @Digits(integer = 3, fraction = 2) BigDecimal passScore,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @NotBlank String pushTime,
      @NotBlank String deadlineTime,
      Boolean randomizeOptions,
      @Pattern(regexp = "ALLOW_REPEAT|REDUCE_COUNT|FAIL") String repeatPolicy,
      Boolean includeWeekends,
      List<Long> batchIds,
      List<Long> businessUnitIds,
      List<Long> employeeIds,
      Integer version) {}

  public record ExclusionRequest(@NotNull LocalDate date, @Size(max = 200) String reason) {}

  public record ParticipantRequest(Boolean active) {}

  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final AuditService audit;
  /** 逐人生成时用独立事务：单个人员失败不影响其他人员，也不会留下半套题。 */
  private final TransactionTemplate tx;
  /** 过期标记用独立事务：即使外层因超时抛异常回滚，EXPIRED 状态也能落库。 */
  private final TransactionTemplate expireTx;

  public DailyPracticeService(JdbcTemplate db, ObjectMapper mapper, AuditService audit,
                              PlatformTransactionManager txManager) {
    this.db = db;
    this.mapper = mapper;
    this.audit = audit;
    this.tx = new TransactionTemplate(txManager);
    this.expireTx = new TransactionTemplate(txManager);
    this.expireTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  // ---------------------------------------------------------------- 计划列表/详情

  public List<Map<String, Object>> listPlans() {
    LocalDate today = TimeWindow.now().toLocalDate();
    var rows = db.queryForList("""
        select p.id,p.name,p.description,p.bank_ids,p.question_types,p.tags,p.daily_count,p.score_per_question,p.pass_score,
               date_format(p.start_date,'%Y-%m-%d') start_date,
               date_format(p.end_date,'%Y-%m-%d') end_date,
               date_format(p.push_time,'%H:%i:%s') push_time,
               date_format(p.deadline_time,'%H:%i:%s') deadline_time,
               p.timezone,p.randomize_options,p.repeat_policy,p.include_weekends,p.status,p.published_at,p.version,p.created_at,
               (select count(*) from exam_daily_participant x where x.plan_id=p.id and x.active=true) participant_count,
               (select count(*) from exam_daily_participant x where x.plan_id=p.id and x.active=false) stopped_count,
               (select group_concat(b.name order by b.name separator '、') from exam_question_bank b
                  where json_contains(p.bank_ids,json_array(b.id))) bank_names,
               (select r.status from exam_daily_job_run r where r.plan_id=p.id and r.practice_date=?) today_run_status,
               (select coalesce(sum(r.generated_count),0) from exam_daily_job_run r where r.plan_id=p.id and r.practice_date=?) today_generated,
               (select count(*) from exam_daily_session s where s.plan_id=p.id and s.practice_date=?) today_session_count,
               (select count(*) from exam_daily_session s where s.plan_id=p.id and s.practice_date=? and s.status='SUBMITTED') today_submitted_count
        from exam_daily_plan p order by p.id desc
        """, Date.valueOf(today), Date.valueOf(today), Date.valueOf(today), Date.valueOf(today));
    for (var row : rows) {
      List<Long> bankIds = parseLongs(row.get("bank_ids"));
      List<String> types = parseStrings(row.get("question_types"));
      List<String> tags = parseStrings(row.get("tags"));
      row.put("bank_ids", bankIds);
      row.put("question_types", types.isEmpty() ? DEFAULT_TYPES : types);
      row.put("tags", tags);
      row.put("available_questions", availableQuestions(bankIds, types, tags));
      row.put("today_status", todayStatus(row, today));
    }
    return rows;
  }

  public Map<String, Object> planDetail(Long id) {
    LocalDate today = TimeWindow.now().toLocalDate();
    var rows = db.queryForList("""
        select p.id,p.name,p.description,p.bank_ids,p.question_types,p.tags,p.daily_count,p.score_per_question,p.pass_score,
               date_format(p.start_date,'%Y-%m-%d') start_date,
               date_format(p.end_date,'%Y-%m-%d') end_date,
               date_format(p.push_time,'%H:%i:%s') push_time,
               date_format(p.deadline_time,'%H:%i:%s') deadline_time,
               p.timezone,p.randomize_options,p.repeat_policy,p.include_weekends,p.status,p.published_at,p.version,p.created_by,p.created_at,
               (select r.status from exam_daily_job_run r where r.plan_id=p.id and r.practice_date=?) today_run_status
        from exam_daily_plan p where p.id=?
        """, Date.valueOf(today), id);
    if (rows.isEmpty()) throw new BusinessException(404, "每日练习计划不存在");
    var plan = new LinkedHashMap<String, Object>(rows.get(0));
    List<Long> bankIds = parseLongs(plan.get("bank_ids"));
    List<String> types = parseStrings(plan.get("question_types"));
    List<String> tags = parseStrings(plan.get("tags"));
    plan.put("bank_ids", bankIds);
    plan.put("question_types", types.isEmpty() ? DEFAULT_TYPES : types);
    plan.put("tags", tags);
    plan.put("available_questions", availableQuestions(bankIds, types, tags));

    var targetRows = db.queryForList("select target_type,target_id from exam_daily_plan_target where plan_id=? order by target_type,target_id", id);
    List<Long> batchIds = new ArrayList<>(), businessUnitIds = new ArrayList<>(), employeeIds = new ArrayList<>();
    for (var target : targetRows) {
      String type = String.valueOf(target.get("target_type"));
      Long targetId = ((Number) target.get("target_id")).longValue();
      switch (type) {
        case "BATCH" -> batchIds.add(targetId);
        case "BUSINESS_UNIT" -> businessUnitIds.add(targetId);
        case "EMPLOYEE" -> employeeIds.add(targetId);
        // 班级/班级职务筛选已下线，历史 CLASS、CLASS_POSITION 行直接忽略，不参与新计划的范围解析。
        default -> { }
      }
    }
    plan.put("batch_ids", batchIds);
    plan.put("business_unit_ids", businessUnitIds);
    plan.put("employee_ids", employeeIds);
    plan.put("participants", participants(id));
    plan.put("exclusions", db.queryForList("""
        select date_format(exclude_date,'%Y-%m-%d') exclude_date,reason
        from exam_daily_plan_exclusion where plan_id=? order by exclude_date
        """, id));

    var todayPanel = new LinkedHashMap<String, Object>();
    todayPanel.put("practice_date", today.toString());
    todayPanel.put("status", todayStatus(rows.get(0), today));
    todayPanel.put("participant_count", activeParticipantCount(id));
    todayPanel.put("session_count", countInt("select count(*) from exam_daily_session where plan_id=? and practice_date=?", id, Date.valueOf(today)));
    todayPanel.put("submitted_count", countInt("select count(*) from exam_daily_session where plan_id=? and practice_date=? and status='SUBMITTED'", id, Date.valueOf(today)));
    var runRows = db.queryForList("select * from exam_daily_job_run where plan_id=? and practice_date=?", id, Date.valueOf(today));
    todayPanel.put("run", runRows.isEmpty() ? null : runRows.get(0));
    plan.put("today", todayPanel);
    return plan;
  }

  // ---------------------------------------------------------------- 计划增删改

  @Transactional
  public Long createPlan(PlanRequest q) {
    Normalized n = normalize(q);
    Long id = insertPlan(n);
    saveTargets(id, n);
    saveParticipants(id, n.employeeIds(), true);
    audit.log("CREATE_DAILY_PLAN", "EXAM_DAILY_PLAN", id, null, q);
    return id;
  }

  @Transactional
  public void updatePlan(Long id, PlanRequest q) {
    var rows = db.queryForList("select * from exam_daily_plan where id=? for update", id);
    if (rows.isEmpty()) throw new BusinessException(404, "每日练习计划不存在");
    var old = rows.get(0);
    if (q.version() != null && number(old.get("version")) != q.version())
      throw new BusinessException(409, "计划已被其他人修改，请刷新后重试");
    String status = String.valueOf(old.get("status"));
    if ("ENDED".equals(status)) throw new BusinessException(400, "已结束的计划不能再修改");
    boolean draft = "DRAFT".equals(status);
    Normalized n = normalize(q);
    if (!draft) {
      requireLockedFieldsUnchanged(old, n);
      LocalDate oldEnd = localDate(old.get("end_date"));
      if (n.endDate().isBefore(oldEnd))
        throw new BusinessException(400, "发布后只能延长结束日期，不能缩短或提前");
      if (!n.startDate().equals(localDate(old.get("start_date"))))
        throw new BusinessException(400, "发布后不允许修改开始日期");
    }
    int updated = db.update("""
        update exam_daily_plan set name=?,description=?,bank_ids=?,question_types=?,tags=?,daily_count=?,
               score_per_question=?,pass_score=?,start_date=?,end_date=?,push_time=?,deadline_time=?,
               randomize_options=?,repeat_policy=?,include_weekends=?,version=version+1 where id=?
        """, n.name(), n.description(), json(n.bankIds()), json(n.questionTypes()), json(n.tags()), n.dailyCount(),
        n.scorePerQuestion(), n.passScore(), Date.valueOf(n.startDate()), Date.valueOf(n.endDate()),
        Time.valueOf(n.pushTime()), Time.valueOf(n.deadlineTime()), n.randomizeOptions(), n.repeatPolicy(),
        n.includeWeekends(), id);
    if (updated == 0) throw new BusinessException(409, "计划已被其他人修改，请刷新后重试");
    saveTargets(id, n);
    if (draft) saveParticipants(id, n.employeeIds(), true);
    else appendParticipants(id, n.employeeIds());
    audit.log("UPDATE_DAILY_PLAN", "EXAM_DAILY_PLAN", id, old, q);
  }

  @Transactional
  public void deletePlan(Long id) {
    int deleted = db.update("delete from exam_daily_plan where id=? and status='DRAFT'", id);
    if (deleted == 0) throw new BusinessException(400, "仅草稿状态的每日练习计划可以删除");
    audit.log("DELETE_DAILY_PLAN", "EXAM_DAILY_PLAN", id, null, null);
  }

  // ---------------------------------------------------------------- 发布/暂停/恢复

  @Transactional
  public void publishPlan(Long id) {
    var rows = db.queryForList("select * from exam_daily_plan where id=? for update", id);
    if (rows.isEmpty()) throw new BusinessException(404, "每日练习计划不存在");
    var plan = rows.get(0);
    String status = String.valueOf(plan.get("status"));
    if (!"DRAFT".equals(status)) throw new BusinessException(400, "仅草稿状态的计划可以发布");
    LocalDate start = localDate(plan.get("start_date"));
    LocalDate end = localDate(plan.get("end_date"));
    LocalDate today = TimeWindow.now().toLocalDate();
    if (end.isBefore(today)) throw new BusinessException(400, "计划结束日期已过，请先调整结束日期再发布");
    requirePublishable(plan);
    db.update("""
        update exam_daily_participant p join employee e on e.id=p.employee_id
        set p.active=false,p.left_at=now()
        where p.plan_id=? and p.active=true and (e.status<>'ACTIVE' or e.user_id is null)
        """, id);
    if (activeParticipantCount(id) == 0)
      throw new BusinessException(400, "计划没有可推送的在职员工，请先匹配并选择参与人");
    db.update("update exam_daily_plan set status='PUBLISHED',published_at=now(),version=version+1 where id=?", id);
    audit.log("PUBLISH_DAILY_PLAN", "EXAM_DAILY_PLAN", id, null,
        Map.of("startDate", start.toString(), "endDate", end.toString(), "participants", activeParticipantCount(id)));
  }

  @Transactional
  public void pausePlan(Long id) {
    int updated = db.update("update exam_daily_plan set status='PAUSED',version=version+1 where id=? and status='PUBLISHED'", id);
    if (updated == 0) throw new BusinessException(400, "仅已发布的计划可以暂停");
    audit.log("PAUSE_DAILY_PLAN", "EXAM_DAILY_PLAN", id, null, null);
  }

  @Transactional
  public void resumePlan(Long id) {
    var rows = db.queryForList("select * from exam_daily_plan where id=? for update", id);
    if (rows.isEmpty()) throw new BusinessException(404, "每日练习计划不存在");
    var plan = rows.get(0);
    if (!"PAUSED".equals(String.valueOf(plan.get("status"))))
      throw new BusinessException(400, "仅已暂停的计划可以恢复");
    LocalDate end = localDate(plan.get("end_date"));
    if (end.isBefore(TimeWindow.now().toLocalDate()))
      throw new BusinessException(400, "计划结束日期已过，无法恢复，请先延长结束日期");
    requirePublishable(plan);
    if (activeParticipantCount(id) == 0)
      throw new BusinessException(400, "计划没有可推送的在职员工，请先刷新参与人");
    db.update("update exam_daily_plan set status='PUBLISHED',version=version+1 where id=?", id);
    audit.log("RESUME_DAILY_PLAN", "EXAM_DAILY_PLAN", id, null, null);
  }

  // ---------------------------------------------------------------- 参与人

  public List<Map<String, Object>> candidates(String batchIds, String businessUnitIds, String keyword) {
    var batches = parseIds(batchIds, "批次");
    var businessUnits = parseIds(businessUnitIds, "板块");
    StringBuilder sql = new StringBuilder("""
        select e.id,e.employee_no,e.name,e.class_id,e.class_position_id,b.name batch_name,
               cls.label class_name,cp.label class_position_name,bu.name business_unit_name
        from employee e
        left join talent_batch b on b.id=e.batch_id
        left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
        left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION'
        left join business_unit bu on bu.id=e.business_unit_id
        where e.status='ACTIVE' and e.user_id is not null
        """);
    var args = new ArrayList<Object>();
    if (!batches.isEmpty()) { sql.append(" and e.batch_id in (").append(marks(batches.size())).append(")"); args.addAll(batches); }
    if (!businessUnits.isEmpty()) { sql.append(" and e.business_unit_id in (").append(marks(businessUnits.size())).append(")"); args.addAll(businessUnits); }
    if (keyword != null && !keyword.isBlank()) {
      sql.append(" and (e.name like ? or e.employee_no like ?)");
      String value = "%" + keyword.trim() + "%";
      args.add(value); args.add(value);
    }
    sql.append(" order by b.name,bu.name,e.employee_no,e.id");
    return db.queryForList(sql.toString(), args.toArray());
  }

  @Transactional
  public int refreshParticipants(Long id) {
    requirePlan(id);
    List<Long> matched = matchedEmployeeIds(id);
    if (matched.isEmpty())
      throw new BusinessException(400, "当前参考范围未匹配到在职且已开通账号的员工");
    int added = 0;
    for (Long employeeId : matched)
      added += db.update("insert ignore into exam_daily_participant(plan_id,employee_id,active,joined_at) values(?,?,true,now())", id, employeeId);
    audit.log("REFRESH_DAILY_PARTICIPANTS", "EXAM_DAILY_PLAN", id, null, Map.of("matched", matched.size(), "added", added));
    return added;
  }

  @Transactional
  public void updateParticipant(Long id, Long employeeId, boolean active) {
    var plans = db.queryForList("select status from exam_daily_plan where id=?", id);
    if (plans.isEmpty()) throw new BusinessException(404, "每日练习计划不存在");
    if ("ENDED".equals(String.valueOf(plans.get(0).get("status"))))
      throw new BusinessException(400, "已结束的计划不能调整参与人");
    int updated = db.update("update exam_daily_participant set active=?,left_at=? where plan_id=? and employee_id=?",
        active, active ? null : java.sql.Timestamp.valueOf(TimeWindow.now()), id, employeeId);
    if (updated == 0) throw new BusinessException(404, "该人员不在计划参与人名单中");
    audit.log(active ? "RESUME_DAILY_PARTICIPANT" : "STOP_DAILY_PARTICIPANT", "EXAM_DAILY_PLAN", id, null,
        Map.of("employeeId", employeeId, "active", active));
  }

  private List<Map<String, Object>> participants(Long planId) {
    return db.queryForList("""
        select p.employee_id,p.active,p.joined_at,p.left_at,e.employee_no,e.name,
               b.name batch_name,cls.label class_name,cp.label class_position_name,bu.name business_unit_name
        from exam_daily_participant p
        join employee e on e.id=p.employee_id
        left join talent_batch b on b.id=e.batch_id
        left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
        left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION'
        left join business_unit bu on bu.id=e.business_unit_id
        where p.plan_id=? order by p.active desc,e.employee_no,e.id
        """, planId);
  }

  private List<Long> matchedEmployeeIds(Long planId) {
    var targets = db.queryForList("select target_type,target_id from exam_daily_plan_target where plan_id=?", planId);
    Map<String, List<Long>> grouped = new HashMap<>();
    for (var target : targets)
      grouped.computeIfAbsent(String.valueOf(target.get("target_type")), k -> new ArrayList<>())
          .add(((Number) target.get("target_id")).longValue());
    List<Long> batches = grouped.getOrDefault("BATCH", List.of());
    List<Long> businessUnits = grouped.getOrDefault("BUSINESS_UNIT", List.of());
    List<Long> employees = grouped.getOrDefault("EMPLOYEE", List.of());
    LinkedHashSet<Long> result = new LinkedHashSet<>();
    boolean hasRange = !batches.isEmpty() || !businessUnits.isEmpty();
    if (hasRange) {
      StringBuilder sql = new StringBuilder("select e.id from employee e where e.status='ACTIVE' and e.user_id is not null");
      var args = new ArrayList<Object>();
      if (!batches.isEmpty()) { sql.append(" and e.batch_id in (").append(marks(batches.size())).append(")"); args.addAll(batches); }
      if (!businessUnits.isEmpty()) { sql.append(" and e.business_unit_id in (").append(marks(businessUnits.size())).append(")"); args.addAll(businessUnits); }
      result.addAll(db.queryForList(sql.toString(), Long.class, args.toArray()));
    }
    if (!employees.isEmpty()) {
      String sql = "select e.id from employee e where e.status='ACTIVE' and e.user_id is not null and e.id in (" + marks(employees.size()) + ")";
      result.addAll(db.queryForList(sql, Long.class, employees.toArray()));
    }
    return new ArrayList<>(result);
  }

  // ---------------------------------------------------------------- 每日生成（阶段三）

  /**
   * 定时任务入口：扫描已发布且在有效期内的计划。
   * 周末/排除日期记 SKIPPED；未到推送时刻或已过当天截止时刻直接跳过；其余按 job_run 唯一键抢占后逐人生成。
   */
  public Map<String, Object> generateDueSessions() {
    LocalDateTime now = TimeWindow.now();
    LocalDate today = now.toLocalDate();
    var plans = db.queryForList("""
        select * from exam_daily_plan
        where status='PUBLISHED' and start_date<=? and end_date>=?
        order by id
        """, Date.valueOf(today), Date.valueOf(today));
    int plansRun = 0, generated = 0, failed = 0;
    for (var plan : plans) {
      Long planId = ((Number) plan.get("id")).longValue();
      try {
        if (!truthy(plan.get("include_weekends"))
            && (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY)) {
          markSkipped(planId, today, "周末不推送");
          continue;
        }
        if (isExcluded(planId, today)) {
          markSkipped(planId, today, "排除日期不推送");
          continue;
        }
        LocalDateTime pushAt = today.atTime(localTime(plan.get("push_time")));
        LocalDateTime deadlineAt = today.atTime(localTime(plan.get("deadline_time")));
        if (now.isBefore(pushAt) || !now.isBefore(deadlineAt)) continue;
        plansRun++;
        var result = generatePlanDay(plan, today, now, false);
        generated += intOf(result.get("generated"));
        failed += intOf(result.get("failed"));
      } catch (Exception e) {
        failed++;
      }
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("plans", plansRun);
    summary.put("generated", generated);
    summary.put("failed", failed);
    return summary;
  }

  /** 过期扫描：超过当天截止时间仍未提交的练习统一置为 EXPIRED（已提交的不动）。 */
  public int expireOverdueSessions() {
    return db.update("""
        update exam_daily_session set status='EXPIRED',version=version+1
        where status in ('PENDING','IN_PROGRESS') and deadline_at<=now()
        """);
  }

  private void markSkipped(Long planId, LocalDate date, String reason) {
    db.update("""
        insert ignore into exam_daily_job_run(plan_id,practice_date,status,generated_count,failed_count,
          error_message,started_at,finished_at)
        values(?,?,'SKIPPED',0,0,?,now(),now())
        """, planId, Date.valueOf(date), reason);
  }

  /** 生成某个计划某一天的全部练习：先抢占 job_run，再逐人用独立事务生成。manual=true 表示管理端「立即推送」，可重试已失败任务。 */
  private Map<String, Object> generatePlanDay(Map<String, Object> plan, LocalDate date, LocalDateTime now, boolean manual) {
    Long planId = ((Number) plan.get("id")).longValue();
    int baseGenerated = 0, baseFailed = 0;
    int acquired = db.update("""
        insert ignore into exam_daily_job_run(plan_id,practice_date,status,started_at)
        values(?,?,'RUNNING',now())
        """, planId, Date.valueOf(date));
    if (acquired == 0) {
      var runs = db.queryForList("select status,generated_count,failed_count from exam_daily_job_run where plan_id=? and practice_date=?",
          planId, Date.valueOf(date));
      String status = runs.isEmpty() ? "" : String.valueOf(runs.get(0).get("status"));
      int retried = 0;
      if ("FAILED".equals(status) && manual) {
        // 自动轮询不再重试失败任务；仅管理端「立即推送」允许人工重试。
        retried = db.update("""
            update exam_daily_job_run set status='RUNNING',generated_count=0,failed_count=0,error_message=null,
                   started_at=now(),finished_at=null
            where plan_id=? and practice_date=? and status='FAILED'
            """, planId, Date.valueOf(date));
      } else if ("RUNNING".equals(status)) {
        // 生成中途崩溃/重启会留下 RUNNING；超过 10 分钟视为陈旧锁，允许接管重跑。
        retried = db.update("""
            update exam_daily_job_run set generated_count=0,failed_count=0,error_message=null,
                   started_at=now(),finished_at=null
            where plan_id=? and practice_date=? and status='RUNNING'
              and started_at < date_sub(now(), interval 10 minute)
            """, planId, Date.valueOf(date));
      }
      if (retried == 0) {
        // 已成功生成时仍允许补齐后来追加的参与人；无人缺失则直接跳过，保证定时轮询不重复干活。
        // SKIPPED 只表示当天被周末/排除日期跳过，并非失败；条件解除后应允许重新生成。
        if (!"SUCCESS".equals(status) && !"SKIPPED".equals(status)) {
          Map<String, Object> skipped = new LinkedHashMap<>();
          skipped.put("generated", 0);
          skipped.put("failed", 0);
          skipped.put("message", "RUNNING".equals(status)
              ? "今天正在生成中，请稍后查看生成记录"
              : "今天生成已失败，修复题库或参与范围后可立即推送重试");
          return skipped;
        }
        if (missingSessionCount(planId, date) == 0) {
          Map<String, Object> skipped = new LinkedHashMap<>();
          skipped.put("generated", 0);
          skipped.put("failed", 0);
          skipped.put("message", "今天已生成或正在生成");
          return skipped;
        }
        if ("SKIPPED".equals(status)) {
          // 从跳过状态重新生成时，清空旧的跳过原因并按本次重跑时间计时。
          db.update("""
              update exam_daily_job_run set generated_count=0,failed_count=0,error_message=null,started_at=now()
              where plan_id=? and practice_date=? and status='SKIPPED'
              """, planId, Date.valueOf(date));
        }
        baseGenerated = number(runs.get(0).get("generated_count"));
        baseFailed = number(runs.get(0).get("failed_count"));
      }
    }
    int generated = 0, failed = 0;
    List<String> errors = new ArrayList<>();
    try {
      List<Long> available = availableQuestionIds(plan);
      if (available.isEmpty()) throw new BusinessException(400, "没有符合条件的可用题目，无法生成今日练习");
      int dailyCount = number(plan.get("daily_count"));
      BigDecimal score = decimal(plan.get("score_per_question"));
      String policy = String.valueOf(plan.get("repeat_policy"));
      LocalDateTime deadlineAt = date.atTime(localTime(plan.get("deadline_time")));
      Map<Long, Map<Long, LocalDate>> history = questionHistory(planId);
      for (Long employeeId : activeParticipantIds(planId)) {
        try {
          String outcome = tx.execute(status -> generateEmployeeDay(planId, employeeId, date, deadlineAt,
              available, history.getOrDefault(employeeId, Map.of()), dailyCount, score, policy));
          if ("CREATED".equals(outcome)) generated++;
        } catch (Exception e) {
          failed++;
          errors.add("员工" + employeeId + "：" + shortMessage(e));
        }
      }
      db.update("""
          update exam_daily_job_run set status=?,generated_count=?,failed_count=?,error_message=?,finished_at=now()
          where plan_id=? and practice_date=?
          """, generated == 0 && failed > 0 ? "FAILED" : "SUCCESS",
          baseGenerated + generated, baseFailed + failed, summarize(errors), planId, Date.valueOf(date));
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("generated", generated);
      result.put("failed", failed);
      result.put("message", errors.isEmpty() ? "今日练习生成完成" : summarize(errors));
      return result;
    } catch (Exception e) {
      db.update("""
          update exam_daily_job_run set status='FAILED',generated_count=?,failed_count=?,error_message=?,finished_at=now()
          where plan_id=? and practice_date=?
          """, baseGenerated + generated, baseFailed + failed + 1, shortMessage(e), planId, Date.valueOf(date));
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("generated", generated);
      result.put("failed", failed + 1);
      result.put("message", shortMessage(e));
      return result;
    }
  }

  /** 今日仍缺 session 的有效参与人数，用于「立即推送」补齐后加入的参与人。 */
  private int missingSessionCount(Long planId, LocalDate date) {
    Integer count = db.queryForObject("""
        select count(*) from exam_daily_participant p
        join employee e on e.id=p.employee_id and e.status='ACTIVE' and e.user_id is not null
        where p.plan_id=? and p.active=1
          and not exists (select 1 from exam_daily_session s
                          where s.plan_id=p.plan_id and s.employee_id=p.employee_id and s.practice_date=?)
        """, Integer.class, planId, Date.valueOf(date));
    return count == null ? 0 : count;
  }

  /** 单人生成：确定性种子抽题 → 写 session → 批量写题目快照（唯一键兜底去重）。 */
  private String generateEmployeeDay(Long planId, Long employeeId, LocalDate date, LocalDateTime deadlineAt,
                                     List<Long> available, Map<Long, LocalDate> history, int dailyCount,
                                     BigDecimal scorePerQuestion, String repeatPolicy) {
    Integer exists = db.queryForObject("""
        select count(*) from exam_daily_session where plan_id=? and employee_id=? and practice_date=?
        """, Integer.class, planId, employeeId, Date.valueOf(date));
    if (exists != null && exists > 0) return "EXISTS";
    PickedQuestions picked = pickQuestions(available, history, dailyCount, repeatPolicy,
        planId * 1_000_003L + date.toEpochDay() * 31L + employeeId);
    int created = db.update("""
        insert ignore into exam_daily_session(plan_id,employee_id,practice_date,status,question_count,deadline_at,version,created_at)
        values(?,?,?,'PENDING',?,?,0,now())
        """, planId, employeeId, Date.valueOf(date), picked.ids().size(), Timestamp.valueOf(deadlineAt));
    if (created == 0) return "EXISTS";
    Long sessionId = db.queryForObject("""
        select id from exam_daily_session where plan_id=? and employee_id=? and practice_date=?
        """, Long.class, planId, employeeId, Date.valueOf(date));
    List<Object[]> rows = new ArrayList<>();
    int order = 1;
    for (Long questionId : picked.ids())
      rows.add(new Object[]{sessionId, questionId, planId, employeeId, scorePerQuestion, order++,
          picked.repeated().contains(questionId)});
    db.batchUpdate("""
        insert ignore into exam_daily_session_question(session_id,question_id,plan_id,employee_id,score,sort_order,repeated)
        values(?,?,?,?,?,?,?)
        """, rows);
    return "CREATED";
  }

  private record PickedQuestions(List<Long> ids, Set<Long> repeated) {}

  /**
   * 抽题：先排除本人已做过的题，再用「计划+日期+人员」确定性种子打乱，保证同一天重跑不换题。
   * 新题不足时按策略处理：循环复用已做题（标记复习）、减少题数、或该人当天不生成。
   */
  private PickedQuestions pickQuestions(List<Long> available, Map<Long, LocalDate> history, int dailyCount,
                                        String repeatPolicy, long seed) {
    List<Long> fresh = new ArrayList<>(available);
    fresh.removeAll(history.keySet());
    Collections.shuffle(fresh, new Random(seed));
    List<Long> picked = new ArrayList<>();
    Set<Long> repeated = new HashSet<>();
    if (fresh.size() >= dailyCount) {
      picked.addAll(fresh.subList(0, dailyCount));
    } else if ("FAIL".equals(repeatPolicy)) {
      throw new BusinessException(400, "可用新题不足：需要 " + dailyCount + " 题，当前仅剩 " + fresh.size() + " 题");
    } else if ("REDUCE_COUNT".equals(repeatPolicy)) {
      picked.addAll(fresh);
    } else {
      picked.addAll(fresh);
      List<Long> used = new ArrayList<>(history.keySet());
      used.sort(Comparator.comparing((Long id) -> history.get(id)).thenComparing(id -> id));
      for (Long questionId : used) {
        if (picked.size() >= dailyCount) break;
        if (picked.contains(questionId)) continue;
        picked.add(questionId);
        repeated.add(questionId);
      }
      // 题库总量仍少于每日题数时（发布后题目被停用等），只能按可用题补足，同一天不允许重复同一道题
      for (Long questionId : fresh) {
        if (picked.size() >= dailyCount) break;
        if (!picked.contains(questionId)) picked.add(questionId);
      }
    }
    if (picked.isEmpty())
      throw new BusinessException(400, "没有可用题目（每日需要 " + dailyCount + " 题）");
    return new PickedQuestions(picked, repeated);
  }

  private List<Long> availableQuestionIds(Map<String, Object> plan) {
    List<String> types = parseStrings(plan.get("question_types"));
    if (types.isEmpty()) types = DEFAULT_TYPES;
    StringBuilder sql = new StringBuilder("""
        select q.id from question_bank q join exam_question_bank b on b.id=q.bank_id and b.enabled=true
        where q.enabled=true and q.question_type in (""");
    sql.append(marks(types.size())).append(")");
    var args = new ArrayList<Object>(types);
    List<Long> bankIds = parseLongs(plan.get("bank_ids"));
    if (!bankIds.isEmpty()) {
      sql.append(" and q.bank_id in (").append(marks(bankIds.size())).append(")");
      args.addAll(bankIds);
    }
    List<String> tags = parseStrings(plan.get("tags"));
    if (!tags.isEmpty()) {
      sql.append(" and (q.tags is null or json_length(q.tags)=0 or json_overlaps(q.tags,cast(? as json)))");
      args.add(json(tags));
    }
    sql.append(" order by q.id");
    return db.queryForList(sql.toString(), Long.class, args.toArray());
  }

  private Map<Long, Map<Long, LocalDate>> questionHistory(Long planId) {
    var rows = db.queryForList("""
        select sq.employee_id,sq.question_id,max(s.practice_date) last_date
        from exam_daily_session_question sq
        join exam_daily_session s on s.id=sq.session_id
        where sq.plan_id=?
        group by sq.employee_id,sq.question_id
        """, planId);
    Map<Long, Map<Long, LocalDate>> result = new HashMap<>();
    for (var row : rows)
      result.computeIfAbsent(((Number) row.get("employee_id")).longValue(), k -> new HashMap<>())
          .put(((Number) row.get("question_id")).longValue(), localDate(row.get("last_date")));
    return result;
  }

  private List<Long> activeParticipantIds(Long planId) {
    return db.queryForList("""
        select p.employee_id from exam_daily_participant p join employee e on e.id=p.employee_id
        where p.plan_id=? and p.active=true and e.status='ACTIVE' and e.user_id is not null
        order by p.employee_id
        """, Long.class, planId);
  }

  // ---------------------------------------------------------------- 今日推送入口

  public Map<String, Object> pushToday(Long id) {
    var rows = db.queryForList("select * from exam_daily_plan where id=?", id);
    if (rows.isEmpty()) throw new BusinessException(404, "每日练习计划不存在");
    var plan = rows.get(0);
    String status = String.valueOf(plan.get("status"));
    if ("DRAFT".equals(status)) throw new BusinessException(400, "草稿计划尚未发布，不能推送");
    if ("PAUSED".equals(status)) throw new BusinessException(400, "计划已暂停，恢复后才能推送");
    if ("ENDED".equals(status)) throw new BusinessException(400, "计划已结束，不能推送");
    LocalDateTime now = TimeWindow.now();
    LocalDate today = now.toLocalDate();
    LocalDate start = localDate(plan.get("start_date"));
    LocalDate end = localDate(plan.get("end_date"));
    if (today.isBefore(start) || today.isAfter(end))
      throw new BusinessException(400, "今天不在计划有效期（" + start + " 至 " + end + "）内");
    if (!truthy(plan.get("include_weekends"))
        && (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY))
      throw new BusinessException(400, "计划设置为周末不推送，今天为周末");
    if (isExcluded(id, today)) throw new BusinessException(400, "今天已被设置为排除日期，不推送");
    if (!now.isBefore(today.atTime(localTime(plan.get("deadline_time")))))
      throw new BusinessException(400, "今天的截止时间已过，无法再推送");
    int participants = activeParticipantCount(id);
    if (participants == 0) throw new BusinessException(400, "计划没有可推送的在职参与人");
    requirePublishable(plan);
    var generated = generatePlanDay(plan, today, now, true);
    audit.log("PUSH_DAILY_PRACTICE", "EXAM_DAILY_PLAN", id, null,
        Map.of("practiceDate", today.toString(), "participants", participants, "generated", generated.get("generated")));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("practice_date", today.toString());
    result.put("participant_count", participants);
    result.put("eligible", true);
    result.put("generated", generated.get("generated"));
    result.put("failed", generated.get("failed"));
    result.put("message", generated.get("message"));
    return result;
  }

  public List<Map<String, Object>> runs(Long id) {
    requirePlan(id);
    return db.queryForList("""
        select date_format(practice_date,'%Y-%m-%d') practice_date,status,generated_count,failed_count,
               error_message,started_at,finished_at
        from exam_daily_job_run where plan_id=? order by practice_date desc limit 90
        """, id);
  }

  // ---------------------------------------------------------------- 学员端（阶段三）

  public record AnswerRequest(Long questionId, Object answer) {}

  /** 学员首页：今日任务 + 历史记录；非员工身份返回空态。 */
  public Map<String, Object> studentOverview(Long userId, String role) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("server_now_epoch_ms", Instant.now().toEpochMilli());
    var employees = db.queryForList("select id,employee_no,name from employee where user_id=?", userId);
    if (employees.isEmpty() || !"EMPLOYEE".equals(role)) {
      result.put("is_employee", false);
      result.put("today", List.of());
      result.put("history", List.of());
      return result;
    }
    Long employeeId = ((Number) employees.get(0).get("id")).longValue();
    LocalDate today = TimeWindow.now().toLocalDate();
    result.put("is_employee", true);
    result.put("employee", employees.get(0));
    var todayRows = db.queryForList("""
        select s.id,s.plan_id,p.name plan_name,s.status,s.question_count,s.correct_count,s.total_score,
               s.deadline_at,s.started_at,s.submitted_at,p.pass_score,p.score_per_question
        from exam_daily_session s join exam_daily_plan p on p.id=s.plan_id
        where s.employee_id=? and s.practice_date=? and p.status in ('PUBLISHED','PAUSED')
        order by s.id
        """, employeeId, Date.valueOf(today));
    for (var row : todayRows) decorateTimes(row);
    result.put("today", todayRows);
    var historyRows = db.queryForList("""
        select s.id,s.plan_id,p.name plan_name,s.practice_date,s.status,s.question_count,s.correct_count,
               s.total_score,s.deadline_at,s.submitted_at,p.pass_score,p.score_per_question
        from exam_daily_session s join exam_daily_plan p on p.id=s.plan_id
        where s.employee_id=? and s.practice_date<?
        order by s.practice_date desc,s.id desc limit 60
        """, employeeId, Date.valueOf(today));
    for (var row : historyRows) decorateTimes(row);
    result.put("history", historyRows);
    return result;
  }

  // ---------------------------------------------------------------- 成绩查询 / 导出

  /** 成绩筛选条件：日期区间、工号/姓名关键字、session 状态。 */
  private record ResultFilter(StringBuilder where, List<Object> args) {}

  private ResultFilter resultFilter(Long planId, String dateFrom, String dateTo, String keyword, String status) {
    StringBuilder where = new StringBuilder("""
        from exam_daily_session s join employee e on e.id=s.employee_id
        left join talent_batch b on b.id=e.batch_id
        where s.plan_id=?
        """);
    var args = new ArrayList<Object>();
    args.add(planId);
    LocalDate from = parseDate(dateFrom, "开始日期");
    LocalDate to = parseDate(dateTo, "结束日期");
    if (from != null && to != null && to.isBefore(from)) throw new BusinessException(400, "结束日期不能早于开始日期");
    if (from != null) { where.append(" and s.practice_date>=?"); args.add(Date.valueOf(from)); }
    if (to != null) { where.append(" and s.practice_date<=?"); args.add(Date.valueOf(to)); }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (e.employee_no like ? or e.name like ?)");
      String value = "%" + keyword.trim() + "%";
      args.add(value); args.add(value);
    }
    if (status != null && !status.isBlank()) {
      String value = status.trim().toUpperCase(Locale.ROOT);
      if (!Set.of("PENDING", "IN_PROGRESS", "SUBMITTED", "EXPIRED").contains(value))
        throw new BusinessException(400, "成绩状态无效");
      where.append(" and s.status=?");
      args.add(value);
    }
    return new ResultFilter(where, args);
  }

  /** 管理端成绩分页：工号、姓名、批次、日期、题数、正确数、得分、状态、提交时间。 */
  public PageResult<Map<String, Object>> results(Long planId, String dateFrom, String dateTo, String keyword,
                                                 String status, int page, int size) {
    requirePlan(planId);
    int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
    ResultFilter filter = resultFilter(planId, dateFrom, dateTo, keyword, status);
    Long total = db.queryForObject("select count(*)" + filter.where(), Long.class, filter.args().toArray());
    var args = new ArrayList<>(filter.args());
    args.add(safeSize); args.add((safePage - 1) * safeSize);
    var rows = db.queryForList("""
        select s.id,s.employee_id,e.employee_no,e.name employee_name,b.name batch_name,
               date_format(s.practice_date,'%Y-%m-%d') practice_date,s.question_count,s.correct_count,
               s.total_score,s.status,s.submitted_at
        """ + filter.where() + """
         order by s.practice_date desc,e.employee_no,s.id desc limit ? offset ?
        """, args.toArray());
    return new PageResult<>(rows, total == null ? 0L : total, safePage, safeSize);
  }

  /** 导出沿用当前筛选条件，不分页。 */
  public List<Map<String, Object>> resultsForExport(Long planId, String dateFrom, String dateTo,
                                                    String keyword, String status) {
    requirePlan(planId);
    ResultFilter filter = resultFilter(planId, dateFrom, dateTo, keyword, status);
    return db.queryForList("""
        select s.id,e.employee_no,e.name employee_name,b.name batch_name,
               date_format(s.practice_date,'%Y-%m-%d') practice_date,s.question_count,s.correct_count,
               s.total_score,s.status,s.submitted_at
        """ + filter.where() + """
         order by s.practice_date desc,e.employee_no,s.id desc
        """, filter.args().toArray());
  }

  private LocalDate parseDate(String value, String field) {
    String text = trim(value);
    if (text == null) return null;
    try {
      return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
    } catch (DateTimeParseException e) {
      throw new BusinessException(400, field + "格式无效，应为 yyyy-MM-dd");
    }
  }

  /** 开始/继续今日练习：校验身份、所有权、时间窗与状态，PENDING 时置 IN_PROGRESS。 */
  @Transactional
  public Map<String, Object> startSession(Long sessionId, Long userId, String role) {
    requireEmployeeRole(role);
    var info = requireOwnSession(sessionId, userId);
    String status = String.valueOf(info.get("status"));
    if ("EXPIRED".equals(status)) throw new BusinessException(400, "今日练习已过期，无法开始");
    if ("SUBMITTED".equals(status)) return sessionDetail(sessionId, userId, role);
    LocalDateTime deadline = TimeWindow.time(info.get("deadline_at"));
    if (deadline == null || !TimeWindow.now().isBefore(deadline)) {
      expireSession(sessionId);
      throw new BusinessException(400, "已超过今日截止时间，无法开始练习");
    }
    if ("PENDING".equals(status))
      db.update("""
          update exam_daily_session set status='IN_PROGRESS',started_at=coalesce(started_at,now()),version=version+1
          where id=? and status='PENDING'
          """, sessionId);
    return sessionDetail(sessionId, userId, role);
  }

  /** 取题：未提交不返回答案与解析；选项按会话+题目确定性种子打乱。 */
  public Map<String, Object> sessionDetail(Long sessionId, Long userId, String role) {
    requireEmployeeRole(role);
    var info = requireOwnSession(sessionId, userId);
    String status = String.valueOf(info.get("status"));
    if ("EXPIRED".equals(status)) throw new BusinessException(400, "今日练习已过期");
    Long sessionValue = ((Number) info.get("id")).longValue();
    Map<Long, Object> saved = new HashMap<>();
    for (var row : db.queryForList("select question_id,answer_json from exam_daily_answer where session_id=?", sessionId))
      saved.put(((Number) row.get("question_id")).longValue(), parseJsonValue(row.get("answer_json")));
    boolean randomize = truthy(info.get("randomize_options"));
    var questions = db.queryForList("""
        select q.id,q.question_type,q.stem,q.options_json,sq.score,sq.sort_order,sq.repeated
        from exam_daily_session_question sq join question_bank q on q.id=sq.question_id
        where sq.session_id=? order by sq.sort_order,q.id
        """, sessionId);
    List<Map<String, Object>> items = new ArrayList<>();
    for (var q : questions) {
      Long questionId = ((Number) q.get("id")).longValue();
      String type = String.valueOf(q.get("question_type"));
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", questionId);
      item.put("question_type", type);
      item.put("stem", q.get("stem"));
      item.put("options", displayOptions(q.get("options_json"), randomize, sessionValue * 31 + questionId, type));
      item.put("score", q.get("score"));
      item.put("sort_order", q.get("sort_order"));
      item.put("repeated", truthy(q.get("repeated")));
      item.put("saved_answer", saved.get(questionId));
      items.add(item);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", sessionId);
    result.put("plan_id", info.get("plan_id"));
    result.put("plan_name", info.get("plan_name"));
    result.put("status", status);
    result.put("question_count", info.get("question_count"));
    result.put("score_per_question", info.get("score_per_question"));
    result.put("pass_score", info.get("pass_score"));
    result.put("total_score", info.get("total_score"));
    result.put("correct_count", info.get("correct_count"));
    result.put("deadline_at", info.get("deadline_at"));
    result.put("started_at", info.get("started_at"));
    result.put("submitted_at", info.get("submitted_at"));
    decorateTimes(result);
    result.put("server_now_epoch_ms", Instant.now().toEpochMilli());
    result.put("questions", items);
    return result;
  }

  /** 保存单题答案（幂等）：只有 IN_PROGRESS 且未过截止时间可保存。 */
  @Transactional
  public Map<String, Object> saveAnswer(Long sessionId, Long userId, String role, AnswerRequest request) {
    requireEmployeeRole(role);
    if (request == null || request.questionId() == null) throw new BusinessException(400, "请选择要保存的题目");
    var info = requireOwnSession(sessionId, userId);
    if (!"IN_PROGRESS".equals(String.valueOf(info.get("status"))))
      throw new BusinessException(400, "请先开始练习后再作答");
    LocalDateTime deadline = TimeWindow.time(info.get("deadline_at"));
    if (deadline == null || !TimeWindow.now().isBefore(deadline)) {
      expireSession(sessionId);
      throw new BusinessException(400, "已超过今日截止时间，无法保存答案");
    }
    Integer belongs = db.queryForObject("""
        select count(*) from exam_daily_session_question where session_id=? and question_id=?
        """, Integer.class, sessionId, request.questionId());
    if (belongs == null || belongs == 0) throw new BusinessException(400, "该题目不属于本次练习");
    String json;
    try {
      json = request.answer() == null ? null : mapper.writeValueAsString(request.answer());
    } catch (Exception e) {
      throw new BusinessException(400, "答案格式无效");
    }
    db.update("""
        insert into exam_daily_answer(session_id,question_id,answer_json,is_correct,score,saved_at)
        values(?,?,?,null,null,now())
        on duplicate key update answer_json=values(answer_json),is_correct=null,score=null,saved_at=now()
        """, sessionId, request.questionId(), json);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("question_id", request.questionId());
    result.put("saved_at", TimeWindow.now().format(API_TIME));
    return result;
  }

  /** 提交并判分（幂等）：复用现有客观题判分口径，多选少选/多选均不得分。 */
  @Transactional
  public Map<String, Object> submitSession(Long sessionId, Long userId, String role) throws Exception {
    requireEmployeeRole(role);
    var info = requireOwnSession(sessionId, userId);
    String status = String.valueOf(info.get("status"));
    if ("SUBMITTED".equals(status)) return sessionResult(sessionId, userId, role);
    if ("EXPIRED".equals(status)) throw new BusinessException(400, "今日练习已过期，无法提交");
    LocalDateTime deadline = TimeWindow.time(info.get("deadline_at"));
    if (deadline == null || !TimeWindow.now().isBefore(deadline)) {
      expireSession(sessionId);
      throw new BusinessException(400, "已超过今日截止时间，无法提交");
    }
    var questions = db.queryForList("""
        select sq.question_id,q.question_type,q.answer_json,sq.score
        from exam_daily_session_question sq join question_bank q on q.id=sq.question_id
        where sq.session_id=? order by sq.sort_order,q.id
        """, sessionId);
    int correctCount = 0;
    BigDecimal totalScore = BigDecimal.ZERO;
    for (var question : questions) {
      Long questionId = ((Number) question.get("question_id")).longValue();
      String type = String.valueOf(question.get("question_type"));
      var answers = db.queryForList("""
          select answer_json from exam_daily_answer where session_id=? and question_id=?
          """, sessionId, questionId);
      boolean correct = false;
      if (!answers.isEmpty()) {
        JsonNode expected = parseJsonTree(question.get("answer_json"));
        JsonNode actual = parseJsonTree(answers.get(0).get("answer_json"));
        correct = expected != null && actual != null && ExamScoringService.answersMatch(type, expected, actual);
      }
      BigDecimal point = correct ? decimal(question.get("score")) : BigDecimal.ZERO;
      if (correct) correctCount++;
      totalScore = totalScore.add(point);
      db.update("update exam_daily_answer set is_correct=?,score=? where session_id=? and question_id=?",
          correct, point, sessionId, questionId);
    }
    int updated = db.update("""
        update exam_daily_session set status='SUBMITTED',correct_count=?,total_score=?,submitted_at=now(),version=version+1
        where id=? and status in ('PENDING','IN_PROGRESS') and deadline_at>now()
        """, correctCount, totalScore, sessionId);
    if (updated == 0) {
      var current = requireOwnSession(sessionId, userId);
      if ("SUBMITTED".equals(String.valueOf(current.get("status")))) return sessionResult(sessionId, userId, role);
      expireSession(sessionId);
      throw new BusinessException(400, "已超过今日截止时间，提交失败");
    }
    audit.log("SUBMIT_DAILY_PRACTICE", "EXAM_DAILY_SESSION", sessionId, null,
        Map.of("correctCount", correctCount, "totalScore", totalScore));
    return sessionResult(sessionId, userId, role);
  }

  /** 提交后查看逐题结果与解析（只允许本人）。 */
  public Map<String, Object> sessionResult(Long sessionId, Long userId, String role) {
    requireEmployeeRole(role);
    var info = requireOwnSession(sessionId, userId);
    if (!"SUBMITTED".equals(String.valueOf(info.get("status"))))
      throw new BusinessException(400, "练习尚未提交，暂无结果");
    Long sessionValue = ((Number) info.get("id")).longValue();
    boolean randomize = truthy(info.get("randomize_options"));
    var rows = db.queryForList("""
        select q.id,q.question_type,q.stem,q.options_json,q.answer_json,q.explanation,
               sq.score,sq.sort_order,sq.repeated,
               a.answer_json saved_answer,a.is_correct,a.score earned_score
        from exam_daily_session_question sq
        join question_bank q on q.id=sq.question_id
        left join exam_daily_answer a on a.session_id=sq.session_id and a.question_id=sq.question_id
        where sq.session_id=? order by sq.sort_order,q.id
        """, sessionId);
    List<Map<String, Object>> items = new ArrayList<>();
    for (var row : rows) {
      Long questionId = ((Number) row.get("id")).longValue();
      String type = String.valueOf(row.get("question_type"));
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", questionId);
      item.put("question_type", type);
      item.put("stem", row.get("stem"));
      item.put("options", displayOptions(row.get("options_json"), randomize, sessionValue * 31 + questionId, type));
      item.put("score", row.get("score"));
      item.put("sort_order", row.get("sort_order"));
      item.put("repeated", truthy(row.get("repeated")));
      item.put("correct_answer", parseJsonValue(row.get("answer_json")));
      item.put("saved_answer", parseJsonValue(row.get("saved_answer")));
      item.put("is_correct", truthy(row.get("is_correct")));
      item.put("earned_score", row.get("earned_score") == null ? BigDecimal.ZERO : decimal(row.get("earned_score")));
      item.put("explanation", row.get("explanation"));
      items.add(item);
    }
    BigDecimal passScore = info.get("pass_score") == null ? null : decimal(info.get("pass_score"));
    BigDecimal totalScore = info.get("total_score") == null ? BigDecimal.ZERO : decimal(info.get("total_score"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", sessionId);
    result.put("plan_id", info.get("plan_id"));
    result.put("plan_name", info.get("plan_name"));
    result.put("status", "SUBMITTED");
    result.put("question_count", info.get("question_count"));
    result.put("correct_count", info.get("correct_count") == null ? 0 : number(info.get("correct_count")));
    result.put("total_score", totalScore);
    result.put("pass_score", passScore);
    result.put("passed", passScore != null && totalScore.compareTo(passScore) >= 0);
    result.put("deadline_at", info.get("deadline_at"));
    result.put("submitted_at", info.get("submitted_at"));
    decorateTimes(result);
    result.put("server_now_epoch_ms", Instant.now().toEpochMilli());
    result.put("questions", items);
    return result;
  }

  private Map<String, Object> requireOwnSession(Long sessionId, Long userId) {
    var rows = db.queryForList("""
        select s.id,s.plan_id,s.employee_id,s.status,s.deadline_at,s.question_count,s.started_at,
               s.correct_count,s.total_score,p.name plan_name,p.pass_score,p.randomize_options,p.score_per_question
        from exam_daily_session s
        join exam_daily_plan p on p.id=s.plan_id
        join employee e on e.id=s.employee_id
        where s.id=? and e.user_id=?
        """, sessionId, userId);
    if (rows.isEmpty()) throw new BusinessException(404, "练习任务不存在或无权访问");
    return rows.get(0);
  }

  private void requireEmployeeRole(String role) {
    if (!"EMPLOYEE".equals(role)) throw new BusinessException(403, "仅员工身份可以参加每日练习");
  }

  private void expireSession(Long sessionId) {
    expireTx.executeWithoutResult(status -> db.update("""
        update exam_daily_session set status='EXPIRED',version=version+1
        where id=? and status in ('PENDING','IN_PROGRESS')
        """, sessionId));
  }

  private void decorateTimes(Map<String, Object> row) {
    LocalDateTime deadline = TimeWindow.time(row.get("deadline_at"));
    row.put("deadline_at", deadline == null ? null : deadline.format(API_TIME));
    row.put("deadline_epoch_ms", deadline == null ? null : deadline.atZone(ZONE).toInstant().toEpochMilli());
    row.put("remaining_seconds", deadline == null ? 0 : Math.max(0, Duration.between(TimeWindow.now(), deadline).getSeconds()));
    LocalDateTime submitted = TimeWindow.time(row.get("submitted_at"));
    row.put("submitted_at", submitted == null ? null : submitted.format(API_TIME));
    LocalDateTime started = TimeWindow.time(row.get("started_at"));
    row.put("started_at", started == null ? null : started.format(API_TIME));
    if (row.get("practice_date") instanceof Date date)
      row.put("practice_date", date.toLocalDate().toString());
  }

  private Object parseJsonValue(Object raw) {
    if (raw == null) return null;
    try {
      return mapper.convertValue(mapper.readTree(String.valueOf(raw)), Object.class);
    } catch (Exception e) {
      return null;
    }
  }

  private JsonNode parseJsonTree(Object raw) {
    if (raw == null) return null;
    try {
      return mapper.readTree(String.valueOf(raw));
    } catch (Exception e) {
      return null;
    }
  }

  private List<Object> displayOptions(Object raw, boolean randomize, long seed, String type) {
    if (raw == null) return List.of();
    try {
      List<Object> values = mapper.convertValue(mapper.readTree(String.valueOf(raw)), List.class);
      if (randomize && !"TRUE_FALSE".equals(type) && values.size() > 1)
        Collections.shuffle(values, new Random(seed));
      return values;
    } catch (Exception e) {
      return List.of();
    }
  }

  private int intOf(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private String shortMessage(Throwable e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
    return message.length() > 150 ? message.substring(0, 150) : message;
  }

  private String summarize(List<String> errors) {
    if (errors.isEmpty()) return null;
    String text = String.join("；", errors.subList(0, Math.min(errors.size(), 5)));
    if (errors.size() > 5) text += "；等共 " + errors.size() + " 人失败";
    return text.length() > 480 ? text.substring(0, 480) : text;
  }

  // ---------------------------------------------------------------- 排除日期

  @Transactional
  public void addExclusion(Long id, ExclusionRequest request) {
    var plans = db.queryForList("select start_date,end_date from exam_daily_plan where id=?", id);
    if (plans.isEmpty()) throw new BusinessException(404, "每日练习计划不存在");
    LocalDate start = localDate(plans.get(0).get("start_date"));
    LocalDate end = localDate(plans.get(0).get("end_date"));
    if (request.date().isBefore(start) || request.date().isAfter(end))
      throw new BusinessException(400, "排除日期必须在计划有效期（" + start + " 至 " + end + "）内");
    try {
      db.update("insert into exam_daily_plan_exclusion(plan_id,exclude_date,reason) values(?,?,?)",
          id, Date.valueOf(request.date()), trim(request.reason()));
    } catch (Exception e) {
      throw new BusinessException(400, "该日期已在排除列表中");
    }
    audit.log("ADD_DAILY_EXCLUSION", "EXAM_DAILY_PLAN", id, null, request);
  }

  @Transactional
  public void removeExclusion(Long id, LocalDate date) {
    if (db.update("delete from exam_daily_plan_exclusion where plan_id=? and exclude_date=?", id, Date.valueOf(date)) == 0)
      throw new BusinessException(404, "排除日期不存在");
    audit.log("DELETE_DAILY_EXCLUSION", "EXAM_DAILY_PLAN", id, null, Map.of("date", date.toString()));
  }

  // ---------------------------------------------------------------- 内部工具

  private record Normalized(String name, String description, List<Long> bankIds, List<String> questionTypes,
                            List<String> tags, int dailyCount, BigDecimal scorePerQuestion, BigDecimal passScore,
                            LocalDate startDate, LocalDate endDate, LocalTime pushTime, LocalTime deadlineTime,
                            boolean randomizeOptions, String repeatPolicy, boolean includeWeekends,
                            List<Long> batchIds, List<Long> businessUnitIds,
                            List<Long> employeeIds) {}

  private Normalized normalize(PlanRequest q) {
    String name = trim(q.name());
    if (name == null) throw new BusinessException(400, "请填写计划名称");
    if (q.startDate() == null || q.endDate() == null) throw new BusinessException(400, "请设置计划起止日期");
    if (q.endDate().isBefore(q.startDate())) throw new BusinessException(400, "结束日期不能早于开始日期");
    LocalTime pushTime = parseTime(q.pushTime(), "每日推送时刻");
    LocalTime deadlineTime = parseTime(q.deadlineTime(), "每日截止时刻");
    if (!pushTime.isBefore(deadlineTime)) throw new BusinessException(400, "每日推送时刻必须早于截止时刻");
    List<String> types = normalizeTypes(q.questionTypes());
    List<Long> bankIds = normalizeLongs(q.bankIds());
    validateBanks(bankIds);
    List<String> tags = normalizeTags(q.tags());
    int dailyCount = q.dailyCount() == null ? 0 : q.dailyCount();
    if (dailyCount < 1) throw new BusinessException(400, "每日题数至少为 1 题");
    if (q.scorePerQuestion() == null || q.scorePerQuestion().signum() <= 0)
      throw new BusinessException(400, "每题分值必须大于 0");
    BigDecimal total = q.scorePerQuestion().multiply(BigDecimal.valueOf(dailyCount));
    BigDecimal passScore = q.passScore();
    if (passScore != null && (passScore.signum() < 0 || passScore.compareTo(total) > 0))
      throw new BusinessException(400, "及格分必须在 0 至满分 " + total.stripTrailingZeros().toPlainString() + " 之间");
    String repeatPolicy = q.repeatPolicy() == null ? "ALLOW_REPEAT" : q.repeatPolicy();
    if (!REPEAT_POLICIES.contains(repeatPolicy)) throw new BusinessException(400, "题库不足策略无效");
    List<Long> employeeIds = normalizeLongs(q.employeeIds());
    validateParticipants(employeeIds);
    return new Normalized(name, trim(q.description()), bankIds, types, tags, dailyCount, q.scorePerQuestion(),
        passScore, q.startDate(), q.endDate(), pushTime, deadlineTime,
        q.randomizeOptions() == null || q.randomizeOptions(), repeatPolicy,
        q.includeWeekends() == null || q.includeWeekends(),
        normalizeLongs(q.batchIds()), normalizeLongs(q.businessUnitIds()), employeeIds);
  }

  private Long insertPlan(Normalized n) {
    db.update("""
        insert into exam_daily_plan(name,description,bank_ids,question_types,tags,daily_count,score_per_question,pass_score,
          start_date,end_date,push_time,deadline_time,timezone,randomize_options,repeat_policy,include_weekends,status,version,created_by)
        values(?,?,?,?,?,?,?,?,?,?,?,?,'Asia/Shanghai',?,?,?,'DRAFT',0,?)
        """, n.name(), n.description(), json(n.bankIds()), json(n.questionTypes()), json(n.tags()), n.dailyCount(),
        n.scorePerQuestion(), n.passScore(), Date.valueOf(n.startDate()), Date.valueOf(n.endDate()),
        Time.valueOf(n.pushTime()), Time.valueOf(n.deadlineTime()), n.randomizeOptions(), n.repeatPolicy(),
        n.includeWeekends(), SecurityUtils.current().id());
    return db.queryForObject("select last_insert_id()", Long.class);
  }

  private void saveTargets(Long planId, Normalized n) {
    // 只重建当前支持的三种范围；历史 CLASS、CLASS_POSITION 行保持原样，避免破坏老计划数据。
    db.update("delete from exam_daily_plan_target where plan_id=? and target_type in ('BATCH','BUSINESS_UNIT','EMPLOYEE')", planId);
    for (Long batchId : n.batchIds()) insertTarget(planId, "BATCH", batchId);
    for (Long businessUnitId : n.businessUnitIds()) insertTarget(planId, "BUSINESS_UNIT", businessUnitId);
    // 没有范围条件时，把手工选择的人员作为 EMPLOYEE 范围保存，保证「刷新参与人」可用
    if (n.batchIds().isEmpty() && n.businessUnitIds().isEmpty())
      for (Long employeeId : n.employeeIds()) insertTarget(planId, "EMPLOYEE", employeeId);
  }

  private void insertTarget(Long planId, String type, Long targetId) {
    db.update("insert into exam_daily_plan_target(plan_id,target_type,target_id) values(?,?,?)", planId, type, targetId);
  }

  private void saveParticipants(Long planId, List<Long> employeeIds, boolean replace) {
    if (replace) db.update("delete from exam_daily_participant where plan_id=?", planId);
    for (Long employeeId : employeeIds)
      db.update("insert ignore into exam_daily_participant(plan_id,employee_id,active,joined_at) values(?,?,true,now())", planId, employeeId);
  }

  private void appendParticipants(Long planId, List<Long> employeeIds) {
    if (employeeIds.isEmpty()) return;
    validateParticipants(employeeIds);
    for (Long employeeId : employeeIds)
      db.update("insert ignore into exam_daily_participant(plan_id,employee_id,active,joined_at) values(?,?,true,now())", planId, employeeId);
  }

  private void validateParticipants(List<Long> employeeIds) {
    if (employeeIds.isEmpty()) return;
    String marks = marks(employeeIds.size());
    Integer count = db.queryForObject("select count(*) from employee where status='ACTIVE' and user_id is not null and id in (" + marks + ")",
        Integer.class, employeeIds.toArray());
    if (count == null || count != employeeIds.size())
      throw new BusinessException(400, "参与人员中包含不存在、已停用或未开通登录账号的员工，请重新筛选");
  }

  private void validateBanks(List<Long> bankIds) {
    if (bankIds.isEmpty()) return;
    Integer count = db.queryForObject("select count(*) from exam_question_bank where enabled=true and id in (" + marks(bankIds.size()) + ")",
        Integer.class, bankIds.toArray());
    if (count == null || count != bankIds.size())
      throw new BusinessException(400, "所选题库中包含不存在或已停用的题库");
  }

  private void requireLockedFieldsUnchanged(Map<String, Object> old, Normalized n) {
    boolean same = Objects.equals(sortedLongs(parseLongs(old.get("bank_ids"))), sortedLongs(n.bankIds()))
        && Objects.equals(sortedStrings(parseStrings(old.get("question_types"))), sortedStrings(n.questionTypes()))
        && Objects.equals(sortedStrings(parseStrings(old.get("tags"))), sortedStrings(n.tags()))
        && number(old.get("daily_count")) == n.dailyCount()
        && decimal(old.get("score_per_question")).compareTo(n.scorePerQuestion()) == 0
        && sameDecimal(old.get("pass_score"), n.passScore())
        && truthy(old.get("randomize_options")) == n.randomizeOptions()
        && String.valueOf(old.get("repeat_policy")).equals(n.repeatPolicy())
        && truthy(old.get("include_weekends")) == n.includeWeekends();
    if (!same)
      throw new BusinessException(400, "计划发布后不允许修改题库、题型、标签、每日题数、每题分值、及格分、选项乱序、题库不足策略和周末规则；如需调整请新建计划");
  }

  private void requirePublishable(Map<String, Object> plan) {
    List<Long> bankIds = parseLongs(plan.get("bank_ids"));
    List<String> types = parseStrings(plan.get("question_types"));
    List<String> tags = parseStrings(plan.get("tags"));
    int dailyCount = number(plan.get("daily_count"));
    int available = availableQuestions(bankIds, types, tags);
    if (available < dailyCount)
      throw new BusinessException(400, "可用题目不足：每日需要 " + dailyCount + " 题，当前符合条件的可用题只有 "
          + available + " 题，还缺 " + (dailyCount - available) + " 题；请补充题库、调整题型/标签或减少每日题数");
  }

  private int availableQuestions(List<Long> bankIds, List<String> types, List<String> tags) {
    List<String> effectiveTypes = types == null || types.isEmpty() ? DEFAULT_TYPES : types;
    StringBuilder sql = new StringBuilder("""
        select count(*) from question_bank q join exam_question_bank b on b.id=q.bank_id and b.enabled=true
        where q.enabled=true and q.question_type in (""");
    sql.append(marks(effectiveTypes.size())).append(")");
    var args = new ArrayList<Object>(effectiveTypes);
    if (bankIds != null && !bankIds.isEmpty()) {
      sql.append(" and q.bank_id in (").append(marks(bankIds.size())).append(")");
      args.addAll(bankIds);
    }
    if (tags != null && !tags.isEmpty()) {
      sql.append(" and (q.tags is null or json_length(q.tags)=0 or json_overlaps(q.tags,cast(? as json)))");
      args.add(json(tags));
    }
    Integer count = db.queryForObject(sql.toString(), Integer.class, args.toArray());
    return count == null ? 0 : count;
  }

  private int activeParticipantCount(Long planId) {
    Integer count = db.queryForObject("select count(*) from exam_daily_participant where plan_id=? and active=true", Integer.class, planId);
    return count == null ? 0 : count;
  }

  private int countInt(String sql, Object... args) {
    Integer value = db.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private String todayStatus(Map<String, Object> plan, LocalDate today) {
    String status = String.valueOf(plan.get("status"));
    if ("DRAFT".equals(status)) return "DRAFT";
    if ("PAUSED".equals(status)) return "PAUSED";
    if ("ENDED".equals(status)) return "ENDED";
    LocalDate start = localDate(plan.get("start_date"));
    LocalDate end = localDate(plan.get("end_date"));
    if (today.isBefore(start) || today.isAfter(end)) return "OUT_OF_RANGE";
    if (!truthy(plan.get("include_weekends"))
        && (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY))
      return "WEEKEND";
    Long id = ((Number) plan.get("id")).longValue();
    if (isExcluded(id, today)) return "EXCLUDED";
    Object run = plan.get("today_run_status");
    return run == null ? "PENDING" : String.valueOf(run);
  }

  private boolean isExcluded(Long planId, LocalDate date) {
    Integer count = db.queryForObject("select count(*) from exam_daily_plan_exclusion where plan_id=? and exclude_date=?",
        Integer.class, planId, Date.valueOf(date));
    return count != null && count > 0;
  }

  private void requirePlan(Long id) {
    if (countInt("select count(*) from exam_daily_plan where id=?", id) == 0)
      throw new BusinessException(404, "每日练习计划不存在");
  }

  private List<String> normalizeTypes(List<String> types) {
    if (types == null || types.isEmpty()) return DEFAULT_TYPES;
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String type : types) {
      String value = trim(type);
      if (value == null || !QUESTION_TYPES.contains(value))
        throw new BusinessException(400, "题型仅支持单选、多选和判断");
      result.add(value);
    }
    return new ArrayList<>(result);
  }

  private List<String> normalizeTags(List<String> tags) {
    if (tags == null) return List.of();
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String tag : tags) {
      String value = trim(tag);
      if (value == null) continue;
      if (value.length() > 32) throw new BusinessException(400, "专业标签不能超过 32 个字符");
      result.add(value);
    }
    return new ArrayList<>(result);
  }

  private List<Long> normalizeLongs(List<Long> ids) {
    if (ids == null) return List.of();
    LinkedHashSet<Long> result = new LinkedHashSet<>();
    for (Long id : ids) if (id != null) result.add(id);
    return new ArrayList<>(result);
  }

  private List<Long> parseIds(String raw, String field) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return Arrays.stream(raw.split(",")).map(String::trim).filter(x -> !x.isEmpty())
          .map(Long::valueOf).distinct().toList();
    } catch (NumberFormatException e) {
      throw new BusinessException(400, field + "筛选条件无效");
    }
  }

  private List<Long> parseLongs(Object raw) {
    if (raw == null) return List.of();
    try {
      if (raw instanceof List<?> list) {
        List<Long> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Number n) result.add(n.longValue());
        return result;
      }
      JsonNode node = mapper.readTree(String.valueOf(raw));
      if (!node.isArray()) return List.of();
      List<Long> result = new ArrayList<>();
      node.forEach(item -> { if (item.canConvertToLong()) result.add(item.asLong()); });
      return result;
    } catch (Exception e) {
      return List.of();
    }
  }

  private List<String> parseStrings(Object raw) {
    if (raw == null) return List.of();
    try {
      if (raw instanceof List<?> list) {
        List<String> result = new ArrayList<>();
        for (Object item : list) if (item != null) result.add(String.valueOf(item));
        return result;
      }
      JsonNode node = mapper.readTree(String.valueOf(raw));
      if (!node.isArray()) return List.of();
      List<String> result = new ArrayList<>();
      node.forEach(item -> { if (item.isTextual()) result.add(item.asText()); });
      return result;
    } catch (Exception e) {
      return List.of();
    }
  }

  private String json(Object value) {
    if (value == null) return null;
    if (value instanceof List<?> list && list.isEmpty()) return null;
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(500, "数据序列化失败");
    }
  }

  private List<Long> sortedLongs(List<Long> values) {
    List<Long> copy = new ArrayList<>(values == null ? List.of() : values);
    copy.sort(Comparator.naturalOrder());
    return copy;
  }

  private List<String> sortedStrings(List<String> values) {
    List<String> copy = new ArrayList<>(values == null ? List.of() : values);
    copy.sort(Comparator.naturalOrder());
    return copy;
  }

  private boolean sameDecimal(Object raw, BigDecimal value) {
    if (raw == null) return value == null;
    return value != null && decimal(raw).compareTo(value) == 0;
  }

  private BigDecimal decimal(Object value) {
    if (value == null) return BigDecimal.ZERO;
    if (value instanceof BigDecimal d) return d;
    return new BigDecimal(String.valueOf(value));
  }

  private int number(Object value) {
    return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
  }

  private static boolean truthy(Object value) {
    if (value instanceof Boolean bool) return bool;
    if (value instanceof Number number) return number.intValue() != 0;
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private LocalDate localDate(Object value) {
    if (value instanceof LocalDate d) return d;
    if (value instanceof Date d) return d.toLocalDate();
    if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
    return LocalDate.parse(String.valueOf(value).replace(' ', 'T').substring(0, 10));
  }

  private LocalTime parseTime(String value, String field) {
    String text = trim(value);
    if (text == null) throw new BusinessException(400, "请设置" + field);
    try {
      return text.length() == 5 ? LocalTime.parse(text + ":00") : LocalTime.parse(text);
    } catch (DateTimeParseException e) {
      throw new BusinessException(400, field + "格式无效，应为 HH:mm 或 HH:mm:ss");
    }
  }

  private LocalTime localTime(Object value) {
    if (value instanceof LocalTime time) return time;
    if (value instanceof Time time) return time.toLocalTime();
    String text = String.valueOf(value);
    return text.length() == 5 ? LocalTime.parse(text + ":00") : LocalTime.parse(text);
  }

  private static String marks(int size) {
    return String.join(",", Collections.nCopies(size, "?"));
  }

  private String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
