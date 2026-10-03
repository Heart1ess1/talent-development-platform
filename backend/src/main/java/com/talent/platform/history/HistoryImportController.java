package com.talent.platform.history;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@RestController
@RequestMapping("/api/v1/history-imports")
public class HistoryImportController {
  private static final Set<String> TYPES = Set.of("EXAM", "EVALUATION");
  private static final Set<String> COMPONENTS = Set.of("EXAM", "TASK", "MENTOR", "STATION", "TRAINING", "BONUS", "DEDUCTION");
  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final PermissionService permissions;
  private final AuditService audit;

  public HistoryImportController(JdbcTemplate db, ObjectMapper mapper, PermissionService permissions, AuditService audit) {
    this.db = db; this.mapper = mapper; this.permissions = permissions; this.audit = audit;
  }

  public record CreateResult(Long batchId, int rowCount, int errorCount, int warningCount) {}
  public record RowInput(@NotBlank String rowType, Map<String,Object> data) {}

  @GetMapping("/templates")
  public void template(@RequestParam String type, HttpServletResponse response) throws Exception {
    permissions.require(Permissions.HISTORY_IMPORT);
    if (!TYPES.contains(type)) throw new BusinessException(400, "不支持的历史数据类型");
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode("历史" + ("EXAM".equals(type) ? "考试成绩" : "综合评价") + "导入模板.xlsx", StandardCharsets.UTF_8));
    ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build();
    try {
      if ("EXAM".equals(type)) {
        writer.write(List.of(), EasyExcel.writerSheet("历史考试").head(HistoryImportRows.Exam.class).build());
        writer.write(List.of(), EasyExcel.writerSheet("历史成绩").head(HistoryImportRows.Result.class).build());
      } else {
        writer.write(List.of(), EasyExcel.writerSheet("历史评价").head(HistoryImportRows.Evaluation.class).build());
        writer.write(List.of(), EasyExcel.writerSheet("评价分项").head(HistoryImportRows.Component.class).build());
      }
    } finally { writer.finish(); }
  }

  @PostMapping
  @Transactional
  public ApiResponse<CreateResult> create(@RequestParam String type, @RequestParam(defaultValue = "历史系统") String sourceSystem, @RequestParam MultipartFile file) throws Exception {
    permissions.require(Permissions.HISTORY_IMPORT);
    if (!TYPES.contains(type)) throw new BusinessException(400, "不支持的历史数据类型");
    if (file.isEmpty() || file.getSize() > 20 * 1024 * 1024) throw new BusinessException(400, "导入文件为空或超过20MB");
    byte[] bytes = file.getBytes(); String sha = sha256(bytes);
    var existing = db.queryForList("select id,row_count,error_count,warning_count from history_import_batch where import_type=? and source_system=? and file_sha256=?", type, sourceSystem.trim(), sha);
    if (!existing.isEmpty()) {
      var x = existing.get(0);
      return ApiResponse.ok(new CreateResult(((Number)x.get("id")).longValue(), number(x.get("row_count")), number(x.get("error_count")), number(x.get("warning_count"))));
    }
    db.update("insert into history_import_batch(import_type,status,source_system,original_filename,file_sha256,created_by) values(?,'DRAFT',?,?,?,?)", type, sourceSystem.trim(), file.getOriginalFilename(), sha, SecurityUtils.current().id());
    Long batchId = lastId();
    if ("EXAM".equals(type)) {
      stageExam(batchId, bytes, sourceSystem);
      stageResults(batchId, bytes);
    } else {
      stageEvaluations(batchId, bytes, sourceSystem);
      stageComponents(batchId, bytes);
    }
    Integer staged = db.queryForObject("select count(*) from history_import_row where batch_id=?", Integer.class, batchId);
    if (staged == null || staged == 0) throw new BusinessException(400, "导入文件未找到对应页签或有效数据");
    validateBatch(batchId);
    Map<String,Object> summary = db.queryForMap("select row_count,error_count,warning_count from history_import_batch where id=?", batchId);
    audit.log("CREATE_HISTORY_IMPORT", "HISTORY_IMPORT_BATCH", batchId, null, Map.of("type", type, "sourceSystem", sourceSystem, "sha256", sha));
    return ApiResponse.ok(new CreateResult(batchId, number(summary.get("row_count")), number(summary.get("error_count")), number(summary.get("warning_count"))));
  }

  @GetMapping
  public ApiResponse<List<Map<String,Object>>> list() {
    permissions.require(Permissions.HISTORY_IMPORT);
    return ApiResponse.ok(db.queryForList("select b.*,u.display_name creator_name,r.display_name reviewer_name from history_import_batch b join sys_user u on u.id=b.created_by left join sys_user r on r.id=b.reviewed_by order by b.id desc"));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String,Object>> detail(@PathVariable Long id) {
    permissions.require(Permissions.HISTORY_IMPORT);
    var result = new LinkedHashMap<>(db.queryForMap("select * from history_import_batch where id=?", id));
    result.put("rows", db.queryForList("select * from history_import_row where batch_id=? order by sheet_name,row_no", id));
    return ApiResponse.ok(result);
  }

  @PostMapping("/{id}/rows")
  @Transactional
  public ApiResponse<Long> addRow(@PathVariable Long id, @RequestBody RowInput input) throws Exception {
    permissions.require(Permissions.HISTORY_IMPORT); ensureDraft(id); validateRowType(input.rowType());
    Long rowId = insertRow(id, "人工录入", nextRow(id), input.rowType(), input.data());
    validateBatch(id); audit.log("ADD_HISTORY_IMPORT_ROW", "HISTORY_IMPORT_ROW", rowId, null, input); return ApiResponse.ok(rowId);
  }

  @PutMapping("/{id}/rows/{rowId}")
  @Transactional
  public ApiResponse<Void> updateRow(@PathVariable Long id, @PathVariable Long rowId, @RequestBody Map<String,Object> data) throws Exception {
    permissions.require(Permissions.HISTORY_IMPORT); ensureDraft(id);
    Integer count = db.queryForObject("select count(*) from history_import_row where id=? and batch_id=?", Integer.class, rowId, id);
    if (count == null || count == 0) throw new BusinessException(404, "导入行不存在");
    String raw = mapper.writeValueAsString(data);
    db.update("update history_import_row set raw_json=?,row_hash=?,state='ERROR',error_message=null,warning_message=null where id=?", raw, sha256(raw.getBytes(StandardCharsets.UTF_8)), rowId);
    validateBatch(id); audit.log("UPDATE_HISTORY_IMPORT_ROW", "HISTORY_IMPORT_ROW", rowId, null, data); return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/validate")
  @Transactional
  public ApiResponse<CreateResult> validate(@PathVariable Long id) {
    permissions.require(Permissions.HISTORY_IMPORT); ensureDraft(id); validateBatch(id);
    var x = db.queryForMap("select row_count,error_count,warning_count from history_import_batch where id=?", id);
    return ApiResponse.ok(new CreateResult(id, number(x.get("row_count")), number(x.get("error_count")), number(x.get("warning_count"))));
  }

  @PostMapping("/{id}/submit")
  @Transactional
  public ApiResponse<Void> submit(@PathVariable Long id) {
    permissions.require(Permissions.HISTORY_IMPORT); ensureDraft(id); validateBatch(id);
    int errors = db.queryForObject("select error_count from history_import_batch where id=?", Integer.class, id);
    if (errors > 0) throw new BusinessException(400, "存在未修复的导入错误");
    db.update("update history_import_batch set status='SUBMITTED' where id=?", id);
    audit.log("SUBMIT_HISTORY_IMPORT", "HISTORY_IMPORT_BATCH", id, null, null); return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/publish")
  @Transactional
  public ApiResponse<Void> publish(@PathVariable Long id) throws Exception {
    permissions.require(Permissions.HISTORY_IMPORT); requireAdmin();
    var batch = db.queryForMap("select * from history_import_batch where id=? for update", id);
    String status = String.valueOf(batch.get("status"));
    if (!Set.of("DRAFT", "SUBMITTED").contains(status)) throw new BusinessException(409, "当前批次不可发布");
    validateBatch(id);
    int errors = db.queryForObject("select error_count from history_import_batch where id=?", Integer.class, id);
    if (errors > 0) throw new BusinessException(400, "存在未修复的导入错误");
    if ("EXAM".equals(batch.get("import_type"))) publishExams(id, String.valueOf(batch.get("source_system")));
    else publishEvaluations(id, String.valueOf(batch.get("source_system")));
    db.update("update history_import_batch set status='PUBLISHED',reviewed_by=?,reviewed_at=now(),published_at=now() where id=?", SecurityUtils.current().id(), id);
    audit.log("PUBLISH_HISTORY_IMPORT", "HISTORY_IMPORT_BATCH", id, batch, Map.of("status", "PUBLISHED")); return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/revoke")
  @Transactional
  public ApiResponse<Void> revoke(@PathVariable Long id) {
    permissions.require(Permissions.HISTORY_IMPORT); requireAdmin();
    var batch = db.queryForMap("select * from history_import_batch where id=? for update", id);
    if (!"PUBLISHED".equals(batch.get("status"))) throw new BusinessException(409, "只有已发布批次可以撤销");
    db.update("update legacy_exam set active=false where batch_id=?", id);
    db.update("update legacy_exam_result set active=false where batch_id=?", id);
    db.update("update legacy_evaluation set active=false where batch_id=?", id);
    db.update("update score_summary set status='REVOKED' where source_type='HISTORICAL' and source_batch_id=?", id);
    db.update("update history_import_batch set status='REVOKED',revoked_at=now(),reviewed_by=?,reviewed_at=now() where id=?", SecurityUtils.current().id(), id);
    audit.log("REVOKE_HISTORY_IMPORT", "HISTORY_IMPORT_BATCH", id, batch, Map.of("status", "REVOKED")); return ApiResponse.ok(null);
  }

  private void stageExam(Long batchId, byte[] bytes, String sourceSystem) throws Exception {
    for (var row : read(bytes, HistoryImportRows.Exam.class, "历史考试")) insertRow(batchId, "历史考试", nextRow(batchId, "历史考试"), "EXAM", mapper.convertValue(row, new TypeReference<>() {}));
  }
  private void stageResults(Long batchId, byte[] bytes) throws Exception {
    for (var row : read(bytes, HistoryImportRows.Result.class, "历史成绩")) insertRow(batchId, "历史成绩", nextRow(batchId, "历史成绩"), "EXAM_RESULT", mapper.convertValue(row, new TypeReference<>() {}));
  }
  private void stageEvaluations(Long batchId, byte[] bytes, String sourceSystem) throws Exception {
    for (var row : read(bytes, HistoryImportRows.Evaluation.class, "历史评价")) insertRow(batchId, "历史评价", nextRow(batchId, "历史评价"), "EVALUATION", mapper.convertValue(row, new TypeReference<>() {}));
  }
  private void stageComponents(Long batchId, byte[] bytes) throws Exception {
    for (var row : read(bytes, HistoryImportRows.Component.class, "评价分项")) insertRow(batchId, "评价分项", nextRow(batchId, "评价分项"), "EVALUATION_COMPONENT", mapper.convertValue(row, new TypeReference<>() {}));
  }

  private <T> List<T> read(byte[] bytes, Class<T> type, String sheet) {
    try { return EasyExcel.read(new ByteArrayInputStream(bytes)).head(type).sheet(sheet).headRowNumber(1).doReadSync(); }
    catch (Exception e) { return List.of(); }
  }

  private Long insertRow(Long batchId, String sheet, int rowNo, String rowType, Map<String,Object> data) throws Exception {
    String raw = mapper.writeValueAsString(data);
    db.update("insert into history_import_row(batch_id,sheet_name,row_no,row_type,raw_json,row_hash) values(?,?,?,?,?,?)", batchId, sheet, rowNo, rowType, raw, sha256(raw.getBytes(StandardCharsets.UTF_8)));
    return lastId();
  }

  private void validateBatch(Long id) {
    var rows = db.queryForList("select * from history_import_row where batch_id=? order by id", id);
    Map<String,Map<String,Object>> exams = new HashMap<>(); Map<String,Map<String,Object>> evaluations = new HashMap<>(); Map<String,List<Map<String,Object>>> components = new HashMap<>();
    for (var row : rows) if ("EXAM".equals(row.get("row_type")) || "EVALUATION".equals(row.get("row_type"))) {
      Map<String,Object> data = jsonMap(String.valueOf(row.get("raw_json"))); String key = "EXAM".equals(row.get("row_type")) ? text(data,"externalExamKey") : text(data,"employeeNo") + "|" + text(data,"summaryType") + "|" + text(data,"periodKey");
      if ("EXAM".equals(row.get("row_type"))) exams.put(key, data); else evaluations.put(key, data);
    } else if ("EVALUATION_COMPONENT".equals(row.get("row_type"))) { Map<String,Object> data=jsonMap(String.valueOf(row.get("raw_json"))); String key=text(data,"employeeNo")+"|"+text(data,"summaryType")+"|"+text(data,"periodKey"); components.computeIfAbsent(key,k->new ArrayList<>()).add(data); }
    int errors=0,warnings=0,valid=0; Set<String> seen=new HashSet<>();
    for (var row : rows) {
      Map<String,Object> data=jsonMap(String.valueOf(row.get("raw_json"))); List<String> es=new ArrayList<>(), ws=new ArrayList<>();
      try {
        String type=String.valueOf(row.get("row_type")); String duplicateKey=switch(type){case "EXAM"->"EXAM|"+text(data,"externalExamKey");case "EXAM_RESULT"->"RESULT|"+text(data,"externalExamKey")+"|"+text(data,"employeeNo")+"|"+text(data,"attemptNo");case "EVALUATION"->"EVAL|"+text(data,"employeeNo")+"|"+text(data,"summaryType")+"|"+text(data,"periodKey");case "EVALUATION_COMPONENT"->"COMP|"+text(data,"employeeNo")+"|"+text(data,"summaryType")+"|"+text(data,"periodKey")+"|"+text(data,"componentCode")+"|"+text(data,"sourceKey")+"|"+text(data,"evaluatorName");default->type+"|"+row.get("id");}; if(!seen.add(duplicateKey))es.add("文件内存在重复记录");
        switch (String.valueOf(row.get("row_type"))) {
          case "EXAM" -> validateExam(data, es);
          case "EXAM_RESULT" -> validateResult(data, exams, es, ws);
          case "EVALUATION" -> { validateEvaluation(data, es); String key=text(data,"employeeNo")+"|"+text(data,"summaryType")+"|"+text(data,"periodKey"); var cs=components.getOrDefault(key,List.of()); if(cs.isEmpty()) es.add("评价记录至少需要一条评分明细"); else { BigDecimal total=cs.stream().map(x->decimalNullable(x,"weightedScore")).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add); BigDecimal finalScore=decimalNullable(data,"finalScore"); if(finalScore!=null&&total.subtract(finalScore).abs().compareTo(new BigDecimal("0.02"))>0) es.add("评分明细加权合计与最终分数不一致"); } }
          case "EVALUATION_COMPONENT" -> validateComponent(data, evaluations, es);
          default -> es.add("不支持的行类型");
        }
      } catch (Exception e) { es.add(e.getMessage()==null?"数据格式错误":e.getMessage()); }
      String state=es.isEmpty()?(ws.isEmpty()?"VALID":"WARNING"):"ERROR"; if (!es.isEmpty()) errors++; else {valid++; if(!ws.isEmpty())warnings++;}
      String employeeNo=text(data,"employeeNo"); Long employeeId=findEmployee(employeeNo);
      db.update("update history_import_row set normalized_json=?,employee_no=?,employee_id=?,state=?,error_message=?,warning_message=? where id=?", toJson(data), blank(employeeNo), employeeId, state, join(es), join(ws), row.get("id"));
    }
    db.update("update history_import_batch set row_count=?,valid_count=?,error_count=?,warning_count=? where id=?", rows.size(), valid, errors, warnings, id);
  }

  private void validateExam(Map<String,Object> d,List<String> es) { req(d,"externalExamKey","外部考试编号",es); req(d,"name","考试名称",es); LocalDate exam=parseDate(text(d,"examDate"),"考试日期",es); LocalDate month=parseMonth(text(d,"scoreMonth"),"成绩月份",es); BigDecimal max=decimal(d,"maxScore",es); if(max!=null&&max.signum()<=0)es.add("满分必须大于0"); if(exam!=null&&month!=null&&month.getDayOfMonth()!=1)es.add("成绩月份必须为月份第一天"); }
  private void validateResult(Map<String,Object> d,Map<String,Map<String,Object>> exams,List<String> es,List<String> ws) { req(d,"externalExamKey","外部考试编号",es); req(d,"employeeNo","工号",es); req(d,"resultStatus","完成状态",es); String key=text(d,"externalExamKey"); Map<String,Object> exam=exams.get(key); if(exam==null)es.add("找不到对应的历史考试"); String status=text(d,"resultStatus"); if(!Set.of("COMPLETED","ABSENT","EXEMPT").contains(status))es.add("完成状态必须为 COMPLETED、ABSENT 或 EXEMPT"); BigDecimal score=decimal(d,"score",es); if("COMPLETED".equals(status)&&score==null)es.add("已完成记录必须填写成绩"); if(!"COMPLETED".equals(status)&&score!=null)ws.add("非完成状态的成绩将被忽略"); if(exam!=null&&score!=null){BigDecimal max=decimal(exam,"maxScore",es);if(max!=null&&score.compareTo(max)>0)es.add("成绩不能超过考试满分");} Long eid=findEmployee(text(d,"employeeNo")); if(eid==null)es.add("工号不存在"); else if(!text(d,"employeeName").isBlank()&&!Objects.equals(employeeName(eid),text(d,"employeeName")))ws.add("姓名与员工档案不一致"); }
  private void validateEvaluation(Map<String,Object> d,List<String> es) { req(d,"employeeNo","工号",es); req(d,"summaryType","评价类型",es); req(d,"periodKey","期间",es); if(!Set.of("MONTH","QUARTER").contains(text(d,"summaryType")))es.add("评价类型必须为 MONTH 或 QUARTER"); String p=text(d,"periodKey"); if("MONTH".equals(text(d,"summaryType"))&&!p.matches("20\\d{2}-[01]\\d"))es.add("月度期间格式应为 yyyy-MM"); if("QUARTER".equals(text(d,"summaryType"))&&!p.matches("20\\d{2}-Q[1-4]"))es.add("季度期间格式应为 yyyy-Q1 至 yyyy-Q4"); decimal(d,"finalScore",es); if(findEmployee(text(d,"employeeNo"))==null)es.add("工号不存在"); }
  private void validateComponent(Map<String,Object> d,Map<String,Map<String,Object>> evals,List<String> es) { String key=text(d,"employeeNo")+"|"+text(d,"summaryType")+"|"+text(d,"periodKey"); if(!evals.containsKey(key))es.add("找不到对应的历史评价"); if(!COMPONENTS.contains(text(d,"componentCode")))es.add("评分项编码不合法"); BigDecimal raw=decimal(d,"rawScore",es), max=decimal(d,"maxScore",es), weight=decimal(d,"weight",es), weighted=decimal(d,"weightedScore",es); if(raw!=null&&max!=null&&raw.compareTo(max)>0)es.add("原始分不能超过满分"); if(weight!=null&&(weight.signum()<0||weight.compareTo(BigDecimal.valueOf(100))>0))es.add("权重必须在0至100之间"); if(raw!=null&&max!=null&&weight!=null&&weighted!=null){BigDecimal expected=raw.multiply(weight).divide(max,2,java.math.RoundingMode.HALF_UP);if(expected.subtract(weighted).abs().compareTo(new BigDecimal("0.02"))>0)es.add("加权分与原始分、满分、权重不一致");} }

  private void publishExams(Long batchId,String sourceSystem){
    Map<String,Long> ids=new HashMap<>(); for(var row:db.queryForList("select raw_json from history_import_row where batch_id=? and row_type='EXAM' and state in ('VALID','WARNING')",batchId)){var d=jsonMap(String.valueOf(row.get("raw_json")));String key=text(d,"externalExamKey");db.update("update legacy_exam set active=false where source_system=? and external_exam_key=? and active=true",sourceSystem,key);Integer v=db.queryForObject("select coalesce(max(version),0)+1 from legacy_exam where source_system=? and external_exam_key=?",Integer.class,sourceSystem,key);db.update("insert into legacy_exam(batch_id,source_system,external_exam_key,name,exam_date,score_month,max_score,remark,version) values(?,?,?,?,?,?,?,?,?)",batchId,sourceSystem,key,text(d,"name"),parseDate(text(d,"examDate"),"考试日期",new ArrayList<>()),parseMonth(text(d,"scoreMonth"),"成绩月份",new ArrayList<>()),decimal(d,"maxScore",new ArrayList<>()),blank(text(d,"remark")),v);ids.put(key,lastId());}
    for(var row:db.queryForList("select raw_json,employee_id,employee_no from history_import_row where batch_id=? and row_type='EXAM_RESULT' and state in ('VALID','WARNING')",batchId)){var d=jsonMap(String.valueOf(row.get("raw_json")));String key=text(d,"externalExamKey");Long eid=((Number)row.get("employee_id")).longValue();db.update("insert into legacy_exam_result(exam_id,batch_id,employee_id,employee_no_snapshot,employee_name_snapshot,result_status,score,objective_score,subjective_score,attempt_no,taken_at,remark) values(?,?,?,?,?,?,?,?,?,?,?,?)",ids.get(key),batchId,eid,text(d,"employeeNo"),employeeName(eid),text(d,"resultStatus"),decimalNullable(d,"score"),decimalNullable(d,"objectiveScore"),decimalNullable(d,"subjectiveScore"),integerNullable(d,"attemptNo",1),dateTimeNullable(d,"takenAt"),blank(text(d,"remark")));}
  }
  private void publishEvaluations(Long batchId,String sourceSystem) throws Exception {
    var parents=db.queryForList("select raw_json,employee_id from history_import_row where batch_id=? and row_type='EVALUATION' and state in ('VALID','WARNING')",batchId);
    for(var row:parents){var d=jsonMap(String.valueOf(row.get("raw_json")));Long eid=((Number)row.get("employee_id")).longValue();String type=text(d,"summaryType"),period=text(d,"periodKey");db.update("update legacy_evaluation set active=false where employee_id=? and summary_type=? and period_key=? and active=true",eid,type,period);db.update("update score_summary set status='REVOKED' where employee_id=? and summary_type=? and period_key=? and source_type='HISTORICAL' and status='PUBLISHED'",eid,type,period);db.update("insert into legacy_evaluation(batch_id,employee_id,employee_no_snapshot,summary_type,period_key,original_final_score,source_system,remark) values(?,?,?,?,?,?,?,?)",batchId,eid,text(d,"employeeNo"),type,period,decimalNullable(d,"finalScore"),sourceSystem,blank(text(d,"remark")));Long legacyId=lastId();var components=new ArrayList<Map<String,Object>>();for(var cr:db.queryForList("select raw_json from history_import_row where batch_id=? and row_type='EVALUATION_COMPONENT' and employee_no=? and state in ('VALID','WARNING')",batchId,text(d,"employeeNo"))){var c=jsonMap(String.valueOf(cr.get("raw_json")));if(!type.equals(text(c,"summaryType"))||!period.equals(text(c,"periodKey")))continue;db.update("insert into legacy_evaluation_component(evaluation_id,component_code,source_key,source_name,raw_score,max_score,weight,weighted_score,evaluator_name,comment,snapshot_json) values(?,?,?,?,?,?,?,?,?,?,cast(? as json))",legacyId,text(c,"componentCode"),blank(text(c,"sourceKey")),blank(text(c,"sourceName")),decimalNullable(c,"rawScore"),decimalNullable(c,"maxScore"),decimalNullable(c,"weight"),decimalNullable(c,"weightedScore"),blank(text(c,"evaluatorName")),blank(text(c,"comment")),toJson(c));components.add(c);}int version=db.queryForObject("select coalesce(max(version),0)+1 from score_summary where employee_id=? and summary_type=? and period_key=?",Integer.class,eid,type,period);Map<String,Object> snapshot=Map.of("source","HISTORICAL","components",components,"originalFinalScore",decimalNullable(d,"finalScore"));db.update("insert into score_summary(employee_id,summary_type,period_key,version,source_type,source_batch_id,read_only,exam_score,task_score,mentor_score,station_score,training_score,bonus,deduction,final_score,status,component_snapshot,generated_at,published_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as json),now(),now())",eid,type,period,version,"HISTORICAL",batchId,true,componentScore(components,"EXAM"),componentScore(components,"TASK"),componentScore(components,"MENTOR"),componentScore(components,"STATION"),componentScore(components,"TRAINING"),componentScore(components,"BONUS"),componentScore(components,"DEDUCTION"),decimalNullable(d,"finalScore"),"PUBLISHED",mapper.writeValueAsString(snapshot));}
  }

  private BigDecimal componentScore(List<Map<String,Object>> cs,String code){for(var c:cs)if(code.equals(text(c,"componentCode")))return decimalNullable(c,"rawScore");return null;}
  private void ensureDraft(Long id){String s=db.queryForObject("select status from history_import_batch where id=?",String.class,id);if(s==null)throw new BusinessException(404,"导入批次不存在");if(!Set.of("DRAFT","SUBMITTED").contains(s))throw new BusinessException(409,"当前批次不可修改");}
  private void requireAdmin(){if(!Set.of("ADMIN","SUPER_ADMIN").contains(SecurityUtils.current().role()))throw new BusinessException(403,"仅管理员可发布或撤销历史数据");}
  private void validateRowType(String type){if(!Set.of("EXAM","EXAM_RESULT","EVALUATION","EVALUATION_COMPONENT").contains(type))throw new BusinessException(400,"不支持的行类型");}
  private int nextRow(Long id){return nextRow(id,"人工录入");}
  private int nextRow(Long id,String sheet){Integer n=db.queryForObject("select coalesce(max(row_no),0)+1 from history_import_row where batch_id=? and sheet_name=?",Integer.class,id,sheet);return n==null?1:n;}
  private Long findEmployee(String no){if(no==null||no.isBlank())return null;var rows=db.queryForList("select id from employee where employee_no=?",no.trim());return rows.isEmpty()?null:((Number)rows.get(0).get("id")).longValue();}
  private String employeeName(Long id){return db.queryForObject("select name from employee where id=?",String.class,id);}
  private static String text(Map<String,Object>d,String k){Object x=d.get(k);return x==null?"":String.valueOf(x).trim();}
  private static String blank(String x){return x==null||x.isBlank()?null:x.trim();}
  private static String join(List<String> x){return x.isEmpty()?null:String.join("；",x);}
  private static int number(Object x){return x==null?0:((Number)x).intValue();}
  private static BigDecimal decimal(Map<String,Object>d,String k,List<String> es){String x=text(d,k);if(x.isBlank()){es.add(k+"不能为空");return null;}try{return new BigDecimal(x);}catch(Exception e){es.add(k+"必须为数字");return null;}}
  private static BigDecimal decimalNullable(Map<String,Object>d,String k){String x=text(d,k);if(x.isBlank())return null;try{return new BigDecimal(x);}catch(Exception e){return null;}}
  private static Integer integerNullable(Map<String,Object>d,String k,int fallback){try{return text(d,k).isBlank()?fallback:Integer.valueOf(text(d,k));}catch(Exception e){return fallback;}}
  private static void req(Map<String,Object>d,String k,String label,List<String> es){if(text(d,k).isBlank())es.add(label+"不能为空");}
  private static LocalDate parseDate(String x,String label,List<String> es){if(x.isBlank()){es.add(label+"不能为空");return null;}try{return LocalDate.parse(x.length()>=10?x.substring(0,10):x);}catch(Exception e){es.add(label+"格式应为 yyyy-MM-dd");return null;}}
  private static LocalDate parseMonth(String x,String label,List<String> es){if(x.isBlank()){es.add(label+"不能为空");return null;}try{return YearMonth.parse(x.substring(0,7)).atDay(1);}catch(Exception e){es.add(label+"格式应为 yyyy-MM");return null;}}
  private static LocalDateTime dateTimeNullable(Map<String,Object>d,String k){String x=text(d,k);if(x.isBlank())return null;try{return LocalDateTime.parse(x.replace(" ","T"));}catch(Exception e){return null;}}
  private Map<String,Object> jsonMap(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){return new LinkedHashMap<>();}}
  private String toJson(Object x){try{return mapper.writeValueAsString(x);}catch(Exception e){throw new BusinessException(500,"历史数据序列化失败");}}
  private Long lastId(){return db.queryForObject("select last_insert_id()",Long.class);}
  private static String sha256(byte[] bytes){try{var md=MessageDigest.getInstance("SHA-256");var out=md.digest(bytes);var s=new StringBuilder();for(byte b:out)s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
