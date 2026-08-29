package com.talent.platform.storage;

import com.talent.platform.task.TaskScoringService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
  private final JdbcTemplate db;
  private final FileStorageService storage;
  private final TaskScoringService scoring;

  public FileController(JdbcTemplate db, FileStorageService storage, TaskScoringService scoring) {
    this.db = db;
    this.storage = storage;
    this.scoring = scoring;
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> download(@PathVariable Long id) {
    var file = db.queryForMap("""
        select f.*,a.employee_id,s.id submission_id
        from stored_file f
        join task_submission s on s.id=f.submission_id
        join task_assignment a on a.id=s.assignment_id
        where f.id=?
        """, id);
    scoring.requireSubmissionRead(((Number) file.get("submission_id")).longValue());
    String name = String.valueOf(file.get("original_name"));
    Object rawContentType = file.get("content_type");
    String contentType = rawContentType == null
        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
        : String.valueOf(rawContentType);
    String key = String.valueOf(file.get("storage_key"));
    var signedUrl = storage.signedDownloadUrl(
        key, name, contentType, false, Duration.ofMinutes(5));
    if (signedUrl.isPresent()) {
      return ResponseEntity.status(302)
          .location(signedUrl.get())
          .cacheControl(CacheControl.noStore())
          .build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contentType))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''"
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
        .body(storage.load(key));
  }
}
