package com.talent.platform.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Optional;

public interface PublicAssetStorageService {
  FileStorageService.StoredObject store(String category, MultipartFile file);

  Resource load(String key);

  void delete(String key);

  Optional<URI> publicUrl(String key);
}
