package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TaskReviewerScopeService {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public TaskReviewerScopeService(JdbcTemplate db, PermissionService permissions, AuditService audit) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
  }

  public record ScopeRequest(Long id, Long batchId, Long businessUnitId, Long classId, List<Long> reviewerIds) {}

  public record ScopePreview(
      int targetEmployees,
      int coveredEmployees,
      int uncoveredEmployees,
      int overlappingEmployees,
      List<Map<String, Object>> scopes,
      List<Map<String, Object>> uncovered,
      List<Map<String, Object>> overlapping
  ) {
    @com.fasterxml.jackson.annotation.JsonProperty("valid")
    public boolean valid() {
      return uncoveredEmployees == 0 && overlappingEmployees == 0;
    }
  }

  private record AssignmentSnapshot(
      Long assignmentId,
      Long employeeId,
      String employeeName,
      String employeeNo,
      Long batchId,
      String batchName,
      Long businessUnitId,
      String businessUnitName,
      Long classId,
      String className
  ) {}

  private record PreparedScope(
      Long id,
      Long batchId,
      String batchName,
      Long businessUnitId,
      String businessUnitName,
      Long classId,
      String className,
      List<Long> reviewerIds,
      boolean locked
  ) {}

  public List<Map<String, Object>> scopes(Long taskId) {
    var rows = db.queryForList("""
        select rs.id,rs.task_id,rs.batch_id,rs.batch_name,rs.business_unit_id,rs.business_unit_name,
               rs.class_id,rs.class_name,rs.created_at,rs.updated_at,
               (select count(*) from task_assignment a where a.scoring_scope_id=rs.id) covered_employee_count,
               (select count(*) from task_assignment a where a.scoring_scope_id=rs.id and a.status='APPROVED') approved_employee_count,
               (select count(*) from task_assignment a join task_submission s on s.assignment_id=a.id
                  where a.scoring_scope_id=rs.id and s.submission_version=(select max(s2.submission_version) from task_submission s2 where s2.assignment_id=a.id)) submitted_count,
               (select count(*) from task_submission_review r where r.scoring_scope_id=rs.id and r.status='SUBMITTED') submitted_review_count,
               (select count(*) from task_submission_review r where r.scoring_scope_id=rs.id and r.status='PENDING') pending_review_count,
               exists(select 1 from task_submission_review r where r.scoring_scope_id=rs.id and r.status='SUBMITTED') locked
        from task_reviewer_scope rs
        where rs.task_id=? and rs.status='ACTIVE'
        order by rs.id
        """, taskId);
    for (var row : rows) {
      Long scopeId = number(row.get("id"));
      var reviewers = db.queryForList("""
          select u.id,u.username,u.display_name,u.role,u.enabled,m.assigned_at
          from task_reviewer_scope_member m join sys_user u on u.id=m.reviewer_user_id
          where m.scope_id=? order by u.display_name,u.id
          """, scopeId);
      row.put("reviewers", reviewers);
      row.put("reviewerIds", reviewers.stream().map(item -> number(item.get("id"))).toList());
      row.put("label", scopeLabel(row));
      long pending = numberValue(row.get("pending_review_count"));
      long submitted = numberValue(row.get("submitted_review_count"));
      long covered = numberValue(row.get("covered_employee_count"));
      long approved = numberValue(row.get("approved_employee_count"));
      boolean locked = Boolean.TRUE.equals(row.get("locked")) || numberValue(row.get("locked")) > 0;
      String status = covered > 0 && approved == covered ? "COMPLETED"
          : locked ? "LOCKED"
          : submitted > 0 || pending > 0 ? "SCORING"
          : covered > 0 ? "NOT_STARTED" : "EMPTY";
      row.put("scope_status", status);
    }
    return rows;
  }

  public List<Map<String, Object>> visibleScopes(Long taskId, boolean globalViewer) {
    var result = scopes(taskId);
    if (globalViewer) return result;
    Long userId = SecurityUtils.current().id();
    return result.stream().filter(scope -> ((List<?>) scope.get("reviewerIds")).stream()
        .anyMatch(id -> userId.equals(number(id)))).toList();
  }

  public ScopePreview preview(Long taskId, Collection<ScopeRequest> requests) {
    permissions.require(Permissions.TASK_MANAGE);
    requireTask(taskId);
    return previewSnapshots(assignmentSnapshots(taskId), prepare(requests));
  }

  public ScopePreview previewEmployees(Collection<Long> employeeIds, Collection<ScopeRequest> requests) {
    return previewSnapshots(employeeSnapshots(employeeIds), prepare(requests));
  }

  @Transactional
  public void setScopes(Long taskId, Collection<ScopeRequest> requested) {
    permissions.require(Permissions.TASK_MANAGE);
    requireTask(taskId);
    var incoming = prepare(requested);
    var existing = preparedExisting(taskId);
    assertLockedScopesPreserved(existing, incoming);
    var preview = previewSnapshots(assignmentSnapshots(taskId), incoming);
    requireValidPreview(preview, !incoming.isEmpty());

    Long operatorId = SecurityUtils.current().id();
    var lockedIds = existing.stream().filter(PreparedScope::locked).map(PreparedScope::id).collect(java.util.stream.Collectors.toSet());
    for (PreparedScope old : existing) {
      if (!lockedIds.contains(old.id())) {
        db.update("update task_reviewer_scope set status='INACTIVE',updated_by=? where id=?", operatorId, old.id());
        db.update("update task_assignment set scoring_scope_id=null where scoring_scope_id=?", old.id());
      }
    }

    var saved = new ArrayList<PreparedScope>();
    for (PreparedScope scope : incoming) {
      if (scope.id() != null && lockedIds.contains(scope.id())) {
        saved.add(scope);
        continue;
      }
      db.update("""
          insert into task_reviewer_scope(task_id,batch_id,batch_name,business_unit_id,business_unit_name,class_id,class_name,status,created_by,updated_by)
          values(?,?,?,?,?,?,?,'ACTIVE',?,?)
          """, taskId, scope.batchId(), scope.batchName(), scope.businessUnitId(), scope.businessUnitName(),
          scope.classId(), scope.className(), operatorId, operatorId);
      Long id = db.queryForObject("select last_insert_id()", Long.class);
      for (Long reviewerId : scope.reviewerIds()) {
        db.update("insert into task_reviewer_scope_member(scope_id,reviewer_user_id,assigned_by) values(?,?,?)",
            id, reviewerId, operatorId);
      }
      saved.add(new PreparedScope(id, scope.batchId(), scope.batchName(), scope.businessUnitId(), scope.businessUnitName(),
          scope.classId(), scope.className(), scope.reviewerIds(), false));
    }

    bindAssignments(taskId, saved);
    synchronizePendingReviews(taskId);
    synchronizeLegacyReviewers(taskId, saved, operatorId);
    audit.log("SET_TASK_REVIEWER_SCOPES", "TASK", taskId,
        Map.of("scopes", existing.stream().map(this::auditScope).toList()),
        Map.of("scopes", saved.stream().map(this::auditScope).toList()));
  }

  public void setUniformReviewers(Long taskId, Collection<Long> reviewerIds) {
    permissions.require(Permissions.TASK_MANAGE);
    var active = preparedExisting(taskId);
    boolean conditional = active.stream().anyMatch(scope -> scope.batchId() != null || scope.businessUnitId() != null || scope.classId() != null);
    if (conditional) throw new BusinessException(409, "当前任务已按范围配置评分人，请前往任务评分页面调整");
    setScopes(taskId, reviewerIds == null || reviewerIds.isEmpty()
        ? List.of()
        : List.of(new ScopeRequest(active.isEmpty() ? null : active.get(0).id(), null, null, null, new ArrayList<>(reviewerIds))));
  }

  public void requireCompatible(Long taskId, Collection<ScopeRequest> requested) {
    var expected = prepare(requested);
    var existing = preparedExisting(taskId);
    if (!canonical(existing).equals(canonical(expected))) {
      throw new BusinessException(409, "复用任务已有不同的评分范围配置，请前往任务评分页面处理");
    }
  }

  public void requireCompatibleUniform(Long taskId, Collection<Long> reviewerIds) {
    var ids = normalizeAndValidateReviewers(reviewerIds);
    requireCompatible(taskId, ids.isEmpty() ? List.of() : List.of(new ScopeRequest(null, null, null, null, ids)));
  }

  public void rebindAssignments(Long taskId) {
    var active = preparedExisting(taskId);
    if (active.isEmpty()) return;
    var preview = previewSnapshots(assignmentSnapshots(taskId), active);
    requireValidPreview(preview, true);
    bindAssignments(taskId, active);
  }

  public List<Long> reviewerIds(Long taskId) {
    return db.queryForList("""
        select distinct m.reviewer_user_id
        from task_reviewer_scope rs join task_reviewer_scope_member m on m.scope_id=rs.id
        where rs.task_id=? and rs.status='ACTIVE' order by m.reviewer_user_id
        """, Long.class, taskId);
  }

  public List<Long> reviewerIdsForAssignment(Long assignmentId) {
    return db.queryForList("""
        select m.reviewer_user_id
        from task_assignment a join task_reviewer_scope_member m on m.scope_id=a.scoring_scope_id
        where a.id=? order by m.reviewer_user_id
        """, Long.class, assignmentId);
  }

  public Long scopeIdForAssignment(Long assignmentId) {
    var rows = db.queryForList("select scoring_scope_id from task_assignment where id=?", assignmentId);
    if (rows.isEmpty() || rows.get(0).get("scoring_scope_id") == null) return null;
    return number(rows.get(0).get("scoring_scope_id"));
  }

  public boolean isReviewerForTask(Long taskId, Long userId) {
    Integer count = db.queryForObject("""
        select count(*) from task_reviewer_scope rs
        join task_reviewer_scope_member m on m.scope_id=rs.id
        where rs.task_id=? and rs.status='ACTIVE' and m.reviewer_user_id=?
        """, Integer.class, taskId, userId);
    return count != null && count > 0;
  }

  public boolean isReviewerForAssignment(Long assignmentId, Long userId) {
    Integer count = db.queryForObject("""
        select count(*) from task_assignment a
        join task_reviewer_scope rs on rs.id=a.scoring_scope_id and rs.status='ACTIVE'
        join task_reviewer_scope_member m on m.scope_id=rs.id
        where a.id=? and m.reviewer_user_id=?
        """, Integer.class, assignmentId, userId);
    return count != null && count > 0;
  }

  public boolean anyLocked(Long taskId) {
    Integer count = db.queryForObject("""
        select count(*) from task_submission_review r
        join task_reviewer_scope rs on rs.id=r.scoring_scope_id
        where rs.task_id=? and rs.status='ACTIVE' and r.status='SUBMITTED'
        """, Integer.class, taskId);
    return count != null && count > 0;
  }

  private List<PreparedScope> prepare(Collection<ScopeRequest> requests) {
    if (requests == null || requests.isEmpty()) return List.of();
    var result = new ArrayList<PreparedScope>();
    boolean hasAll = false;
    for (ScopeRequest request : requests) {
      if (request == null) throw new BusinessException(400, "评分范围不能为空");
      var reviewerIds = normalizeAndValidateReviewers(request.reviewerIds());
      if (reviewerIds.isEmpty()) throw new BusinessException(400, "每个评分范围至少选择一名评分人");
      String batchName = masterName("talent_batch", "name", request.batchId(), "批次");
      String unitName = masterName("business_unit", "name", request.businessUnitId(), "板块");
      String className = dictionaryName(request.classId(), "CLASS", "班级");
      boolean all = request.batchId() == null && request.businessUnitId() == null && request.classId() == null;
      hasAll |= all;
      result.add(new PreparedScope(request.id(), request.batchId(), batchName, request.businessUnitId(), unitName,
          request.classId(), className, reviewerIds, false));
    }
    if (hasAll && result.size() > 1) throw new BusinessException(400, "“全部员工”范围只能单独使用，不能和条件范围并存");
    return result;
  }

  private List<Long> normalizeAndValidateReviewers(Collection<Long> requested) {
    var ids = new ArrayList<>(new LinkedHashSet<>(requested == null ? List.of() : requested));
    if (ids.isEmpty()) return ids;
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    Integer count = db.queryForObject(
        "select count(*) from sys_user where enabled=true and role<>'EMPLOYEE' and id in (" + marks + ")",
        Integer.class, ids.toArray());
    if (count == null || count != ids.size()) throw new BusinessException(400, "评分人中包含员工、已停用或不存在的账号");
    ids.sort(Long::compareTo);
    return ids;
  }

  private ScopePreview previewSnapshots(List<AssignmentSnapshot> employees, List<PreparedScope> scopes) {
    if (scopes.isEmpty()) return new ScopePreview(employees.size(), 0, 0, 0, List.of(), List.of(), List.of());
    var counts = new long[scopes.size()];
    var uncovered = new ArrayList<Map<String, Object>>();
    var overlapping = new ArrayList<Map<String, Object>>();
    for (AssignmentSnapshot employee : employees) {
      var matches = new ArrayList<Integer>();
      for (int i = 0; i < scopes.size(); i++) {
        if (matches(scopes.get(i), employee)) {
          matches.add(i);
          counts[i]++;
        }
      }
      if (matches.isEmpty()) uncovered.add(employeeMap(employee, List.of()));
      if (matches.size() > 1) overlapping.add(employeeMap(employee, matches.stream().map(index -> scopeLabel(scopes.get(index))).toList()));
    }
    var scopeRows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < scopes.size(); i++) {
      var row = new LinkedHashMap<String, Object>();
      row.put("index", i);
      row.put("id", scopes.get(i).id());
      row.put("label", scopeLabel(scopes.get(i)));
      row.put("coveredEmployees", counts[i]);
      row.put("reviewerIds", scopes.get(i).reviewerIds());
      scopeRows.add(row);
    }
    int covered = employees.size() - uncovered.size();
    return new ScopePreview(employees.size(), covered, uncovered.size(), overlapping.size(), scopeRows, uncovered, overlapping);
  }

  private void requireValidPreview(ScopePreview preview, boolean configured) {
    if (!configured) return;
    var emptyScopes = preview.scopes().stream().filter(row -> numberValue(row.get("coveredEmployees")) == 0).toList();
    if (!emptyScopes.isEmpty()) {
      throw new BusinessException(400, "评分范围未覆盖任何任务员工：" + emptyScopes.stream().map(row -> String.valueOf(row.get("label"))).toList());
    }
    if (preview.overlappingEmployees() > 0) {
      throw new BusinessException(400, "评分范围存在重叠，冲突员工：" + employeeSummary(preview.overlapping()));
    }
    if (preview.uncoveredEmployees() > 0) {
      throw new BusinessException(400, "评分范围未完整覆盖任务员工：" + employeeSummary(preview.uncovered()));
    }
  }

  private void assertLockedScopesPreserved(List<PreparedScope> existing, List<PreparedScope> incoming) {
    var incomingById = new HashMap<Long, PreparedScope>();
    incoming.stream().filter(scope -> scope.id() != null).forEach(scope -> incomingById.put(scope.id(), scope));
    for (PreparedScope locked : existing.stream().filter(PreparedScope::locked).toList()) {
      PreparedScope replacement = incomingById.get(locked.id());
      if (replacement == null || !sameDefinition(locked, replacement)) {
        throw new BusinessException(409, "评分范围“" + scopeLabel(locked) + "”已开始评分，不能删除、修改条件或更换评分人");
      }
    }
  }

  private boolean sameDefinition(PreparedScope a, PreparedScope b) {
    return Objects.equals(a.batchId(), b.batchId())
        && Objects.equals(a.businessUnitId(), b.businessUnitId())
        && Objects.equals(a.classId(), b.classId())
        && new LinkedHashSet<>(a.reviewerIds()).equals(new LinkedHashSet<>(b.reviewerIds()));
  }

  private void bindAssignments(Long taskId, List<PreparedScope> scopes) {
    db.update("update task_assignment set scoring_scope_id=null where task_id=?", taskId);
    for (AssignmentSnapshot employee : assignmentSnapshots(taskId)) {
      PreparedScope match = scopes.stream().filter(scope -> matches(scope, employee)).findFirst().orElse(null);
      if (match != null) db.update("update task_assignment set scoring_scope_id=? where id=?", match.id(), employee.assignmentId());
    }
  }

  private void synchronizePendingReviews(Long taskId) {
    var pending = db.queryForList("""
        select s.id submission_id,a.scoring_scope_id
        from task_submission s join task_assignment a on a.id=s.assignment_id
        where a.task_id=? and s.status='PENDING_REVIEW'
          and s.submission_version=(select max(s2.submission_version) from task_submission s2 where s2.assignment_id=s.assignment_id)
        """, taskId);
    for (var row : pending) {
      Long submissionId = number(row.get("submission_id"));
      Long scopeId = row.get("scoring_scope_id") == null ? null : number(row.get("scoring_scope_id"));
      db.update("update task_submission_review set status='VOIDED' where submission_id=? and status='PENDING'", submissionId);
      if (scopeId == null) continue;
      var reviewers = db.queryForList("select reviewer_user_id from task_reviewer_scope_member where scope_id=?", Long.class, scopeId);
      for (Long reviewerId : reviewers) {
        db.update("""
            insert into task_submission_review(submission_id,scoring_scope_id,reviewer_user_id,status)
            values(?,?,?,'PENDING')
            on duplicate key update scoring_scope_id=values(scoring_scope_id),status='PENDING',decision=null,score=null,comment=null,submitted_at=null
            """, submissionId, scopeId, reviewerId);
      }
    }
  }

  private void synchronizeLegacyReviewers(Long taskId, List<PreparedScope> scopes, Long operatorId) {
    db.update("delete from task_reviewer where task_id=?", taskId);
    if (scopes.size() != 1) return;
    PreparedScope scope = scopes.get(0);
    if (scope.batchId() != null || scope.businessUnitId() != null || scope.classId() != null) return;
    for (Long reviewerId : scope.reviewerIds()) {
      db.update("insert into task_reviewer(task_id,reviewer_user_id,assigned_by) values(?,?,?)", taskId, reviewerId, operatorId);
    }
  }

  private List<PreparedScope> preparedExisting(Long taskId) {
    return scopes(taskId).stream().map(row -> new PreparedScope(
        number(row.get("id")), nullableLong(row.get("batch_id")), Objects.toString(row.get("batch_name"), null),
        nullableLong(row.get("business_unit_id")), Objects.toString(row.get("business_unit_name"), null),
        nullableLong(row.get("class_id")), Objects.toString(row.get("class_name"), null),
        ((List<?>) row.get("reviewerIds")).stream().map(this::number).toList(),
        Boolean.TRUE.equals(row.get("locked")) || numberValue(row.get("locked")) > 0)).toList();
  }

  private List<AssignmentSnapshot> assignmentSnapshots(Long taskId) {
    return db.query("""
        select a.id assignment_id,e.id employee_id,e.name employee_name,e.employee_no,
               a.batch_id_snapshot batch_id,a.batch_name_snapshot batch_name,
               a.business_unit_id_snapshot business_unit_id,a.business_unit_name_snapshot business_unit_name,
               a.class_id_snapshot class_id,a.class_name_snapshot class_name
        from task_assignment a join employee e on e.id=a.employee_id
        where a.task_id=? order by e.employee_no,e.id
        """, (rs, rowNum) -> snapshot(rs), taskId);
  }

  private List<AssignmentSnapshot> employeeSnapshots(Collection<Long> employeeIds) {
    if (employeeIds == null || employeeIds.isEmpty()) return List.of();
    var ids = new ArrayList<>(employeeIds);
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    return db.query("""
        select null assignment_id,e.id employee_id,e.name employee_name,e.employee_no,
               e.batch_id,b.name batch_name,e.business_unit_id,bu.name business_unit_name,
               e.class_id,cls.label class_name
        from employee e
        left join talent_batch b on b.id=e.batch_id
        left join business_unit bu on bu.id=e.business_unit_id
        left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
        where e.id in (""" + marks + ") order by e.employee_no,e.id", (rs, rowNum) -> snapshot(rs), ids.toArray());
  }

  private AssignmentSnapshot snapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AssignmentSnapshot(nullable(rs, "assignment_id"), rs.getLong("employee_id"), rs.getString("employee_name"),
        rs.getString("employee_no"), nullable(rs, "batch_id"), rs.getString("batch_name"),
        nullable(rs, "business_unit_id"), rs.getString("business_unit_name"), nullable(rs, "class_id"), rs.getString("class_name"));
  }

  private Long nullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private boolean matches(PreparedScope scope, AssignmentSnapshot employee) {
    return (scope.batchId() == null || Objects.equals(scope.batchId(), employee.batchId()))
        && (scope.businessUnitId() == null || Objects.equals(scope.businessUnitId(), employee.businessUnitId()))
        && (scope.classId() == null || Objects.equals(scope.classId(), employee.classId()));
  }

  private String masterName(String table, String column, Long id, String label) {
    if (id == null) return null;
    var names = db.queryForList("select " + column + " from " + table + " where id=?", String.class, id);
    if (names.isEmpty()) throw new BusinessException(400, label + "不存在");
    return names.get(0);
  }

  private String dictionaryName(Long id, String type, String label) {
    if (id == null) return null;
    var names = db.queryForList("select label from dictionary_item where id=? and type_code=?", String.class, id, type);
    if (names.isEmpty()) throw new BusinessException(400, label + "不存在");
    return names.get(0);
  }

  private void requireTask(Long taskId) {
    Integer count = db.queryForObject("select count(*) from challenge_task where id=?", Integer.class, taskId);
    if (count == null || count == 0) throw new BusinessException(404, "任务不存在");
  }

  private Map<String, Object> employeeMap(AssignmentSnapshot employee, List<String> scopeLabels) {
    var result = new LinkedHashMap<String, Object>();
    result.put("employeeId", employee.employeeId());
    result.put("employeeName", employee.employeeName());
    result.put("employeeNo", employee.employeeNo());
    result.put("batchName", employee.batchName());
    result.put("businessUnitName", employee.businessUnitName());
    result.put("className", employee.className());
    result.put("scopeLabels", scopeLabels);
    return result;
  }

  private Map<String, Object> auditScope(PreparedScope scope) {
    return Map.of("id", scope.id() == null ? 0 : scope.id(), "label", scopeLabel(scope), "reviewerIds", scope.reviewerIds());
  }

  private List<String> canonical(List<PreparedScope> scopes) {
    return scopes.stream().map(scope -> String.join("|",
        Objects.toString(scope.batchId(), "*"), Objects.toString(scope.businessUnitId(), "*"),
        Objects.toString(scope.classId(), "*"), scope.reviewerIds().toString())).sorted().toList();
  }

  private String scopeLabel(PreparedScope scope) {
    return String.join(" / ", List.of(
        scope.batchName() == null ? "全部批次" : scope.batchName(),
        scope.businessUnitName() == null ? "全部板块" : scope.businessUnitName(),
        scope.className() == null ? "全部班级" : scope.className()));
  }

  private String scopeLabel(Map<String, Object> scope) {
    return String.join(" / ", List.of(
        scope.get("batch_name") == null ? "全部批次" : String.valueOf(scope.get("batch_name")),
        scope.get("business_unit_name") == null ? "全部板块" : String.valueOf(scope.get("business_unit_name")),
        scope.get("class_name") == null ? "全部班级" : String.valueOf(scope.get("class_name"))));
  }

  private String employeeSummary(List<Map<String, Object>> employees) {
    return employees.stream().limit(10).map(row -> row.get("employeeName") + "（" + row.get("employeeNo") + "）").toList()
        + (employees.size() > 10 ? "等" + employees.size() + "人" : "");
  }

  private Long nullableLong(Object value) {
    return value == null ? null : number(value);
  }

  private Long number(Object value) {
    return ((Number) value).longValue();
  }

  private long numberValue(Object value) {
    return value instanceof Number number ? number.longValue() : 0L;
  }
}
