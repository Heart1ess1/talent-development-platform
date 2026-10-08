package com.talent.platform.task;

import com.talent.platform.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class TaskArchiveController {
  private final TaskArchiveService archives;
  public TaskArchiveController(TaskArchiveService archives) { this.archives = archives; }

  @PostMapping("/tasks/{taskId}/submission-archives")
  public ApiResponse<TaskArchiveService.Status> create(@PathVariable Long taskId) {
    return ApiResponse.ok(archives.create(taskId));
  }

  @GetMapping("/submission-archives/{jobId}")
  public ApiResponse<TaskArchiveService.Status> status(@PathVariable String jobId, HttpServletResponse response) {
    response.setHeader("Cache-Control", "private, no-store");
    return ApiResponse.ok(archives.get(jobId));
  }

  @GetMapping("/submission-archives/{jobId}/download")
  public void download(@PathVariable String jobId, HttpServletResponse response) throws IOException {
    archives.download(jobId, response);
  }
}
