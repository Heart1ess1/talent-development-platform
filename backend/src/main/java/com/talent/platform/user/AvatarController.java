package com.talent.platform.user;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.SecurityUtils;
import com.talent.platform.storage.PublicAssetStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class AvatarController {
  private static final long MAX_SIZE = 5 * 1024 * 1024;
  private static final Set<String> CONTENT_TYPES = Set.of(MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE);

  private final JdbcTemplate db;
  private final PublicAssetStorageService storage;
  private final AuditService audit;

  public AvatarController(JdbcTemplate db, PublicAssetStorageService storage, AuditService audit) {
    this.db = db;
    this.storage = storage;
    this.audit = audit;
  }

  public record AvatarResponse(String avatarToken, String avatarUrl) {}

  @PostMapping("/api/v1/profile/avatar")
  public ApiResponse<AvatarResponse> upload(@RequestParam MultipartFile file) {
    var contentType = validate(file);
    var user = SecurityUtils.current();
    var before = db.queryForMap(
        "select avatar_storage_key,avatar_token from sys_user where id=?",
        user.id());
    var stored = storage.store("avatars", file);
    var token = UUID.randomUUID().toString();
    try {
      db.update("""
          update sys_user
          set avatar_storage_key=?,avatar_content_type=?,avatar_size=?,avatar_token=?,
              avatar_updated_at=now(),version=version+1
          where id=?
          """,
          stored.key(), contentType, stored.size(), token, user.id());
    } catch (RuntimeException exception) {
      storage.delete(stored.key());
      throw exception;
    }
    deletePrevious(before.get("avatar_storage_key"), stored.key());
    audit.log(
        "UPDATE_OWN_AVATAR",
        "USER",
        user.id(),
        Map.of("hadAvatar", before.get("avatar_token") != null),
        Map.of("avatarToken", token));
    return ApiResponse.ok(new AvatarResponse(token, "/api/v1/avatars/" + token));
  }

  @DeleteMapping("/api/v1/profile/avatar")
  public ApiResponse<Void> delete() {
    var user = SecurityUtils.current();
    var before = db.queryForMap(
        "select avatar_storage_key,avatar_token from sys_user where id=?",
        user.id());
    db.update("""
        update sys_user
        set avatar_storage_key=null,avatar_content_type=null,avatar_size=null,avatar_token=null,
            avatar_updated_at=now(),version=version+1
        where id=?
        """,
        user.id());
    deletePrevious(before.get("avatar_storage_key"), null);
    audit.log("DELETE_OWN_AVATAR", "USER", user.id(), Map.of("hadAvatar", before.get("avatar_token") != null), null);
    return ApiResponse.ok(null);
  }

  @GetMapping("/api/v1/avatars/{token}")
  public ResponseEntity<?> image(@PathVariable UUID token) {
    var rows = db.queryForList(
        "select avatar_storage_key,avatar_content_type from sys_user where avatar_token=?",
        token.toString());
    if (rows.isEmpty()) throw new BusinessException(404, "头像不存在");
    var avatar = rows.get(0);
    var contentType = String.valueOf(avatar.get("avatar_content_type"));
    String storageKey = String.valueOf(avatar.get("avatar_storage_key"));
    var publicUrl = storage.publicUrl(storageKey);
    if (publicUrl.isPresent()) {
      return ResponseEntity.status(302)
          .location(publicUrl.get())
          .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
          .build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contentType))
        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .header("X-Content-Type-Options", "nosniff")
        .body(storage.load(storageKey));
  }

  private String validate(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择头像图片");
    if (file.getSize() > MAX_SIZE) throw new BusinessException(400, "头像图片不能超过 5MB");
    if (!CONTENT_TYPES.contains(normalizedContentType(file))) {
      throw new BusinessException(400, "头像仅支持 JPG 或 PNG 格式");
    }
    try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
      if (input == null) throw new BusinessException(400, "文件不是有效的图片");
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new BusinessException(400, "文件不是有效的图片");
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        var format = reader.getFormatName().toLowerCase();
        var actualContentType = "png".equals(format) ? MediaType.IMAGE_PNG_VALUE
            : ("jpeg".equals(format) || "jpg".equals(format)) ? MediaType.IMAGE_JPEG_VALUE
            : null;
        if (actualContentType == null) throw new BusinessException(400, "头像仅支持 JPG 或 PNG 格式");
        var width = reader.getWidth(0);
        var height = reader.getHeight(0);
        if (width < 120 || height < 120) {
          throw new BusinessException(400, "图片尺寸不能小于 120×120 像素");
        }
        if (width > 8000 || height > 8000) {
          throw new BusinessException(400, "图片尺寸过大");
        }
        return actualContentType;
      } finally {
        reader.dispose();
      }
    } catch (IOException exception) {
      throw new BusinessException(400, "图片读取失败");
    }
  }

  private String normalizedContentType(MultipartFile file) {
    return file.getContentType() == null ? "" : file.getContentType().toLowerCase();
  }

  private void deletePrevious(Object previousKey, String currentKey) {
    if (previousKey == null) return;
    var key = String.valueOf(previousKey);
    if (key.isBlank() || key.equals(currentKey)) return;
    try {
      storage.delete(key);
    } catch (RuntimeException ignored) {
      // The database already points to the current avatar. An orphaned old object can be cleaned up later.
    }
  }
}
