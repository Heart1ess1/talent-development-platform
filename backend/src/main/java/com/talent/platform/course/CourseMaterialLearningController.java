package com.talent.platform.course;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/course-materials")
public class CourseMaterialLearningController {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final CourseMaterialPreviewService preview;

  public CourseMaterialLearningController(JdbcTemplate db, PermissionService permissions,
                                          CourseMaterialPreviewService preview) {
    this.db = db;
    this.permissions = permissions;
    this.preview = preview;
  }

  @GetMapping("/learning")
  @PreAuthorize("hasRole('EMPLOYEE')")
  public ApiResponse<List<Map<String, Object>>> myMaterials() {
    return ApiResponse.ok(db.queryForList("""
        select distinct m.id,m.course_id,m.original_name,m.content_type,m.size,m.created_at,
          c.name course_name,
          exists(select 1 from course_material_view_session v
                 where v.material_id=m.id and v.employee_id=e.id) learned
        from employee e
        join course_enrollment ce on ce.employee_id=e.id
        join course_session s on s.id=ce.session_id
        join course c on c.id=s.course_id and c.enabled=true
        join course_material m on m.course_id=c.id
        where e.user_id=?
        order by learned,m.created_at desc,m.id desc
        """, SecurityUtils.current().id()));
  }

  @GetMapping("/manage")
  public ApiResponse<List<Map<String, Object>>> managedMaterials() {
    permissions.require(Permissions.COURSE_MANAGE);
    return ApiResponse.ok(db.queryForList("""
        select m.id,m.course_id,m.original_name,m.content_type,m.size,m.created_at,c.name course_name,
          count(distinct ce.employee_id) assigned_count,
          count(distinct case when v.id is not null then ce.employee_id end) learned_count,
          count(distinct ce.employee_id)-count(distinct case when v.id is not null then ce.employee_id end) unlearned_count
        from course_material m
        join course c on c.id=m.course_id
        left join course_session s on s.course_id=c.id
        left join course_enrollment ce on ce.session_id=s.id
        left join course_material_view_session v on v.material_id=m.id and v.employee_id=ce.employee_id
        group by m.id,m.course_id,m.original_name,m.content_type,m.size,m.created_at,c.name
        order by m.created_at desc,m.id desc
        """));
  }

  @GetMapping("/manage/{materialId}/learners")
  public ApiResponse<List<Map<String, Object>>> materialLearners(@PathVariable Long materialId) {
    permissions.require(Permissions.COURSE_MANAGE);
    requireMaterial(materialId);
    return ApiResponse.ok(db.queryForList("""
        select e.id employee_id,e.employee_no,e.name employee_name,b.name batch_name,sn.name station_name,
          case when coalesce(v.view_count,0)>0 then true else false end learned,
          coalesce(v.view_count,0) view_count,coalesce(v.duration_seconds,0) duration_seconds,
          v.last_viewed_at
        from employee e
        left join talent_batch b on b.id=e.batch_id
        left join service_station sn on sn.id=e.station_id
        join (select distinct ce.employee_id
              from course_enrollment ce join course_session cs on cs.id=ce.session_id
              join course_material cm on cm.course_id=cs.course_id where cm.id=?) assigned
          on assigned.employee_id=e.id
        left join (select employee_id,count(*) view_count,sum(duration_seconds) duration_seconds,
                          max(last_seen_at) last_viewed_at
                   from course_material_view_session where material_id=? and employee_id is not null
                   group by employee_id) v on v.employee_id=e.id
        order by learned,e.employee_no,e.id
        """, materialId, materialId));
  }

  @PostMapping("/{materialId}/preview-sessions")
  @Transactional
  public ApiResponse<Map<String, Object>> startPreview(@PathVariable Long materialId) {
    var material = requireMaterial(materialId);
    var user = SecurityUtils.current();
    Long employeeId = "EMPLOYEE".equals(user.role()) ? employeeId(user.id()) : null;
    if ("EMPLOYEE".equals(user.role()) && employeeId == null) {
      throw new AccessDeniedException("当前账号未绑定员工档案");
    }
    if (!user.can(Permissions.COURSE_MANAGE)) requireEmployeeMaterialAccess(materialId, employeeId);
    db.update("insert into course_material_view_session(material_id,employee_id,user_id) values(?,?,?)",
        materialId, employeeId, user.id());
    Long sessionId = db.queryForObject("select last_insert_id()", Long.class);
    int pageCount = preview.pageCount(String.valueOf(material.get("storage_key")),
        String.valueOf(material.get("original_name")));
    return ApiResponse.ok(Map.of("sessionId", sessionId, "pageCount", pageCount));
  }

  @GetMapping("/{materialId}/preview-sessions/{sessionId}/pages/{pageNumber}")
  public ResponseEntity<byte[]> previewPage(@PathVariable Long materialId, @PathVariable Long sessionId,
                                            @PathVariable int pageNumber) {
    var session = ownedSession(materialId, sessionId);
    var material = requireMaterial(materialId);
    String watermark = watermark(session);
    byte[] image = preview.renderPage(String.valueOf(material.get("storage_key")),
        String.valueOf(material.get("original_name")), pageNumber - 1, watermark);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .body(image);
  }

  @PostMapping("/{materialId}/preview-sessions/{sessionId}/heartbeat")
  public ApiResponse<Void> heartbeat(@PathVariable Long materialId, @PathVariable Long sessionId) {
    touch(materialId, sessionId, false);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{materialId}/preview-sessions/{sessionId}/close")
  public ApiResponse<Void> close(@PathVariable Long materialId, @PathVariable Long sessionId) {
    touch(materialId, sessionId, true);
    return ApiResponse.ok(null);
  }

  private void touch(Long materialId, Long sessionId, boolean close) {
    int updated = db.update("""
        update course_material_view_session
        set duration_seconds=duration_seconds+least(60,greatest(0,timestampdiff(second,last_seen_at,now()))),
            last_seen_at=now(),ended_at=case when ? then now() else ended_at end
        where id=? and material_id=? and user_id=? and ended_at is null
        """, close, sessionId, materialId, SecurityUtils.current().id());
    if (updated == 0 && !close) throw new BusinessException(404, "课件预览会话已结束");
  }

  private Map<String, Object> ownedSession(Long materialId, Long sessionId) {
    var rows = db.queryForList("""
        select v.*,e.name employee_name,e.employee_no,u.display_name,u.username
        from course_material_view_session v
        join sys_user u on u.id=v.user_id
        left join employee e on e.id=v.employee_id
        where v.id=? and v.material_id=? and v.user_id=? and v.ended_at is null
        """, sessionId, materialId, SecurityUtils.current().id());
    if (rows.isEmpty()) throw new BusinessException(404, "课件预览会话不存在或已结束");
    return rows.get(0);
  }

  private String watermark(Map<String, Object> session) {
    if (session.get("employee_id") != null) {
      return session.get("employee_name") + "（" + session.get("employee_no") + "）";
    }
    return session.get("display_name") + "（" + session.get("username") + "）";
  }

  private Map<String, Object> requireMaterial(Long materialId) {
    var rows = db.queryForList("select * from course_material where id=?", materialId);
    if (rows.isEmpty()) throw new BusinessException(404, "课件不存在");
    return new LinkedHashMap<>(rows.get(0));
  }

  private Long employeeId(Long userId) {
    var ids = db.queryForList("select id from employee where user_id=?", Long.class, userId);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private void requireEmployeeMaterialAccess(Long materialId, Long employeeId) {
    if (employeeId == null) throw new AccessDeniedException("无权访问该课件");
    Integer count = db.queryForObject("""
        select count(*) from course_material m
        join course_session s on s.course_id=m.course_id
        join course_enrollment ce on ce.session_id=s.id
        where m.id=? and ce.employee_id=?
        """, Integer.class, materialId, employeeId);
    if (count == null || count == 0) throw new AccessDeniedException("您未被安排学习该课件");
  }
}
