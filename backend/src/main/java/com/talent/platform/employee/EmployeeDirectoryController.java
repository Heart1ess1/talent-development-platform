package com.talent.platform.employee;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.PageResult;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/employee-directory")
public class EmployeeDirectoryController {
  private static final String SELECT = """
      select e.id,e.employee_no,e.name,e.gender,e.batch_id,e.class_id,e.class_position_id,e.business_unit_id,e.station_id,
             e.mentor_user_id,e.skill_mentor_user_id,e.school,e.major,e.education,
             e.birth_date,e.native_place,e.residence,e.phone,e.email,e.onboard_date,
             e.status,e.political_status,e.hobbies,e.speciality,e.id_card,e.notes,
             u.avatar_token,
             b.name batch_name,cls.label class_name,cp.label class_position_name,
             bu.name business_unit_name,s.name station_name,
             tm.display_name technical_mentor_name,tm.display_name mentor_name,
             sm.display_name skill_mentor_name,
             (select count(*) from station_change_request scr
              where scr.employee_id=e.id and scr.status='APPROVED') station_change_count,
             (select max(coalesce(scr.reviewed_at,scr.updated_at)) from station_change_request scr
              where scr.employee_id=e.id and scr.status='APPROVED') last_station_change_at
      """;
  private static final String FROM = """
       from employee e
       left join sys_user u on u.id=e.user_id
       left join talent_batch b on b.id=e.batch_id
       left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
       left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION'
       left join business_unit bu on bu.id=e.business_unit_id
       left join service_station s on s.id=e.station_id
       left join sys_user tm on tm.id=e.mentor_user_id
       left join sys_user sm on sm.id=e.skill_mentor_user_id
      """;

  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public EmployeeDirectoryController(
      JdbcTemplate db,
      PermissionService permissions,
      AuditService audit) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
  }

  @GetMapping
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long batchId,
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) Long classPositionId,
      @RequestParam(required = false) Long businessUnitId,
      @RequestParam(required = false) Long stationId,
      @RequestParam(required = false) Long mentorId,
      @RequestParam(required = false) Long skillMentorId,
      @RequestParam(required = false) String education,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean all) {
    permissions.require(Permissions.EMPLOYEE_READ);
    var query = filters(
        keyword, batchId, classId, classPositionId, businessUnitId, stationId, mentorId, skillMentorId, education, status);
    long total = db.queryForObject(
        "select count(*)" + FROM + query.sql(),
        Long.class,
        query.args().toArray());
    if (all) {
      var rows = db.queryForList(
          SELECT + FROM + query.sql() + " order by e.id desc",
          query.args().toArray());
      return ApiResponse.ok(new PageResult<>(rows, total, 1, rows.size()));
    }
    int pageSize = Math.min(Math.max(size, 1), 100);
    var args = new ArrayList<>(query.args());
    args.add(pageSize);
    args.add(Math.max(0, (page - 1) * pageSize));
    var rows = db.queryForList(
        SELECT + FROM + query.sql() + " order by e.id desc limit ? offset ?",
        args.toArray());
    return ApiResponse.ok(new PageResult<>(rows, total, page, pageSize));
  }

  @GetMapping("/summary")
  public ApiResponse<Map<String, Object>> summary(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long batchId,
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) Long classPositionId,
      @RequestParam(required = false) Long businessUnitId,
      @RequestParam(required = false) Long stationId,
      @RequestParam(required = false) Long mentorId,
      @RequestParam(required = false) Long skillMentorId,
      @RequestParam(required = false) String education) {
    permissions.require(Permissions.EMPLOYEE_READ);
    var query = filters(
        keyword, batchId, classId, classPositionId, businessUnitId, stationId, mentorId, skillMentorId, education, null);
    return ApiResponse.ok(db.queryForMap("""
        select
          count(*) totalEmployees,
          coalesce(sum(case when e.status='ACTIVE' then 1 else 0 end),0) activeEmployees,
          coalesce(sum(case when e.status='INACTIVE' then 1 else 0 end),0) inactiveEmployees,
          coalesce(sum(case when e.station_id is not null then 1 else 0 end),0) stationAssigned,
          coalesce(sum(case when e.mentor_user_id is not null
                              and e.skill_mentor_user_id is not null then 1 else 0 end),0) mentorReady
        """ + FROM + query.sql(), query.args().toArray()));
  }

  @GetMapping("/export")
  public void export(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long batchId,
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) Long classPositionId,
      @RequestParam(required = false) Long businessUnitId,
      @RequestParam(required = false) Long stationId,
      @RequestParam(required = false) Long mentorId,
      @RequestParam(required = false) Long skillMentorId,
      @RequestParam(required = false) String education,
      @RequestParam(required = false) String status,
      HttpServletResponse response) throws Exception {
    permissions.require(Permissions.EMPLOYEE_EXPORT);
    var query = filters(
        keyword, batchId, classId, classPositionId, businessUnitId, stationId, mentorId, skillMentorId, education, status);
    var rows = db.queryForList(
        SELECT + FROM + query.sql() + " order by e.id desc",
        query.args().toArray());
    var sequence = new AtomicInteger(1);
    var output = rows.stream()
        .map(row -> toExportRow(row, sequence.getAndIncrement()))
        .toList();
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader(
        "Content-Disposition",
        "attachment; filename*=UTF-8''"
            + URLEncoder.encode("人员台账.xlsx", StandardCharsets.UTF_8));
    audit.log("EXPORT_EMPLOYEES", "EMPLOYEE", null, null, Map.of("count", output.size()));
    EasyExcel.write(response.getOutputStream(), EmployeeDirectoryExportRow.class)
        .registerWriteHandler(new EmployeeExcelSheetHandler(24))
        .sheet("人员台账")
        .doWrite(output);
  }

  private FilterQuery filters(
      String keyword,
      Long batchId,
      Long classId,
      Long classPositionId,
      Long businessUnitId,
      Long stationId,
      Long mentorId,
      Long skillMentorId,
      String education,
      String status) {
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    var scope = permissions.employeeFilter("e");
    where.append(scope.sql());
    args.addAll(scope.args());
    if (keyword != null && !keyword.isBlank()) {
      where.append("""
           and (e.name like ? or e.employee_no like ? or e.phone like ? or e.email like ?)
          """);
      String value = "%" + keyword.trim() + "%";
      args.add(value);
      args.add(value);
      args.add(value);
      args.add(value);
    }
    if (batchId != null) {
      where.append(" and e.batch_id=?");
      args.add(batchId);
    }
    if (classId != null) {
      where.append(" and e.class_id=?");
      args.add(classId);
    }
    if (classPositionId != null) {
      where.append(" and e.class_position_id=?");
      args.add(classPositionId);
    }
    if (businessUnitId != null) {
      where.append(" and e.business_unit_id=?");
      args.add(businessUnitId);
    }
    if (stationId != null) {
      where.append(" and e.station_id=?");
      args.add(stationId);
    }
    if (mentorId != null) {
      where.append(" and e.mentor_user_id=?");
      args.add(mentorId);
    }
    if (skillMentorId != null) {
      where.append(" and e.skill_mentor_user_id=?");
      args.add(skillMentorId);
    }
    if (education != null && !education.isBlank()) {
      where.append(" and e.education=?");
      args.add(education);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and e.status=?");
      args.add(status);
    }
    return new FilterQuery(where.toString(), args);
  }

  private EmployeeDirectoryExportRow toExportRow(Map<String, Object> row, int serialNo) {
    var output = new EmployeeDirectoryExportRow();
    output.setSerialNo(serialNo);
    output.setName(string(row, "name"));
    output.setEmployeeNo(string(row, "employee_no"));
    output.setGender(string(row, "gender"));
    output.setBatchName(string(row, "batch_name"));
    output.setClassName(string(row, "class_name"));
    output.setClassPositionName(string(row, "class_position_name"));
    output.setBusinessUnitName(string(row, "business_unit_name"));
    output.setStationName(string(row, "station_name"));
    output.setTechnicalMentorName(string(row, "technical_mentor_name"));
    output.setSkillMentorName(string(row, "skill_mentor_name"));
    output.setIdCard(string(row, "id_card"));
    output.setSchool(string(row, "school"));
    output.setMajor(string(row, "major"));
    output.setEducation(string(row, "education"));
    output.setNativePlace(string(row, "native_place"));
    output.setPoliticalStatus(string(row, "political_status"));
    output.setResidence(string(row, "residence"));
    output.setHobbies(string(row, "hobbies"));
    output.setSpeciality(string(row, "speciality"));
    output.setEmail(string(row, "email"));
    output.setPhone(string(row, "phone"));
    output.setStatus("ACTIVE".equals(string(row, "status")) ? "在职" : "停用");
    output.setBirthDate(date(row.get("birth_date")));
    output.setOnboardDate(date(row.get("onboard_date")));
    return output;
  }

  private String string(Map<String, Object> row, String key) {
    var value = row.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private java.time.LocalDate date(Object value) {
    return value instanceof Date date ? date.toLocalDate() : null;
  }

  private record FilterQuery(String sql, List<Object> args) {}
}
