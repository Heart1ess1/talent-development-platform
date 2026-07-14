package com.talent.platform.storage;
import org.springframework.core.io.Resource;import org.springframework.web.multipart.MultipartFile;
public interface FileStorageService {
  StoredObject store(MultipartFile file); Resource load(String key); void delete(String key);
  record StoredObject(String key,long size,String contentType){}
}

