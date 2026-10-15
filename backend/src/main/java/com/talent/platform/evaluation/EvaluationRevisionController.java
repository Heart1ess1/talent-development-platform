package com.talent.platform.evaluation;
import com.talent.platform.common.ApiResponse;
import com.talent.platform.security.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/evaluation/revisions")
public class EvaluationRevisionController {
  private final EvaluationRevisionService service; private final PermissionService permissions;private final AuditService audit;
  public EvaluationRevisionController(EvaluationRevisionService service,PermissionService permissions,AuditService audit){this.service=service;this.permissions=permissions;this.audit=audit;}
  @GetMapping public ApiResponse<?> list(){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.list());}
  @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.detail(id));}
  @PostMapping("/{id}/refresh") public ApiResponse<?> refresh(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(service.refresh(id));}
  public record Waiver(Long summaryId,String reason,java.math.BigDecimal score){}
  @PostMapping("/{id}/waivers") public ApiResponse<?> waive(@PathVariable Long id,@RequestBody Waiver body){permissions.require(Permissions.EVALUATION_MANAGE);service.waive(id,body.summaryId(),body.reason(),body.score());audit.log("WAIVE_REVISION_SUMMARY","REVISION_GROUP",id,null,body);return ApiResponse.ok(service.detail(id));}
  @PostMapping("/{id}/publish") public ApiResponse<?> publish(@PathVariable Long id,@RequestBody Map<String,String> body){permissions.require(Permissions.EVALUATION_MANAGE);service.publish(id,body.get("reviewToken"));audit.log("PUBLISH_EVALUATION_REVISION","REVISION_GROUP",id,null,null);return ApiResponse.ok(null);}
}
