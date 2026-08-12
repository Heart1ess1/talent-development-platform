package com.talent.platform.storage;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadTicketServiceTest {
  private JdbcTemplate db;
  private FileStorageService storage;
  private UploadTicketService service;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    storage = mock(FileStorageService.class);
    service = new UploadTicketService(db, storage);
    var user = new CurrentUser(7L, "employee", "员工", "EMPLOYEE", false);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void issuesShortLivedOssPutTicket() {
    Instant expiresAt = Instant.now().plusSeconds(600);
    when(storage.supportsDirectTransfer()).thenReturn(true);
    when(storage.prepareDirectUpload(
        eq("submission-file"), eq("result.pdf"), eq("application/pdf"), eq(1024L), any()))
        .thenReturn(new FileStorageService.SignedUpload(
            "private/submission/file.pdf",
            URI.create("https://example.oss/upload?signature=hidden"),
            "PUT",
            Map.of("Content-Type", "application/pdf"),
            expiresAt));

    var ticket = service.issue("submission-file", 12L, "result.pdf", "application/pdf", 1024L);

    assertThat(ticket.method()).isEqualTo("PUT");
    assertThat(ticket.expiresAt()).isEqualTo(expiresAt);
    assertThat(ticket.headers()).containsEntry("Content-Type", "application/pdf");
    verify(db).update(anyString(),
        anyString(), eq("submission-file"), eq(12L), eq("private/submission/file.pdf"),
        eq("result.pdf"), eq("application/pdf"), eq(1024L), eq(7L), any(Timestamp.class));
  }

  @Test
  void verifiesObjectBeforeConsumingTicket() {
    UUID ticketId = UUID.randomUUID();
    when(db.queryForList(anyString(), eq(ticketId.toString()))).thenReturn(List.of(Map.of(
        "purpose", "task-attachment",
        "owner_id", 30L,
        "created_by", 7L,
        "object_key", "private/task/a.pdf",
        "original_name", "a.pdf",
        "content_type", "application/pdf",
        "expected_size", 2048L,
        "expires_at", Timestamp.from(Instant.now().plusSeconds(600))
    )));
    when(storage.verifyDirectUpload("private/task/a.pdf", 2048L, "application/pdf"))
        .thenReturn(new FileStorageService.StoredObject(
            "private/task/a.pdf", 2048L, "application/pdf"));

    var upload = service.consume(ticketId, "task-attachment", 30L);

    assertThat(upload.storageKey()).isEqualTo("private/task/a.pdf");
    verify(db).update(anyString(), eq(ticketId.toString()));
  }

  @Test
  void consumesTicketWhenMysqlReturnsExpiresAtAsLocalDateTime() {
    UUID ticketId = UUID.randomUUID();
    when(db.queryForList(anyString(), eq(ticketId.toString()))).thenReturn(List.of(Map.of(
        "purpose", "course-material",
        "owner_id", 12L,
        "created_by", 7L,
        "object_key", "private/course-material/test.png",
        "original_name", "test.png",
        "content_type", "image/png",
        "expected_size", 42L,
        "expires_at", LocalDateTime.now().plusMinutes(10)
    )));
    when(storage.verifyDirectUpload("private/course-material/test.png", 42L, "image/png"))
        .thenReturn(new FileStorageService.StoredObject(
            "private/course-material/test.png", 42L, "image/png"));

    var upload = service.consume(ticketId, "course-material", 12L);

    assertThat(upload.storageKey()).isEqualTo("private/course-material/test.png");
    verify(db).update(anyString(), eq(ticketId.toString()));
  }

  @Test
  void rejectsTicketCreatedByAnotherUser() {
    UUID ticketId = UUID.randomUUID();
    when(db.queryForList(anyString(), eq(ticketId.toString()))).thenReturn(List.of(Map.of(
        "purpose", "task-attachment",
        "owner_id", 30L,
        "created_by", 99L,
        "object_key", "private/task/a.pdf",
        "original_name", "a.pdf",
        "content_type", "application/pdf",
        "expected_size", 2048L,
        "expires_at", Timestamp.from(Instant.now().plusSeconds(600))
    )));

    assertThatThrownBy(() -> service.consume(ticketId, "task-attachment", 30L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("其他用户");
    verify(storage, never()).verifyDirectUpload(anyString(), anyLong(), anyString());
  }
}
