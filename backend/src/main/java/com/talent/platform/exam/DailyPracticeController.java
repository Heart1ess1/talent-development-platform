package com.talent.platform.exam;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.common.PageResult;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 考试中心「每日随机推题」管理端接口。
 * 阶段二只提供计划侧能力；学员端作答与每日生成在后续阶段接入。
 */
@RestController
@RequestMapping("/api/v1/exam-daily")
public class DailyPracticeController {
  private static final DateTimeFormatter RESULT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private final DailyPracticeService service;
  private final PermissionService permissions;

  public DailyPracticeController(DailyPracticeService service, PermissionService permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping("/plans")
  public ApiResponse<List<Map<String, Object>>> plans() {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.listPlans());
  }

  @PostMapping("/plans")
  public ApiResponse<Long> createPlan(@Valid @RequestBody DailyPracticeService.PlanRequest request) {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.createPlan(request));
  }

  @GetMapping("/plans/{id}")
  public ApiResponse<Map<String, Object>> planDetail(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.planDetail(id));
  }

  @PutMapping("/plans/{id}")
  public ApiResponse<Void> updatePlan(@PathVariable Long id, @Valid @RequestBody DailyPracticeService.PlanRequest request) {
    permissions.require(Permissions.EXAM_MANAGE);
    service.updatePlan(id, request);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/plans/{id}")
  public ApiResponse<Void> deletePlan(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    service.deletePlan(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/plans/{id}/publish")
  public ApiResponse<Void> publishPlan(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    service.publishPlan(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/plans/{id}/pause")
  public ApiResponse<Void> pausePlan(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    service.pausePlan(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/plans/{id}/resume")
  public ApiResponse<Void> resumePlan(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    service.resumePlan(id);
    return ApiResponse.ok(null);
  }

  @GetMapping("/candidates")
  public ApiResponse<List<Map<String, Object>>> candidates(@RequestParam(required = false) String batchIds,
                                                           @RequestParam(required = false) String businessUnitIds,
                                                           @RequestParam(required = false) String keyword) {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.candidates(batchIds, businessUnitIds, keyword));
  }

  @PostMapping("/plans/{id}/participants/refresh")
  public ApiResponse<Integer> refreshParticipants(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.refreshParticipants(id));
  }

  @PutMapping("/plans/{id}/participants/{employeeId}")
  public ApiResponse<Void> updateParticipant(@PathVariable Long id, @PathVariable Long employeeId,
                                             @RequestBody DailyPracticeService.ParticipantRequest request) {
    permissions.require(Permissions.EXAM_MANAGE);
    service.updateParticipant(id, employeeId, request.active() == null || request.active());
    return ApiResponse.ok(null);
  }

  @PostMapping("/plans/{id}/push")
  public ApiResponse<Map<String, Object>> pushToday(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.pushToday(id));
  }

  @GetMapping("/plans/{id}/runs")
  public ApiResponse<List<Map<String, Object>>> runs(@PathVariable Long id) {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.runs(id));
  }

  /** 成绩查询：按日期区间、工号/姓名、状态筛选后分页返回。 */
  @GetMapping("/plans/{id}/results")
  public ApiResponse<PageResult<Map<String, Object>>> results(@PathVariable Long id,
                                                              @RequestParam(required = false) String dateFrom,
                                                              @RequestParam(required = false) String dateTo,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(required = false) String status,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
    permissions.require(Permissions.EXAM_MANAGE);
    return ApiResponse.ok(service.results(id, dateFrom, dateTo, keyword, status, page, size));
  }

  /** 成绩导出：沿用当前筛选条件；无数据时直接返回友好提示，不生成空文件。 */
  @GetMapping("/plans/{id}/results/export")
  public void exportResults(@PathVariable Long id,
                            @RequestParam(required = false) String dateFrom,
                            @RequestParam(required = false) String dateTo,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            HttpServletResponse response) throws Exception {
    permissions.require(Permissions.EXAM_MANAGE);
    var rows = service.resultsForExport(id, dateFrom, dateTo, keyword, status);
    if (rows.isEmpty()) throw new BusinessException(400, "当前筛选条件下没有可导出的练习成绩");
    var data = new ArrayList<DailyPracticeResultExportRow>();
    for (var row : rows) {
      var item = new DailyPracticeResultExportRow();
      item.setEmployeeNo(cellText(row.get("employee_no")));
      item.setEmployeeName(cellText(row.get("employee_name")));
      item.setBatchName(cellText(row.get("batch_name")));
      item.setPracticeDate(cellText(row.get("practice_date")));
      item.setQuestionCount(row.get("question_count") == null ? null : ((Number) row.get("question_count")).intValue());
      item.setCorrectCount(row.get("correct_count") == null ? null : ((Number) row.get("correct_count")).intValue());
      item.setTotalScore(row.get("total_score") == null ? null : new java.math.BigDecimal(String.valueOf(row.get("total_score"))));
      item.setStatus(resultStatusLabel(String.valueOf(row.get("status"))));
      item.setSubmittedAt(resultTime(row.get("submitted_at")));
      data.add(item);
    }
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
        URLEncoder.encode("每日练习成绩.xlsx", StandardCharsets.UTF_8));
    EasyExcel.write(response.getOutputStream(), DailyPracticeResultExportRow.class).sheet("每日练习成绩").doWrite(data);
  }

  private static String resultStatusLabel(String status) {
    return switch (status) {
      case "PENDING" -> "待开始";
      case "IN_PROGRESS" -> "作答中";
      case "SUBMITTED" -> "已提交";
      case "EXPIRED" -> "已过期";
      default -> status;
    };
  }

  private static String cellText(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String resultTime(Object value) {
    if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime().format(RESULT_TIME);
    if (value instanceof java.time.LocalDateTime time) return time.format(RESULT_TIME);
    return value == null ? "" : String.valueOf(value);
  }

  @PostMapping("/plans/{id}/exclusions")
  public ApiResponse<Void> addExclusion(@PathVariable Long id,
                                        @Valid @RequestBody DailyPracticeService.ExclusionRequest request) {
    permissions.require(Permissions.EXAM_MANAGE);
    service.addExclusion(id, request);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/plans/{id}/exclusions/{date}")
  public ApiResponse<Void> removeExclusion(@PathVariable Long id, @PathVariable String date) {
    permissions.require(Permissions.EXAM_MANAGE);
    try {
      service.removeExclusion(id, LocalDate.parse(date));
    } catch (DateTimeParseException e) {
      throw new BusinessException(400, "排除日期格式无效，应为 yyyy-MM-dd");
    }
    return ApiResponse.ok(null);
  }

  // ---------------------------------------------------------------- 学员端（登录即可）

  @GetMapping("/my")
  public ApiResponse<Map<String, Object>> my() {
    var user = SecurityUtils.current();
    return ApiResponse.ok(service.studentOverview(user.id(), user.role()));
  }

  @PostMapping("/sessions/{id}/start")
  public ApiResponse<Map<String, Object>> startSession(@PathVariable Long id) {
    var user = SecurityUtils.current();
    return ApiResponse.ok(service.startSession(id, user.id(), user.role()));
  }

  @GetMapping("/sessions/{id}")
  public ApiResponse<Map<String, Object>> sessionDetail(@PathVariable Long id) {
    var user = SecurityUtils.current();
    return ApiResponse.ok(service.sessionDetail(id, user.id(), user.role()));
  }

  @PutMapping("/sessions/{id}/answers")
  public ApiResponse<Map<String, Object>> saveAnswer(@PathVariable Long id,
                                                     @RequestBody DailyPracticeService.AnswerRequest request) {
    var user = SecurityUtils.current();
    return ApiResponse.ok(service.saveAnswer(id, user.id(), user.role(), request));
  }

  @PostMapping("/sessions/{id}/submit")
  public ApiResponse<Map<String, Object>> submitSession(@PathVariable Long id) throws Exception {
    var user = SecurityUtils.current();
    return ApiResponse.ok(service.submitSession(id, user.id(), user.role()));
  }

  @GetMapping("/sessions/{id}/result")
  public ApiResponse<Map<String, Object>> sessionResult(@PathVariable Long id) {
    var user = SecurityUtils.current();
    return ApiResponse.ok(service.sessionResult(id, user.id(), user.role()));
  }
}
