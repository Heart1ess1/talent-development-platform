package com.talent.platform.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
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
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "oss")
public class OssPublicAssetStorageService implements PublicAssetStorageService {
  private final OSS oss;
  private final String bucket;
  private final String cdnBaseUrl;

  public OssPublicAssetStorageService(
      @Value("${app.storage.oss-endpoint}") String endpoint,
      @Value("${app.storage.oss-public-bucket}") String bucket,
      @Value("${app.storage.oss-access-key}") String key,
      @Value("${app.storage.oss-secret-key}") String secret,
      @Value("${app.storage.oss-ram-role:}") String ramRole,
      @Value("${app.storage.cdn-base-url:}") String cdnBaseUrl
  ) {
    if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)) {
      throw new IllegalArgumentException("OSS 公共资源 Endpoint 和 Bucket 配置不完整");
    }
    var builder = new OSSClientBuilder();
    this.oss = StringUtils.hasText(ramRole)
        ? builder.build(endpoint, new EcsRamRoleOssCredentialsProvider(ramRole))
        : builder.build(endpoint, key, secret);
    this.bucket = bucket;
    this.cdnBaseUrl = trimTrailingSlash(cdnBaseUrl);
  }

  @Override
  public FileStorageService.StoredObject store(String category, MultipartFile file) {
    String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
    String extension = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.')) : "";
    extension = extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
    String safeCategory = category == null ? "asset"
        : category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    String objectKey = safeCategory + "/" + LocalDate.now() + "/" + UUID.randomUUID() + extension;
    try {
      var metadata = new ObjectMetadata();
      metadata.setContentLength(file.getSize());
      if (StringUtils.hasText(file.getContentType())) metadata.setContentType(file.getContentType());
      metadata.setCacheControl("public, max-age=31536000, immutable");
      oss.putObject(bucket, objectKey, file.getInputStream(), metadata);
      return new FileStorageService.StoredObject(objectKey, file.getSize(), file.getContentType());
    } catch (IOException | OSSException exception) {
      throw new BusinessException(500, "公共图片上传失败");
    }
  }

  @Override
  public Resource load(String key) {
    try {
      OSSObject object = oss.getObject(bucket, key);
      return new InputStreamResource(object.getObjectContent());
    } catch (OSSException exception) {
      throw new BusinessException(404, "公共图片不存在");
    }
  }

  @Override
  public void delete(String key) {
    oss.deleteObject(bucket, key);
  }

  @Override
  public Optional<URI> publicUrl(String key) {
    if (!StringUtils.hasText(cdnBaseUrl)) return Optional.empty();
    String encodedPath = java.util.Arrays.stream(key.split("/"))
        .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
        .reduce((left, right) -> left + "/" + right)
        .orElse("");
    return Optional.of(URI.create(cdnBaseUrl + "/" + encodedPath));
  }

  private String trimTrailingSlash(String value) {
    if (value == null) return "";
    return value.replaceAll("/+$", "");
  }
}
