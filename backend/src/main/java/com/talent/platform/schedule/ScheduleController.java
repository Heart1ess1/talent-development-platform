package com.talent.platform.schedule;
import com.talent.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/schedules/{type}/{id}")
public class ScheduleController {
  private final ScheduleService service;
  public ScheduleController(ScheduleService service){this.service=service;}
  @GetMapping public ApiResponse<?> detail(@PathVariable String type,@PathVariable Long id){return ApiResponse.ok(service.detail(type,id));}
  @PostMapping("/preview") public ApiResponse<?> preview(@PathVariable String type,@PathVariable Long id,@Valid @RequestBody ScheduleService.Request request){return ApiResponse.ok(service.preview(type,id,request));}
  @PutMapping public ApiResponse<?> change(@PathVariable String type,@PathVariable Long id,@Valid @RequestBody ScheduleService.Request request){return ApiResponse.ok(service.change(type,id,request));}
  @PostMapping("/opportunities") public ApiResponse<?> grant(@PathVariable String type,@PathVariable Long id,@Valid @RequestBody ScheduleService.Request request){return ApiResponse.ok(service.grant(type,id,request));}
  @DeleteMapping("/opportunities/{opportunity}") public ApiResponse<?> revoke(@PathVariable String type,@PathVariable Long id,@PathVariable Long opportunity){service.revoke(type,id,opportunity);return ApiResponse.ok(null);}
}
