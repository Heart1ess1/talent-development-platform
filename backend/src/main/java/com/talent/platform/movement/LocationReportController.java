package com.talent.platform.movement;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.common.PageResult;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/location-reports")
public class LocationReportController {
  private static final String CURRENT_CONDITION = """
      not exists(
        select 1 from employee_location_report newer
        where newer.employee_id=r.employee_id
          and (newer.occurred_at>r.occurred_at
            or (newer.occurred_at=r.occurred_at and newer.id>r.id))
      )
      """;

  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public LocationReportController(
      JdbcTemplate db,
      PermissionService permissions,
      AuditService audit) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
  }

  public record SubmitRequest(
      @NotBlank @Size(max = 128) String location,
      @NotBlank @Size(max = 500) String reason,
      @NotNull LocalDateTime occurredAt,
      LocalDateTime expectedReturnAt) {}

  public record MineResponse(
      Long employeeId,
      String employeeName,
      String stationName,
      String currentLocation,
      List<Map<String, Object>> records) {}

  public record Summary(
      long totalReports,
      long trackedEmployees,
      long todayReports,
      long weekReports) {}

  @PostMapping
  @Transactional
  public ApiResponse<Long> submit(@Valid @RequestBody SubmitRequest request) {
    var user = SecurityUtils.current();
    if (!"EMPLOYEE".equals(user.role())) {
      throw new BusinessException(403, "仅员工可提交位置报备");
    }
    validateTimes(request);
    var employees = db.queryForList("""
        select e.id,e.name,s.name station_name
        from employee e
        left join service_station s on s.id=e.station_id
        where e.user_id=? and e.status='ACTIVE'
        for update
        """,
        user.id());
    if (employees.isEmpty()) throw new BusinessException(404, "在职员工信息不存在");
    var employee = employees.get(0);
    var employeeId = ((Number) employee.get("id")).longValue();
    var previous = db.queryForList("""
        select to_location,occurred_at
        from employee_location_report
        where employee_id=?
        order by occurred_at desc,id desc
        limit 1
        """,
        employeeId);
    if (!previous.isEmpty()) {
      var previousOccurredAt = toLocalDateTime(previous.get(0).get("occurred_at"));
      if (previousOccurredAt != null && request.occurredAt().isBefore(previousOccurredAt)) {
        throw new BusinessException(400, "变动时间不能早于上一条位置报备");
      }
    }
    var fromLocation = previous.isEmpty()
        ? textOrDefault(employee.get("station_name"), "未登记位置")
        : textOrDefault(previous.get(0).get("to_location"), "未登记位置");
    var toLocation = request.location().trim();
    var reason = request.reason().trim();
    db.update("""
        insert into employee_location_report(
          employee_id,from_location,to_location,reason,occurred_at,
          expected_return_at,report_source,reported_by
        ) values(?,?,?,?,?,?,'MANUAL',?)
        """,
        employeeId, fromLocation, toLocation, reason, request.occurredAt(),
        request.expectedReturnAt(), user.id());
    var id = db.queryForObject("select last_insert_id()", Long.class);
    var detail = new LinkedHashMap<String, Object>();
    detail.put("fromLocation", fromLocation);
    detail.put("toLocation", toLocation);
    detail.put("reason", reason);
    detail.put("occurredAt", request.occurredAt());
    if (request.expectedReturnAt() != null) detail.put("expectedReturnAt", request.expectedReturnAt());
    audit.log("SUBMIT_LOCATION_REPORT", "EMPLOYEE", employeeId, null, detail);
    return ApiResponse.ok(id);
  }

  @GetMapping("/mine")
  public ApiResponse<MineResponse> mine() {
    var user = SecurityUtils.current();
    if (!"EMPLOYEE".equals(user.role())) {
      throw new BusinessException(403, "仅员工可查看本人位置报备");
    }
    var employees = db.queryForList("""
        select e.id,e.name,s.name station_name
        from employee e
        left join service_station s on s.id=e.station_id
        where e.user_id=?
        """,
        user.id());
    if (employees.isEmpty()) throw new BusinessException(404, "员工信息不存在");
    var employee = employees.get(0);
    var employeeId = ((Number) employee.get("id")).longValue();
    var records = db.queryForList("""
        select r.id,r.employee_id,r.from_location,r.to_location,r.reason,
               r.occurred_at,r.expected_return_at,r.created_at
        from employee_location_report r
        where r.employee_id=?
        order by r.occurred_at desc,r.id desc
        limit 100
        """,
        employeeId);
    var stationName = textOrDefault(employee.get("station_name"), "未分配服务站");
    var currentLocation = records.isEmpty()
        ? stationName
        : textOrDefault(records.get(0).get("to_location"), stationName);
    return ApiResponse.ok(new MineResponse(
        employeeId,
        String.valueOf(employee.get("name")),
        stationName,
        currentLocation,
        records));
  }

  @GetMapping
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) Long classPositionId,
      @RequestParam(required = false) String location,
      @RequestParam(required = false) Boolean currentOnly,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
    rejectEmployeeManagementAccess();
    permissions.require(Permissions.EMPLOYEE_READ);
    var query = filters(keyword, classId, classPositionId, location, Boolean.TRUE.equals(currentOnly), dateFrom, dateTo);
    var countSql = """
        select count(*)
        from employee_location_report r
        join employee e on e.id=r.employee_id
        """ + query.sql();
    var total = db.queryForObject(countSql, Long.class, query.args().toArray());
    int pageSize = Math.min(Math.max(size, 1), 100);
    var args = new ArrayList<>(query.args());
    args.add(pageSize);
    args.add(Math.max(0, (page - 1) * pageSize));
    var selectSql = """
        select r.id,r.employee_id,r.from_location,r.to_location,r.reason,
               r.occurred_at,r.expected_return_at,r.created_at,
                e.name employee_name,e.employee_no,e.class_id,e.class_position_id,e.status employee_status,
                cls.label class_name,cp.label class_position_name,
               u.avatar_token,b.name batch_name,bu.name business_unit_name,
               s.name station_name,tm.display_name mentor_name,
        """ + CURRENT_CONDITION + """
               as is_current
        from employee_location_report r
        join employee e on e.id=r.employee_id
        join sys_user u on u.id=e.user_id
         left join talent_batch b on b.id=e.batch_id
         left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
         left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION'
        left join business_unit bu on bu.id=e.business_unit_id
        left join service_station s on s.id=e.station_id
        left join sys_user tm on tm.id=e.mentor_user_id
        """ + query.sql() + """
        order by r.occurred_at desc,r.id desc
        limit ? offset ?
        """;
    var rows = db.queryForList(selectSql, args.toArray());
    return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, page, pageSize));
  }

  @GetMapping("/summary")
  public ApiResponse<Summary> summary() {
    rejectEmployeeManagementAccess();
    permissions.require(Permissions.EMPLOYEE_READ);
    var scope = permissions.employeeFilter("e");
    var row = db.queryForMap("""
        select count(*) total_reports,
               count(distinct r.employee_id) tracked_employees,
               coalesce(sum(date(r.occurred_at)=current_date),0) today_reports,
               coalesce(sum(r.occurred_at>=date_sub(now(),interval 7 day)),0) week_reports
        from employee_location_report r
        join employee e on e.id=r.employee_id
        where 1=1
        """ + scope.sql(),
        scope.args().toArray());
    return ApiResponse.ok(new Summary(
        number(row.get("total_reports")),
        number(row.get("tracked_employees")),
        number(row.get("today_reports")),
        number(row.get("week_reports"))));
  }

  @GetMapping("/employee/{employeeId}")
  public ApiResponse<List<Map<String, Object>>> employeeHistory(@PathVariable Long employeeId) {
    rejectEmployeeManagementAccess();
    permissions.require(Permissions.EMPLOYEE_READ);
    permissions.requireEmployee(employeeId);
    return ApiResponse.ok(db.queryForList("""
        select r.id,r.employee_id,r.from_location,r.to_location,r.reason,
               r.occurred_at,r.expected_return_at,r.created_at
        from employee_location_report r
        where r.employee_id=?
        order by r.occurred_at desc,r.id desc
        """,
        employeeId));
  }

  private FilterQuery filters(
      String keyword,
      Long classId,
      Long classPositionId,
      String location,
      boolean currentOnly,
      LocalDate dateFrom,
      LocalDate dateTo) {
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    var scope = permissions.employeeFilter("e");
    where.append(scope.sql());
    args.addAll(scope.args());
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (e.name like ? or e.employee_no like ? or r.reason like ?)");
      var value = "%" + keyword.trim() + "%";
      args.add(value);
      args.add(value);
      args.add(value);
    }
    if (classId != null) {
      where.append(" and e.class_id=?");
      args.add(classId);
    }
    if (classPositionId != null) {
      where.append(" and e.class_position_id=?");
      args.add(classPositionId);
    }
    if (location != null && !location.isBlank()) {
      where.append(" and (r.from_location like ? or r.to_location like ?)");
      var value = "%" + location.trim() + "%";
      args.add(value);
      args.add(value);
    }
    if (currentOnly) where.append(" and ").append(CURRENT_CONDITION);
    if (dateFrom != null) {
      where.append(" and r.occurred_at>=?");
      args.add(dateFrom.atStartOfDay());
    }
    if (dateTo != null) {
      where.append(" and r.occurred_at<?");
      args.add(dateTo.plusDays(1).atStartOfDay());
    }
    return new FilterQuery(where.toString(), args);
  }

  private void validateTimes(SubmitRequest request) {
    if (request.occurredAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
      throw new BusinessException(400, "变动时间不能晚于当前时间");
    }
    if (request.expectedReturnAt() != null
        && !request.expectedReturnAt().isAfter(request.occurredAt())) {
      throw new BusinessException(400, "预计返回时间必须晚于变动时间");
    }
  }

  private void rejectEmployeeManagementAccess() {
    if ("EMPLOYEE".equals(SecurityUtils.current().role())) {
      throw new BusinessException(403, "员工只能查看本人的位置报备");
    }
  }

  private String textOrDefault(Object value, String fallback) {
    if (value == null || String.valueOf(value).isBlank()) return fallback;
    return String.valueOf(value);
  }

  private long number(Object value) {
    return value == null ? 0 : ((Number) value).longValue();
  }

  private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime dateTime) return dateTime;
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
    return null;
  }

  private record FilterQuery(String sql, List<Object> args) {}
}
