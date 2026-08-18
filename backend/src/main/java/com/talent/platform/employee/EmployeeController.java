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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {
  private static final String DIRECTORY_SELECT = """
      select e.*,b.name batch_name,cls.label class_name,bu.name business_unit_name,s.name station_name,
             tm.display_name technical_mentor_name,tm.display_name mentor_name,
             sm.display_name skill_mentor_name
      from employee e
      left join talent_batch b on b.id=e.batch_id
      left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
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
      Long batchId,
      Long classId,
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

  @GetMapping
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long batchId,
      @RequestParam(required = false) Long classId,
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
        values(?,?,?,'EMPLOYEE',false,true)
        """, request.employeeNo(), encoder.encode(UUID.randomUUID().toString()), request.name());
    Long userId = db.queryForObject("select last_insert_id()", Long.class);
    db.update("""
        insert into employee(
          user_id,employee_no,name,batch_id,class_id,business_unit_id,station_id,
          mentor_user_id,skill_mentor_user_id,school,major,education,birth_date,
          native_place,residence,phone,email,onboard_date,political_status,
          hobbies,speciality,id_card,notes,status
        ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        userId, request.employeeNo(), request.name(), request.batchId(),
        request.classId(), request.businessUnitId(), request.stationId(), request.mentorUserId(),
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
        set employee_no=?,name=?,batch_id=?,class_id=?,business_unit_id=?,station_id=?,
            mentor_user_id=?,skill_mentor_user_id=?,school=?,major=?,education=?,
            birth_date=?,native_place=?,residence=?,phone=?,email=?,onboard_date=?,
            political_status=?,hobbies=?,speciality=?,id_card=?,notes=?,status=?,version=version+1
        where id=?
        """,
        request.employeeNo(), request.name(), request.batchId(), request.classId(),
        request.businessUnitId(), request.stationId(), request.mentorUserId(), request.skillMentorUserId(),
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

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    rejectEmployeeLedgerAccess();
    permissions.require(Permissions.EMPLOYEE_READ);
    permissions.requireEmployee(id);
    return ApiResponse.ok(db.queryForMap(DIRECTORY_SELECT + " where e.id=?", id));
  }

  private void validateReferences(EmployeeRequest request) {
    requireEnabledMaster("talent_batch", "批次", request.batchId());
    requireEnabledClass(request.classId());
    requireEnabledMaster("business_unit", "所属板块", request.businessUnitId());
    requireEnabledMaster("service_station", "服务站点", request.stationId());
    if (request.mentorUserId() != null) requireMentor(request.mentorUserId());
    if (request.skillMentorUserId() != null) requireMentor(request.skillMentorUserId());
  }

  private void requireEnabledClass(Long id) {
    if (id == null) return;
    Integer count = db.queryForObject(
        "select count(*) from dictionary_item where id=? and type_code='CLASS' and enabled=true",
        Integer.class,
        id);
    if (count == null || count == 0) throw new BusinessException(400, "班级不存在或已停用");
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
