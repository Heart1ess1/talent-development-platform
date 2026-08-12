package com.talent.platform.course;

import com.talent.platform.storage.FileStorageService;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.element.Paragraph;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseMaterialPreviewServiceTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void rendersImageAsWatermarkedPngWithFooter() throws Exception {
    var source = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
    var graphics = source.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 640, 360);
    graphics.dispose();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(source, "png", bytes);

    FileStorageService storage = mock(FileStorageService.class);
    when(storage.load("materials/sample.png")).thenReturn(new ByteArrayResource(bytes.toByteArray()));
    var service = new CourseMaterialPreviewService(storage);

    byte[] rendered = service.renderPage("materials/sample.png", "sample.png", 0, "张三（E001）");
    var image = ImageIO.read(new ByteArrayInputStream(rendered));

    assertThat(service.pageCount("materials/sample.png", "sample.png")).isEqualTo(1);
    assertThat(image.getWidth()).isEqualTo(640);
    assertThat(image.getHeight()).isEqualTo(416);
    assertThat(rendered).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
  }

  @Test
  void rendersRealOfdAsWatermarkedPng() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var document = new OFDDoc(bytes)) {
      document.add(new Paragraph("Talent platform OFD preview"));
    }

    FileStorageService storage = mock(FileStorageService.class);
    when(storage.load("materials/sample.ofd")).thenReturn(new ByteArrayResource(bytes.toByteArray()));
    var service = new CourseMaterialPreviewService(storage);
    ReflectionTestUtils.setField(service, "cacheRoot", temporaryDirectory.toString());

    assertThat(service.pageCount("materials/sample.ofd", "sample.ofd")).isEqualTo(1);
    byte[] rendered = service.renderPage("materials/sample.ofd", "sample.ofd", 0, "李四（E002）");
    var image = ImageIO.read(new ByteArrayInputStream(rendered));

    assertThat(image).isNotNull();
    assertThat(image.getWidth()).isGreaterThan(500);
    assertThat(image.getHeight()).isGreaterThan(500);
    assertThat(rendered).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
  }
}
