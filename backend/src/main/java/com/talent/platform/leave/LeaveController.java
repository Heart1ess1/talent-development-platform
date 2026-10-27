package com.talent.platform.leave;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.PageResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/leave-requests")
public class LeaveController {
  private final LeaveService service;
  public LeaveController(LeaveService service) { this.service=service; }
  @GetMapping("/context") public ApiResponse<Map<String,Object>> context(){return ApiResponse.ok(service.context());}
  @GetMapping("/{id}/versions/{version}/approvers") public ApiResponse<List<Map<String,Object>>> transferCandidates(@PathVariable long id,@PathVariable int version){return ApiResponse.ok(service.transferCandidates(id,version));}
  @GetMapping("/preview") public ApiResponse<Map<String,Object>> preview(
      @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
      @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt) {
    return ApiResponse.ok(service.preview(startAt,endAt));
  }
  @GetMapping public ApiResponse<PageResult<Map<String,Object>>> list(@RequestParam(defaultValue="mine") String view,
      @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
      @RequestParam(required=false) Long stationId,@RequestParam(required=false) String scenario,
      @RequestParam(required=false) String approvalStatus,@RequestParam(required=false) String returnStatus,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
    return ApiResponse.ok(service.list(view,keyword,status,stationId,scenario,approvalStatus,returnStatus,dateFrom,dateTo,page,size));
  }
  @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable long id) {return ApiResponse.ok(service.detail(id));}
  @GetMapping("/plans") public ApiResponse<List<Map<String,Object>>> plans(@RequestParam(required=false) Long employeeId) {return ApiResponse.ok(service.plans(employeeId));}
  @GetMapping("/draft") public ApiResponse<Map<String,Object>> draft() {return ApiResponse.ok(service.draft());}
  @PutMapping("/draft") public ApiResponse<Void> saveDraft(@RequestBody Map<String,Object> content) {service.saveDraft(content);return ApiResponse.ok(null);}
  @DeleteMapping("/draft") public ApiResponse<Void> deleteDraft() {service.deleteDraft();return ApiResponse.ok(null);}
  @PostMapping public ApiResponse<Long> submit(@RequestBody LeaveService.Form form) {return ApiResponse.ok(service.submit(form,null));}
  @PostMapping("/{id}/changes") public ApiResponse<Long> change(@PathVariable long id,@RequestBody LeaveService.Form form) {return ApiResponse.ok(service.submit(form,id));}
  @PostMapping("/{id}/versions/{version}/review") public ApiResponse<Void> review(@PathVariable long id,@PathVariable int version,@RequestBody LeaveService.ReviewForm form) {service.review(id,version,form);return ApiResponse.ok(null);}
  @PostMapping("/{id}/versions/{version}/withdraw") public ApiResponse<Void> withdraw(@PathVariable long id,@PathVariable int version) {service.withdraw(id,version);return ApiResponse.ok(null);}
  @PostMapping("/{id}/cancel") public ApiResponse<Void> cancel(@PathVariable long id,@RequestParam int version) {service.cancel(id,version);return ApiResponse.ok(null);}
  @PostMapping("/{id}/return") public ApiResponse<Void> returned(@PathVariable long id,@RequestBody LeaveService.ReturnForm form) {service.returnFromLeave(id,form);return ApiResponse.ok(null);}
  @PostMapping("/{id}/versions/{version}/transfer") public ApiResponse<Void> transfer(@PathVariable long id,@PathVariable int version,@RequestBody LeaveService.TransferForm form) {service.transfer(id,version,form);return ApiResponse.ok(null);}
}
