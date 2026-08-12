package com.talent.platform.storage;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.oss.common.auth.BasicCredentials;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import org.springframework.util.StringUtils;

/**
 * Adapts Alibaba Cloud's current Credentials SDK to the OSS SDK v1 credential interface.
 * The Credentials SDK handles STS refresh and uses hardened ECS metadata (IMDSv2).
 */
final class EcsRamRoleOssCredentialsProvider implements CredentialsProvider {
  private final Client client;

  EcsRamRoleOssCredentialsProvider(String roleName) {
    this(new Client(new Config()
        .setType("ecs_ram_role")
        .setRoleName(roleName)
        .setDisableIMDSv1(true)));
  }

  EcsRamRoleOssCredentialsProvider(Client client) {
    this.client = client;
  }

  @Override
  public void setCredentials(Credentials credentials) {
    // Credentials are managed and refreshed by the Alibaba Cloud Credentials SDK.
  }

  @Override
  public Credentials getCredentials() {
    var credential = client.getCredential();
    if (credential == null
        || !StringUtils.hasText(credential.getAccessKeyId())
        || !StringUtils.hasText(credential.getAccessKeySecret())) {
      throw new IllegalStateException("ECS RAM role did not return usable OSS credentials");
    }
    return new BasicCredentials(
        credential.getAccessKeyId(),
        credential.getAccessKeySecret(),
        credential.getSecurityToken());
  }
}
