package com.talent.platform.storage;

import com.aliyun.oss.*;import com.aliyun.oss.model.OSSObject;import com.talent.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.core.io.*;import org.springframework.stereotype.Service;import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;import java.time.LocalDate;import java.util.UUID;

@Service @ConditionalOnProperty(name="app.storage.type",havingValue="oss")
public class OssFileStorageService implements FileStorageService {
  private final OSS oss;private final String bucket;
  public OssFileStorageService(@Value("${app.storage.oss-endpoint}")String endpoint,@Value("${app.storage.oss-bucket}")String bucket,@Value("${app.storage.oss-access-key}")String key,@Value("${app.storage.oss-secret-key}")String secret){this.oss=new OSSClientBuilder().build(endpoint,key,secret);this.bucket=bucket;}
  public StoredObject store(MultipartFile f){String n=f.getOriginalFilename()==null?"file":f.getOriginalFilename();String ext=n.lastIndexOf('.')>=0?n.substring(n.lastIndexOf('.')):"";String k=LocalDate.now()+"/"+UUID.randomUUID()+ext;try{oss.putObject(bucket,k,f.getInputStream());return new StoredObject(k,f.getSize(),f.getContentType());}catch(IOException|OSSException e){throw new BusinessException(500,"OSS上传失败");}}
  public Resource load(String key){try{OSSObject o=oss.getObject(bucket,key);return new InputStreamResource(o.getObjectContent());}catch(OSSException e){throw new BusinessException(404,"文件不存在");}}
  public void delete(String key){oss.deleteObject(bucket,key);}
}

