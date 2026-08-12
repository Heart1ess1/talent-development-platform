package com.talent.platform.training;

import com.talent.platform.common.*;
import com.talent.platform.security.*;
import com.talent.platform.task.TaskAttachmentService;
import com.talent.platform.storage.UploadTicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1/training-plans")
public class TrainingPlanController {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;
  private final TaskAttachmentService taskAttachments;

  public TrainingPlanController(
      JdbcTemplate db,
      PermissionService permissions,
      AuditService audit,
      TaskAttachmentService taskAttachments
  ) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
    this.taskAttachments = taskAttachments;
  }

  public record PlanRequest(@NotBlank @Size(max = 128) String name, @Size(max = 2000) String description) {}
  public record CopyPlanRequest(@NotBlank @Size(max = 128) String name) {}
  public record PlanTaskRequest(
      @NotBlank @Size(max = 128) String title,
      @NotBlank @Size(max = 4000) String description,
      @Size(max = 4000) String requirements
  ) {}
  public record PlanTaskOrderItem(@NotNull Long id, @NotNull @Min(1) Integer sortOrder) {}
  public record ReplacePlanTaskOrderRequest(@NotNull List<@Valid PlanTaskOrderItem> items) {}
  public record EnabledRequest(@NotNull Boolean enabled) {}
  public record DirectUploadRequest(
      @NotBlank @Size(max = 255) String originalName,
      @Size(max = 128) String contentType,
      @Min(1) long size
  ) {}

  @GetMapping
  public ApiResponse<List<Map<String, Object>>> plans(
      @RequestParam(required = false, defaultValue = "") String keyword
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    String normalizedKeyword = keyword.trim();
    String pattern = "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%";
    return ApiResponse.ok(db.queryForList("""
        select p.*,u.display_name creator_name,
          (select count(*) from training_plan_task t where t.plan_id=p.id) task_count,
          (select count(*) from challenge_task ct where ct.training_plan_id=p.id) dispatched_task_count
        from training_plan p
        join sys_user u on u.id=p.created_by
        where (?='' or lower(p.name) like ? or lower(coalesce(p.description,'')) like ?)
        order by p.updated_at desc,p.id desc
        """, normalizedKeyword, pattern, pattern));
  }

  @GetMapping("/summary")
  public ApiResponse<Map<String, Object>> summary() {
    permissions.require(Permissions.TASK_MANAGE);
    return ApiResponse.ok(db.queryForMap("""
        select
          (select count(*) from training_plan) totalPlans,
          (select count(*) from training_plan where enabled=true) enabledPlans,
          (select count(*) from training_plan p where exists(
            select 1 from training_plan_task t where t.plan_id=p.id
          )) readyPlans,
          (select count(*) from training_plan_task) totalTasks,
          (select count(*) from challenge_task where training_plan_id is not null) dispatchedTasks
        """));
  }

  @PostMapping
  public ApiResponse<Long> createPlan(@Valid @RequestBody PlanRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    db.update("insert into training_plan(name,description,enabled,created_by) values(?,?,false,?)",
        q.name().trim(), trimToNull(q.description()), SecurityUtils.current().id());
    Long id = lastId();
    audit.log("CREATE_TRAINING_PLAN", "TRAINING_PLAN", id, null, q);
    return ApiResponse.ok(id);
  }

  @PutMapping("/{id}")
  public ApiResponse<Void> updatePlan(@PathVariable Long id, @Valid @RequestBody PlanRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan where id=?", id);
    db.update("update training_plan set name=?,description=?,version=version+1 where id=?",
        q.name().trim(), trimToNull(q.description()), id);
    audit.log("UPDATE_TRAINING_PLAN", "TRAINING_PLAN", id, before, q);
    return ApiResponse.ok(null);
  }

  @PutMapping("/{id}/enabled")
  public ApiResponse<Void> enablePlan(@PathVariable Long id, @Valid @RequestBody EnabledRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan where id=?", id);
    if (q.enabled() && db.queryForObject(
        "select count(*) from training_plan_task where plan_id=?", Integer.class, id) == 0) {
      throw new BusinessException(400, "请先至少编排一项任务，再启用培养计划");
    }
    db.update("update training_plan set enabled=?,version=version+1 where id=?", q.enabled(), id);
    audit.log(q.enabled() ? "ENABLE_TRAINING_PLAN" : "DISABLE_TRAINING_PLAN", "TRAINING_PLAN", id, before, q);
    return ApiResponse.ok(null);
  }

  @GetMapping("/{id}/tasks")
  public ApiResponse<List<Map<String, Object>>> planTasks(@PathVariable Long id) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan where id=?", id);
    var tasks = db.queryForList("""
        select t.id,t.plan_id,t.title,t.description,t.requirements,t.sort_order,t.created_at,
          (select count(*) from challenge_task ct where ct.training_plan_task_id=t.id) dispatched_count
        from training_plan_task t
        where t.plan_id=?
        order by t.sort_order,t.id
        """, id);
    for (var task : tasks) {
      task.put("attachments", taskAttachments.listForPlanTask(number(task.get("id"))));
    }
    return ApiResponse.ok(tasks);
  }

  @PostMapping("/{id}/tasks")
  public ApiResponse<Long> createPlanTask(@PathVariable Long id, @Valid @RequestBody PlanTaskRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan where id=?", id);
    Integer sortOrder = db.queryForObject("select coalesce(max(sort_order),0)+1 from training_plan_task where plan_id=?", Integer.class, id);
    db.update("insert into training_plan_task(plan_id,title,description,requirements,sort_order) values(?,?,?,?,?)",
        id, q.title().trim(), q.description().trim(), trimToNull(q.requirements()), sortOrder);
    Long taskId = lastId();
    audit.log("CREATE_TRAINING_PLAN_TASK", "TRAINING_PLAN_TASK", taskId, null, q);
    return ApiResponse.ok(taskId);
  }

  @PutMapping("/{planId}/tasks/{taskId}")
  public ApiResponse<Void> updatePlanTask(@PathVariable Long planId, @PathVariable Long taskId, @Valid @RequestBody PlanTaskRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan_task where id=? and plan_id=?", taskId, planId);
    db.update("update training_plan_task set title=?,description=?,requirements=? where id=? and plan_id=?",
        q.title().trim(), q.description().trim(), trimToNull(q.requirements()), taskId, planId);
    audit.log("UPDATE_TRAINING_PLAN_TASK", "TRAINING_PLAN_TASK", taskId, before, q);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/{planId}/tasks/{taskId}")
  @Transactional
  public ApiResponse<Void> deletePlanTask(@PathVariable Long planId, @PathVariable Long taskId) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan_task where id=? and plan_id=?", taskId, planId);
    if (db.queryForObject("select count(*) from challenge_task where training_plan_task_id=?", Integer.class, taskId) > 0) {
      throw new BusinessException(400, "该计划任务已下发，不能删除");
    }
    taskAttachments.deleteForPlanTask(taskId);
    db.update("delete from training_plan_task where id=? and plan_id=?", taskId, planId);
    audit.log("DELETE_TRAINING_PLAN_TASK", "TRAINING_PLAN_TASK", taskId, before, null);
    return ApiResponse.ok(null);
  }

  @PutMapping("/{id}/tasks/order")
  @Transactional
  public ApiResponse<Void> reorderPlanTasks(@PathVariable Long id, @Valid @RequestBody ReplacePlanTaskOrderRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan where id=?", id);
    int taskCount = db.queryForObject("select count(*) from training_plan_task where plan_id=?", Integer.class, id);
    if (q.items().size() != taskCount) throw new BusinessException(400, "请提交当前计划的完整任务顺序");
    var ids = new HashSet<Long>();
    var orders = new HashSet<Integer>();
    for (var item : q.items()) {
      if (!ids.add(item.id()) || !orders.add(item.sortOrder())) throw new BusinessException(400, "计划任务顺序重复");
      if (db.queryForObject("select count(*) from training_plan_task where id=? and plan_id=?", Integer.class, item.id(), id) == 0) {
        throw new BusinessException(404, "计划任务不存在");
      }
    }
    for (int order = 1; order <= taskCount; order++) {
      if (!orders.contains(order)) throw new BusinessException(400, "计划任务顺序必须从 1 连续排列");
    }
    db.update("update training_plan_task set sort_order=sort_order+100000 where plan_id=?", id);
    for (var item : q.items()) db.update("update training_plan_task set sort_order=? where id=? and plan_id=?", item.sortOrder(), item.id(), id);
    audit.log("REORDER_TRAINING_PLAN_TASKS", "TRAINING_PLAN", id, null, q);
    return ApiResponse.ok(null);
  }

  @GetMapping("/{planId}/tasks/{taskId}/attachments")
  public ApiResponse<List<Map<String, Object>>> planTaskAttachments(
      @PathVariable Long planId,
      @PathVariable Long taskId
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan_task where id=? and plan_id=?", taskId, planId);
    return ApiResponse.ok(taskAttachments.listForPlanTask(taskId));
  }

  @PostMapping(value = "/{planId}/tasks/{taskId}/attachments", consumes = "multipart/form-data")
  public ApiResponse<Long> uploadPlanTaskAttachment(
      @PathVariable Long planId,
      @PathVariable Long taskId,
      @RequestParam MultipartFile file
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan_task where id=? and plan_id=?", taskId, planId);
    Long attachmentId = taskAttachments.uploadForPlanTask(taskId, file);
    audit.log("UPLOAD_PLAN_TASK_ATTACHMENT", "TASK_ATTACHMENT", attachmentId, null,
        Map.of("planId", planId, "taskId", taskId,
            "name", Optional.ofNullable(file.getOriginalFilename()).orElse("附件")));
    return ApiResponse.ok(attachmentId);
  }

  @PostMapping("/{planId}/tasks/{taskId}/attachments/upload-ticket")
  public ApiResponse<UploadTicketService.UploadTicket> createPlanTaskAttachmentUploadTicket(
      @PathVariable Long planId,
      @PathVariable Long taskId,
      @Valid @RequestBody DirectUploadRequest request
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan_task where id=? and plan_id=?", taskId, planId);
    return ApiResponse.ok(taskAttachments.createUploadTicket(
        "plan-task-attachment", taskId, request.originalName(), request.contentType(), request.size()));
  }

  @PostMapping("/{planId}/tasks/{taskId}/attachments/upload-complete/{ticketId}")
  public ApiResponse<Long> completePlanTaskAttachmentUpload(
      @PathVariable Long planId,
      @PathVariable Long taskId,
      @PathVariable UUID ticketId
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan_task where id=? and plan_id=?", taskId, planId);
    Long attachmentId = taskAttachments.completeUpload(
        "plan-task-attachment", taskId, ticketId, true);
    audit.log("UPLOAD_PLAN_TASK_ATTACHMENT", "TASK_ATTACHMENT", attachmentId, null,
        Map.of("planId", planId, "taskId", taskId, "transfer", "OSS_DIRECT"));
    return ApiResponse.ok(attachmentId);
  }

  @DeleteMapping("/{planId}/tasks/{taskId}/attachments/{attachmentId}")
  public ApiResponse<Void> deletePlanTaskAttachment(
      @PathVariable Long planId,
      @PathVariable Long taskId,
      @PathVariable Long attachmentId
  ) {
    permissions.require(Permissions.TASK_MANAGE);
    one("""
        select a.id from task_attachment a
        join training_plan_task t on t.id=a.training_plan_task_id
        where a.id=? and a.training_plan_task_id=? and t.plan_id=?
        """, attachmentId, taskId, planId);
    var before = taskAttachments.delete(attachmentId);
    audit.log("DELETE_PLAN_TASK_ATTACHMENT", "TASK_ATTACHMENT", attachmentId, before, null);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/copy")
  @Transactional
  public ApiResponse<Long> copyPlan(@PathVariable Long id, @Valid @RequestBody CopyPlanRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var source = one("select * from training_plan where id=?", id);
    db.update("insert into training_plan(name,description,enabled,created_by) values(?,?,false,?)",
        q.name().trim(), source.get("description"), SecurityUtils.current().id());
    Long copyId = lastId();
    var sourceTasks = db.queryForList("""
        select id,title,description,requirements,sort_order
        from training_plan_task where plan_id=? order by sort_order,id
        """, id);
    for (var task : sourceTasks) {
      db.update("""
          insert into training_plan_task(plan_id,title,description,requirements,sort_order)
          values(?,?,?,?,?)
          """, copyId, task.get("title"), task.get("description"), task.get("requirements"),
          task.get("sort_order"));
      Long copyTaskId = lastId();
      taskAttachments.copyPlanTaskAttachments(number(task.get("id")), copyTaskId);
    }
    audit.log("COPY_TRAINING_PLAN", "TRAINING_PLAN", copyId, null, Map.of("sourcePlanId", id, "name", q.name().trim()));
    return ApiResponse.ok(copyId);
  }

  @DeleteMapping("/{id}")
  @Transactional
  public ApiResponse<Void> deletePlan(@PathVariable Long id) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan where id=?", id);
    if (db.queryForObject("select count(*) from challenge_task where training_plan_id=?", Integer.class, id) > 0) {
      throw new BusinessException(400, "该培养计划已有任务下发记录，不能删除；可将计划停用后保留历史");
    }
    var taskIds = db.queryForList(
        "select id from training_plan_task where plan_id=?", Long.class, id);
    for (Long taskId : taskIds) taskAttachments.deleteForPlanTask(taskId);
    db.update("delete from training_plan_task where plan_id=?", id);
    db.update("delete from training_plan where id=?", id);
    audit.log("DELETE_TRAINING_PLAN", "TRAINING_PLAN", id, before, null);
    return ApiResponse.ok(null);
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new BusinessException(404, "资源不存在");
    return rows.get(0);
  }

  private Long lastId() {
    return db.queryForObject("select last_insert_id()", Long.class);
  }

  private Long number(Object value) {
    return ((Number) value).longValue();
  }

  private String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
