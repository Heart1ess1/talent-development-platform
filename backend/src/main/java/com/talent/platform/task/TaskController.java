package com.talent.platform.task;

import com.talent.platform.common.*;
import com.talent.platform.security.*;
import com.talent.platform.storage.FileStorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
  private final JdbcTemplate db;
  private final FileStorageService storage;
  private final PermissionService permissions;
  private final AuditService audit;
  private final TaskStatusService taskStatus;

  public TaskController(JdbcTemplate db, FileStorageService storage, PermissionService permissions, AuditService audit,
                        TaskStatusService taskStatus) {
    this.db = db;
    this.storage = storage;
    this.permissions = permissions;
    this.audit = audit;
    this.taskStatus = taskStatus;
  }

  public record TaskRequest(@NotBlank String title, @NotBlank String description, String requirements,
                            @NotNull LocalDateTime deadline) {}
  public record AssignRequest(@NotNull Long taskId, List<Long> employeeIds, Long batchId, Long stationId) {}
  public record ManualDispatchRequest(@NotBlank String title, @NotBlank String description, String requirements,
                                      @NotNull LocalDateTime deadline, List<Long> employeeIds, Long batchId,
                                      Long stationId) {}
  public record ManualDispatchResult(Long taskId, int assignedEmployees) {}
  public record PlanDispatchRequest(
      @NotNull Long planId,
      @NotEmpty List<@NotNull Long> planTaskIds,
      @Size(max = 128) String taskTitle,
      @NotBlank @Pattern(regexp = "OFFSET|ABSOLUTE") String deadlineMode,
      LocalDate baseDate,
      @Min(0) Integer offsetDays,
      LocalDate deadlineDate,
      List<Long> employeeIds,
      Long batchId,
      Long stationId) {}
  public record PlanDispatchResult(int targetEmployees, int createdTasks, int createdAssignments) {}
  public record ReviewRequest(@NotNull @Pattern(regexp = "APPROVE|RETURN") String decision, String comment,
                              @Min(0) @Max(100) Integer score) {}

  @GetMapping("/tasks")
  public ApiResponse<List<Map<String, Object>>> tasks() {
    taskStatus.refreshOverdueAssignments();
    var u = SecurityUtils.current();
    if ("ALL".equals(u.dataScope())) {
      return ApiResponse.ok(db.queryForList("select t.id,t.title,t.description,t.requirements,t.deadline,u.display_name creator_name,(select count(*) from task_assignment a where a.task_id=t.id) assigned_count,(select count(*) from task_assignment a where a.task_id=t.id and a.status not in ('NOT_SUBMITTED','OVERDUE')) submitted_count,(select count(*) from task_assignment a where a.task_id=t.id and a.status='APPROVED') approved_count from challenge_task t join sys_user u on u.id=t.created_by order by t.id desc"));
    }
    var scope = permissions.employeeFilter("e");
    return ApiResponse.ok(db.queryForList("select t.id,t.title,t.description,t.requirements,t.deadline,count(a.id) assigned_count,sum(a.status not in ('NOT_SUBMITTED','OVERDUE')) submitted_count,sum(a.status='APPROVED') approved_count from challenge_task t join task_assignment a on a.task_id=t.id join employee e on e.id=a.employee_id where 1=1" + scope.sql() + " group by t.id,t.title,t.description,t.requirements,t.deadline order by t.id desc", scope.args().toArray()));
  }

  @PostMapping("/tasks")
  public ApiResponse<Long> create(@Valid @RequestBody TaskRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    db.update("insert into challenge_task(title,description,requirements,deadline,created_by) values(?,?,?,?,?)",
        q.title(), q.description(), q.requirements(), q.deadline(), SecurityUtils.current().id());
    Long id = db.queryForObject("select last_insert_id()", Long.class);
    audit.log("CREATE_TASK", "TASK", id, null, q);
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(id);
  }

  @GetMapping("/tasks/{id}")
  public ApiResponse<Map<String, Object>> taskDetail(@PathVariable Long id) {
    requireTaskReadAccess(id);
    return ApiResponse.ok(task(id));
  }

  @PutMapping("/tasks/{id}")
  public ApiResponse<Void> updateTask(@PathVariable Long id, @Valid @RequestBody TaskRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = task(id);
    db.update("update challenge_task set title=?,description=?,requirements=?,deadline=? where id=?",
        q.title(), q.description(), q.requirements(), q.deadline(), id);
    audit.log("UPDATE_TASK", "TASK", id, before, q);
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(null);
  }

  @PostMapping("/assignments/assign")
  @Transactional
  public ApiResponse<Integer> assign(@Valid @RequestBody AssignRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var u = SecurityUtils.current();
    int count = 0;
    for (Long employeeId : targetEmployees(q.employeeIds(), q.batchId(), q.stationId())) {
      count += db.update("insert ignore into task_assignment(task_id,employee_id,assigned_by) values(?,?,?)",
          q.taskId(), employeeId, u.id());
    }
    audit.log("ASSIGN_TASK", "TASK", q.taskId(), null, Map.of("count", count));
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(count);
  }

  @PostMapping("/tasks/dispatch-manual")
  @Transactional
  public ApiResponse<ManualDispatchResult> dispatchManual(@Valid @RequestBody ManualDispatchRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var employeeIds = targetEmployees(q.employeeIds(), q.batchId(), q.stationId());
    if (employeeIds.isEmpty()) throw new BusinessException(400, "未匹配到在职员工，无法下达任务");
    var user = SecurityUtils.current();
    db.update("insert into challenge_task(title,description,requirements,deadline,created_by) values(?,?,?,?,?)",
        q.title(), q.description(), q.requirements(), q.deadline(), user.id());
    Long taskId = db.queryForObject("select last_insert_id()", Long.class);
    int assignedEmployees = 0;
    for (Long employeeId : employeeIds) {
      assignedEmployees += db.update("insert ignore into task_assignment(task_id,employee_id,assigned_by) values(?,?,?)",
          taskId, employeeId, user.id());
    }
    var result = new ManualDispatchResult(taskId, assignedEmployees);
    audit.log("DISPATCH_MANUAL_TASK", "TASK", taskId, null, result);
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(result);
  }

  @GetMapping("/tasks/{id}/progress")
  public ApiResponse<List<Map<String, Object>>> taskProgress(@PathVariable Long id) {
    taskStatus.refreshOverdueAssignments();
    requireTaskReadAccess(id);
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>();
    args.add(id);
    args.addAll(scope.args());
    String sql = "select a.id,a.status,a.assigned_at,a.final_score,"
        + "e.id employee_id,e.name employee_name,e.employee_no,s.submitted_at,s.status submission_status,s.submission_version "
        + "from task_assignment a join challenge_task t on t.id=a.task_id join employee e on e.id=a.employee_id "
        + "left join task_submission s on s.id=(select s2.id from task_submission s2 where s2.assignment_id=a.id order by s2.submission_version desc limit 1) "
        + "where a.task_id=?" + scope.sql() + " order by a.assigned_at,e.id";
    return ApiResponse.ok(db.queryForList(sql, args.toArray()));
  }

  @DeleteMapping("/tasks/{id}")
  @Transactional
  public ApiResponse<Void> deleteTask(@PathVariable Long id) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = task(id);
    if (db.queryForObject("select count(*) from task_submission s join task_assignment a on a.id=s.assignment_id where a.task_id=?", Integer.class, id) > 0) {
      throw new BusinessException(400, "任务已有提交记录，不能删除");
    }
    db.update("delete from task_assignment where task_id=?", id);
    db.update("delete from challenge_task where id=?", id);
    audit.log("DELETE_TASK", "TASK", id, before, null);
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(null);
  }

  @PostMapping("/tasks/dispatch-plan")
  @Transactional
  public ApiResponse<PlanDispatchResult> dispatchPlan(@Valid @RequestBody PlanDispatchRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var plans = db.queryForList("select id,name,enabled from training_plan where id=?", q.planId());
    if (plans.isEmpty()) throw new BusinessException(404, "培养计划不存在");
    var plan = plans.get(0);
    if (!Boolean.TRUE.equals(plan.get("enabled"))) throw new BusinessException(400, "培养计划已停用，不能下达任务");

    var selectedTaskIds = new LinkedHashSet<>(q.planTaskIds());
    String placeholders = String.join(",", Collections.nCopies(selectedTaskIds.size(), "?"));
    var args = new ArrayList<Object>();
    args.add(q.planId());
    args.addAll(selectedTaskIds);
    var planTasks = db.queryForList("select id,title,description,requirements from training_plan_task where plan_id=? and id in (" + placeholders + ") order by sort_order,id", args.toArray());
    if (planTasks.size() != selectedTaskIds.size()) {
      throw new BusinessException(400, "所选计划任务不存在或不属于该培养计划");
    }

    var employees = targetEmployees(q.employeeIds(), q.batchId(), q.stationId());
    var deadline = resolveDeadline(q);
    var u = SecurityUtils.current();
    int createdTasks = 0;
    int createdAssignments = 0;
    for (var planTask : planTasks) {
      String title = q.taskTitle() == null || q.taskTitle().isBlank()
          ? String.valueOf(planTask.get("title"))
          : q.taskTitle().trim();
      createdTasks += db.update("insert ignore into challenge_task(title,description,requirements,deadline,created_by,training_plan_id,training_plan_task_id,source_base_date) values(?,?,?,?,?,?,?,?)",
          title, planTask.get("description"), planTask.get("requirements"), deadline, u.id(), q.planId(), planTask.get("id"), deadline.toLocalDate());
      Long taskId = db.queryForObject("select id from challenge_task where training_plan_task_id=? and source_base_date=?",
          Long.class, planTask.get("id"), deadline.toLocalDate());
      for (Long employeeId : employees) {
        createdAssignments += db.update("insert ignore into task_assignment(task_id,employee_id,assigned_by) values(?,?,?)",
            taskId, employeeId, u.id());
      }
    }
    var result = new PlanDispatchResult(employees.size(), createdTasks, createdAssignments);
    audit.log("DISPATCH_TRAINING_PLAN_TASKS", "TRAINING_PLAN", q.planId(), null,
        Map.of("planTaskIds", selectedTaskIds, "deadlineMode", q.deadlineMode(), "deadline", deadline,
            "targetEmployees", employees.size(), "createdAssignments", createdAssignments));
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(result);
  }

  @GetMapping("/assignments")
  public ApiResponse<List<Map<String, Object>>> assignments(@RequestParam(required = false) String status) {
    taskStatus.refreshOverdueAssignments();
    String where = " where 1=1";
    var scope = permissions.employeeFilter("e");
    where += scope.sql();
    var args = new ArrayList<Object>(scope.args());
    if (status != null && !status.isBlank()) {
      where += " and a.status=?";
      args.add(status);
    }
    String sql = "select a.id,a.task_id,a.employee_id,a.status,a.final_score,a.assigned_by,a.assigned_at,a.version,"
        + "t.title,t.description,t.requirements,t.deadline,e.name employee_name,e.employee_no,"
        + "(select max(s.submission_version) from task_submission s where s.assignment_id=a.id) latest_version "
        + "from task_assignment a join challenge_task t on t.id=a.task_id join employee e on e.id=a.employee_id"
        + where + " order by t.deadline,a.id desc";
    return ApiResponse.ok(db.queryForList(sql, args.toArray()));
  }

  @GetMapping("/assignments/pending-review")
  public ApiResponse<List<Map<String, Object>>> pendingReviewAssignments() {
    permissions.require(Permissions.TASK_REVIEW);
    taskStatus.refreshOverdueAssignments();
    var scope = permissions.employeeFilter("e");
    String sql = "select a.id,a.assigned_at,t.id task_id,t.title,t.deadline,e.id employee_id,e.name employee_name,e.employee_no,"
        + "s.id submission_id,s.submission_version,s.submitted_at "
        + "from task_assignment a join challenge_task t on t.id=a.task_id join employee e on e.id=a.employee_id "
        + "join task_submission s on s.assignment_id=a.id and s.status='PENDING_REVIEW' "
        + "where a.status='PENDING_REVIEW'" + scope.sql() + " order by s.submitted_at asc";
    return ApiResponse.ok(db.queryForList(sql, scope.args().toArray()));
  }

  @GetMapping("/assignments/{id}/submissions")
  public ApiResponse<List<Map<String, Object>>> submissions(@PathVariable Long id) {
    assertAssignment(id, SecurityUtils.current(), false);
    var rows = db.queryForList("select s.*,u.display_name reviewer_name from task_submission s left join sys_user u on u.id=s.reviewed_by where s.assignment_id=? order by s.submission_version desc", id);
    for (var row : rows) row.put("files", db.queryForList("select id,original_name,size,content_type from stored_file where submission_id=?", row.get("id")));
    return ApiResponse.ok(rows);
  }

  @PostMapping(value = "/assignments/{id}/submissions", consumes = "multipart/form-data")
  @PreAuthorize("hasRole('EMPLOYEE')")
  @Transactional
  public ApiResponse<Long> submit(@PathVariable Long id, @RequestPart(required = false) String content,
                                  @RequestPart(required = false) List<MultipartFile> files) {
    var u = SecurityUtils.current();
    assertAssignment(id, u, true);
    var assignment = db.queryForMap("select a.status,t.deadline from task_assignment a join challenge_task t on t.id=a.task_id where a.id=?", id);
    String assignmentStatus = String.valueOf(assignment.get("status"));
    if (!List.of("NOT_SUBMITTED", "RETURNED", "PENDING_REVIEW").contains(assignmentStatus)) {
      throw new BusinessException(400, "当前状态不能提交");
    }
    if (LocalDateTime.now().isAfter(asLocalDateTime(assignment.get("deadline")))) throw new BusinessException(400, "任务已截止");
    if ((content == null || content.isBlank()) && (files == null || files.isEmpty())) throw new BusinessException(400, "请填写说明或上传成果文件");
    if (files != null && files.size() > 5) throw new BusinessException(400, "单次最多上传 5 个文件");
    if ("PENDING_REVIEW".equals(assignmentStatus)) {
      db.update("update task_submission set status='SUPERSEDED' where assignment_id=? and status='PENDING_REVIEW'", id);
    }
    Integer version = db.queryForObject("select coalesce(max(submission_version),0)+1 from task_submission where assignment_id=?", Integer.class, id);
    db.update("insert into task_submission(assignment_id,submission_version,content) values(?,?,?)", id, version, content);
    Long submissionId = db.queryForObject("select last_insert_id()", Long.class);
    if (files != null) for (MultipartFile file : files) {
      validateFile(file);
      var stored = storage.store(file);
      db.update("insert into stored_file(submission_id,original_name,content_type,size,storage_key,uploader_user_id) values(?,?,?,?,?,?)",
          submissionId, file.getOriginalFilename(), stored.contentType(), stored.size(), stored.key(), u.id());
    }
    db.update("update task_assignment set status='PENDING_REVIEW',version=version+1 where id=?", id);
    return ApiResponse.ok(submissionId);
  }

  @PostMapping("/submissions/{id}/review")
  @Transactional
  public ApiResponse<Void> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest q) {
    permissions.require(Permissions.TASK_REVIEW);
    var u = SecurityUtils.current();
    var submission = db.queryForMap("select s.assignment_id,s.status,a.employee_id from task_submission s join task_assignment a on a.id=s.assignment_id where s.id=?", id);
    Long assignmentId = ((Number) submission.get("assignment_id")).longValue();
    permissions.requireEmployee(((Number) submission.get("employee_id")).longValue());
    if (!"PENDING_REVIEW".equals(submission.get("status"))) throw new BusinessException(400, "该版本已审核");
    if (!"APPROVE".equals(q.decision()) && !"RETURN".equals(q.decision())) throw new BusinessException(400, "审核结论无效");
    if ("APPROVE".equals(q.decision()) && q.score() == null) throw new BusinessException(400, "通过时必须评分");
    if ("RETURN".equals(q.decision()) && (q.comment() == null || q.comment().isBlank())) throw new BusinessException(400, "退回时必须填写意见");
    String state = "APPROVE".equals(q.decision()) ? "APPROVED" : "RETURNED";
    db.update("update task_submission set status=?,reviewed_by=?,reviewed_at=now(),review_comment=?,score=? where id=?", state, u.id(), q.comment(), q.score(), id);
    db.update("update task_assignment set status=?,final_score=?,version=version+1 where id=?", state, "APPROVED".equals(state) ? q.score() : null, assignmentId);
    audit.log("REVIEW_TASK", "SUBMISSION", id, null, q);
    return ApiResponse.ok(null);
  }

  private List<Long> targetEmployees(List<Long> employeeIds, Long batchId, Long stationId) {
    var where = new StringBuilder(" where e.status='ACTIVE'");
    var args = new ArrayList<Object>();
    var groups = new ArrayList<String>();
    if (employeeIds != null && !employeeIds.isEmpty()) {
      groups.add("e.id in (" + String.join(",", Collections.nCopies(employeeIds.size(), "?")) + ")");
      args.addAll(employeeIds);
    }
    if (batchId != null) { groups.add("e.batch_id=?"); args.add(batchId); }
    if (stationId != null) { groups.add("e.station_id=?"); args.add(stationId); }
    if (groups.isEmpty()) throw new BusinessException(400, "请选择人员、批次或服务站");
    where.append(" and (").append(String.join(" or ", groups)).append(")");
    return db.queryForList("select e.id from employee e" + where, Long.class, args.toArray());
  }

  private LocalDateTime resolveDeadline(PlanDispatchRequest q) {
    return switch (q.deadlineMode()) {
      case "OFFSET" -> {
        if (q.baseDate() == null || q.offsetDays() == null) throw new BusinessException(400, "日期偏移方式需要填写基准日期和偏移天数");
        yield q.baseDate().plusDays(q.offsetDays()).atTime(23, 59, 59);
      }
      case "ABSOLUTE" -> {
        if (q.deadlineDate() == null) throw new BusinessException(400, "绝对日期方式需要填写截止日期");
        yield q.deadlineDate().atTime(23, 59, 59);
      }
      default -> throw new BusinessException(400, "不支持的截止日期方式");
    };
  }

  private Map<String, Object> task(Long id) {
    var rows = db.queryForList("select * from challenge_task where id=?", id);
    if (rows.isEmpty()) throw new BusinessException(404, "任务不存在");
    return rows.get(0);
  }

  private void requireTaskReadAccess(Long taskId) {
    if ("ALL".equals(SecurityUtils.current().dataScope())) return;
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>();
    args.add(taskId);
    args.addAll(scope.args());
    Integer count = db.queryForObject("select count(*) from task_assignment a join employee e on e.id=a.employee_id where a.task_id=?" + scope.sql(), Integer.class, args.toArray());
    if (count == null || count == 0) throw new AccessDeniedException("无权访问该任务");
  }

  private void validateFile(MultipartFile file) {
    if (file.isEmpty()) throw new BusinessException(400, "不能上传空文件");
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase();
    if (!name.matches(".*\\.(pdf|doc|docx|xls|xlsx|ppt|pptx|png|jpg|jpeg|zip)$")) throw new BusinessException(400, "不支持的文件类型: " + name);
  }

  private LocalDateTime asLocalDateTime(Object value) {
    if (value instanceof LocalDateTime dateTime) return dateTime;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
    if (value instanceof java.util.Date date) return new java.sql.Timestamp(date.getTime()).toLocalDateTime();
    throw new BusinessException(400, "任务截止时间数据异常");
  }

  private void assertAssignment(Long id, CurrentUser user, boolean employeeOnly) {
    Long employeeId = db.queryForObject("select employee_id from task_assignment where id=?", Long.class, id);
    permissions.requireEmployee(employeeId);
    if (employeeOnly && !"EMPLOYEE".equals(user.role())) throw new AccessDeniedException("仅员工本人可提交");
  }
}
