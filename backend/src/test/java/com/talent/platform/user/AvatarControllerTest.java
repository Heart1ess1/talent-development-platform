package com.talent.platform.user;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.storage.FileStorageService;
import com.talent.platform.storage.PublicAssetStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvatarControllerTest {
  private JdbcTemplate db;
  private PublicAssetStorageService storage;
  private AvatarController controller;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    storage = mock(PublicAssetStorageService.class);
    controller = new AvatarController(db, storage, mock(AuditService.class));
    var user = new CurrentUser(7L, "user", "用户", "ADMIN", false);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validImageReplacesPreviousAvatar() throws Exception {
    var file = imageFile(240, 320);
    when(db.queryForMap(anyString(), org.mockito.ArgumentMatchers.eq(7L)))
        .thenReturn(Map.of("avatar_storage_key", "old-key", "avatar_token", "old-token"));
    when(storage.store("avatars", file))
        .thenReturn(new FileStorageService.StoredObject("new-key", file.getSize(), "image/png"));

    var result = controller.upload(file);

    assertThat(result.data().avatarToken()).isNotBlank();
    assertThat(result.data().avatarUrl()).startsWith("/api/v1/avatars/");
    verify(storage).delete("old-key");
  }

  @Test
  void rejectsSpoofedImageContent() {
    var file = new MockMultipartFile(
        "file", "avatar.png", "image/png", "not-an-image".getBytes());

    assertThatThrownBy(() -> controller.upload(file))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("有效的图片");
    verify(storage, never()).store(anyString(), org.mockito.ArgumentMatchers.eq(file));
  }

  private MockMultipartFile imageFile(int width, int height) throws Exception {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return new MockMultipartFile("file", "avatar.png", "image/png", output.toByteArray());
  }
}
