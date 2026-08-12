package com.talent.platform.storage;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UploadTicketService {
  private static final Duration UPLOAD_VALIDITY = Duration.ofMinutes(15);

  private final JdbcTemplate db;
  private final FileStorageService storage;

  public UploadTicketService(JdbcTemplate db, FileStorageService storage) {
    this.db = db;
    this.storage = storage;
  }

  public boolean directTransferEnabled() {
    return storage.supportsDirectTransfer();
  }

  @Transactional
  public UploadTicket issue(
      String purpose,
      Long ownerId,
      String originalName,
      String contentType,
      long size
  ) {
    if (!storage.supportsDirectTransfer()) {
      throw new BusinessException(409, "当前环境未启用 OSS 客户端直传");
    }
    if (ownerId == null || ownerId <= 0) throw new BusinessException(400, "上传归属无效");
    if (originalName == null || originalName.isBlank() || originalName.length() > 255) {
      throw new BusinessException(400, "文件名无效或过长");
    }
    if (size <= 0) throw new BusinessException(400, "不能上传空文件");

    var signed = storage.prepareDirectUpload(
        purpose,
        originalName,
        contentType,
        size,
        UPLOAD_VALIDITY);
    UUID ticketId = UUID.randomUUID();
    db.update("""
        insert into object_upload_ticket(
          id,purpose,owner_id,object_key,original_name,content_type,expected_size,
          created_by,expires_at
        ) values(?,?,?,?,?,?,?,?,?)
        """,
        ticketId.toString(), purpose, ownerId, signed.key(), originalName,
        normalizeContentType(contentType), size, SecurityUtils.current().id(),
        Timestamp.from(signed.expiresAt()));
    return new UploadTicket(
        ticketId,
        signed.url().toString(),
        signed.method(),
        signed.headers(),
        signed.expiresAt());
  }

  @Transactional
  public CompletedUpload consume(UUID ticketId, String expectedPurpose, Long expectedOwnerId) {
    var rows = db.queryForList("select * from object_upload_ticket where id=? for update", ticketId.toString());
    if (rows.isEmpty()) throw new BusinessException(404, "上传票据不存在");
    var row = rows.get(0);
    if (!expectedPurpose.equals(String.valueOf(row.get("purpose")))
        || expectedOwnerId.longValue() != ((Number) row.get("owner_id")).longValue()) {
      throw new BusinessException(400, "上传票据与目标资源不匹配");
    }
    if (((Number) row.get("created_by")).longValue() != SecurityUtils.current().id()) {
      throw new BusinessException(403, "不能使用其他用户创建的上传票据");
    }
    if (row.get("consumed_at") != null) throw new BusinessException(409, "上传票据已被使用");
    if (isExpired(row.get("expires_at"))) {
      throw new BusinessException(410, "上传票据已过期，请重新上传");
    }

    String key = String.valueOf(row.get("object_key"));
    long size = ((Number) row.get("expected_size")).longValue();
    String contentType = normalizeContentType((String) row.get("content_type"));
    var stored = storage.verifyDirectUpload(key, size, contentType);
    db.update("update object_upload_ticket set consumed_at=now() where id=? and consumed_at is null",
        ticketId.toString());
    return new CompletedUpload(
        ticketId,
        String.valueOf(row.get("original_name")),
        stored.contentType(),
        stored.size(),
        stored.key());
  }

  @Transactional
  public void abandon(UUID ticketId) {
    var rows = db.queryForList("""
        select object_key from object_upload_ticket
        where id=? and created_by=? and consumed_at is null
        for update
        """, ticketId.toString(), SecurityUtils.current().id());
    if (rows.isEmpty()) return;
    String key = String.valueOf(rows.get(0).get("object_key"));
    db.update("delete from object_upload_ticket where id=?", ticketId.toString());
    try {
      storage.delete(key);
    } catch (RuntimeException ignored) {
      // The ticket is invalidated even if OSS cleanup must be retried asynchronously.
    }
  }

  @Scheduled(cron = "0 17 * * * *", zone = "Asia/Shanghai")
  @Transactional
  public void cleanupExpiredTickets() {
    var expired = db.queryForList("""
        select id,object_key from object_upload_ticket
        where consumed_at is null and expires_at<now()
        order by expires_at
        limit 100
        """);
    for (var row : expired) {
      try {
        storage.delete(String.valueOf(row.get("object_key")));
      } catch (RuntimeException ignored) {
        continue;
      }
      db.update("delete from object_upload_ticket where id=? and consumed_at is null",
          String.valueOf(row.get("id")));
    }
    db.update("delete from object_upload_ticket where consumed_at<date_sub(now(),interval 7 day)");
  }

  private String normalizeContentType(String contentType) {
    return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
  }

  private boolean isExpired(Object value) {
    if (value instanceof LocalDateTime expiresAt) return LocalDateTime.now().isAfter(expiresAt);
    if (value instanceof Instant expiresAt) return Instant.now().isAfter(expiresAt);
    if (value instanceof Timestamp expiresAt) return Instant.now().isAfter(expiresAt.toInstant());
    if (value instanceof java.util.Date expiresAt) return Instant.now().isAfter(expiresAt.toInstant());
    throw new BusinessException(500, "上传票据过期时间数据异常");
  }

  public record UploadTicket(
      UUID ticketId,
      String uploadUrl,
      String method,
      Map<String, String> headers,
      Instant expiresAt
  ) {
    public UploadTicket {
      headers = Map.copyOf(new LinkedHashMap<>(headers));
    }
  }

  public record CompletedUpload(
      UUID ticketId,
      String originalName,
      String contentType,
      long size,
      String storageKey
  ) {}
}
