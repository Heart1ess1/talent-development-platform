package com.talent.platform.training;

import com.talent.platform.common.*;
import com.talent.platform.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/training-plans")
public class TrainingPlanController {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public TrainingPlanController(JdbcTemplate db, PermissionService permissions, AuditService audit) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
  }

  public record PlanRequest(@NotBlank String name, String description) {}
  public record PlanTaskRequest(@NotBlank String title, @NotBlank String description, String requirements) {}
  public record PlanTaskOrderItem(@NotNull Long id, @NotNull @Min(1) Integer sortOrder) {}
  public record ReplacePlanTaskOrderRequest(@NotNull List<@Valid PlanTaskOrderItem> items) {}
  public record EnabledRequest(@NotNull Boolean enabled) {}

  @GetMapping
  public ApiResponse<List<Map<String, Object>>> plans() {
    permissions.require(Permissions.TASK_MANAGE);
    return ApiResponse.ok(db.queryForList("select p.*,u.display_name creator_name,(select count(*) from training_plan_task t where t.plan_id=p.id) task_count from training_plan p join sys_user u on u.id=p.created_by order by p.id desc"));
  }

  @PostMapping
  public ApiResponse<Long> createPlan(@Valid @RequestBody PlanRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    db.update("insert into training_plan(name,description,created_by) values(?,?,?)", q.name(), q.description(), SecurityUtils.current().id());
    Long id = lastId();
    audit.log("CREATE_TRAINING_PLAN", "TRAINING_PLAN", id, null, q);
    return ApiResponse.ok(id);
  }

  @PutMapping("/{id}")
  public ApiResponse<Void> updatePlan(@PathVariable Long id, @Valid @RequestBody PlanRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan where id=?", id);
    db.update("update training_plan set name=?,description=?,version=version+1 where id=?", q.name(), q.description(), id);
    audit.log("UPDATE_TRAINING_PLAN", "TRAINING_PLAN", id, before, q);
    return ApiResponse.ok(null);
  }

  @PutMapping("/{id}/enabled")
  public ApiResponse<Void> enablePlan(@PathVariable Long id, @Valid @RequestBody EnabledRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan where id=?", id);
    db.update("update training_plan set enabled=?,version=version+1 where id=?", q.enabled(), id);
    audit.log(q.enabled() ? "ENABLE_TRAINING_PLAN" : "DISABLE_TRAINING_PLAN", "TRAINING_PLAN", id, before, q);
    return ApiResponse.ok(null);
  }

  @GetMapping("/{id}/tasks")
  public ApiResponse<List<Map<String, Object>>> planTasks(@PathVariable Long id) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan where id=?", id);
    return ApiResponse.ok(db.queryForList("select id,plan_id,title,description,requirements,sort_order,created_at from training_plan_task where plan_id=? order by sort_order,id", id));
  }

  @PostMapping("/{id}/tasks")
  public ApiResponse<Long> createPlanTask(@PathVariable Long id, @Valid @RequestBody PlanTaskRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan where id=?", id);
    Integer sortOrder = db.queryForObject("select coalesce(max(sort_order),0)+1 from training_plan_task where plan_id=?", Integer.class, id);
    db.update("insert into training_plan_task(plan_id,title,description,requirements,sort_order) values(?,?,?,?,?)", id, q.title(), q.description(), q.requirements(), sortOrder);
    Long taskId = lastId();
    audit.log("CREATE_TRAINING_PLAN_TASK", "TRAINING_PLAN_TASK", taskId, null, q);
    return ApiResponse.ok(taskId);
  }

  @PutMapping("/{planId}/tasks/{taskId}")
  public ApiResponse<Void> updatePlanTask(@PathVariable Long planId, @PathVariable Long taskId, @Valid @RequestBody PlanTaskRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan_task where id=? and plan_id=?", taskId, planId);
    db.update("update training_plan_task set title=?,description=?,requirements=? where id=? and plan_id=?", q.title(), q.description(), q.requirements(), taskId, planId);
    audit.log("UPDATE_TRAINING_PLAN_TASK", "TRAINING_PLAN_TASK", taskId, before, q);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/{planId}/tasks/{taskId}")
  @Transactional
  public ApiResponse<Void> deletePlanTask(@PathVariable Long planId, @PathVariable Long taskId) {
    permissions.require(Permissions.TASK_MANAGE);
    var before = one("select * from training_plan_task where id=? and plan_id=?", taskId, planId);
    if (db.queryForObject("select count(*) from challenge_task where training_plan_task_id=?", Integer.class, taskId) > 0) {
      throw new BusinessException(400, "该计划任务已下达，不能删除");
    }
    db.update("delete from training_plan_task where id=? and plan_id=?", taskId, planId);
    audit.log("DELETE_TRAINING_PLAN_TASK", "TRAINING_PLAN_TASK", taskId, before, null);
    return ApiResponse.ok(null);
  }

  @PutMapping("/{id}/tasks/order")
  @Transactional
  public ApiResponse<Void> reorderPlanTasks(@PathVariable Long id, @Valid @RequestBody ReplacePlanTaskOrderRequest q) {
    permissions.require(Permissions.TASK_MANAGE);
    one("select id from training_plan where id=?", id);
    var ids = new HashSet<Long>();
    var orders = new HashSet<Integer>();
    for (var item : q.items()) {
      if (!ids.add(item.id()) || !orders.add(item.sortOrder())) throw new BusinessException(400, "计划任务顺序重复");
      if (db.queryForObject("select count(*) from training_plan_task where id=? and plan_id=?", Integer.class, item.id(), id) == 0) {
        throw new BusinessException(404, "计划任务不存在");
      }
    }
    db.update("update training_plan_task set sort_order=sort_order+100000 where plan_id=?", id);
    for (var item : q.items()) db.update("update training_plan_task set sort_order=? where id=? and plan_id=?", item.sortOrder(), item.id(), id);
    audit.log("REORDER_TRAINING_PLAN_TASKS", "TRAINING_PLAN", id, null, q);
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
}
