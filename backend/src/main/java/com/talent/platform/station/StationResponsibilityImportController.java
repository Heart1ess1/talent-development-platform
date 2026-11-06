package com.talent.platform.station;

import com.talent.platform.common.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/station-responsibilities")
public class StationResponsibilityImportController {
  private final StationResponsibilityImportService service;
  public StationResponsibilityImportController(StationResponsibilityImportService service){this.service=service;}
  @GetMapping("/import-template") public ResponseEntity<byte[]> template(){return excel(service.template(),"站长与区域经理导入模板.xlsx");}
  @PostMapping("/imports/preview") public ApiResponse<StationResponsibilityImportService.Preview> preview(@RequestParam MultipartFile file){return ApiResponse.ok(service.preview(file));}
  @GetMapping("/imports/{batchId}") public ApiResponse<Map<String,Object>> get(@PathVariable String batchId){return ApiResponse.ok(service.get(batchId));}
  @GetMapping("/imports/{batchId}/errors") public ResponseEntity<byte[]> errors(@PathVariable String batchId){return excel(service.errors(batchId),"负责人导入错误明细.xlsx");}
  @PostMapping("/imports/{batchId}/commit") public ApiResponse<Map<String,Object>> commit(@PathVariable String batchId,@RequestBody StationResponsibilityImportService.Commit request){return ApiResponse.ok(service.commit(batchId,request));}
  private ResponseEntity<byte[]> excel(byte[] bytes,String name){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString()).body(bytes);}
}
