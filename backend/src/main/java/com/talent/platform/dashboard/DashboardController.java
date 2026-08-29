package com.talent.platform.dashboard;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import com.talent.platform.task.TaskStatusService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final TaskStatusService taskStatus;

  public DashboardController(JdbcTemplate db, PermissionService permissions, TaskStatusService taskStatus) {
    this.db = db;
    this.permissions = permissions;
    this.taskStatus = taskStatus;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> dashboard() {
    taskStatus.refreshOverdueAssignments();
    CurrentUser user = SecurityUtils.current();
    Map<String, Object> result = "EMPLOYEE".equals(user.role())
        ? employeeDashboard(user)
        : managerDashboard(user);
    result.put("generated_at", LocalDateTime.now(ZONE));
    return ApiResponse.ok(result);
  }

  private Map<String, Object> employeeDashboard(CurrentUser user) {
    var rows = db.queryForList("""
        select e.id employee_id,e.employee_no,e.name,e.onboard_date,
          b.name batch_name,s.name station_name,m.display_name mentor_name
        from employee e
        left join talent_batch b on b.id=e.batch_id
        left join service_station s on s.id=e.station_id
        left join sys_user m on m.id=e.mentor_user_id
        where e.user_id=?
        """, user.id());
    var result = common("EMPLOYEE", user, "个人学习主页");
    if (rows.isEmpty()) {
      result.put("profile", null);
      result.put("metrics", Map.of());
      result.put("action_items", List.of());
      result.put("learning_schedule", List.of());
      result.put("quarter_scores", List.of());
      result.put("mentor_feedback", List.of());
      result.put("completed_tasks", List.of());
      return result;
    }

    Map<String, Object> profile = rows.get(0);
    long employeeId = number(profile.get("employee_id")).longValue();
    result.put("profile", profile);

    Map<String, Object> task = db.queryForMap("""
        select count(*) task_total,
          coalesce(sum(a.status<>'APPROVED'),0) pending_tasks,
          coalesce(sum(a.status='PENDING_REVIEW'),0) awaiting_review_tasks,
          coalesce(sum(a.status='APPROVED'),0) completed_tasks,
          coalesce(sum(a.status='OVERDUE'),0) overdue_tasks
        from task_assignment a where a.employee_id=?
        """, employeeId);
    Map<String, Object> course = db.queryForMap("""
        select count(*) enrolled_courses,
          coalesce(sum(s.ends_at>=now()),0) upcoming_courses,
          coalesce(sum(s.ends_at<now()),0) finished_courses,
          coalesce(sum(s.ends_at<now() and exists(
            select 1 from attendance a where a.session_id=s.id and a.employee_id=ce.employee_id
          )),0) attended_courses
        from course_enrollment ce
        join course_session s on s.id=ce.session_id
        where ce.employee_id=?
        """, employeeId);
    var examRows = db.queryForList("""
        select count(*) assigned_exams,
          coalesce(sum(p.status='PUBLISHED' and p.ends_at>=now() and not exists(
            select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id and x.status='GRADED'
          )),0) pending_exams,
          coalesce(sum(exists(
            select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id and x.status='GRADED'
          )),0) completed_exams,
          coalesce((select round(avg(x.total_score),1) from exam_attempt x
            where x.employee_id=ea.employee_id and x.status='GRADED' and x.published=true),0) exam_average
        from exam_assignment ea
        join exam_plan p on p.id=ea.plan_id
        where ea.employee_id=?
        group by ea.employee_id
        """, employeeId);
    Map<String, Object> exam = examRows.isEmpty() ? new LinkedHashMap<>() : examRows.get(0);
    Object latestQuarterScore = scalarOrNull("""
        select final_score from score_summary
        where employee_id=? and summary_type='QUARTER' and status='PUBLISHED'
        order by period_key desc,version desc limit 1
        """, employeeId);

    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("pending_tasks", number(task.get("pending_tasks")));
    metrics.put("completed_tasks", number(task.get("completed_tasks")));
    metrics.put("upcoming_courses", number(course.get("upcoming_courses")));
    metrics.put("pending_exams", number(exam.get("pending_exams")));
    metrics.put("latest_quarter_score", latestQuarterScore);
    metrics.put("task_completion_rate", percent(task.get("completed_tasks"), task.get("task_total")));
    metrics.put("course_attendance_rate", percent(course.get("attended_courses"), course.get("finished_courses")));
    metrics.put("exam_completion_rate", percent(exam.get("completed_exams"), exam.get("assigned_exams")));
    metrics.put("exam_average", number(exam.get("exam_average")));
    result.put("metrics", metrics);

    var actions = new ArrayList<Map<String, Object>>();
    for (var row : db.queryForList("""
        select a.id,t.title,t.deadline,a.status
        from task_assignment a join challenge_task t on t.id=a.task_id
        where a.employee_id=? and a.status<>'APPROVED'
        order by field(a.status,'OVERDUE','RETURNED','NOT_SUBMITTED','PENDING_REVIEW'),t.deadline
        limit 6
        """, employeeId)) {
      String status = text(row.get("status"));
      actions.add(action(
          "TASK",
          text(row.get("title")),
          switch (status) {
            case "OVERDUE" -> "任务已逾期，请尽快补交并说明情况";
            case "RETURNED" -> "成果已退回，请根据审核意见修改后重新提交";
            case "PENDING_REVIEW" -> "成果已提交，正在等待审核";
            default -> "任务待提交，请留意截止时间";
          },
          row.get("deadline"),
          status,
          "OVERDUE".equals(status) || "RETURNED".equals(status) ? "DANGER" : "WARNING",
          "/tasks"
      ));
    }
    for (var row : db.queryForList("""
        select p.id,p.name,p.starts_at,p.ends_at,
          case when exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id and x.status='IN_PROGRESS') then 'IN_PROGRESS'
               when p.starts_at>now() then 'UPCOMING' else 'READY' end status
        from exam_assignment ea join exam_plan p on p.id=ea.plan_id
        where ea.employee_id=? and p.status='PUBLISHED' and p.ends_at>=now()
          and not exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id and x.status='GRADED')
        order by p.starts_at limit 4
        """, employeeId)) {
      String status = text(row.get("status"));
      actions.add(action("EXAM", text(row.get("name")),
          "UPCOMING".equals(status) ? "考试即将开放，请提前安排复习" : "考试已开放，请在截止前完成",
          row.get("ends_at"), status, "IN_PROGRESS".equals(status) ? "DANGER" : "PRIMARY", "/exams/my"));
    }
    actions.sort(Comparator.comparing(item -> sortableTime(item.get("due_at"))));
    result.put("action_items", actions.stream().limit(8).toList());

    var schedule = new ArrayList<Map<String, Object>>();
    for (var row : db.queryForList("""
        select s.id,s.title,c.name course_name,s.location,s.starts_at,s.ends_at,
          exists(select 1 from attendance a where a.session_id=s.id and a.employee_id=ce.employee_id) checked_in
        from course_enrollment ce
        join course_session s on s.id=ce.session_id
        join course c on c.id=s.course_id
        where ce.employee_id=? and s.ends_at>=now() and s.starts_at<date_add(now(),interval 30 day)
        order by s.starts_at limit 8
        """, employeeId)) {
      row.put("type", "COURSE");
      row.put("route", "/courses/my");
      schedule.add(row);
    }
    for (var row : db.queryForList("""
        select p.id,p.name title,ep.name exam_name,p.starts_at,p.ends_at
        from exam_assignment ea
        join exam_plan p on p.id=ea.plan_id
        join exam_paper ep on ep.id=p.paper_id
        where ea.employee_id=? and p.status='PUBLISHED' and p.ends_at>=now()
          and p.starts_at<date_add(now(),interval 30 day)
        order by p.starts_at limit 8
        """, employeeId)) {
      row.put("type", "EXAM");
      row.put("route", "/exams/my");
      schedule.add(row);
    }
    schedule.sort(Comparator.comparing(item -> sortableTime(item.get("starts_at"))));
    result.put("learning_schedule", schedule.stream().limit(8).toList());

    result.put("quarter_scores", db.queryForList("""
        select period_key,final_score,published_at,component_snapshot,quarter_snapshot
        from score_summary
        where employee_id=? and summary_type='QUARTER' and status='PUBLISHED'
        order by period_key desc,version desc limit 4
        """, employeeId));
    result.put("mentor_feedback", db.queryForList("""
        select date_format(m.period_month,'%Y-%m') period_key,m.score,m.comment,m.submitted_at,
          u.display_name evaluator_name
        from monthly_evaluation m
        join sys_user u on u.id=m.evaluator_user_id
        where m.employee_id=? and m.evaluator_type='MENTOR'
          and exists(select 1 from score_summary s where s.employee_id=m.employee_id
            and s.summary_type='MONTH' and s.period_key=date_format(m.period_month,'%Y-%m') and s.status='PUBLISHED')
        order by m.period_month desc limit 4
        """, employeeId));
    result.put("completed_tasks", db.queryForList("""
        select a.id,t.title,t.deadline,a.final_score,
          (select max(s.reviewed_at) from task_submission s where s.assignment_id=a.id and s.status='APPROVED') completed_at
        from task_assignment a join challenge_task t on t.id=a.task_id
        where a.employee_id=? and a.status='APPROVED'
        order by completed_at desc,a.id desc limit 6
        """, employeeId));
    return result;
  }

  private Map<String, Object> managerDashboard(CurrentUser user) {
    var result = common("MANAGER", user, scopeLabel(user));
    var scope = permissions.employeeFilter("e");
    Object[] scopeArgs = scope.args().toArray();
    String month = YearMonth.now(ZONE).toString();

    Map<String, Object> task = db.queryForMap("""
        select count(*) assignment_total,
          coalesce(sum(a.status='APPROVED'),0) approved,
          coalesce(sum(a.status='PENDING_REVIEW'),0) pending_review,
          coalesce(sum(a.status='RETURNED'),0) returned,
          coalesce(sum(a.status='OVERDUE'),0) overdue
        from task_assignment a join employee e on e.id=a.employee_id
        where e.status='ACTIVE'
        """ + scope.sql(), scopeArgs);
    Map<String, Object> course = db.queryForMap("""
        select count(distinct s.id) session_total,
          count(distinct case when s.starts_at>now() then s.id end) upcoming_sessions,
          count(distinct case when now() between s.starts_at and s.ends_at then s.id end) ongoing_sessions,
          coalesce(sum(s.ends_at<now()),0) finished_enrollments,
          coalesce(sum(s.ends_at<now() and exists(select 1 from attendance a
            where a.session_id=s.id and a.employee_id=ce.employee_id)),0) attended_enrollments
        from course_enrollment ce
        join course_session s on s.id=ce.session_id
        join employee e on e.id=ce.employee_id
        where e.status='ACTIVE'
        """ + scope.sql(), scopeArgs);
    Map<String, Object> exam = db.queryForMap("""
        select count(distinct p.id) plan_total,
          count(distinct case when p.status='PUBLISHED' and p.starts_at>now() then p.id end) upcoming_plans,
          count(distinct case when p.status='PUBLISHED' and now() between p.starts_at and p.ends_at then p.id end) open_plans,
          count(*) assigned_total,
          coalesce(sum(exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id and x.status='GRADED')),0) completed_total,
          coalesce(sum(p.ends_at<now() and not exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id)),0) absent_total,
          coalesce(sum(exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=ea.employee_id and x.status='PENDING_REVIEW')),0) pending_review
        from exam_assignment ea
        join exam_plan p on p.id=ea.plan_id
        join employee e on e.id=ea.employee_id
        where e.status='ACTIVE' and p.status='PUBLISHED'
        """ + scope.sql(), scopeArgs);

    String evaluationSql = """
        select count(*) employee_total,
          coalesce(sum(exists(select 1 from score_summary s where s.employee_id=e.id and s.summary_type='MONTH'
            and s.period_key=? and s.status='PUBLISHED')),0) published_total,
          coalesce(sum(exists(select 1 from score_summary s where s.employee_id=e.id and s.summary_type='MONTH'
            and s.period_key=? and s.status='DRAFT')),0) draft_total,
          coalesce((select round(avg(s.final_score),1) from score_summary s join employee x on x.id=s.employee_id
            where s.summary_type='MONTH' and s.period_key=? and s.status='PUBLISHED'
        """ + scope.sql().replace("e.", "x.") + """
          ),0) average_score
        from employee e where e.status='ACTIVE'
        """ + scope.sql();
    Map<String, Object> evaluation = db.queryForMap(evaluationSql, evaluationQueryArgs(month, scope));

    long employeeCount = number(evaluation.get("employee_total")).longValue();
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("employee_count", employeeCount);
    metrics.put("pending_actions", pendingActionCount(user, task, exam, evaluation, month, scope));
    metrics.put("task_completion_rate", percent(task.get("approved"), task.get("assignment_total")));
    metrics.put("course_attendance_rate", percent(course.get("attended_enrollments"), course.get("finished_enrollments")));
    metrics.put("exam_completion_rate", percent(exam.get("completed_total"), exam.get("assigned_total")));
    metrics.put("evaluation_coverage", percent(evaluation.get("published_total"), evaluation.get("employee_total")));
    metrics.put("evaluation_average", number(evaluation.get("average_score")));
    result.put("metrics", metrics);

    task.put("completion_rate", metrics.get("task_completion_rate"));
    course.put("attendance_rate", metrics.get("course_attendance_rate"));
    exam.put("completion_rate", metrics.get("exam_completion_rate"));
    evaluation.put("coverage", metrics.get("evaluation_coverage"));
    evaluation.put("period_key", month);
    result.put("operations", Map.of("task", task, "course", course, "exam", exam, "evaluation", evaluation));

    var queue = new ArrayList<Map<String, Object>>();
    if (user.can(Permissions.TASK_SCORE)) queue.add(queueItem("TASK_REVIEW", "评分任务成果",
        "仅处理分配给你的成果评分", pendingTaskScores(user.id()),
        "/task-scoring?status=MY_PENDING", "WARNING"));
    if (user.can(Permissions.EXAM_MANAGE)) {
      queue.add(queueItem("EXAM_REVIEW", "批阅主观题",
          "完成主观题评分后才能形成正式考试成绩", number(exam.get("pending_review")).longValue(),
          "/exams/results?focus=review", "DANGER"));
    }
    if (user.can(Permissions.EVALUATION_SUBMIT)) {
      queue.add(queueItem("MANUAL_EVALUATION", manualEvaluationTitle(user.role()),
          "完成本月职责范围内的人工评价", pendingManualScores(user, month, scope),
          "/evaluation/monthly", "WARNING"));
    }
    if (user.can(Permissions.EVALUATION_MANAGE)) {
      long unfinished = Math.max(0, employeeCount - number(evaluation.get("published_total")).longValue());
      queue.add(queueItem("EVALUATION_PROGRESS", "推进月度评价", "检查缺失项、生成汇总并发布月度结果", unfinished,
          "/evaluation/workbench", "PRIMARY"));
    }
    if (user.can(Permissions.ATTENDANCE_MANAGE)) {
      long missingAttendance = scopedCount("""
          select count(*) from course_enrollment ce
          join course_session s on s.id=ce.session_id
          join employee e on e.id=ce.employee_id
          where s.ends_at<now() and s.ends_at>=date_sub(now(),interval 30 day)
            and not exists(select 1 from attendance a where a.session_id=s.id and a.employee_id=ce.employee_id)
          """, scope);
      queue.add(queueItem("ATTENDANCE", "补齐课程签到", "核对近 30 天已结束场次的缺失签到记录", missingAttendance,
          "/courses/attendance", "NEUTRAL"));
    }
    result.put("work_queue", queue);

    var schedule = new ArrayList<Map<String, Object>>();
    for (var row : scopedRows("""
        select s.id,s.title,c.name course_name,s.location,s.starts_at,s.ends_at,
          count(distinct ce.employee_id) target_count
        from course_session s
        join course c on c.id=s.course_id
        join course_enrollment ce on ce.session_id=s.id
        join employee e on e.id=ce.employee_id
        where s.ends_at>=now() and s.starts_at<date_add(now(),interval 30 day)
        """, " group by s.id,s.title,c.name,s.location,s.starts_at,s.ends_at order by s.starts_at limit 8", scope)) {
      row.put("type", "COURSE");
      row.put("route", user.can(Permissions.COURSE_MANAGE) ? "/courses/sessions" : "/courses/attendance");
      schedule.add(row);
    }
    for (var row : scopedRows("""
        select p.id,p.name title,ep.name exam_name,p.starts_at,p.ends_at,
          count(distinct ea.employee_id) target_count
        from exam_plan p
        join exam_paper ep on ep.id=p.paper_id
        join exam_assignment ea on ea.plan_id=p.id
        join employee e on e.id=ea.employee_id
        where p.status='PUBLISHED' and p.ends_at>=now() and p.starts_at<date_add(now(),interval 30 day)
        """, " group by p.id,p.name,ep.name,p.starts_at,p.ends_at order by p.starts_at limit 8", scope)) {
      row.put("type", "EXAM");
      row.put("route", user.can(Permissions.EXAM_MANAGE) ? "/exams/plans" : "/exams/results");
      schedule.add(row);
    }
    schedule.sort(Comparator.comparing(item -> sortableTime(item.get("starts_at"))));
    result.put("schedule", schedule.stream().limit(10).toList());

    var attentionArgs = new ArrayList<Object>();
    attentionArgs.add(month);
    attentionArgs.addAll(scope.args());
    var attention = db.queryForList("""
        select e.id,e.name,e.employee_no,b.name batch_name,st.name station_name,
          (select count(*) from task_assignment a where a.employee_id=e.id and a.status='OVERDUE') overdue_tasks,
          (select count(*) from task_assignment a where a.employee_id=e.id and a.status='RETURNED') returned_tasks,
          (select count(*) from exam_assignment ea join exam_plan p on p.id=ea.plan_id
            where ea.employee_id=e.id and p.status='PUBLISHED' and p.ends_at<now()
              and not exists(select 1 from exam_attempt x where x.plan_id=p.id and x.employee_id=e.id)) absent_exams,
          not exists(select 1 from score_summary ss where ss.employee_id=e.id and ss.summary_type='MONTH'
            and ss.period_key=? and ss.status='PUBLISHED') evaluation_missing,
          (select ss.final_score from score_summary ss where ss.employee_id=e.id and ss.status='PUBLISHED'
            order by ss.generated_at desc limit 1) latest_score
        from employee e
        left join talent_batch b on b.id=e.batch_id
        left join service_station st on st.id=e.station_id
        where e.status='ACTIVE'
        """ + scope.sql() + " having overdue_tasks+returned_tasks+absent_exams+evaluation_missing>0 " +
        "order by overdue_tasks+returned_tasks+absent_exams+evaluation_missing desc,e.employee_no limit 8",
        attentionArgs.toArray());
    for (var row : attention) {
      long issueCount = number(row.get("overdue_tasks")).longValue()
          + number(row.get("returned_tasks")).longValue()
          + number(row.get("absent_exams")).longValue()
          + (truthy(row.get("evaluation_missing")) ? 1 : 0);
      row.put("issue_count", issueCount);
      row.put("risk_level", issueCount >= 3 ? "HIGH" : issueCount >= 2 ? "MEDIUM" : "LOW");
    }
    result.put("attention_employees", attention);
    return result;
  }

  private Map<String, Object> common(String audience, CurrentUser user, String scopeLabel) {
    var result = new LinkedHashMap<String, Object>();
    result.put("audience", audience);
    result.put("role", user.role());
    result.put("role_label", roleLabel(user.role()));
    result.put("display_name", user.displayName());
    result.put("scope_label", scopeLabel);
    result.put("period_key", YearMonth.now(ZONE).toString());
    return result;
  }

  private Map<String, Object> action(String type, String title, String description, Object dueAt,
                                     String status, String tone, String route) {
    var item = new LinkedHashMap<String, Object>();
    item.put("type", type);
    item.put("title", title);
    item.put("description", description);
    item.put("due_at", dueAt);
    item.put("status", status);
    item.put("tone", tone);
    item.put("route", route);
    return item;
  }

  private Map<String, Object> queueItem(String code, String title, String description, long count,
                                        String route, String tone) {
    var item = new LinkedHashMap<String, Object>();
    item.put("code", code);
    item.put("title", title);
    item.put("description", description);
    item.put("count", count);
    item.put("route", route);
    item.put("tone", tone);
    return item;
  }

  private long pendingActionCount(CurrentUser user, Map<String, Object> task, Map<String, Object> exam,
                                  Map<String, Object> evaluation, String month,
                                  PermissionService.ScopeFilter scope) {
    long count = 0;
    if (user.can(Permissions.TASK_SCORE)) count += pendingTaskScores(user.id());
    if (user.can(Permissions.EXAM_MANAGE)) {
      count += number(exam.get("pending_review")).longValue();
      count += scopedCount("""
          select count(*) from exam_attempt a join employee e on e.id=a.employee_id
          where a.status='GRADED' and a.published=false
          """, scope);
    }
    if (user.can(Permissions.EVALUATION_SUBMIT)) count += pendingManualScores(user, month, scope);
    if (user.can(Permissions.EVALUATION_MANAGE)) {
      count += Math.max(0, number(evaluation.get("employee_total")).longValue()
          - number(evaluation.get("published_total")).longValue());
    }
    return count;
  }

  private long pendingTaskScores(Long userId) {
    Integer count = db.queryForObject("""
        select count(*)
        from task_submission_review r
        join task_submission s on s.id=r.submission_id
        where r.reviewer_user_id=? and r.status='PENDING' and s.status='PENDING_REVIEW'
        """, Integer.class, userId);
    return count == null ? 0 : count.longValue();
  }

  private long pendingManualScores(CurrentUser user, String month, PermissionService.ScopeFilter scope) {
    String component = switch (user.role()) {
      case "MENTOR" -> "MENTOR";
      case "STATION_MANAGER" -> "STATION";
      case "TRAINING_ADMIN" -> "TRAINING";
      default -> null;
    };
    if (component == null) return 0;
    String enabledColumn = component.toLowerCase() + "_enabled";
    var args = new ArrayList<Object>();
    args.add(month + "-01");
    args.add(month + "-01");
    args.add(component);
    args.addAll(scope.args());
    Number value = db.queryForObject("select count(*) from employee e where e.status='ACTIVE' " +
        "and coalesce((select s." + enabledColumn + " from score_scheme s where s.batch_id=e.batch_id " +
        "and s.status='PUBLISHED' and s.effective_month<=? order by s.effective_month desc,s.version desc limit 1),false)=true " +
        "and not exists(select 1 from monthly_evaluation m where m.employee_id=e.id and m.period_month=? and m.evaluator_type=?)" +
        scope.sql(), Number.class, args.toArray());
    return number(value).longValue();
  }

  private Object[] evaluationQueryArgs(String month, PermissionService.ScopeFilter scope) {
    var args = new ArrayList<Object>();
    args.add(month);
    args.add(month);
    args.add(month);
    args.addAll(scope.args());
    args.addAll(scope.args());
    return args.toArray();
  }

  private long scopedCount(String sql, PermissionService.ScopeFilter scope) {
    Number value = db.queryForObject(sql + scope.sql(), Number.class, scope.args().toArray());
    return number(value).longValue();
  }

  private List<Map<String, Object>> scopedRows(String base, String suffix,
                                               PermissionService.ScopeFilter scope) {
    return db.queryForList(base + scope.sql() + suffix, scope.args().toArray());
  }

  private Object scalarOrNull(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    return rows.isEmpty() ? null : rows.get(0).values().iterator().next();
  }

  private BigDecimal percent(Object numerator, Object denominator) {
    BigDecimal bottom = decimal(denominator);
    if (bottom.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
    return decimal(numerator).multiply(BigDecimal.valueOf(100))
        .divide(bottom, 1, RoundingMode.HALF_UP);
  }

  private Number number(Object value) {
    return value instanceof Number n ? n : BigDecimal.ZERO;
  }

  private BigDecimal decimal(Object value) {
    if (value instanceof BigDecimal number) return number;
    if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
    return BigDecimal.ZERO;
  }

  private boolean truthy(Object value) {
    return Boolean.TRUE.equals(value) || number(value).intValue() == 1;
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private LocalDateTime sortableTime(Object value) {
    if (value instanceof LocalDateTime dateTime) return dateTime;
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
    return LocalDateTime.MAX;
  }

  private String scopeLabel(CurrentUser user) {
    return switch (user.dataScope()) {
      case "MENTORED" -> "我负责指导的员工";
      case "STATION" -> "我负责服务站的员工";
      case "SELF" -> "个人范围";
      default -> "全部在职员工";
    };
  }

  private String roleLabel(String role) {
    return switch (role) {
      case "EMPLOYEE" -> "员工";
      case "MENTOR" -> "导师";
      case "STATION_MANAGER" -> "服务站负责人";
      case "TRAINING_ADMIN" -> "培训管理员";
      case "ADMIN" -> "管理员";
      case "SUPER_ADMIN" -> "超级管理员";
      default -> role;
    };
  }

  private String manualEvaluationTitle(String role) {
    return switch (role) {
      case "MENTOR" -> "提交导师评价";
      case "STATION_MANAGER" -> "提交站点评价";
      case "TRAINING_ADMIN" -> "提交培训评价";
      default -> "提交人工评价";
    };
  }
}
