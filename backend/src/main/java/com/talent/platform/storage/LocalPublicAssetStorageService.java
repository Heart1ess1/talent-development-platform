package com.talent.platform.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalPublicAssetStorageService implements PublicAssetStorageService {
  private final FileStorageService storage;

  public LocalPublicAssetStorageService(FileStorageService storage) {
    this.storage = storage;
  }

  @Override
  public FileStorageService.StoredObject store(String category, MultipartFile file) {
    return storage.store(file);
  }

  @Override
  public Resource load(String key) {
    return storage.load(key);
  }

  @Override
  public void delete(String key) {
    storage.delete(key);
  }

  @Override
  public Optional<URI> publicUrl(String key) {
    return Optional.empty();
  }
}
