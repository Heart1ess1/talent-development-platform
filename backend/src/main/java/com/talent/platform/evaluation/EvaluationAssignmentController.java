package com.talent.platform.evaluation;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/evaluation/assignments")
public class EvaluationAssignmentController {
  private final EvaluationAssignmentService service;
  private final PermissionService permissions;
  private final AuditService audit;

  public EvaluationAssignmentController(EvaluationAssignmentService service,PermissionService permissions,AuditService audit){this.service=service;this.permissions=permissions;this.audit=audit;}

  public record GenerateRequest(@NotNull YearMonth month,LocalDateTime dueAt){}
  public record AssignRequest(
    @NotEmpty List<@NotNull Long> taskIds,
    List<@NotNull Long> reviewerIds,
    @Pattern(regexp="REPLACE|ADD") String mode,
    LocalDateTime dueAt,
    @Size(max=500) String note){}

  @GetMapping
  public ApiResponse<List<Map<String,Object>>> list(@RequestParam YearMonth month,@RequestParam(required=false)String component,@RequestParam(required=false)String status,@RequestParam(required=false)Long reviewerId,@RequestParam(required=false)String keyword){
    permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.list(month,component,status,reviewerId,keyword));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String,Object>> detail(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.detail(id));}

  @GetMapping("/reviewers")
  public ApiResponse<List<Map<String,Object>>> reviewers(@RequestParam String component){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.reviewerOptions(component));}

  @GetMapping("/mine")
  public ApiResponse<List<Map<String,Object>>> mine(@RequestParam YearMonth month,@RequestParam(required=false)String status,@RequestParam(required=false)String component){
    permissions.require(Permissions.EVALUATION_SUBMIT);return ApiResponse.ok(service.list(month,component,status,SecurityUtils.current().id(),null));
  }

  @PostMapping("/generate")
  public ApiResponse<Integer> generate(@Valid @RequestBody GenerateRequest request){
    permissions.require(Permissions.EVALUATION_MANAGE);int created=service.generateMonth(request.month(),request.dueAt());audit.log("GENERATE_EVALUATION_RATING_TASKS","EVALUATION_MONTH",null,null,Map.of("month",request.month(),"created",created));return ApiResponse.ok(created);
  }

  @PutMapping("/reviewers")
  public ApiResponse<Void> assign(@Valid @RequestBody AssignRequest request){
    permissions.require(Permissions.EVALUATION_MANAGE);service.assign(request.taskIds(),request.reviewerIds(),request.mode(),request.dueAt(),request.note());audit.log("ASSIGN_EVALUATION_REVIEWERS","EVALUATION_RATING_TASK",null,null,request);return ApiResponse.ok(null);
  }
}
