package com.talent.platform.storage;

import com.talent.platform.security.*;import org.springframework.http.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.security.access.AccessDeniedException;import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

@RestController @RequestMapping("/api/v1/files")
public class FileController {
  private final JdbcTemplate db;private final FileStorageService storage;private final PermissionService permissions;public FileController(JdbcTemplate db,FileStorageService storage,PermissionService permissions){this.db=db;this.storage=storage;this.permissions=permissions;}
  @GetMapping("/{id}") public ResponseEntity<?> download(@PathVariable Long id){var f=db.queryForMap("select f.*,a.employee_id from stored_file f join task_submission s on s.id=f.submission_id join task_assignment a on a.id=s.assignment_id where f.id=?",id);permissions.requireEmployee(((Number)f.get("employee_id")).longValue());String name=String.valueOf(f.get("original_name"));return ResponseEntity.ok().contentType(MediaType.parseMediaType(String.valueOf(f.getOrDefault("content_type","application/octet-stream")))).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+java.net.URLEncoder.encode(name,StandardCharsets.UTF_8)).body(storage.load(String.valueOf(f.get("storage_key"))));}
}
