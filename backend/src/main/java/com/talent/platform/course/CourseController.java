package com.talent.platform.course;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import com.talent.platform.storage.FileStorageService;
import com.talent.platform.storage.UploadTicketService;
import org.apache.pdfbox.pdmodel.PDDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RestController
@RequestMapping("/api/v1")
public class CourseController {
  private static final long MATERIAL_MAX_SIZE = 50L * 1024 * 1024;
  private static final int MATERIAL_MAX_PAGES = 500;
  private static final int MATERIAL_MAX_ARCHIVE_ENTRIES = 10_000;
  private static final byte[] OLE_MAGIC = {
      (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
      (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
  };

  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;
  private final FileStorageService storage;
  private final UploadTicketService uploadTickets;
  private final SecureRandom random = new SecureRandom();

  public CourseController(
      JdbcTemplate db,
      PermissionService permissions,
      AuditService audit,
      FileStorageService storage,
      UploadTicketService uploadTickets
  ) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
    this.storage = storage;
    this.uploadTickets = uploadTickets;
  }

  public record CourseRequest(
      @NotBlank @Size(max = 128) String name,
      @Size(max = 4000) String description
  ) {}

  public record EnabledRequest(@NotNull Boolean enabled) {}

  public record SessionRequest(
      @NotNull Long courseId,
      @NotBlank @Size(max = 128) String title,
      @Size(max = 128) String location,
      BigDecimal hours,
      @NotNull LocalDateTime startsAt,
      @NotNull LocalDateTime endsAt,
      @NotNull LocalDateTime checkinStartsAt,
      @NotNull LocalDateTime checkinEndsAt
  ) {}

  public record CheckinRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}

  public record ManualAttendanceRequest(
      @NotNull Long sessionId,
      @NotNull Long employeeId,
      @Size(max = 255) String remark
  ) {}

  public record EnrollRequest(@NotEmpty List<@NotNull Long> employeeIds) {}

  public record DirectUploadRequest(
      @NotBlank @Size(max = 255) String originalName,
      @Size(max = 128) String contentType,
      long size
  ) {}

  @GetMapping("/courses")
  public ApiResponse<List<Map<String, Object>>> courses(
      @RequestParam(required = false, defaultValue = "") String keyword,
      @RequestParam(required = false, defaultValue = "false") boolean includeDisabled
  ) {
    var user = SecurityUtils.current();
    String term = keyword.trim().toLowerCase(Locale.ROOT);
    String pattern = "%" + term + "%";
    if ("EMPLOYEE".equals(user.role())) {
      return ApiResponse.ok(db.queryForList("""
          select distinct c.id,c.name,c.description,c.enabled,c.created_at,
            (select count(*) from course_session cs where cs.course_id=c.id) session_count,
            (select count(*) from course_material cm where cm.course_id=c.id) material_count
          from course c
          join course_session s on s.course_id=c.id
          join course_enrollment ce on ce.session_id=s.id
          join employee e on e.id=ce.employee_id
          where e.user_id=? and c.enabled=true
            and (?='' or lower(c.name) like ? or lower(coalesce(c.description,'')) like ?)
          order by c.id desc
          """, user.id(), term, pattern, pattern));
    }
    String enabledClause = includeDisabled && user.can(Permissions.COURSE_MANAGE) ? "" : " and c.enabled=true";
    return ApiResponse.ok(db.queryForList("""
        select c.id,c.name,c.description,c.enabled,c.created_at,u.display_name creator_name,
          (select count(*) from course_session s where s.course_id=c.id) session_count,
          (select count(*) from course_material m where m.course_id=c.id) material_count,
          (select count(*) from course_enrollment ce join course_session s on s.id=ce.session_id where s.course_id=c.id) enrollment_count
        from course c
        join sys_user u on u.id=c.created_by
        where (?='' or lower(c.name) like ? or lower(coalesce(c.description,'')) like ?)
        """ + enabledClause + " order by c.id desc", term, pattern, pattern));
  }

  @GetMapping("/courses/summary")
  public ApiResponse<Map<String, Object>> courseSummary() {
    permissions.require(Permissions.COURSE_MANAGE);
    return ApiResponse.ok(db.queryForMap("""
        select
          (select count(*) from course) totalCourses,
          (select count(*) from course where enabled=true) enabledCourses,
          (select count(*) from course_session where starts_at>=current_date()) upcomingSessions,
          (select count(*) from course_material) totalMaterials,
          (select count(*) from course_enrollment) totalEnrollments
        """));
  }

  @PostMapping("/courses")
  public ApiResponse<Long> createCourse(@Valid @RequestBody CourseRequest request) {
    permissions.require(Permissions.COURSE_MANAGE);
    db.update("insert into course(name,description,created_by) values(?,?,?)",
        request.name().trim(), trimToNull(request.description()), SecurityUtils.current().id());
    Long id = lastId();
    audit.log("CREATE_COURSE", "COURSE", id, null, request);
    return ApiResponse.ok(id);
  }

  @PutMapping("/courses/{id}")
  public ApiResponse<Void> updateCourse(@PathVariable Long id, @Valid @RequestBody CourseRequest request) {
    permissions.require(Permissions.COURSE_MANAGE);
    var before = one("select * from course where id=?", id);
    db.update("update course set name=?,description=? where id=?",
        request.name().trim(), trimToNull(request.description()), id);
    audit.log("UPDATE_COURSE", "COURSE", id, before, request);
    return ApiResponse.ok(null);
  }

  @PutMapping("/courses/{id}/enabled")
  public ApiResponse<Void> enableCourse(@PathVariable Long id, @Valid @RequestBody EnabledRequest request) {
    permissions.require(Permissions.COURSE_MANAGE);
    var before = one("select * from course where id=?", id);
    db.update("update course set enabled=? where id=?", request.enabled(), id);
    audit.log(request.enabled() ? "ENABLE_COURSE" : "DISABLE_COURSE", "COURSE", id, before, request);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/courses/{id}")
  @Transactional
  public ApiResponse<Void> deleteCourse(@PathVariable Long id) {
    permissions.require(Permissions.COURSE_MANAGE);
    var before = one("select * from course where id=?", id);
    if (count("select count(*) from course_session where course_id=?", id) > 0) {
      throw new BusinessException(400, "该课程已有培训场次，不能删除；可停用课程后保留历史");
    }
    if (count("select count(*) from course_material where course_id=?", id) > 0) {
      throw new BusinessException(400, "请先删除课程课件，再删除课程");
    }
    db.update("delete from course where id=?", id);
    audit.log("DELETE_COURSE", "COURSE", id, before, null);
    return ApiResponse.ok(null);
  }

  @GetMapping("/courses/{id}/materials")
  public ApiResponse<List<Map<String, Object>>> materials(@PathVariable Long id) {
    requireCourseAccess(id);
    return ApiResponse.ok(db.queryForList("""
        select m.id,m.course_id,m.original_name,m.content_type,m.size,m.created_at,u.display_name uploader_name
        from course_material m
        join sys_user u on u.id=m.uploaded_by
        where m.course_id=?
        order by m.created_at desc,m.id desc
        """, id));
  }

  @PostMapping(value = "/courses/{id}/materials", consumes = "multipart/form-data")
  public ApiResponse<Long> uploadMaterial(@PathVariable Long id, @RequestParam MultipartFile file) {
    permissions.require(Permissions.COURSE_MANAGE);
    one("select id from course where id=?", id);
    validateMaterial(file);
    var stored = storage.store(file);
    try {
      db.update("""
          insert into course_material(course_id,original_name,content_type,size,storage_key,uploaded_by)
          values(?,?,?,?,?,?)
          """, id, file.getOriginalFilename(), stored.contentType(), stored.size(), stored.key(),
          SecurityUtils.current().id());
      Long materialId = lastId();
      audit.log("UPLOAD_COURSE_MATERIAL", "COURSE_MATERIAL", materialId, null,
          Map.of("courseId", id, "name", Optional.ofNullable(file.getOriginalFilename()).orElse("课件")));
      return ApiResponse.ok(materialId);
    } catch (RuntimeException exception) {
      storage.delete(stored.key());
      throw exception;
    }
  }

  @PostMapping("/courses/{id}/materials/upload-ticket")
  public ApiResponse<UploadTicketService.UploadTicket> createMaterialUploadTicket(
      @PathVariable Long id,
      @Valid @RequestBody DirectUploadRequest request
  ) {
    permissions.require(Permissions.COURSE_MANAGE);
    one("select id from course where id=?", id);
    validateMaterialMetadata(request.originalName(), request.size());
    return ApiResponse.ok(uploadTickets.issue(
        "course-material", id, request.originalName(), request.contentType(), request.size()));
  }

  @PostMapping("/courses/{id}/materials/upload-complete/{ticketId}")
  @Transactional
  public ApiResponse<Long> completeMaterialUpload(
      @PathVariable Long id,
      @PathVariable UUID ticketId
  ) {
    permissions.require(Permissions.COURSE_MANAGE);
    one("select id from course where id=?", id);
    var upload = uploadTickets.consume(ticketId, "course-material", id);
    try {
      validateStoredMaterial(upload.storageKey(), upload.originalName(), upload.size());
      db.update("""
          insert into course_material(course_id,original_name,content_type,size,storage_key,uploaded_by)
          values(?,?,?,?,?,?)
          """, id, upload.originalName(), upload.contentType(), upload.size(), upload.storageKey(),
          SecurityUtils.current().id());
      Long materialId = lastId();
      audit.log("UPLOAD_COURSE_MATERIAL", "COURSE_MATERIAL", materialId, null,
          Map.of("courseId", id, "name", upload.originalName(), "transfer", "OSS_DIRECT"));
      return ApiResponse.ok(materialId);
    } catch (RuntimeException exception) {
      try {
        storage.delete(upload.storageKey());
      } catch (RuntimeException ignored) {
        // Database rollback is authoritative; orphan cleanup can retry later.
      }
      throw exception;
    }
  }

  @GetMapping("/course-materials/{id}")
  public ResponseEntity<?> downloadMaterial(
      @PathVariable Long id,
      @RequestParam(required = false, defaultValue = "false") boolean inline
  ) {
    one("select id from course_material where id=?", id);
    throw new BusinessException(403, "课件仅支持带水印在线预览，不提供原文件查看或下载");
  }

  @DeleteMapping("/course-materials/{id}")
  @Transactional
  public ApiResponse<Void> deleteMaterial(@PathVariable Long id) {
    permissions.require(Permissions.COURSE_MANAGE);
    var before = one("select * from course_material where id=?", id);
    db.update("delete from course_material where id=?", id);
    storage.delete(String.valueOf(before.get("storage_key")));
    audit.log("DELETE_COURSE_MATERIAL", "COURSE_MATERIAL", id, before, null);
    return ApiResponse.ok(null);
  }

  @GetMapping("/sessions")
  public ApiResponse<List<Map<String, Object>>> sessions(
      @RequestParam(required = false) Long courseId,
      @RequestParam(required = false, defaultValue = "") String keyword
  ) {
    var user = SecurityUtils.current();
    var args = new ArrayList<Object>();
    var where = new StringBuilder(" where 1=1");
    if (courseId != null) {
      where.append(" and s.course_id=?");
      args.add(courseId);
    }
    if (!keyword.isBlank()) {
      where.append(" and (lower(s.title) like ? or lower(c.name) like ? or lower(coalesce(s.location,'')) like ?)");
      String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    String select = """
        select distinct s.id,s.course_id,s.title,s.location,s.hours,s.starts_at,s.ends_at,
          s.checkin_starts_at,s.checkin_ends_at,%s c.name course_name,
          (select count(*) from course_enrollment x where x.session_id=s.id) enrollment_count,
          (select count(*) from attendance a where a.session_id=s.id) attendance_count
        from course_session s
        join course c on c.id=s.course_id
        """;
    if ("ALL".equals(user.dataScope())) {
      return ApiResponse.ok(db.queryForList(
          select.formatted("s.checkin_code,") + where + " order by s.starts_at desc", args.toArray()));
    }
    var scope = permissions.employeeFilter("e");
    args.addAll(scope.args());
    String sql = select.formatted("") + """
        join course_enrollment ce on ce.session_id=s.id
        join employee e on e.id=ce.employee_id
        """ + where + scope.sql() + " order by s.starts_at desc";
    return ApiResponse.ok(db.queryForList(sql, args.toArray()));
  }

  @GetMapping("/sessions/summary")
  public ApiResponse<Map<String, Object>> sessionSummary() {
    permissions.require(Permissions.COURSE_MANAGE);
    return ApiResponse.ok(db.queryForMap("""
        select
          (select count(*) from course_session) totalSessions,
          (select count(*) from course_session where starts_at>now()) upcomingSessions,
          (select count(*) from course_session where now() between starts_at and ends_at) ongoingSessions,
          (select count(*) from course_enrollment) totalEnrollments,
          (select count(*) from attendance) totalAttendance
        """));
  }

  @PostMapping("/sessions")
  public ApiResponse<Map<String, Object>> createSession(@Valid @RequestBody SessionRequest request) {
    permissions.require(Permissions.COURSE_MANAGE);
    validateSession(request);
    String code;
    do {
      code = String.format("%06d", random.nextInt(1_000_000));
    } while (!db.queryForList("select id from course_session where checkin_code=?", code).isEmpty());
    db.update("""
        insert into course_session(
          course_id,title,location,hours,starts_at,ends_at,checkin_starts_at,checkin_ends_at,
          checkin_code,created_by
        ) values(?,?,?,?,?,?,?,?,?,?)
        """, request.courseId(), request.title().trim(), trimToNull(request.location()),
        request.hours(), request.startsAt(), request.endsAt(), request.checkinStartsAt(),
        request.checkinEndsAt(), code, SecurityUtils.current().id());
    Long id = lastId();
    audit.log("CREATE_SESSION", "SESSION", id, null, request);
    return ApiResponse.ok(Map.of("id", id, "checkinCode", code));
  }

  @PutMapping("/sessions/{id}")
  public ApiResponse<Void> updateSession(@PathVariable Long id, @Valid @RequestBody SessionRequest request) {
    permissions.require(Permissions.COURSE_MANAGE);
    validateSession(request);
    var before = one("select * from course_session where id=?", id);
    db.update("""
        update course_session
        set course_id=?,title=?,location=?,hours=?,starts_at=?,ends_at=?,checkin_starts_at=?,checkin_ends_at=?
        where id=?
        """, request.courseId(), request.title().trim(), trimToNull(request.location()),
        request.hours(), request.startsAt(), request.endsAt(), request.checkinStartsAt(),
        request.checkinEndsAt(), id);
    audit.log("UPDATE_SESSION", "SESSION", id, before, request);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/sessions/{id}")
  @Transactional
  public ApiResponse<Void> deleteSession(@PathVariable Long id) {
    permissions.require(Permissions.COURSE_MANAGE);
    var before = one("select * from course_session where id=?", id);
    if (count("select count(*) from attendance where session_id=?", id) > 0) {
      throw new BusinessException(400, "该场次已有签到记录，不能删除");
    }
    db.update("delete from course_enrollment where session_id=?", id);
    db.update("delete from course_session where id=?", id);
    audit.log("DELETE_SESSION", "SESSION", id, before, null);
    return ApiResponse.ok(null);
  }

  @GetMapping("/sessions/{id}/enrollments")
  public ApiResponse<List<Map<String, Object>>> enrollments(@PathVariable Long id) {
    permissions.require(Permissions.COURSE_MANAGE);
    one("select id from course_session where id=?", id);
    return ApiResponse.ok(db.queryForList("""
        select e.id employee_id,e.employee_no,e.name employee_name,e.status,
          ce.assigned_at,a.status attendance_status,a.checked_at
        from course_enrollment ce
        join employee e on e.id=ce.employee_id
        left join attendance a on a.session_id=ce.session_id and a.employee_id=ce.employee_id
        where ce.session_id=?
        order by e.employee_no,e.id
        """, id));
  }

  @PostMapping("/sessions/{id}/enroll")
  public ApiResponse<Integer> enroll(@PathVariable Long id, @Valid @RequestBody EnrollRequest request) {
    permissions.require(Permissions.COURSE_MANAGE);
    one("select id from course_session where id=?", id);
    int added = 0;
    for (Long employeeId : new LinkedHashSet<>(request.employeeIds())) {
      added += db.update("""
          insert ignore into course_enrollment(session_id,employee_id,assigned_by) values(?,?,?)
          """, id, employeeId, SecurityUtils.current().id());
    }
    audit.log("ENROLL_SESSION", "SESSION", id, null, request);
    return ApiResponse.ok(added);
  }

  @DeleteMapping("/sessions/{id}/enrollments/{employeeId}")
  public ApiResponse<Void> removeEnrollment(@PathVariable Long id, @PathVariable Long employeeId) {
    permissions.require(Permissions.COURSE_MANAGE);
    if (count("select count(*) from attendance where session_id=? and employee_id=?", id, employeeId) > 0) {
      throw new BusinessException(400, "该员工已有签到记录，不能移除课程安排");
    }
    db.update("delete from course_enrollment where session_id=? and employee_id=?", id, employeeId);
    audit.log("REMOVE_SESSION_ENROLLMENT", "SESSION", id, null, Map.of("employeeId", employeeId));
    return ApiResponse.ok(null);
  }

  @PostMapping("/attendance/checkin")
  @PreAuthorize("hasRole('EMPLOYEE')")
  public ApiResponse<Void> checkin(@Valid @RequestBody CheckinRequest request) {
    var user = SecurityUtils.current();
    var rows = db.queryForList("""
        select id,checkin_starts_at,checkin_ends_at from course_session where checkin_code=?
        """, request.code());
    if (rows.isEmpty()) throw new BusinessException(404, "签到码无效");
    var session = rows.get(0);
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime from = asDateTime(session.get("checkin_starts_at"));
    LocalDateTime to = asDateTime(session.get("checkin_ends_at"));
    if (now.isBefore(from) || now.isAfter(to)) throw new BusinessException(400, "当前不在签到时间范围内");
    Long employeeId = db.queryForObject("select id from employee where user_id=?", Long.class, user.id());
    if (count("select count(*) from course_enrollment where session_id=? and employee_id=?",
        session.get("id"), employeeId) == 0) {
      throw new AccessDeniedException("您未被安排参加该场次");
    }
    if (count("select count(*) from attendance where session_id=? and employee_id=?",
        session.get("id"), employeeId) > 0) {
      throw new BusinessException(400, "您已完成该场次签到，无需重复提交");
    }
    db.update("""
        insert into attendance(session_id,employee_id,status,source,checked_at,operator_user_id)
        values(?,?,'CHECKED_IN','SELF',?,?)
        """, session.get("id"), employeeId, now, user.id());
    audit.log("SELF_CHECKIN", "SESSION", number(session.get("id")), null, Map.of("employeeId", employeeId));
    return ApiResponse.ok(null);
  }

  @PostMapping("/attendance/manual")
  @Transactional
  public ApiResponse<Void> manualAttendance(@Valid @RequestBody ManualAttendanceRequest request) {
    permissions.require(Permissions.ATTENDANCE_MANAGE);
    one("select id from course_session where id=?", request.sessionId());
    permissions.requireEmployee(request.employeeId());
    var user = SecurityUtils.current();
    db.update("""
        insert ignore into course_enrollment(session_id,employee_id,assigned_by) values(?,?,?)
        """, request.sessionId(), request.employeeId(), user.id());
    db.update("""
        insert into attendance(
          session_id,employee_id,status,source,checked_at,operator_user_id,remark
        ) values(?,?,'MANUAL','MANUAL',now(),?,?)
        on duplicate key update status='MANUAL',source='MANUAL',checked_at=now(),
          operator_user_id=values(operator_user_id),remark=values(remark)
        """, request.sessionId(), request.employeeId(), user.id(), trimToNull(request.remark()));
    audit.log("MANUAL_ATTENDANCE", "EMPLOYEE", request.employeeId(), null, request);
    return ApiResponse.ok(null);
  }

  @GetMapping("/attendance")
  public ApiResponse<List<Map<String, Object>>> attendance(
      @RequestParam(required = false) Long employeeId,
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) Long courseId,
      @RequestParam(required = false) Long sessionId,
      @RequestParam(required = false, defaultValue = "") String keyword,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) LocalDate dateFrom,
      @RequestParam(required = false) LocalDate dateTo
  ) {
    if (employeeId != null) permissions.requireEmployee(employeeId);
    var scope = permissions.employeeFilter("e");
    var where = new StringBuilder(" where 1=1").append(scope.sql());
    var args = new ArrayList<Object>(scope.args());
    if (employeeId != null) {
      where.append(" and e.id=?");
      args.add(employeeId);
    }
    if (classId != null) {
      where.append(" and e.class_id=?");
      args.add(classId);
    }
    if (courseId != null) {
      where.append(" and c.id=?");
      args.add(courseId);
    }
    if (sessionId != null) {
      where.append(" and s.id=?");
      args.add(sessionId);
    }
    if (!keyword.isBlank()) {
      where.append(" and (lower(e.name) like ? or lower(e.employee_no) like ? or lower(c.name) like ?)");
      String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    if (source != null && !source.isBlank()) {
      where.append(" and a.source=?");
      args.add(source);
    }
    if (dateFrom != null) {
      where.append(" and a.checked_at>=?");
      args.add(dateFrom.atStartOfDay());
    }
    if (dateTo != null) {
      where.append(" and a.checked_at<?");
      args.add(dateTo.plusDays(1).atStartOfDay());
    }
    return ApiResponse.ok(db.queryForList("""
        select a.id,a.session_id,a.employee_id,a.status,a.source,a.checked_at,a.remark,
          e.employee_no,e.name employee_name,e.class_id,cls.label class_name,
          s.title session_title,s.location,
          c.id course_id,c.name course_name
        from attendance a
        join employee e on e.id=a.employee_id
        left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
        join course_session s on s.id=a.session_id
        join course c on c.id=s.course_id
        """ + where + " order by a.checked_at desc limit 1000", args.toArray()));
  }

  @GetMapping("/attendance/summary")
  public ApiResponse<Map<String, Object>> attendanceSummary() {
    var scope = permissions.employeeFilter("e");
    String suffix = """
        from attendance a join employee e on e.id=a.employee_id where 1=1
        """ + scope.sql();
    var args = scope.args().toArray();
    return ApiResponse.ok(Map.of(
        "totalAttendance", count("select count(*) " + suffix, args),
        "todayAttendance", count("select count(*) " + suffix + " and date(a.checked_at)=current_date()", args),
        "selfAttendance", count("select count(*) " + suffix + " and a.source='SELF'", args),
        "manualAttendance", count("select count(*) " + suffix + " and a.source='MANUAL'", args)
    ));
  }

  private void validateSession(SessionRequest request) {
    var course = one("select id,enabled from course where id=?", request.courseId());
    if (!asBoolean(course.get("enabled"))) throw new BusinessException(400, "已停用课程不能创建或调整场次");
    if (!request.endsAt().isAfter(request.startsAt())) {
      throw new BusinessException(400, "场次结束时间必须晚于开始时间");
    }
    if (!request.checkinEndsAt().isAfter(request.checkinStartsAt())) {
      throw new BusinessException(400, "签到结束时间必须晚于签到开始时间");
    }
    if (request.hours() != null && (request.hours().signum() <= 0 || request.hours().compareTo(new BigDecimal("999.9")) > 0)) {
      throw new BusinessException(400, "课程学时必须大于 0");
    }
  }

  private void requireCourseAccess(Long courseId) {
    var user = SecurityUtils.current();
    if (user.can(Permissions.COURSE_MANAGE)) {
      one("select id from course where id=?", courseId);
      return;
    }
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>();
    args.add(courseId);
    args.addAll(scope.args());
    int allowed = count("""
        select count(*) from course_session s
        join course_enrollment ce on ce.session_id=s.id
        join employee e on e.id=ce.employee_id
        where s.course_id=?
        """ + scope.sql(), args.toArray());
    if (allowed == 0) throw new AccessDeniedException("无权访问该课程资料");
  }

  private void validateMaterial(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new BusinessException(400, "不能上传空文件");
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
    validateMaterialMetadata(name, file.getSize());
    try {
      validateMaterialContent(name, file.getBytes());
    } catch (IOException | IllegalArgumentException exception) {
      throw new BusinessException(400, "课件内容与文件格式不符或文件已损坏");
    }
  }

  private void validateMaterialMetadata(String originalName, long size) {
    if (size <= 0) throw new BusinessException(400, "不能上传空文件");
    if (size > MATERIAL_MAX_SIZE) throw new BusinessException(400, "单个课件不能超过 50MB");
    String name = Optional.ofNullable(originalName).orElse("").toLowerCase(Locale.ROOT);
    if (!name.matches(".*\\.(pdf|doc|docx|ppt|pptx|ofd|png|jpg|jpeg)$")) {
      throw new BusinessException(400, "仅支持 Word、PDF、PPT、OFD、PNG、JPG 课件安全预览");
    }
  }

  private void validateStoredMaterial(String storageKey, String originalName, long size) {
    validateMaterialMetadata(originalName, size);
    try (var input = storage.load(storageKey).getInputStream()) {
      validateMaterialContent(originalName, input.readAllBytes());
    } catch (IOException | IllegalArgumentException exception) {
      throw new BusinessException(400, "课件内容与文件格式不符或文件已损坏");
    }
  }

  private void validateMaterialContent(String originalName, byte[] content) throws IOException {
    String name = originalName.toLowerCase(Locale.ROOT);
    if (name.endsWith(".pdf")) {
      try (var document = PDDocument.load(content)) {
        int pages = document.getNumberOfPages();
        if (pages <= 0 || pages > MATERIAL_MAX_PAGES) throw new IOException("invalid pdf page count");
      }
    } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
      if (ImageIO.read(new ByteArrayInputStream(content)) == null) throw new IOException("invalid image");
    } else if (name.endsWith(".doc") || name.endsWith(".ppt")) {
      if (!startsWith(content, OLE_MAGIC)) throw new IOException("invalid legacy office document");
    } else if (name.endsWith(".docx")) {
      validateZipPackage(content, "[content_types].xml", "word/document.xml");
    } else if (name.endsWith(".pptx")) {
      validateZipPackage(content, "[content_types].xml", "ppt/presentation.xml");
    } else if (name.endsWith(".ofd")) {
      validateZipPackage(content, "ofd.xml");
    } else {
      throw new IOException("unsupported material format");
    }
  }

  private void validateZipPackage(byte[] content, String... requiredEntries) throws IOException {
    if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
      throw new IOException("invalid zip package");
    }
    Path temporary = Files.createTempFile("talent-material-", ".zip");
    try {
      Files.write(temporary, content);
      var missing = new LinkedHashSet<String>();
      for (String entry : requiredEntries) missing.add(entry.toLowerCase(Locale.ROOT));
      try (var archive = new ZipFile(temporary.toFile())) {
        int entries = 0;
        var iterator = archive.entries();
        while (iterator.hasMoreElements()) {
          ZipEntry entry = iterator.nextElement();
          if (++entries > MATERIAL_MAX_ARCHIVE_ENTRIES) throw new IOException("too many archive entries");
          String normalized = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
          while (normalized.startsWith("/")) normalized = normalized.substring(1);
          missing.remove(normalized);
        }
      }
      if (!missing.isEmpty()) throw new IOException("missing package entries: " + missing);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private boolean startsWith(byte[] content, byte[] magic) {
    if (content.length < magic.length) return false;
    for (int index = 0; index < magic.length; index++) {
      if (content[index] != magic[index]) return false;
    }
    return true;
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new BusinessException(404, "资源不存在");
    return rows.get(0);
  }

  private int count(String sql, Object... args) {
    Integer value = db.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private Long lastId() {
    return db.queryForObject("select last_insert_id()", Long.class);
  }

  private Long number(Object value) {
    return ((Number) value).longValue();
  }

  private boolean asBoolean(Object value) {
    return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() == 1);
  }

  private LocalDateTime asDateTime(Object value) {
    if (value instanceof LocalDateTime dateTime) return dateTime;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
    if (value instanceof java.util.Date date) return new java.sql.Timestamp(date.getTime()).toLocalDateTime();
    throw new BusinessException(400, "场次时间数据异常");
  }

  private String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
