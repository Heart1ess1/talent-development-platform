package com.talent.platform.task;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.*;
import com.talent.platform.security.*;
import com.talent.platform.storage.FileStorageService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
  private final JdbcTemplate db;
  private final FileStorageService storage;
  private final PermissionService permissions;
  private final AuditService audit;
  private final TaskStatusService taskStatus;
  private final TaskAttachmentService taskAttachments;

  public TaskController(JdbcTemplate db, FileStorageService storage, PermissionService permissions, AuditService audit,
                        TaskStatusService taskStatus, TaskAttachmentService taskAttachments) {
    this.db = db;
    this.storage = storage;
    this.permissions = permissions;
    this.audit = audit;
    this.taskStatus = taskStatus;
    this.taskAttachments = taskAttachments;
  }

  public record TaskRequest(@NotBlank String title, @NotBlank String description, String requirements,
                            @NotNull LocalDateTime deadline) {}
  public record AssignRequest(@NotNull Long taskId, Long batchId, Long businessUnitId, Long stationId) {}
  public record ManualDispatchRequest(@NotBlank String title, @NotBlank String description, String requirements,
                                      @NotNull LocalDateTime deadline, Long batchId, Long businessUnitId,
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
      Long batchId,
      Long businessUnitId,
      Long stationId) {}
  public record PlanDispatchResult(int targetEmployees, int createdTasks, int createdAssignments) {}
  public record PlanDispatchPreview(
      int targetEmployees,
      int selectedTasks,
      int reusedTasks,
      LocalDateTime deadline,
      List<String> taskTitles) {}
  public record ReviewRequest(@NotNull @Pattern(regexp = "APPROVE|RETURN") String decision, String comment,
                              @Min(0) @Max(100) Integer score) {}

  @GetMapping("/tasks")
  public ApiResponse<List<Map<String, Object>>> tasks() {
    taskStatus.refreshOverdueAssignments();
    var u = SecurityUtils.current();
    if ("ALL".equals(u.dataScope())) {
      var rows = db.queryForList("select t.id,t.title,t.description,t.requirements,t.deadline,u.display_name creator_name,(select count(*) from task_assignment a where a.task_id=t.id) assigned_count,(select count(*) from task_assignment a where a.task_id=t.id and a.status not in ('NOT_SUBMITTED','OVERDUE')) submitted_count,(select count(*) from task_assignment a where a.task_id=t.id and a.status='APPROVED') approved_count from challenge_task t join sys_user u on u.id=t.created_by order by t.id desc");
      addAttachments(rows);
      return ApiResponse.ok(rows);
    }
    var scope = permissions.employeeFilter("e");
    var rows = db.queryForList("select t.id,t.title,t.description,t.requirements,t.deadline,count(a.id) assigned_count,sum(a.status not in ('NOT_SUBMITTED','OVERDUE')) submitted_count,sum(a.status='APPROVED') approved_count from challenge_task t join task_assignment a on a.task_id=t.id join employee e on e.id=a.employee_id where 1=1" + scope.sql() + " group by t.id,t.title,t.description,t.requirements,t.deadline order by t.id desc", scope.args().toArray());
    addAttachments(rows);
    return ApiResponse.ok(rows);
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
    var detail = task(id);
    detail.put("attachments", taskAttachments.listForTask(id));
    return ApiResponse.ok(detail);
  }

  @GetMapping("/tasks/{id}/attachments")
  public ApiResponse<List<Map<String, Object>>> taskAttachments(@PathVariable Long id) {
    requireTaskReadAccess(id);
    return ApiResponse.ok(taskAttachments.listForTask(id));
  }

  @PostMapping(value = "/tasks/{id}/attachments", consumes = "multipart/form-data")
  public ApiResponse<Long> uploadTaskAttachment(
      @PathVariable Long id,
      @RequestParam MultipartFile file
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    task(id);
    Long attachmentId = taskAttachments.uploadForTask(id, file);
    audit.log("UPLOAD_TASK_ATTACHMENT", "TASK_ATTACHMENT", attachmentId, null,
        Map.of("taskId", id, "name",
            Optional.ofNullable(file.getOriginalFilename()).orElse("附件")));
    return ApiResponse.ok(attachmentId);
  }

  @DeleteMapping("/tasks/{id}/attachments/{attachmentId}")
  public ApiResponse<Void> deleteTaskAttachment(
      @PathVariable Long id,
      @PathVariable Long attachmentId
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = taskAttachments.attachment(attachmentId);
    if (before.get("challenge_task_id") == null
        || ((Number) before.get("challenge_task_id")).longValue() != id) {
      throw new BusinessException(404, "任务附件不存在");
    }
    taskAttachments.delete(attachmentId);
    audit.log("DELETE_TASK_ATTACHMENT", "TASK_ATTACHMENT", attachmentId, before, null);
    return ApiResponse.ok(null);
  }

  @GetMapping("/task-attachments/{id}")
  public ResponseEntity<?> downloadTaskAttachment(
      @PathVariable Long id,
      @RequestParam(required = false, defaultValue = "false") boolean inline
  ) {
    var attachment = taskAttachments.attachment(id);
    Object taskId = attachment.get("challenge_task_id");
    if (taskId != null) requireTaskReadAccess(((Number) taskId).longValue());
    else permissions.require(Permissions.TASK_MANAGE);
    String name = String.valueOf(attachment.get("original_name"));
    Object rawContentType = attachment.get("content_type");
    String contentType = rawContentType == null
        ? "application/octet-stream" : String.valueOf(rawContentType);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contentType))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                + URLEncoder.encode(name, StandardCharsets.UTF_8))
        .body(taskAttachments.storage().load(String.valueOf(attachment.get("storage_key"))));
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
    for (Long employeeId : targetEmployees(q.batchId(), q.businessUnitId(), q.stationId())) {
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
    var employeeIds = targetEmployees(q.batchId(), q.businessUnitId(), q.stationId());
    if (employeeIds.isEmpty()) throw new BusinessException(400, "未匹配到在职员工，无法下发任务");
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
    return ApiResponse.ok(taskProgressRows(id));
  }

  @GetMapping("/tasks/{id}/progress/export")
  public void exportTaskProgress(@PathVariable Long id, HttpServletResponse response) throws IOException {
    taskStatus.refreshOverdueAssignments();
    var rows = taskProgressRows(id).stream().map(row -> {
      var output = new TaskProgressExportRow();
      output.setEmployeeName(text(row.get("employee_name")));
      output.setEmployeeNo(text(row.get("employee_no")));
      output.setAssignedAt(text(row.get("assigned_at")));
      output.setSubmittedAt(text(row.get("submitted_at")));
      output.setStatus(taskStatusLabel(text(row.get("status"))));
      output.setScore(row.get("final_score") instanceof Number value ? value.intValue() : null);
      output.setSubmissionVersion(row.get("submission_version") instanceof Number value ? value.intValue() : null);
      output.setFileCount(row.get("file_count") instanceof Number value ? value.intValue() : 0);
      output.setReviewComment(text(row.get("review_comment")));
      return output;
    }).toList();
    String taskTitle = text(task(id).get("title"));
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
        + URLEncoder.encode(safeFilePart(taskTitle) + "-提交情况.xlsx", StandardCharsets.UTF_8));
    EasyExcel.write(response.getOutputStream(), TaskProgressExportRow.class)
        .sheet("提交情况")
        .doWrite(rows);
  }

  @GetMapping("/tasks/{id}/submissions/archive")
  public void exportTaskSubmissionArchive(@PathVariable Long id, HttpServletResponse response) throws IOException {
    requireTaskReadAccess(id);
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>();
    args.add(id);
    args.addAll(scope.args());
    var submissions = db.queryForList("""
        select s.id,s.submission_version,s.content,s.submitted_at,
               e.name employee_name,e.employee_no
        from task_submission s
        join task_assignment a on a.id=s.assignment_id
        join employee e on e.id=a.employee_id
        where a.task_id=?
        """ + scope.sql() + " order by e.employee_no,e.id,s.submission_version", args.toArray());
    if (submissions.isEmpty()) throw new BusinessException(400, "当前任务暂无可导出的提交资料");
    String taskTitle = text(task(id).get("title"));
    writeSubmissionArchive(response, submissions, safeFilePart(taskTitle) + "-全部提交文件.zip");
  }

  @GetMapping("/submissions/{id}/files/archive")
  public void exportSubmissionArchive(@PathVariable Long id, HttpServletResponse response) throws IOException {
    var rows = db.queryForList("""
        select s.id,s.assignment_id,s.submission_version,s.content,s.submitted_at,
               e.name employee_name,e.employee_no,t.title task_title
        from task_submission s
        join task_assignment a on a.id=s.assignment_id
        join challenge_task t on t.id=a.task_id
        join employee e on e.id=a.employee_id
        where s.id=?
        """, id);
    if (rows.isEmpty()) throw new BusinessException(404, "提交记录不存在");
    var submission = rows.get(0);
    assertAssignment(((Number) submission.get("assignment_id")).longValue(), SecurityUtils.current(), false);
    String filename = safeFilePart(submission.get("task_title")) + "-"
        + safeFilePart(submission.get("employee_name")) + "-提交资料.zip";
    writeSubmissionArchive(response, List.of(submission), filename);
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
    taskAttachments.deleteForTask(id);
    db.update("delete from challenge_task where id=?", id);
    audit.log("DELETE_TASK", "TASK", id, before, null);
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(null);
  }

  @PostMapping("/tasks/dispatch-plan")
  @Transactional
  public ApiResponse<PlanDispatchResult> dispatchPlan(@Valid @RequestBody PlanDispatchRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    requireDispatchablePlan(q.planId());
    var planTasks = selectedPlanTasks(q);

    var employees = targetEmployees(q.batchId(), q.businessUnitId(), q.stationId());
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
      taskAttachments.snapshotPlanTaskAttachments(
          ((Number) planTask.get("id")).longValue(), taskId);
      for (Long employeeId : employees) {
        createdAssignments += db.update("insert ignore into task_assignment(task_id,employee_id,assigned_by) values(?,?,?)",
            taskId, employeeId, u.id());
      }
    }
    var result = new PlanDispatchResult(employees.size(), createdTasks, createdAssignments);
    audit.log("DISPATCH_TRAINING_PLAN_TASKS", "TRAINING_PLAN", q.planId(), null,
        Map.of("planTaskIds", q.planTaskIds(), "deadlineMode", q.deadlineMode(), "deadline", deadline,
            "targetEmployees", employees.size(), "createdAssignments", createdAssignments));
    taskStatus.rescheduleNextDeadline();
    return ApiResponse.ok(result);
  }

  @PostMapping("/tasks/dispatch-plan/preview")
  public ApiResponse<PlanDispatchPreview> previewPlanDispatch(
      @Valid @RequestBody PlanDispatchRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    requireDispatchablePlan(q.planId());
    var planTasks = selectedPlanTasks(q);
    var employees = targetEmployees(q.batchId(), q.businessUnitId(), q.stationId());
    var deadline = resolveDeadline(q);
    var taskIds = planTasks.stream().map(row -> row.get("id")).toList();
    String placeholders = String.join(",", Collections.nCopies(taskIds.size(), "?"));
    var existingArgs = new ArrayList<Object>(taskIds);
    existingArgs.add(deadline.toLocalDate());
    Integer reusedTasks = db.queryForObject(
        "select count(*) from challenge_task where training_plan_task_id in ("
            + placeholders + ") and source_base_date=?",
        Integer.class,
        existingArgs.toArray());
    return ApiResponse.ok(new PlanDispatchPreview(
        employees.size(),
        planTasks.size(),
        reusedTasks == null ? 0 : reusedTasks,
        deadline,
        planTasks.stream().map(row -> String.valueOf(row.get("title"))).toList()));
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
    var rows = db.queryForList(sql, args.toArray());
    for (var row : rows) {
      row.put("attachments",
          taskAttachments.listForTask(((Number) row.get("task_id")).longValue()));
    }
    return ApiResponse.ok(rows);
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

  private List<Long> targetEmployees(Long batchId, Long businessUnitId, Long stationId) {
    var where = new StringBuilder(" where e.status='ACTIVE'");
    var args = new ArrayList<Object>();
    if (batchId == null && businessUnitId == null && stationId == null) {
      throw new BusinessException(400, "请选择批次、所属板块或服务站");
    }
    if (batchId != null) {
      where.append(" and e.batch_id=?");
      args.add(batchId);
    }
    if (businessUnitId != null) {
      where.append(" and e.business_unit_id=?");
      args.add(businessUnitId);
    }
    if (stationId != null) {
      where.append(" and e.station_id=?");
      args.add(stationId);
    }
    return db.queryForList("select e.id from employee e" + where, Long.class, args.toArray());
  }

  private void requireDispatchablePlan(Long planId) {
    var plans = db.queryForList("select id,name,enabled from training_plan where id=?", planId);
    if (plans.isEmpty()) throw new BusinessException(404, "培养计划不存在");
    if (!Boolean.TRUE.equals(plans.get(0).get("enabled"))) {
      throw new BusinessException(400, "培养计划已停用，不能下发任务");
    }
  }

  private List<Map<String, Object>> selectedPlanTasks(PlanDispatchRequest q) {
    var selectedTaskIds = new LinkedHashSet<>(q.planTaskIds());
    String placeholders = String.join(",", Collections.nCopies(selectedTaskIds.size(), "?"));
    var args = new ArrayList<Object>();
    args.add(q.planId());
    args.addAll(selectedTaskIds);
    var planTasks = db.queryForList(
        "select id,title,description,requirements from training_plan_task "
            + "where plan_id=? and id in (" + placeholders + ") order by sort_order,id",
        args.toArray());
    if (planTasks.size() != selectedTaskIds.size()) {
      throw new BusinessException(400, "所选计划任务不存在或不属于该培养计划");
    }
    return planTasks;
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

  private List<Map<String, Object>> taskProgressRows(Long taskId) {
    requireTaskReadAccess(taskId);
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>();
    args.add(taskId);
    args.addAll(scope.args());
    String sql = "select a.id,a.status,a.assigned_at,a.final_score,"
        + "e.id employee_id,e.name employee_name,e.employee_no,"
        + "s.id submission_id,s.submitted_at,s.status submission_status,s.submission_version,s.review_comment,"
        + "(select count(*) from stored_file f where f.submission_id=s.id) file_count "
        + "from task_assignment a join challenge_task t on t.id=a.task_id join employee e on e.id=a.employee_id "
        + "left join task_submission s on s.id=(select s2.id from task_submission s2 where s2.assignment_id=a.id order by s2.submission_version desc limit 1) "
        + "where a.task_id=?" + scope.sql() + " order by a.assigned_at,e.id";
    return db.queryForList(sql, args.toArray());
  }

  private void writeSubmissionArchive(
      HttpServletResponse response,
      List<Map<String, Object>> submissions,
      String filename
  ) throws IOException {
    var archiveFiles = new LinkedHashMap<Long, List<SubmissionArchiveFile>>();
    for (var submission : submissions) {
      Long submissionId = ((Number) submission.get("id")).longValue();
      var files = db.queryForList(
          "select id,original_name,storage_key from stored_file where submission_id=? order by id",
          submissionId);
      var resolvedFiles = new ArrayList<SubmissionArchiveFile>();
      for (var file : files) {
        Resource resource = null;
        try {
          resource = storage.load(text(file.get("storage_key")));
        } catch (BusinessException exception) {
          if (exception.getCode() != 404) throw exception;
        }
        resolvedFiles.add(new SubmissionArchiveFile(
            text(file.get("id")),
            text(file.get("original_name")),
            resource));
      }
      archiveFiles.put(submissionId, resolvedFiles);
    }

    response.setContentType("application/zip");
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
        + URLEncoder.encode(filename, StandardCharsets.UTF_8));
    try (var zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
      for (var submission : submissions) {
        Long submissionId = ((Number) submission.get("id")).longValue();
        String employeeName = safeFilePart(submission.get("employee_name"));
        String employeeNo = text(submission.get("employee_no")).isBlank()
            ? "" : "（" + safeFilePart(submission.get("employee_no")) + "）";
        String employeeFolder = employeeName + employeeNo;
        String version = submission.get("submission_version") instanceof Number value
            ? String.valueOf(value.intValue()) : "1";
        String folder = employeeFolder + "/第" + version + "版/";
        String content = text(submission.get("content"));
        if (!content.isBlank()) {
          putZipText(zip, folder + "提交说明.txt", content);
        }
        for (var file : archiveFiles.getOrDefault(submissionId, List.of())) {
          String entryName = folder + file.id() + "-" + safeFilePart(file.originalName());
          if (file.resource() == null) {
            putZipText(zip, entryName + ".缺失说明.txt",
                "原附件“" + file.originalName() + "”的物理文件已不存在，请联系系统管理员核查存储或备份。");
            continue;
          }
          zip.putNextEntry(new ZipEntry(entryName));
          try (var input = file.resource().getInputStream()) {
            input.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
    }
  }

  private void putZipText(ZipOutputStream zip, String entryName, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(entryName));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private String taskStatusLabel(String status) {
    return switch (status) {
      case "NOT_SUBMITTED" -> "未提交";
      case "PENDING_REVIEW" -> "待审核";
      case "APPROVED" -> "已通过";
      case "RETURNED" -> "已退回";
      case "OVERDUE" -> "已逾期";
      default -> status;
    };
  }

  private String safeFilePart(Object value) {
    String result = text(value)
        .replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_")
        .replace("..", "_")
        .trim();
    if (result.isBlank()) return "未命名";
    return result.length() > 80 ? result.substring(0, 80) : result;
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private record SubmissionArchiveFile(String id, String originalName, Resource resource) {}

  private Map<String, Object> task(Long id) {
    var rows = db.queryForList("select * from challenge_task where id=?", id);
    if (rows.isEmpty()) throw new BusinessException(404, "任务不存在");
    return rows.get(0);
  }

  private void addAttachments(List<Map<String, Object>> rows) {
    for (var row : rows) {
      row.put("attachments",
          taskAttachments.listForTask(((Number) row.get("id")).longValue()));
    }
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
