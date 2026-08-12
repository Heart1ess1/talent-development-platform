package com.talent.platform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "oss")
final class OssStorageConfigurationValidator {
  OssStorageConfigurationValidator(
      @Value("${app.storage.oss-private-bucket}") String privateBucket,
      @Value("${app.storage.oss-public-bucket}") String publicBucket
  ) {
    if (!StringUtils.hasText(privateBucket) || !StringUtils.hasText(publicBucket)) {
      throw new IllegalArgumentException("OSS private and public buckets must both be configured");
    }
    if (privateBucket.trim().equals(publicBucket.trim())) {
      throw new IllegalArgumentException("OSS private and public buckets must be different");
    }
  }
}
