package com.talent.platform.storage;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.CredentialModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsRamRoleOssCredentialsProviderTest {
  @Test
  void adaptsRotatingStsCredentialsForOssSdk() {
    var client = mock(Client.class);
    when(client.getCredential()).thenReturn(CredentialModel.builder()
        .accessKeyId("STS.example")
        .accessKeySecret("temporary-secret")
        .securityToken("security-token")
        .build());

    var credential = new EcsRamRoleOssCredentialsProvider(client).getCredentials();

    assertEquals("STS.example", credential.getAccessKeyId());
    assertEquals("temporary-secret", credential.getSecretAccessKey());
    assertEquals("security-token", credential.getSecurityToken());
  }
}
