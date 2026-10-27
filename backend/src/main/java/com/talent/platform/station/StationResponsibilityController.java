package com.talent.platform.station;

import com.talent.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/station-responsibilities")
public class StationResponsibilityController {
  private final StationResponsibilityService service;
  public StationResponsibilityController(StationResponsibilityService service){this.service=service;}
  @GetMapping public ApiResponse<Map<String,Object>> context(){return ApiResponse.ok(service.context());}
  @PostMapping("/preview") public ApiResponse<Map<String,Object>> preview(@RequestBody StationResponsibilityService.Change request){return ApiResponse.ok(service.preview(request));}
  @PostMapping public ApiResponse<Map<String,Object>> save(@RequestBody StationResponsibilityService.Change request){return ApiResponse.ok(service.save(request));}
}
