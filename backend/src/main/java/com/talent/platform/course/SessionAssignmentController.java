package com.talent.platform.course;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.PageResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sessions/{id}")
public class SessionAssignmentController {
  private final SessionAssignmentService service;
  public SessionAssignmentController(SessionAssignmentService service) {this.service=service;}

  @GetMapping("/assignment-state")
  public ApiResponse<SessionAssignmentService.State> state(@PathVariable Long id) {
    return ApiResponse.ok(service.state(id));
  }

  @GetMapping("/enrollment-candidates")
  public ApiResponse<PageResult<Map<String,Object>>> candidates(@PathVariable Long id,
      @ModelAttribute SessionAssignmentService.Filters filters,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
    return ApiResponse.ok(service.candidates(id,filters,page,size));
  }

  @PostMapping("/enrollment-candidates/resolve")
  public ApiResponse<List<Map<String,Object>>> resolve(@PathVariable Long id,
      @RequestBody SessionAssignmentService.Filters filters) {
    return ApiResponse.ok(service.resolve(id,filters));
  }

  @PostMapping("/enrollment-changes")
  public ApiResponse<SessionAssignmentService.ChangeResult> change(@PathVariable Long id,
      @Valid @RequestBody SessionAssignmentService.Changes request) {
    return ApiResponse.ok(service.change(id,request));
  }
}
