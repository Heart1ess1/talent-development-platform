package com.talent.platform.storage;

import com.talent.platform.common.BusinessException;import org.springframework.beans.factory.annotation.Value;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.core.io.*;import org.springframework.stereotype.Service;import org.springframework.web.multipart.MultipartFile;
import java.io.*;import java.nio.file.*;import java.time.LocalDate;import java.util.UUID;

@Service @ConditionalOnProperty(name="app.storage.type",havingValue="local",matchIfMissing=true)
public class LocalFileStorageService implements FileStorageService {
  private final Path root;public LocalFileStorageService(@Value("${app.storage.local-root}")String root)throws IOException{this.root=Paths.get(root).toAbsolutePath().normalize();Files.createDirectories(this.root);}
  public StoredObject store(MultipartFile f){String name=f.getOriginalFilename()==null?"file":f.getOriginalFilename();String ext=name.lastIndexOf('.')>=0?name.substring(name.lastIndexOf('.')):"";String key=LocalDate.now()+"/"+UUID.randomUUID()+ext;Path target=root.resolve(key).normalize();if(!target.startsWith(root))throw new BusinessException(400,"非法文件路径");try{Files.createDirectories(target.getParent());f.transferTo(target);return new StoredObject(key,f.getSize(),f.getContentType());}catch(IOException e){throw new BusinessException(500,"文件保存失败");}}
  public Resource load(String key){Path p=root.resolve(key).normalize();if(!p.startsWith(root))throw new BusinessException(400,"非法文件路径");Resource r=new FileSystemResource(p);if(!r.exists())throw new BusinessException(404,"文件不存在");return r;}
  public void delete(String key){try{Files.deleteIfExists(root.resolve(key).normalize());}catch(IOException e){throw new BusinessException(500,"文件删除失败");}}
}

