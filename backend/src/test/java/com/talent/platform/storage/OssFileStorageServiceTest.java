package com.talent.platform.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PolicyConditions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class OssFileStorageServiceTest {
  @Test
  void rejectsSharedPrivateAndPublicBucket() {
    assertThatThrownBy(() -> new OssStorageConfigurationValidator("same-bucket", "same-bucket"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be different");
  }

  @Test
  void postPolicyBindsExactUploadSizeAndForbidsOverwrite() {
    OSS signing = mock(OSS.class);
    CredentialsProvider provider = mock(CredentialsProvider.class);
    Credentials credentials = mock(Credentials.class);
    when(provider.getCredentials()).thenReturn(credentials);
    when(credentials.getAccessKeyId()).thenReturn("temporary-ak");
    when(credentials.getSecurityToken()).thenReturn("sts-token");
    when(signing.generatePostPolicy(any(Date.class), any(PolicyConditions.class)))
        .thenReturn("encoded-policy");
    when(signing.calculatePostSignature("encoded-policy")).thenReturn("signature");
    var storage = new OssFileStorageService(mock(OSS.class), signing, "private-bucket",
        provider, "https://oss-cn-shanghai.aliyuncs.com");

    var upload = storage.prepareDirectUpload(
        "course-material", "handbook.pdf", "application/pdf", 2048L, Duration.ofMinutes(15));

    assertThat(upload.method()).isEqualTo("POST");
    assertThat(upload.url()).hasToString("https://private-bucket.oss-cn-shanghai.aliyuncs.com/");
    assertThat(upload.formFields()).containsEntry("key", upload.key())
        .containsEntry("x-oss-forbid-overwrite", "true")
        .containsEntry("x-oss-security-token", "sts-token");
    var policy = ArgumentCaptor.forClass(PolicyConditions.class);
    verify(signing).generatePostPolicy(any(Date.class), policy.capture());
    assertThat(policy.getValue().jsonize())
        .contains("content-length-range", "2048", "x-oss-forbid-overwrite", "true");
  }

  @Test
  void signedDownloadDoesNotRequestForbiddenContentTypeOverride() {
    var headers = OssFileStorageService.downloadResponseHeaders("员工 手册.pdf", false);

    assertThat(headers.getContentType()).isNull();
    assertThat(headers.getContentDisposition())
        .isEqualTo("attachment; filename*=UTF-8''%E5%91%98%E5%B7%A5%20%E6%89%8B%E5%86%8C.pdf");
    assertThat(headers.getCacheControl()).isEqualTo("private, no-store");
  }

  @Test
  void commitsVerifiedUploadToKeyThatSignedPostCannotOverwrite() {
    OSS oss = mock(OSS.class);
    var metadata = new ObjectMetadata();
    metadata.setContentLength(2048L);
    metadata.setContentType("application/pdf");
    String stagingKey = "staging/course-material/2026-08-12/upload.pdf";
    when(oss.getObjectMetadata("private-bucket", stagingKey)).thenReturn(metadata);
    var storage = new OssFileStorageService(oss, mock(OSS.class), "private-bucket");

    var committed = storage.verifyDirectUpload(stagingKey, 2048L, "application/pdf");

    assertThat(committed.key()).startsWith("private/committed/").endsWith(".pdf");
    assertThat(committed.key()).isNotEqualTo(stagingKey);
    verify(oss).copyObject(eq("private-bucket"), eq(stagingKey),
        eq("private-bucket"), eq(committed.key()));
    verify(oss).deleteObject("private-bucket", stagingKey);
  }
}
