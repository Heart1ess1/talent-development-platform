package com.talent.platform.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface FileStorageService {
  StoredObject store(MultipartFile file);

  Resource load(String key);

  void delete(String key);

  default boolean supportsDirectTransfer() {
    return false;
  }

  default SignedUpload prepareDirectUpload(
      String purpose,
      String originalName,
      String contentType,
      long size,
      Duration validity
  ) {
    throw new UnsupportedOperationException("当前存储不支持客户端直传");
  }

  default StoredObject verifyDirectUpload(
      String key,
      long expectedSize,
      String expectedContentType
  ) {
    throw new UnsupportedOperationException("当前存储不支持客户端直传");
  }

  default Optional<URI> signedDownloadUrl(
      String key,
      String originalName,
      String contentType,
      boolean inline,
      Duration validity
  ) {
    return Optional.empty();
  }

  record StoredObject(String key, long size, String contentType) {}

  record SignedUpload(
      String key,
      URI url,
      String method,
      Map<String, String> headers,
      Instant expiresAt
  ) {}
}
