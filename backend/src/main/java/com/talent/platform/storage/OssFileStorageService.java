package com.talent.platform.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PolicyConditions;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.talent.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
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
  private final CredentialsProvider credentialsProvider;
  private final String publicEndpoint;

  @Autowired
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
    this.credentialsProvider = credentialsProvider(key, secret, ramRole);
    var builder = new OSSClientBuilder();
    this.oss = builder.build(endpoint, credentialsProvider);
    this.signingOss = builder.build(publicEndpoint, credentialsProvider);
    this.bucket = bucket;
    this.publicEndpoint = publicEndpoint;
  }

  OssFileStorageService(OSS oss, OSS signingOss, String bucket) {
    this(oss, signingOss, bucket, null, "https://oss.invalid");
  }

  OssFileStorageService(OSS oss, OSS signingOss, String bucket,
                        CredentialsProvider credentialsProvider, String publicEndpoint) {
    this.oss = oss;
    this.signingOss = signingOss;
    this.bucket = bucket;
    this.credentialsProvider = credentialsProvider;
    this.publicEndpoint = publicEndpoint;
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
    String key = "staging/" + safePurpose + "/" + LocalDate.now() + "/" + UUID.randomUUID() + extension;
    Instant expiresAt = Instant.now().plus(validity);
    try {
      Credentials credentials = credentialsProvider.getCredentials();
      var conditions = new PolicyConditions();
      conditions.addConditionItem(PolicyConditions.COND_KEY, key);
      conditions.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, size, size);
      conditions.addConditionItem(PolicyConditions.COND_SUCCESS_ACTION_STATUS, "200");
      conditions.addConditionItem("x-oss-forbid-overwrite", "true");
      if (StringUtils.hasText(contentType)) {
        conditions.addConditionItem(PolicyConditions.COND_CONTENT_TYPE, contentType);
      }
      if (StringUtils.hasText(credentials.getSecurityToken())) {
        conditions.addConditionItem("x-oss-security-token", credentials.getSecurityToken());
      }
      String policy = signingOss.generatePostPolicy(Date.from(expiresAt), conditions);
      var fields = new LinkedHashMap<String, String>();
      fields.put("key", key);
      fields.put("policy", policy);
      fields.put("OSSAccessKeyId", credentials.getAccessKeyId());
      fields.put("Signature", signingOss.calculatePostSignature(policy));
      fields.put("success_action_status", "200");
      fields.put("x-oss-forbid-overwrite", "true");
      if (StringUtils.hasText(contentType)) fields.put("Content-Type", contentType);
      if (StringUtils.hasText(credentials.getSecurityToken())) {
        fields.put("x-oss-security-token", credentials.getSecurityToken());
      }
      return new SignedUpload(key, formUploadEndpoint(), "POST", Map.of(), fields, expiresAt);
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
      String committedKey = committedKey(key);
      oss.copyObject(bucket, key, bucket, committedKey);
      try {
        delete(key);
      } catch (RuntimeException ignored) {
        // The signed URL can only recreate the staging object; scheduled cleanup removes it after expiry.
      }
      return new StoredObject(committedKey, actualSize,
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

  private String committedKey(String stagingKey) {
    int dot = stagingKey == null ? -1 : stagingKey.lastIndexOf('.');
    String extension = dot < 0 ? "" : stagingKey.substring(dot).toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9.]", "");
    return "private/committed/" + LocalDate.now() + "/" + UUID.randomUUID() + extension;
  }

  private URI formUploadEndpoint() {
    URI endpoint = URI.create(publicEndpoint);
    String host = endpoint.getHost();
    if (host == null || host.isBlank()) throw new IllegalArgumentException("Invalid OSS public endpoint");
    String bucketHost = host.startsWith(bucket + ".") ? host : bucket + "." + host;
    return URI.create(endpoint.getScheme() + "://" + bucketHost + "/");
  }

  private static CredentialsProvider credentialsProvider(String key, String secret, String ramRole) {
    return StringUtils.hasText(ramRole)
        ? new EcsRamRoleOssCredentialsProvider(ramRole)
        : new DefaultCredentialProvider(key, secret);
  }
}
