package com.talent.platform.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.talent.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "oss")
public class OssFileStorageService implements FileStorageService {
  private final OSS oss;
  private final OSS signingOss;
  private final String bucket;

  public OssFileStorageService(
      @Value("${app.storage.oss-endpoint}") String endpoint,
      @Value("${app.storage.oss-public-endpoint}") String publicEndpoint,
      @Value("${app.storage.oss-private-bucket}") String bucket,
      @Value("${app.storage.oss-access-key}") String key,
      @Value("${app.storage.oss-secret-key}") String secret,
      @Value("${app.storage.oss-ram-role:}") String ramRole) {
    if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(publicEndpoint)
        || !StringUtils.hasText(bucket)) {
      throw new IllegalArgumentException("OSS 私有存储 Endpoint 和 Bucket 配置不完整");
    }
    if (publicEndpoint.contains("-internal.")) {
      throw new IllegalArgumentException("OSS_PUBLIC_ENDPOINT 必须是浏览器可访问的公网 Endpoint");
    }
    this.oss = buildClient(endpoint, key, secret, ramRole);
    this.signingOss = buildClient(publicEndpoint, key, secret, ramRole);
    this.bucket = bucket;
  }

  @Override
  public StoredObject store(MultipartFile file) {
    String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
    String extension = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.')) : "";
    String key = LocalDate.now() + "/" + UUID.randomUUID() + extension;
    try {
      var metadata = new ObjectMetadata();
      metadata.setContentLength(file.getSize());
      if (StringUtils.hasText(file.getContentType())) metadata.setContentType(file.getContentType());
      oss.putObject(bucket, key, file.getInputStream(), metadata);
      return new StoredObject(key, file.getSize(), file.getContentType());
    } catch (IOException | OSSException e) {
      throw new BusinessException(500, "OSS上传失败");
    }
  }

  @Override
  public Resource load(String key) {
    try {
      OSSObject object = oss.getObject(bucket, key);
      return new InputStreamResource(object.getObjectContent());
    } catch (OSSException e) {
      throw new BusinessException(404, "文件不存在");
    }
  }

  @Override
  public void delete(String key) {
    oss.deleteObject(bucket, key);
  }

  @Override
  public boolean supportsDirectTransfer() {
    return true;
  }

  @Override
  public SignedUpload prepareDirectUpload(
      String purpose,
      String originalName,
      String contentType,
      long size,
      Duration validity
  ) {
    String safePurpose = purpose == null ? "file"
        : purpose.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    String name = originalName == null ? "file" : originalName;
    String extension = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.')) : "";
    extension = extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
    String key = "private/" + safePurpose + "/" + LocalDate.now() + "/" + UUID.randomUUID() + extension;
    Instant expiresAt = Instant.now().plus(validity);
    try {
      var request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.PUT);
      request.setExpiration(Date.from(expiresAt));
      if (StringUtils.hasText(contentType)) request.setContentType(contentType);
      URI url = signingOss.generatePresignedUrl(request).toURI();
      Map<String, String> headers = StringUtils.hasText(contentType)
          ? Map.of("Content-Type", contentType)
          : Map.of();
      return new SignedUpload(key, url, "PUT", headers, expiresAt);
    } catch (Exception exception) {
      throw new BusinessException(500, "OSS上传地址生成失败");
    }
  }

  @Override
  public StoredObject verifyDirectUpload(String key, long expectedSize, String expectedContentType) {
    try {
      var metadata = oss.getObjectMetadata(bucket, key);
      long actualSize = metadata.getContentLength();
      if (actualSize != expectedSize) {
        delete(key);
        throw new BusinessException(400, "上传文件大小校验失败，请重新上传");
      }
      String actualContentType = metadata.getContentType();
      if (StringUtils.hasText(expectedContentType)
          && StringUtils.hasText(actualContentType)
          && !expectedContentType.equalsIgnoreCase(actualContentType)) {
        delete(key);
        throw new BusinessException(400, "上传文件类型校验失败，请重新上传");
      }
      return new StoredObject(key, actualSize,
          StringUtils.hasText(actualContentType) ? actualContentType : expectedContentType);
    } catch (BusinessException exception) {
      throw exception;
    } catch (OSSException exception) {
      throw new BusinessException(404, "OSS上传文件不存在或上传尚未完成");
    }
  }

  @Override
  public Optional<URI> signedDownloadUrl(
      String key,
      String originalName,
      String contentType,
      boolean inline,
      Duration validity
  ) {
    try {
      var request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
      request.setExpiration(Date.from(Instant.now().plus(validity)));
      request.setResponseHeaders(downloadResponseHeaders(originalName, inline));
      return Optional.of(signingOss.generatePresignedUrl(request).toURI());
    } catch (Exception exception) {
      throw new BusinessException(500, "OSS下载地址生成失败");
    }
  }

  static ResponseHeaderOverrides downloadResponseHeaders(String originalName, boolean inline) {
    var overrides = new ResponseHeaderOverrides();
    String encoded = URLEncoder.encode(originalName == null ? "file" : originalName, StandardCharsets.UTF_8)
        .replace("+", "%20");
    overrides.setContentDisposition((inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded);
    overrides.setCacheControl("private, no-store");
    return overrides;
  }

  private OSS buildClient(String endpoint, String key, String secret, String ramRole) {
    var builder = new OSSClientBuilder();
    return StringUtils.hasText(ramRole)
        ? builder.build(endpoint, new EcsRamRoleOssCredentialsProvider(ramRole))
        : builder.build(endpoint, key, secret);
  }
}
