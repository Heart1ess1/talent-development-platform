package com.talent.platform.course;

import com.talent.platform.common.BusinessException;
import com.talent.platform.storage.FileStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.ofdrw.converter.export.ImageExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class CourseMaterialPreviewService {
  private static final int FOOTER_HEIGHT = 56;
  private static final int MAX_PREVIEW_PAGES = 500;
  private static final Duration CACHE_RETENTION = Duration.ofDays(7);
  private static final Duration OFFICE_TIMEOUT = Duration.ofSeconds(120);
  private static final int CONVERSION_LOCK_COUNT = 128;
  private final FileStorageService storage;
  private final Object[] conversionLocks = new Object[CONVERSION_LOCK_COUNT];

  @Value("${app.preview.cache-root:${java.io.tmpdir}/talent-preview-cache}")
  private String cacheRoot = Path.of(System.getProperty("java.io.tmpdir"), "talent-preview-cache").toString();

  @Value("${app.preview.office-command:libreoffice}")
  private String officeCommand = "libreoffice";

  public CourseMaterialPreviewService(FileStorageService storage) {
    this.storage = storage;
    for (int index = 0; index < conversionLocks.length; index++) conversionLocks[index] = new Object();
  }

  public int pageCount(String storageKey, String originalName) {
    String extension = extension(originalName);
    if ("pdf".equals(extension)) return pdfPageCount(loadOriginalPdf(storageKey));
    if (isOffice(extension)) return pdfPageCount(prepareOfficePdf(storageKey, originalName));
    if ("ofd".equals(extension)) return ofdPages(storageKey, originalName).size();
    if (isImage(extension)) return 1;
    throw unsupportedFormat();
  }

  public byte[] renderPage(String storageKey, String originalName, int pageIndex, String watermark) {
    String extension = extension(originalName);
    BufferedImage page;
    if ("pdf".equals(extension)) {
      page = renderPdfPage(loadOriginalPdf(storageKey), pageIndex);
    } else if (isOffice(extension)) {
      page = renderPdfPage(prepareOfficePdf(storageKey, originalName), pageIndex);
    } else if ("ofd".equals(extension)) {
      page = readCachedImage(ofdPages(storageKey, originalName), pageIndex);
    } else if (isImage(extension)) {
      if (pageIndex != 0) throw new BusinessException(404, "课件页不存在");
      try (InputStream input = storage.load(storageKey).getInputStream()) {
        page = ImageIO.read(input);
        if (page == null) throw new BusinessException(400, "图片课件无法解析");
      } catch (IOException exception) {
        throw previewFailure(exception);
      }
    } else {
      throw unsupportedFormat();
    }

    try {
      return encodePng(addWatermark(page, watermark));
    } catch (IOException exception) {
      throw previewFailure(exception);
    }
  }

  private Path loadOriginalPdf(String storageKey) {
    Path dir = cacheDirectory(storageKey, "pdf");
    Path pdf = dir.resolve("original.pdf");
    if (isUsableFile(pdf)) return touch(pdf);
    synchronized (conversionLock(dir)) {
      if (isUsableFile(pdf)) return touch(pdf);
      try {
        Files.createDirectories(dir);
        try (InputStream input = storage.load(storageKey).getInputStream()) {
          Files.copy(input, pdf, StandardCopyOption.REPLACE_EXISTING);
        }
        return touch(pdf);
      } catch (IOException exception) {
        throw previewFailure(exception);
      }
    }
  }

  private Path prepareOfficePdf(String storageKey, String originalName) {
    String extension = extension(originalName);
    Path dir = cacheDirectory(storageKey, extension);
    Path normalized = dir.resolve("normalized.pdf");
    if (isUsableFile(normalized)) return touch(normalized);
    synchronized (conversionLock(dir)) {
      if (isUsableFile(normalized)) return touch(normalized);
      try {
        Files.createDirectories(dir);
        Path source = dir.resolve("source." + extension);
        try (InputStream input = storage.load(storageKey).getInputStream()) {
          Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
        }
        Path profile = dir.resolve("libreoffice-profile");
        Path log = dir.resolve("libreoffice.log");
        Files.createDirectories(profile);
        var builder = new ProcessBuilder(
            officeCommand,
            "-env:UserInstallation=" + profile.toUri(),
            "--headless", "--safe-mode", "--nologo", "--nodefault", "--norestore",
            "--nofirststartwizard", "--nolockcheck",
            "--convert-to", "pdf", "--outdir", dir.toString(), source.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
        String systemPath = builder.environment().getOrDefault("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        builder.environment().clear();
        builder.environment().put("PATH", systemPath);
        builder.environment().put("HOME", profile.toString());
        builder.environment().put("LANG", "C.UTF-8");
        var process = builder.start();
        boolean finished = process.waitFor(OFFICE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
          process.destroyForcibly();
          throw new IOException("office conversion timeout");
        }
        Path generated = dir.resolve("source.pdf");
        if (process.exitValue() != 0 || !isUsableFile(generated)) {
          String details = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
          throw new IOException("office conversion failed: " + details.substring(0, Math.min(details.length(), 1000)));
        }
        Files.move(generated, normalized, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(source);
        return touch(normalized);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw previewFailure(exception);
      } catch (IOException exception) {
        throw new BusinessException(500, "Office 课件转换失败，请确认文件未加密且格式完整");
      }
    }
  }

  private List<Path> ofdPages(String storageKey, String originalName) {
    Path dir = cacheDirectory(storageKey, extension(originalName));
    Path pages = dir.resolve("pages");
    Path marker = dir.resolve("complete");
    if (!Files.exists(marker)) {
      synchronized (conversionLock(dir)) {
        if (!Files.exists(marker)) exportOfd(storageKey, dir, pages, marker);
      }
    }
    touch(marker);
    try (Stream<Path> stream = Files.list(pages)) {
      List<Path> result = stream
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
          .sorted(Comparator.comparingInt(this::numericPageIndex))
          .toList();
      if (result.isEmpty() || result.size() > MAX_PREVIEW_PAGES) throw new IOException("invalid OFD page count");
      return result;
    } catch (IOException exception) {
      throw new BusinessException(500, "OFD 课件解析失败，请确认文件符合 OFD 标准");
    }
  }

  private void exportOfd(String storageKey, Path dir, Path pages, Path marker) {
    try {
      Files.createDirectories(pages);
      Path source = dir.resolve("source.ofd");
      try (InputStream input = storage.load(storageKey).getInputStream()) {
        Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
      }
      try (ImageExporter exporter = new ImageExporter(source, pages, "PNG", 5d)) {
        exporter.export();
      }
      long count;
      try (Stream<Path> stream = Files.list(pages)) {
        count = stream.filter(path -> path.getFileName().toString().endsWith(".png")).count();
      }
      if (count == 0 || count > MAX_PREVIEW_PAGES) throw new IOException("invalid OFD page count");
      Files.writeString(marker, "ok", StandardCharsets.UTF_8);
      Files.deleteIfExists(source);
    } catch (Exception exception) {
      deleteRecursively(dir);
      throw new BusinessException(500, "OFD 课件解析失败，请确认文件符合 OFD 标准");
    }
  }

  private int pdfPageCount(Path pdf) {
    try (var document = PDDocument.load(pdf.toFile())) {
      int count = document.getNumberOfPages();
      if (count <= 0 || count > MAX_PREVIEW_PAGES) {
        throw new BusinessException(400, "课件页数必须在 1 到 " + MAX_PREVIEW_PAGES + " 页之间");
      }
      return count;
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException exception) {
      throw previewFailure(exception);
    }
  }

  private BufferedImage renderPdfPage(Path pdf, int pageIndex) {
    try (var document = PDDocument.load(pdf.toFile())) {
      if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
        throw new BusinessException(404, "课件页不存在");
      }
      return new PDFRenderer(document).renderImageWithDPI(pageIndex, 120, ImageType.RGB);
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException exception) {
      throw previewFailure(exception);
    }
  }

  private BufferedImage readCachedImage(List<Path> pages, int pageIndex) {
    if (pageIndex < 0 || pageIndex >= pages.size()) throw new BusinessException(404, "课件页不存在");
    try {
      BufferedImage image = ImageIO.read(pages.get(pageIndex).toFile());
      if (image == null) throw new IOException("invalid rendered image");
      return image;
    } catch (IOException exception) {
      throw previewFailure(exception);
    }
  }

  private BufferedImage addWatermark(BufferedImage source, String watermark) {
    int width = source.getWidth();
    int height = source.getHeight();
    var output = new BufferedImage(width, height + FOOTER_HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = output.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
    graphics.drawImage(source, 0, 0, null);

    int fontSize = Math.max(16, Math.min(25, width / 55));
    Font font = new Font("Microsoft YaHei", Font.PLAIN, fontSize);
    graphics.setFont(font);
    graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.12f));
    graphics.setColor(new Color(35, 88, 150));
    graphics.rotate(-Math.PI / 7, width / 2.0, height / 2.0);
    FontMetrics metrics = graphics.getFontMetrics();
    int stepX = Math.max(340, metrics.stringWidth(watermark) + 120);
    for (int y = -height; y < height * 2; y += 230) {
      for (int x = -width; x < width * 2; x += stepX) graphics.drawString(watermark, x, y);
    }
    graphics.rotate(Math.PI / 7, width / 2.0, height / 2.0);

    graphics.setComposite(AlphaComposite.SrcOver);
    graphics.setColor(new Color(244, 248, 253));
    graphics.fillRect(0, height, width, FOOTER_HEIGHT);
    graphics.setColor(new Color(37, 87, 145));
    graphics.setFont(font.deriveFont(Font.BOLD));
    String footer = "内部培训资料 · " + watermark + " · 请勿外传";
    int textWidth = graphics.getFontMetrics().stringWidth(footer);
    graphics.drawString(footer, Math.max(16, (width - textWidth) / 2), height + 36);
    graphics.dispose();
    return output;
  }

  private byte[] encodePng(BufferedImage image) throws IOException {
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  private Path cacheDirectory(String storageKey, String type) {
    return Path.of(cacheRoot).toAbsolutePath().normalize().resolve(hash(storageKey + "|" + type));
  }

  private Object conversionLock(Path path) {
    return conversionLocks[Math.floorMod(path.toString().hashCode(), conversionLocks.length)];
  }

  private String hash(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      var result = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) result.append(String.format("%02x", current));
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private Path touch(Path path) {
    try {
      Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.now()));
    } catch (IOException ignored) {
      // Cache age is an optimization; preview remains valid if mtime cannot be updated.
    }
    return path;
  }

  private boolean isUsableFile(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0;
    } catch (IOException exception) {
      return false;
    }
  }

  private int numericPageIndex(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    try {
      return Integer.parseInt(dot < 0 ? name : name.substring(0, dot));
    } catch (NumberFormatException exception) {
      return Integer.MAX_VALUE;
    }
  }

  private boolean isImage(String extension) {
    return "png".equals(extension) || "jpg".equals(extension) || "jpeg".equals(extension);
  }

  private boolean isOffice(String extension) {
    return "doc".equals(extension) || "docx".equals(extension)
        || "ppt".equals(extension) || "pptx".equals(extension);
  }

  private String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private BusinessException unsupportedFormat() {
    return new BusinessException(400, "仅支持 Word、PDF、PPT、OFD、PNG、JPG 课件安全预览");
  }

  private BusinessException previewFailure(Exception exception) {
    return new BusinessException(500, "课件预览生成失败");
  }

  @Scheduled(cron = "0 43 3 * * *", zone = "Asia/Shanghai")
  public void cleanupExpiredCache() {
    Path root = Path.of(cacheRoot).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) return;
    Instant cutoff = Instant.now().minus(CACHE_RETENTION);
    try (Stream<Path> stream = Files.list(root)) {
      stream.filter(Files::isDirectory).forEach(path -> {
        try {
          Instant newest;
          try (Stream<Path> files = Files.walk(path)) {
            newest = files.map(candidate -> {
              try {
                return Files.getLastModifiedTime(candidate).toInstant();
              } catch (IOException ignored) {
                return Instant.EPOCH;
              }
            }).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
          }
          if (newest.isBefore(cutoff)) deleteRecursively(path);
        } catch (IOException ignored) {
          // Retry during the next scheduled cleanup.
        }
      });
    } catch (IOException ignored) {
      // A cache cleanup failure must not affect course learning.
    }
  }

  private void deleteRecursively(Path path) {
    if (!Files.exists(path)) return;
    try (Stream<Path> stream = Files.walk(path)) {
      stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
        try {
          Files.deleteIfExists(candidate);
        } catch (IOException ignored) {
          // Best-effort cache cleanup.
        }
      });
    } catch (IOException ignored) {
      // Best-effort cache cleanup.
    }
  }
}
