package com.talent.platform.task;

import com.talent.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TaskScoringController {
  private final TaskScoringService scoring;

  public TaskScoringController(TaskScoringService scoring) {
    this.scoring = scoring;
  }

  public record ReviewerRequest(List<Long> reviewerIds) {}
  public record ScoreRequest(
      @NotNull @Pattern(regexp = "APPROVE|RETURN") String decision,
      @Min(0) @Max(100) Integer score,
      String comment
  ) {}

  @GetMapping("/task-scoring/reviewer-options")
  public ApiResponse<List<Map<String, Object>>> reviewerOptions() {
    return ApiResponse.ok(scoring.reviewerOptions());
  }

  @GetMapping("/task-scoring/tasks")
  public ApiResponse<List<Map<String, Object>>> tasks(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword
  ) {
    return ApiResponse.ok(scoring.taskList(status, keyword));
  }

  @GetMapping("/task-scoring/tasks/{taskId}")
  public ApiResponse<Map<String, Object>> task(@PathVariable Long taskId) {
    return ApiResponse.ok(scoring.taskDetail(taskId));
  }

  @PutMapping("/tasks/{taskId}/reviewers")
  public ApiResponse<Void> reviewers(@PathVariable Long taskId, @RequestBody ReviewerRequest request) {
    scoring.setReviewers(taskId, request.reviewerIds());
    return ApiResponse.ok(null);
  }

  @GetMapping("/task-scoring/submissions/{submissionId}")
  public ApiResponse<Map<String, Object>> submission(@PathVariable Long submissionId) {
    return ApiResponse.ok(scoring.submissionDetail(submissionId));
  }

  @PostMapping("/task-scoring/submissions/{submissionId}/reviews")
  public ApiResponse<Void> score(@PathVariable Long submissionId, @Valid @RequestBody ScoreRequest request) {
    scoring.submitReview(submissionId, request.decision(), request.comment(), request.score());
    return ApiResponse.ok(null);
  }

  @PostMapping("/task-scoring/submissions/{submissionId}/reset")
  public ApiResponse<Void> reset(@PathVariable Long submissionId) {
    scoring.resetReview(submissionId);
    return ApiResponse.ok(null);
  }
}
