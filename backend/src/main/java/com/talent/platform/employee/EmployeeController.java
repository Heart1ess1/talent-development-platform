package com.talent.platform.employee;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.common.PageResult;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {
  private static final String DIRECTORY_SELECT = """
      select e.*,b.name batch_name,cls.label class_name,cp.label class_position_name,
             bu.name business_unit_name,s.name station_name,
             tm.display_name technical_mentor_name,tm.display_name mentor_name,
             sm.display_name skill_mentor_name
      from employee e
      left join talent_batch b on b.id=e.batch_id
      left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
      left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION'
      left join business_unit bu on bu.id=e.business_unit_id
      left join service_station s on s.id=e.station_id
      left join sys_user tm on tm.id=e.mentor_user_id
      left join sys_user sm on sm.id=e.skill_mentor_user_id
      """;

  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final PermissionService permissions;
  private final AuditService audit;

  public EmployeeController(
      JdbcTemplate db,
      PasswordEncoder encoder,
      PermissionService permissions,
      AuditService audit) {
    this.db = db;
    this.encoder = encoder;
    this.permissions = permissions;
    this.audit = audit;
  }

  public record EmployeeRequest(
      @NotBlank String employeeNo,
      @NotBlank String name,
      @Pattern(regexp = "男|女") String gender,
      Long batchId,
      Long classId,
      Long classPositionId,
      Long businessUnitId,
      Long stationId,
      Long mentorUserId,
      Long skillMentorUserId,
      String school,
      String major,
      String education,
      LocalDate birthDate,
      String nativePlace,
      String residence,
      String phone,
      @Email String email,
      LocalDate onboardDate,
      String politicalStatus,
      String hobbies,
      String speciality,
      String idCard,
      @Size(max = 10000) String notes,
      @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

  public record BindRequest(
      @NotEmpty List<Long> employeeIds,
      @NotNull Long mentorUserId,
      @Pattern(regexp = "TECHNICAL|SKILL") String mentorType) {}

  public record BulkSelection(
      String mode,
      List<Long> ids,
      Map<String, Object> filters,
      List<Long> excludedIds) {}

  public record BulkRequest(
      BulkSelection selection,
      Map<String, Object> changes,
      boolean syncLinkedAccount,
      Integer expectedCount,
      String selectionHash,
      String requestId) {}

  @GetMapping
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long batchId,
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) Long classPositionId,
      @RequestParam(required = false) Long stationId,
      @RequestParam(required = false) Long mentorId) {
    rejectEmployeeLedgerAccess();
    permissions.require(Permissions.EMPLOYEE_READ);
    var where = new StringBuilder(" where 1=1");
    var parameters = new ArrayList<Object>();
    var scope = permissions.employeeFilter("e");
    where.append(scope.sql());
    parameters.addAll(scope.args());
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (e.name like ? or e.employee_no like ?)");
      parameters.add("%" + keyword.trim() + "%");
      parameters.add("%" + keyword.trim() + "%");
    }
    if (batchId != null) {
      where.append(" and e.batch_id=?");
      parameters.add(batchId);
    }
    if (classId != null) {
      where.append(" and e.class_id=?");
      parameters.add(classId);
    }
    if (classPositionId != null) {
      where.append(" and e.class_position_id=?");
      parameters.add(classPositionId);
    }
    if (stationId != null) {
      where.append(" and e.station_id=?");
      parameters.add(stationId);
    }
    if (mentorId != null) {
      where.append(" and e.mentor_user_id=?");
      parameters.add(mentorId);
    }
    int pageSize = Math.min(Math.max(size, 1), 100);
    long total = db.queryForObject(
        "select count(*) from employee e" + where,
        Long.class,
        parameters.toArray());
    parameters.add(pageSize);
    parameters.add(Math.max(0, (page - 1) * pageSize));
    var rows = db.queryForList(
        DIRECTORY_SELECT + where + " order by e.id desc limit ? offset ?",
        parameters.toArray());
    return ApiResponse.ok(new PageResult<>(rows, total, page, pageSize));
  }

  @PostMapping
  @Transactional
  public ApiResponse<Long> create(@Valid @RequestBody EmployeeRequest request) {
    permissions.require(Permissions.EMPLOYEE_WRITE);
    validateReferences(request);
    db.update("""
        insert into sys_user(username,password_hash,display_name,role,enabled,must_change_password)
        values(?,?,?,'EMPLOYEE',?,true)
        """, request.employeeNo(), encoder.encode(UUID.randomUUID().toString()), request.name(),
        "ACTIVE".equals(normalizedStatus(request.status(), "ACTIVE")));
    Long userId = db.queryForObject("select last_insert_id()", Long.class);
    db.update("""
        insert into employee(
          user_id,employee_no,name,gender,batch_id,class_id,class_position_id,business_unit_id,station_id,
          mentor_user_id,skill_mentor_user_id,school,major,education,birth_date,
          native_place,residence,phone,email,onboard_date,political_status,
          hobbies,speciality,id_card,notes,status
        ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        userId, request.employeeNo(), request.name(), request.gender(), request.batchId(),
        request.classId(), request.classPositionId(), request.businessUnitId(), request.stationId(), request.mentorUserId(),
        request.skillMentorUserId(), request.school(), request.major(), request.education(),
        request.birthDate(), request.nativePlace(), request.residence(), request.phone(),
        request.email(), request.onboardDate(), request.politicalStatus(), request.hobbies(),
        request.speciality(), request.idCard(), request.notes(),
        normalizedStatus(request.status(), "ACTIVE"));
    Long employeeId = db.queryForObject("select last_insert_id()", Long.class);
    audit.log("CREATE_EMPLOYEE", "EMPLOYEE", employeeId, null, request);
    return ApiResponse.ok(employeeId);
  }

  @PutMapping("/{id}")
  @Transactional
  public ApiResponse<Void> update(
      @PathVariable Long id,
      @Valid @RequestBody EmployeeRequest request) {
    permissions.require(Permissions.EMPLOYEE_UPDATE);
    validateReferences(request);
    var before = db.queryForMap("select * from employee where id=? for update", id);
    Long userId = ((Number) before.get("user_id")).longValue();
    Long previousStationId = number(before.get("station_id"));
    String previousStatus = String.valueOf(before.get("status"));
    db.update("""
        update employee
        set employee_no=?,name=?,gender=?,batch_id=?,class_id=?,class_position_id=?,business_unit_id=?,station_id=?,
            mentor_user_id=?,skill_mentor_user_id=?,school=?,major=?,education=?,
            birth_date=?,native_place=?,residence=?,phone=?,email=?,onboard_date=?,
            political_status=?,hobbies=?,speciality=?,id_card=?,notes=?,status=?,version=version+1
        where id=?
        """,
        request.employeeNo(), request.name(), request.gender(), request.batchId(), request.classId(),
        request.classPositionId(), request.businessUnitId(), request.stationId(), request.mentorUserId(), request.skillMentorUserId(),
        request.school(), request.major(), request.education(), request.birthDate(),
        request.nativePlace(), request.residence(), request.phone(), request.email(),
        request.onboardDate(), request.politicalStatus(), request.hobbies(),
        request.speciality(), request.idCard(), request.notes(),
        normalizedStatus(request.status(), previousStatus), id);
    db.update("""
        update sys_user
        set username=?,display_name=?,version=version+1,security_version=security_version+1
        where id=?
        """, request.employeeNo(), request.name(), userId);
    db.update("update sys_user set enabled=?,security_version=security_version+1 where id=? and role='EMPLOYEE'",
        "ACTIVE".equals(normalizedStatus(request.status(), previousStatus)), userId);
    if (!Objects.equals(previousStationId, request.stationId())) {
      var currentUser = SecurityUtils.current();
      db.update("""
          insert into station_change_request(
            employee_id,current_station_id,requested_station_id,status,
            review_comment,reviewed_by,reviewed_at
          ) values(?,?,?,'APPROVED',?,?,now())
          """, id, previousStationId, request.stationId(), "管理员直接调整", currentUser.id());
    }
    audit.log("UPDATE_EMPLOYEE", "EMPLOYEE", id, before, request);
    return ApiResponse.ok(null);
  }

  @PostMapping("/bind-mentor")
  public ApiResponse<Integer> bind(@Valid @RequestBody BindRequest request) {
    permissions.require(Permissions.EMPLOYEE_WRITE);
    requireMentor(request.mentorUserId());
    String mentorType = request.mentorType() == null ? "TECHNICAL" : request.mentorType();
    String column = "SKILL".equals(mentorType) ? "skill_mentor_user_id" : "mentor_user_id";
    String marks = String.join(",", Collections.nCopies(request.employeeIds().size(), "?"));
    var args = new ArrayList<Object>();
    args.add(request.mentorUserId());
    args.addAll(request.employeeIds());
    int updated = db.update(
        "update employee set " + column + "=?,version=version+1 where id in (" + marks + ")",
        args.toArray());
    audit.log("BIND_" + mentorType + "_MENTOR", "EMPLOYEE", null, null, request);
    return ApiResponse.ok(updated);
  }

  @PostMapping("/bulk/preview")
  public ApiResponse<Map<String, Object>> bulkPreview(@RequestBody BulkRequest request) {
    validateBulkRequest(request);
    requireBulkPermissions(request);
    var ids = resolveBulkIds(request.selection());
    var versions = employeeVersions(ids);
    var result = new LinkedHashMap<String, Object>();
    result.put("operationId", UUID.randomUUID().toString());
    result.put("selectionHash", selectionHash(versions));
    result.put("matched", ids.size());
    result.put("changed", countChanged(ids, request.changes()));
    result.put("unchanged", ids.size() - countChanged(ids, request.changes()));
    result.put("blocked", List.of());
    result.put("status", "PREVIEWED");
    return ApiResponse.ok(result);
  }

  @PostMapping("/bulk/execute")
  @Transactional
  public ApiResponse<Map<String, Object>> bulkExecute(@RequestBody BulkRequest request) {
    validateBulkRequest(request);
    requireBulkPermissions(request);
    ensureRequestNotUsed(request.requestId(), "BULK_UPDATE_EMPLOYEES");
    var ids = resolveBulkIds(request.selection());
    var versions = employeeVersions(ids);
    if (request.expectedCount() != null && request.expectedCount() != ids.size()) {
      throw new BusinessException(409, "人员筛选结果已变化，请重新预览");
    }
    if (request.selectionHash() != null && !request.selectionHash().equals(selectionHash(versions))) {
      throw new BusinessException(409, "人员数据已变化，请重新预览");
    }
    var operationId = UUID.randomUUID().toString();
    var changed = 0;
    for (Long id : ids) {
      var row = db.queryForMap("select * from employee where id=? for update", id);
      var stationBefore = number(row.get("station_id"));
      var updated = updateBulkEmployee(id, request.changes(), row);
      if (updated) {
        changed++;
        var stationAfter = number(changedValue(request.changes(), "stationId", stationBefore));
        if (!Objects.equals(stationBefore, stationAfter)) {
          var currentUser = SecurityUtils.current();
          db.update("""
              insert into station_change_request(
                employee_id,current_station_id,requested_station_id,status,
                review_comment,reviewed_by,reviewed_at
              ) values(?,?,?,'APPROVED',?,?,now())
              """, id, stationBefore, stationAfter, "批量修改服务站", currentUser.id());
        }
      }
      if (updated && request.syncLinkedAccount() && request.changes().containsKey("status")) {
        syncLinkedAccount(id, String.valueOf(changedValue(request.changes(), "status", null)));
      }
    }
    audit.logWithRequestId(request.requestId(), "BULK_UPDATE_EMPLOYEES", "EMPLOYEE", null,
        Map.of("operationId", operationId, "ids", ids, "count", ids.size()),
        Map.of("operationId", operationId, "changes", request.changes(), "changed", changed));
    return ApiResponse.ok(Map.of(
        "operationId", operationId,
        "matched", ids.size(),
        "changed", changed,
        "unchanged", ids.size() - changed,
        "blocked", List.of(),
        "status", "COMPLETED"));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    rejectEmployeeLedgerAccess();
    permissions.require(Permissions.EMPLOYEE_READ);
    permissions.requireEmployee(id);
    return ApiResponse.ok(db.queryForMap(DIRECTORY_SELECT + " where e.id=?", id));
  }

  private void validateReferences(EmployeeRequest request) {
    requireEnabledMaster("talent_batch", "批次", request.batchId());
    requireEnabledDictionary("CLASS", "班级", request.classId());
    requireEnabledDictionary("CLASS_POSITION", "班级职务", request.classPositionId());
    requireEnabledMaster("business_unit", "所属板块", request.businessUnitId());
    requireEnabledMaster("service_station", "服务站点", request.stationId());
    if (request.mentorUserId() != null) requireMentor(request.mentorUserId());
    if (request.skillMentorUserId() != null) requireMentor(request.skillMentorUserId());
  }

  private void validateBulkRequest(BulkRequest request) {
    if (request == null || request.selection() == null || request.changes() == null || request.changes().isEmpty()) {
      throw new BusinessException(400, "请选择人员和至少一个修改字段");
    }
    var selection = request.selection();
    if (!Set.of("IDS", "FILTER").contains(selection.mode())) throw new BusinessException(400, "非法选择方式");
    if ("IDS".equals(selection.mode()) && (selection.ids() == null || selection.ids().isEmpty())) {
      throw new BusinessException(400, "请选择至少一名人员");
    }
    if (selection.ids() != null && selection.ids().size() > 500) throw new BusinessException(400, "单次最多修改500人");
    if (request.changes().keySet().stream().anyMatch(key -> !Set.of(
        "gender", "status", "batchId", "classId", "classPositionId", "businessUnitId", "stationId",
        "mentorUserId", "skillMentorUserId", "education", "politicalStatus", "onboardDate").contains(key))) {
      throw new BusinessException(400, "包含不支持的批量字段");
    }
    Object status = bulkInputValue(request.changes().get("status"));
    if (status != null && !Set.of("ACTIVE", "INACTIVE").contains(String.valueOf(status))) {
      throw new BusinessException(400, "人员状态不合法");
    }
  }

  private void requireBulkPermissions(BulkRequest request) {
    permissions.require(Permissions.EMPLOYEE_UPDATE);
    if (request.changes().containsKey("mentorUserId") || request.changes().containsKey("skillMentorUserId")) {
      permissions.require(Permissions.EMPLOYEE_WRITE);
    }
    if (request.syncLinkedAccount()) permissions.require(Permissions.USER_EMPLOYEE_MANAGE);
  }

  private List<Long> resolveBulkIds(BulkSelection selection) {
    var args = new ArrayList<Object>();
    var where = new StringBuilder(" where 1=1");
    var scope = permissions.employeeFilter("e");
    where.append(scope.sql());
    args.addAll(scope.args());
    if ("IDS".equals(selection.mode())) {
      var ids = new LinkedHashSet<>(selection.ids() == null ? List.<Long>of() : selection.ids());
      if (selection.excludedIds() != null) ids.removeAll(selection.excludedIds());
      if (ids.isEmpty()) throw new BusinessException(400, "没有可修改的人员");
      if (ids.size() > 500) throw new BusinessException(400, "单次最多修改500人");
      where.append(" and e.id in (").append(String.join(",", Collections.nCopies(ids.size(), "?"))).append(")");
      args.addAll(ids);
    } else {
      applyEmployeeFilters(where, args, selection.filters());
    }
    var rows = db.queryForList("select e.id" + " from employee e" + where + " order by e.id", args.toArray());
    if (rows.isEmpty()) throw new BusinessException(400, "筛选结果为空");
    if (rows.size() > 500) throw new BusinessException(400, "单次最多修改500人，请缩小筛选范围");
    return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
  }

  private void applyEmployeeFilters(StringBuilder where, List<Object> args, Map<String, Object> filters) {
    if (filters == null) return;
    addFilter(where, args, filters, "keyword", " and (e.name like ? or e.employee_no like ? or e.phone like ? or e.email like ?)", 4);
    addEqualsFilter(where, args, filters, "batchId", "e.batch_id");
    addEqualsFilter(where, args, filters, "classId", "e.class_id");
    addEqualsFilter(where, args, filters, "classPositionId", "e.class_position_id");
    addEqualsFilter(where, args, filters, "businessUnitId", "e.business_unit_id");
    addEqualsFilter(where, args, filters, "stationId", "e.station_id");
    addEqualsFilter(where, args, filters, "mentorId", "e.mentor_user_id");
    addEqualsFilter(where, args, filters, "skillMentorId", "e.skill_mentor_user_id");
    addEqualsFilter(where, args, filters, "education", "e.education");
    addEqualsFilter(where, args, filters, "status", "e.status");
  }

  private void addFilter(StringBuilder where, List<Object> args, Map<String, Object> filters, String key, String sql, int copies) {
    var value = filters.get(key);
    if (value == null || String.valueOf(value).isBlank()) return;
    where.append(sql);
    for (int i = 0; i < copies; i++) args.add("%" + String.valueOf(value).trim() + "%");
  }

  private void addEqualsFilter(StringBuilder where, List<Object> args, Map<String, Object> filters, String key, String column) {
    var value = filters.get(key);
    if (value == null || String.valueOf(value).isBlank()) return;
    where.append(" and ").append(column).append("=?");
    args.add(value);
  }

  private Map<Long, Integer> employeeVersions(List<Long> ids) {
    var marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    var rows = db.queryForList("select id,version from employee where id in (" + marks + ") order by id", ids.toArray());
    if (rows.size() != ids.size()) throw new BusinessException(404, "部分人员不存在或无权访问");
    var result = new LinkedHashMap<Long, Integer>();
    rows.forEach(row -> result.put(((Number) row.get("id")).longValue(), ((Number) row.get("version")).intValue()));
    return result;
  }

  private String selectionHash(Map<Long, Integer> versions) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var value = versions.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue()).reduce((a, b) -> a + "," + b).orElse("");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private int countChanged(List<Long> ids, Map<String, Object> changes) {
    var columns = changes.keySet().stream().filter(Set.of("gender", "status", "batchId", "classId", "classPositionId", "businessUnitId", "stationId", "mentorUserId", "skillMentorUserId", "education", "politicalStatus", "onboardDate")::contains).toList();
    if (columns.isEmpty()) return 0;
    var marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    var rows = db.queryForList("select id," + String.join(",", columns.stream().map(this::employeeColumn).toList()) + " from employee where id in (" + marks + ")", ids.toArray());
    return (int) rows.stream().filter(row -> columns.stream().anyMatch(column -> !Objects.equals(normalizeBulkValue(column, row.get(employeeColumn(column))), normalizeBulkValue(column, changedValue(changes, column, row.get(employeeColumn(column))))))).count();
  }

  private boolean updateBulkEmployee(Long id, Map<String, Object> changes, Map<String, Object> current) {
    if (!changes.keySet().stream().filter(key -> Set.of("gender", "status", "batchId", "classId", "classPositionId", "businessUnitId", "stationId", "mentorUserId", "skillMentorUserId", "education", "politicalStatus", "onboardDate").contains(key)).anyMatch(key -> !Objects.equals(normalizeBulkValue(key, current.get(employeeColumn(key))), normalizeBulkValue(key, changedValue(changes, key, current.get(employeeColumn(key))))))) return false;
    var assignments = new ArrayList<String>();
    var args = new ArrayList<Object>();
    for (String key : changes.keySet()) {
      if (!Set.of("gender", "status", "batchId", "classId", "classPositionId", "businessUnitId", "stationId", "mentorUserId", "skillMentorUserId", "education", "politicalStatus", "onboardDate").contains(key)) continue;
      validateBulkReference(key, changes.get(key));
      assignments.add(employeeColumn(key) + "=?");
      args.add(changedValue(changes, key, null));
    }
    if (assignments.isEmpty()) return false;
    args.add(id);
    return db.update("update employee set " + String.join(",", assignments) + ",version=version+1 where id=?", args.toArray()) > 0;
  }

  private void validateBulkReference(String key, Object value) {
    value = bulkInputValue(value);
    if (value == null || "CLEAR".equals(value)) return;
    if (Set.of("mentorUserId", "skillMentorUserId").contains(key)) requireMentor(Long.valueOf(String.valueOf(value)));
    if (Set.of("batchId", "businessUnitId", "stationId").contains(key)) requireEnabledMaster(switch (key) {
      case "batchId" -> "talent_batch";
      case "businessUnitId" -> "business_unit";
      default -> "service_station";
    }, key, Long.valueOf(String.valueOf(value)));
    if (Set.of("classId", "classPositionId").contains(key)) requireEnabledDictionary("classId".equals(key) ? "CLASS" : "CLASS_POSITION", key, Long.valueOf(String.valueOf(value)));
  }

  private String employeeColumn(String key) {
    return switch (key) {
      case "batchId" -> "batch_id"; case "classId" -> "class_id"; case "classPositionId" -> "class_position_id";
      case "businessUnitId" -> "business_unit_id"; case "stationId" -> "station_id"; case "mentorUserId" -> "mentor_user_id";
      case "skillMentorUserId" -> "skill_mentor_user_id"; case "politicalStatus" -> "political_status"; case "onboardDate" -> "onboard_date";
      default -> key;
    };
  }

  private Object changedValue(Map<String, Object> changes, String key, Object fallback) {
    if (!changes.containsKey(key)) return fallback;
    var value = changes.get(key);
    if (value instanceof Map<?, ?> operation) {
      if ("CLEAR".equals(operation.get("operation"))) return null;
      return operation.get("value");
    }
    if ("CLEAR".equals(value)) return null;
    return value;
  }

  private Object changedValue(Map<String, Object> changes, String key, Long fallback) {
    return changedValue(changes, key, (Object) fallback);
  }

  private Object bulkInputValue(Object value) {
    if (!(value instanceof Map<?, ?> operation)) return value;
    return "CLEAR".equals(operation.get("operation")) ? null : operation.get("value");
  }

  private Object normalizeBulkValue(String key, Object value) {
    if (value == null) return null;
    if (Set.of("batchId", "classId", "classPositionId", "businessUnitId", "stationId", "mentorUserId", "skillMentorUserId").contains(key)) {
      return Long.valueOf(String.valueOf(value));
    }
    return String.valueOf(value);
  }

  private void syncLinkedAccount(Long employeeId, String status) {
    db.update("update sys_user u join employee e on e.user_id=u.id set u.enabled=?,u.version=u.version+1,u.security_version=u.security_version+1 where e.id=?", "ACTIVE".equals(status), employeeId);
  }

  private void ensureRequestNotUsed(String requestId, String action) {
    if (requestId == null || requestId.isBlank()) return;
    Integer count = db.queryForObject("select count(*) from operation_log where request_id=? and action=?", Integer.class, requestId, action);
    if (count != null && count > 0) throw new BusinessException(409, "该批量请求已处理，请勿重复提交");
  }

  private void requireEnabledDictionary(String typeCode, String label, Long id) {
    if (id == null) return;
    Integer count = db.queryForObject(
        "select count(*) from dictionary_item where id=? and type_code=? and enabled=true",
        Integer.class,
        id, typeCode);
    if (count == null || count == 0) throw new BusinessException(400, label + "不存在或已停用");
  }

  private void requireEnabledMaster(String table, String label, Long id) {
    if (id == null) return;
    Integer count = db.queryForObject(
        "select count(*) from " + table + " where id=? and enabled=true",
        Integer.class,
        id);
    if (count == null || count == 0) {
      throw new BusinessException(400, label + "不存在或已停用");
    }
  }

  private void requireMentor(Long mentorId) {
    Integer count = db.queryForObject(
        "select count(*) from sys_user where id=? and role='MENTOR' and enabled=true",
        Integer.class,
        mentorId);
    if (count == null || count == 0) {
      throw new BusinessException(400, "导师账号不存在或未启用");
    }
  }

  private Long number(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private String normalizedStatus(String requested, String fallback) {
    return requested == null || requested.isBlank() ? fallback : requested;
  }

  private void rejectEmployeeLedgerAccess() {
    if ("EMPLOYEE".equals(SecurityUtils.current().role())) {
      throw new BusinessException(403, "员工请在个人信息页面查看本人信息");
    }
  }
}
