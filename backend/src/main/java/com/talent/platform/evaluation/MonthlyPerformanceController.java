package com.talent.platform.evaluation;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.ApiResponse;
import com.talent.platform.security.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.*;

@RestController
@RequestMapping("/api/v1/evaluation/performance")
@org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class MonthlyPerformanceController {
  private final MonthlyPerformanceService service;
  private final PermissionService permissions;
  private final AuditService audit;
  public MonthlyPerformanceController(MonthlyPerformanceService service,PermissionService permissions,AuditService audit){this.service=service;this.permissions=permissions;this.audit=audit;}
  public record Configuration(@NotNull Long batchId,@NotNull YearMonth month,@NotNull BigDecimal ratioA,@NotNull BigDecimal ratioB,@NotNull BigDecimal ratioC){}
  public record Participation(@NotNull Boolean participating,@Size(max=1000) String reason){}
  public record Reason(@NotBlank @Size(max=1000) String reason){}
  public record Waiver(@NotNull Long employeeId,@NotBlank @Size(max=1000) String reason,@NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal score){}
  public record Publication(@NotBlank String reviewToken){}
  private void manage(){permissions.require(Permissions.EVALUATION_MANAGE);}
  @GetMapping public ApiResponse<?> list(@RequestParam long batchId,@RequestParam YearMonth month){manage();return ApiResponse.ok(service.list(batchId,month));}
  @PutMapping public ApiResponse<?> save(@Valid @RequestBody Configuration q){manage();long id=service.save(q.batchId(),q.month(),q.ratioA(),q.ratioB(),q.ratioC());audit.log("SAVE_MONTHLY_PERFORMANCE","MONTHLY_PERFORMANCE",id,null,q);return ApiResponse.ok(service.detail(id));}
  @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable long id){manage();return ApiResponse.ok(service.detail(id));}
  @PostMapping("/{id}/roster/refresh") public ApiResponse<?> refresh(@PathVariable long id){manage();service.refreshRoster(id);audit.log("REFRESH_PERFORMANCE_ROSTER","MONTHLY_PERFORMANCE",id,null,null);return ApiResponse.ok(service.detail(id));}
  @PutMapping("/{id}/members/{employeeId}") public ApiResponse<?> participation(@PathVariable long id,@PathVariable long employeeId,@Valid @RequestBody Participation q){manage();service.participation(id,employeeId,q.participating(),q.reason());audit.log("UPDATE_PERFORMANCE_PARTICIPATION","MONTHLY_PERFORMANCE",id,null,Map.of("employeeId",employeeId,"request",q));return ApiResponse.ok(service.detail(id));}
  @PostMapping("/{id}/preview") public ApiResponse<?> preview(@PathVariable long id){manage();return ApiResponse.ok(service.preview(id));}
  @PostMapping("/{id}/waivers") public ApiResponse<?> waive(@PathVariable long id,@Valid @RequestBody Waiver q){manage();service.waive(id,q.employeeId(),q.reason(),q.score());audit.log("WAIVE_PERFORMANCE_SCORE","MONTHLY_PERFORMANCE",id,null,q);return ApiResponse.ok(service.preview(id));}
  @PostMapping("/{id}/publish") public ApiResponse<?> publish(@PathVariable long id,@Valid @RequestBody Publication q){manage();service.publish(id,q.reviewToken());audit.log("PUBLISH_MONTHLY_PERFORMANCE","MONTHLY_PERFORMANCE",id,null,null);return ApiResponse.ok(null);}
  @PostMapping("/{id}/reopen") public ApiResponse<?> reopen(@PathVariable long id,@Valid @RequestBody Reason q){manage();if(!Set.of("ADMIN","SUPER_ADMIN").contains(SecurityUtils.current().role()))throw new com.talent.platform.common.BusinessException(403,"仅管理员可重开绩效结果");long created=service.reopen(id,q.reason());audit.log("REOPEN_MONTHLY_PERFORMANCE","MONTHLY_PERFORMANCE",created,null,q);return ApiResponse.ok(service.detail(created));}
  @GetMapping("/{id}/export")
  @SuppressWarnings("unchecked")
  public void export(@PathVariable long id,HttpServletResponse response)throws Exception{
    manage();var g=service.detail(id);List<Map<String,Object>> members;
    if("DRAFT".equals(g.get("status"))){var preview=service.preview(id);g=((List<Map<String,Object>>)preview.get("groups")).stream().filter(x->((Number)x.get("id")).longValue()==id).findFirst().orElseThrow();}
    members=(List<Map<String,Object>>)g.get("members");
    List<List<String>> headers=List.of("月份","培养批次","绩效版本","发布状态","工号","姓名","参评状态","不参评原因","综合分","成绩版本","并列名次","绩效等级","A比例(%)","B比例(%)","C比例(%)").stream().map(List::of).toList();
    var rows=new ArrayList<List<Object>>();
    for(var m:members){var row=new ArrayList<Object>();Collections.addAll(row,g.get("period_month").toString().substring(0,7),g.get("batch_name"),g.get("version"),"PUBLISHED".equals(g.get("status"))?"已发布":"SUPERSEDED".equals(g.get("status"))?"历史版本":"草稿",m.get("employee_no"),m.get("employee_name"),Boolean.TRUE.equals(m.get("participating"))||Integer.valueOf(1).equals(m.get("participating"))?"参评":"不参评",m.get("exclusion_reason"),m.get("final_score"),m.get("summary_version"),m.get("rank_no"),m.get("grade"),g.get("ratio_a"),g.get("ratio_b"),g.get("ratio_c"));rows.add(row);}
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode("月度绩效-"+g.get("batch_name")+"-"+g.get("period_month")+".xlsx",StandardCharsets.UTF_8));
    EasyExcel.write(response.getOutputStream()).head(headers).sheet("月度绩效").doWrite(rows);
  }
}
