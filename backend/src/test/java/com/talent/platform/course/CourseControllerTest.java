package com.talent.platform.course;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.storage.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseControllerTest {
  private JdbcTemplate db;
  private FileStorageService storage;
  private CourseController controller;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    storage = mock(FileStorageService.class);
    controller = new CourseController(
        db,
        mock(PermissionService.class),
        mock(AuditService.class),
        storage
    );
    var user = new CurrentUser(
        7L, "training", "培训管理员", "TRAINING_ADMIN", false, 1,
        Set.of(Permissions.COURSE_MANAGE, Permissions.ATTENDANCE_MANAGE), "ALL"
    );
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of())
    );
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void refusesToDeleteCourseWithSessionHistory() {
    when(db.queryForList(startsWith("select * from course"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L, "name", "基础课程")));
    when(db.queryForObject(startsWith("select count(*) from course_session"), eq(Integer.class), eq(1L)))
        .thenReturn(1);

    assertThatThrownBy(() -> controller.deleteCourse(1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("保留历史");
    verify(db, never()).update("delete from course where id=?", 1L);
  }

  @Test
  void refusesUnsupportedCourseMaterialBeforeStorage() {
    when(db.queryForList(startsWith("select id from course"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L)));
    var file = new MockMultipartFile("file", "setup.exe", "application/octet-stream", new byte[]{1});

    assertThatThrownBy(() -> controller.uploadMaterial(1L, file))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("支持 PDF");
    verify(storage, never()).store(file);
  }

  @Test
  void refusesDuplicateEmployeeCheckin() {
    LocalDateTime now = LocalDateTime.now();
    when(db.queryForList(startsWith("select id,checkin_starts_at"), eq("123456")))
        .thenReturn(List.of(Map.of(
            "id", 9L,
            "checkin_starts_at", Timestamp.valueOf(now.minusMinutes(10)),
            "checkin_ends_at", Timestamp.valueOf(now.plusMinutes(10))
        )));
    when(db.queryForObject("select id from employee where user_id=?", Long.class, 7L)).thenReturn(3L);
    when(db.queryForObject(startsWith("select count(*) from course_enrollment"), eq(Integer.class), eq(9L), eq(3L)))
        .thenReturn(1);
    when(db.queryForObject(startsWith("select count(*) from attendance"), eq(Integer.class), eq(9L), eq(3L)))
        .thenReturn(1);

    assertThatThrownBy(() -> controller.checkin(new CourseController.CheckinRequest("123456")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无需重复");
  }
}
