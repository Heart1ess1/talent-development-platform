package com.talent.platform.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OssFileStorageServiceTest {
  @Test
  void signedDownloadDoesNotRequestForbiddenContentTypeOverride() {
    var headers = OssFileStorageService.downloadResponseHeaders("员工 手册.pdf", false);

    assertThat(headers.getContentType()).isNull();
    assertThat(headers.getContentDisposition())
        .isEqualTo("attachment; filename*=UTF-8''%E5%91%98%E5%B7%A5%20%E6%89%8B%E5%86%8C.pdf");
    assertThat(headers.getCacheControl()).isEqualTo("private, no-store");
  }
}
