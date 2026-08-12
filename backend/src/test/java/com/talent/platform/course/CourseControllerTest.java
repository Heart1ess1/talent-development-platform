package com.talent.platform.course;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.storage.FileStorageService;
import com.talent.platform.storage.UploadTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        storage,
        mock(UploadTicketService.class)
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
        .hasMessageContaining("仅支持 Word、PDF");
    verify(storage, never()).store(file);
  }

  @Test
  void refusesFakePdfBeforeStorage() {
    when(db.queryForList(startsWith("select id from course"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L)));
    var file = new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a pdf".getBytes());

    assertThatThrownBy(() -> controller.uploadMaterial(1L, file))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文件已损坏");
    verify(storage, never()).store(file);
  }

  @Test
  void acceptsStructurallyValidDocxPptxAndOfdMaterials() throws IOException {
    when(db.queryForList(startsWith("select id from course"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L)));
    when(storage.store(any(MultipartFile.class))).thenAnswer(invocation -> {
      MultipartFile file = invocation.getArgument(0);
      return new FileStorageService.StoredObject("materials/" + file.getOriginalFilename(),
          file.getSize(), file.getContentType());
    });
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(42L);

    controller.uploadMaterial(1L, material("guide.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "[Content_Types].xml", "word/document.xml"));
    controller.uploadMaterial(1L, material("slides.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "[Content_Types].xml", "ppt/presentation.xml"));
    controller.uploadMaterial(1L, material("notice.ofd", "application/ofd", "OFD.xml"));

    verify(storage, times(3)).store(any(MultipartFile.class));
  }

  @Test
  void refusesRenamedArchiveThatDoesNotMatchMaterialFormat() throws IOException {
    when(db.queryForList(startsWith("select id from course"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L)));
    var file = material("fake.docx", "application/octet-stream", "OFD.xml");

    assertThatThrownBy(() -> controller.uploadMaterial(1L, file))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文件已损坏");
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

  private MockMultipartFile material(String name, String contentType, String... entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var archive = new ZipOutputStream(bytes)) {
      for (String entry : entries) {
        archive.putNextEntry(new ZipEntry(entry));
        archive.write("test".getBytes());
        archive.closeEntry();
      }
    }
    return new MockMultipartFile("file", name, contentType, bytes.toByteArray());
  }
}
