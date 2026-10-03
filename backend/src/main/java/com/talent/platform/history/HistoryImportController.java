package com.talent.platform.history;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
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
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/history-imports")
public class HistoryImportController {
  private static final Set<String> TYPES=Set.of("EXAM","EVALUATION");
  private static final Set<String> COMPONENTS=Set.of("EXAM","TASK","MENTOR","STATION","TRAINING","BONUS","DEDUCTION");
  private final JdbcTemplate db; private final ObjectMapper mapper; private final PermissionService permissions; private final AuditService audit;
  public HistoryImportController(JdbcTemplate db,ObjectMapper mapper,PermissionService permissions,AuditService audit){this.db=db;this.mapper=mapper;this.permissions=permissions;this.audit=audit;}
  public record CreateResult(Long batchId,int rowCount,int errorCount,int warningCount){}
  public record RowInput(@NotBlank String rowType,Map<String,Object> data){}
  private record Metadata(String examName,LocalDate examDate,LocalDate scoreMonth,String summaryType,String periodKey,String remark){}

  @GetMapping("/templates")
  public void template(@RequestParam String type,HttpServletResponse response)throws Exception{
    requireType(type);response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode("历史"+("EXAM".equals(type)?"成绩":"综合评价")+"导入模板.xlsx",StandardCharsets.UTF_8));
    ExcelWriter writer=EasyExcel.write(response.getOutputStream()).build();
    try{if("EXAM".equals(type))writer.write(List.of(),EasyExcel.writerSheet("历史成绩").head(HistoryImportRows.ExamResult.class).build());
    else writer.write(List.of(),EasyExcel.writerSheet("历史评价").head(HistoryImportRows.EvaluationFlat.class).build());}finally{writer.finish();}
  }

  @PostMapping @Transactional
  public ApiResponse<CreateResult> create(@RequestParam String type,@RequestParam String metadata,@RequestParam MultipartFile file)throws Exception{
    requireType(type);if(file.isEmpty()||file.getSize()>20*1024*1024)throw new BusinessException(400,"导入文件为空或超过20MB");
    Metadata meta=parseMetadata(type,jsonMap(metadata));byte[] bytes=file.getBytes();String sha=sha256(bytes);
    var existing=db.queryForList("select id,row_count,error_count,warning_count from history_import_batch where import_type=? and file_sha256=? order by id desc limit 1",type,sha);
    if(!existing.isEmpty()){var x=existing.get(0);return ApiResponse.ok(new CreateResult(((Number)x.get("id")).longValue(),number(x.get("row_count")),number(x.get("error_count")),number(x.get("warning_count"))));}
    String key="HIST-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
    db.update("insert into history_import_batch(import_type,status,original_filename,file_sha256,exam_name,exam_date,score_month,summary_type,period_key,remark,generated_exam_key,created_by) values(?,'DRAFT',?,?,?,?,?,?,?,?,?,?)",
        type,file.getOriginalFilename(),sha,meta.examName(),meta.examDate(),meta.scoreMonth(),meta.summaryType(),meta.periodKey(),meta.remark(),key,SecurityUtils.current().id());
    Long id=lastId();if("EXAM".equals(type))stageExam(id,bytes);else stageEvaluation(id,bytes);
    Integer staged=db.queryForObject("select count(*) from history_import_row where batch_id=?",Integer.class,id);
    if(staged==null||staged==0)throw new BusinessException(400,"导入文件未找到对应页签或有效数据");
    validateBatch(id);Map<String,Object> s=db.queryForMap("select row_count,error_count,warning_count from history_import_batch where id=?",id);
    audit.log("CREATE_HISTORY_IMPORT","HISTORY_IMPORT_BATCH",id,null,Map.of("type",type,"sha256",sha,"generatedExamKey",key));
    return ApiResponse.ok(new CreateResult(id,number(s.get("row_count")),number(s.get("error_count")),number(s.get("warning_count"))));
  }

  @GetMapping
  public ApiResponse<List<Map<String,Object>>> list(@RequestParam(required=false)String type,@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,@RequestParam(defaultValue="200")int size){
    permissions.require(Permissions.HISTORY_IMPORT);StringBuilder q=new StringBuilder("select b.*,u.display_name creator_name,r.display_name reviewer_name from history_import_batch b join sys_user u on u.id=b.created_by left join sys_user r on r.id=b.reviewed_by where 1=1");List<Object>a=new ArrayList<>();
    if(type!=null&&!type.isBlank()){requireType(type);q.append(" and b.import_type=?");a.add(type);}
    if(keyword!=null&&!keyword.isBlank()){q.append(" and (coalesce(b.exam_name,'') like ? or coalesce(b.generated_exam_key,'') like ? or b.original_filename like ?)");String k="%"+keyword.trim()+"%";a.add(k);a.add(k);a.add(k);}
    if(status!=null&&!status.isBlank()){q.append(" and b.status=?");a.add(status.trim());}q.append(" order by b.id desc limit ?");a.add(Math.max(1,Math.min(size,500)));return ApiResponse.ok(db.queryForList(q.toString(),a.toArray()));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String,Object>> detail(@PathVariable Long id){permissions.require(Permissions.HISTORY_IMPORT);var r=new LinkedHashMap<>(db.queryForMap("select * from history_import_batch where id=?",id));r.put("rows",db.queryForList("select * from history_import_row where batch_id=? order by sheet_name,row_no,id",id));return ApiResponse.ok(r);}

  @PostMapping("/{id}/revision") @Transactional
  public ApiResponse<CreateResult> revision(@PathVariable Long id)throws Exception{
    permissions.require(Permissions.HISTORY_IMPORT);var s=db.queryForMap("select * from history_import_batch where id=? for update",id);
    if(!"PUBLISHED".equals(s.get("status")))throw new BusinessException(409,"只有已发布批次可以创建修订");
    String key="HIST-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
    db.update("insert into history_import_batch(import_type,status,original_filename,file_sha256,exam_name,exam_date,score_month,summary_type,period_key,remark,generated_exam_key,revision_of_batch_id,created_by) values(?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?)",
        s.get("import_type"),String.valueOf(s.get("original_filename"))+"-修订","REVISION-"+UUID.randomUUID(),s.get("exam_name"),s.get("exam_date"),s.get("score_month"),s.get("summary_type"),s.get("period_key"),s.get("remark"),key,id,SecurityUtils.current().id());
    Long n=lastId();db.update("insert into history_import_row(batch_id,sheet_name,row_no,row_type,raw_json,normalized_json,employee_no,employee_id,state,row_hash) select ?,sheet_name,row_no,row_type,raw_json,normalized_json,employee_no,employee_id,'ERROR',row_hash from history_import_row where batch_id=?",n,id);validateBatch(n);
    var x=db.queryForMap("select row_count,error_count,warning_count from history_import_batch where id=?",n);audit.log("CREATE_HISTORY_IMPORT_REVISION","HISTORY_IMPORT_BATCH",n,s,Map.of("revisionOfBatchId",id));
    return ApiResponse.ok(new CreateResult(n,number(x.get("row_count")),number(x.get("error_count")),number(x.get("warning_count"))));
  }

  @PostMapping("/{id}/rows") @Transactional
  public ApiResponse<Long> addRow(@PathVariable Long id,@RequestBody RowInput input)throws Exception{permissions.require(Permissions.HISTORY_IMPORT);ensureDraft(id);validateRowType(input.rowType());Long row=insertRow(id,"人工录入",nextRow(id,"人工录入"),input.rowType(),input.data());validateBatch(id);audit.log("ADD_HISTORY_IMPORT_ROW","HISTORY_IMPORT_ROW",row,null,input);return ApiResponse.ok(row);}
  @PutMapping("/{id}/rows/{rowId}") @Transactional
  public ApiResponse<Void> updateRow(@PathVariable Long id,@PathVariable Long rowId,@RequestBody Map<String,Object> data)throws Exception{permissions.require(Permissions.HISTORY_IMPORT);ensureDraft(id);Integer c=db.queryForObject("select count(*) from history_import_row where id=? and batch_id=?",Integer.class,rowId,id);if(c==null||c==0)throw new BusinessException(404,"导入行不存在");String raw=mapper.writeValueAsString(data);db.update("update history_import_row set raw_json=?,row_hash=?,state='ERROR',error_message=null,warning_message=null where id=?",raw,sha256(raw.getBytes(StandardCharsets.UTF_8)),rowId);validateBatch(id);audit.log("UPDATE_HISTORY_IMPORT_ROW","HISTORY_IMPORT_ROW",rowId,null,data);return ApiResponse.ok(null);}
  @PostMapping("/{id}/validate") @Transactional
  public ApiResponse<CreateResult> validate(@PathVariable Long id){permissions.require(Permissions.HISTORY_IMPORT);ensureDraft(id);validateBatch(id);var x=db.queryForMap("select row_count,error_count,warning_count from history_import_batch where id=?",id);return ApiResponse.ok(new CreateResult(id,number(x.get("row_count")),number(x.get("error_count")),number(x.get("warning_count"))));}
  @PostMapping("/{id}/submit") @Transactional
  public ApiResponse<Void> submit(@PathVariable Long id){permissions.require(Permissions.HISTORY_IMPORT);ensureDraft(id);validateBatch(id);int e=db.queryForObject("select error_count from history_import_batch where id=?",Integer.class,id);if(e>0)throw new BusinessException(400,"存在未修复的导入错误");db.update("update history_import_batch set status='SUBMITTED' where id=?",id);audit.log("SUBMIT_HISTORY_IMPORT","HISTORY_IMPORT_BATCH",id,null,null);return ApiResponse.ok(null);}
  @PostMapping("/{id}/publish") @Transactional
  public ApiResponse<Void> publish(@PathVariable Long id)throws Exception{permissions.require(Permissions.HISTORY_IMPORT);requireAdmin();var b=db.queryForMap("select * from history_import_batch where id=? for update",id);if(!Set.of("DRAFT","SUBMITTED").contains(String.valueOf(b.get("status"))))throw new BusinessException(409,"当前批次不可发布");validateBatch(id);int e=db.queryForObject("select error_count from history_import_batch where id=?",Integer.class,id);if(e>0)throw new BusinessException(400,"存在未修复的导入错误");if("EXAM".equals(b.get("import_type")))publishExams(id,b);else publishEvaluations(id,b);Long old=nullableLong(b.get("revision_of_batch_id"));if(old!=null){db.update("update history_import_batch set status='SUPERSEDED',superseded_by_batch_id=? where id=? and status='PUBLISHED'",id,old);}db.update("update history_import_batch set status='PUBLISHED',reviewed_by=?,reviewed_at=now(),published_at=now() where id=?",SecurityUtils.current().id(),id);audit.log("PUBLISH_HISTORY_IMPORT","HISTORY_IMPORT_BATCH",id,b,Map.of("status","PUBLISHED"));return ApiResponse.ok(null);}
  @PostMapping("/{id}/revoke") @Transactional
  public ApiResponse<Void> revoke(@PathVariable Long id){permissions.require(Permissions.HISTORY_IMPORT);requireAdmin();var b=db.queryForMap("select * from history_import_batch where id=? for update",id);if(!"PUBLISHED".equals(b.get("status")))throw new BusinessException(409,"只有已发布批次可以撤销");db.update("update legacy_exam set active=false where batch_id=?",id);db.update("update legacy_exam_result set active=false where batch_id=?",id);db.update("update legacy_evaluation set active=false where batch_id=?",id);db.update("update score_summary set status='REVOKED' where source_type='HISTORICAL' and source_batch_id=?",id);db.update("update history_import_batch set status='REVOKED',revoked_at=now(),reviewed_by=?,reviewed_at=now() where id=?",SecurityUtils.current().id(),id);audit.log("REVOKE_HISTORY_IMPORT","HISTORY_IMPORT_BATCH",id,b,Map.of("status","REVOKED"));return ApiResponse.ok(null);}

  private void stageExam(Long id,byte[] bytes)throws Exception{for(var r:read(bytes,HistoryImportRows.ExamResult.class,"历史成绩")){Map<String,Object>d=new LinkedHashMap<>();d.put("employeeNo",r.getEmployeeNo());d.put("employeeName",r.getEmployeeName());d.put("score",r.getScore());d.put("resultStatus",text(r.getScore()).isBlank()?"ABSENT":"COMPLETED");d.put("attemptNo","1");insertRow(id,"历史成绩",nextRow(id,"历史成绩"),"EXAM_RESULT",d);}}
  private void stageEvaluation(Long id,byte[] bytes)throws Exception{Map<String,Boolean> parents=new HashMap<>();for(var r:read(bytes,HistoryImportRows.EvaluationFlat.class,"历史评价")){Map<String,Object>c=mapper.convertValue(r,new TypeReference<>(){});String k=text(c.get("employeeNo"))+"|"+text(c.get("summaryType"))+"|"+text(c.get("periodKey"));if(!parents.containsKey(k)){Map<String,Object>p=new LinkedHashMap<>();p.put("employeeNo",c.get("employeeNo"));p.put("employeeName",c.get("employeeName"));p.put("summaryType",c.get("summaryType"));p.put("periodKey",c.get("periodKey"));p.put("finalScore",c.get("finalScore"));insertRow(id,"历史评价",nextRow(id,"历史评价"),"EVALUATION",p);parents.put(k,true);}insertRow(id,"历史评价",nextRow(id,"历史评价"),"EVALUATION_COMPONENT",c);}}
  private <T>List<T> read(byte[]b,Class<T>t,String s){try{return EasyExcel.read(new ByteArrayInputStream(b)).head(t).sheet(s).headRowNumber(1).doReadSync();}catch(Exception e){return List.of();}}
  private Long insertRow(Long id,String sheet,int no,String type,Map<String,Object>d)throws Exception{String raw=mapper.writeValueAsString(d);db.update("insert into history_import_row(batch_id,sheet_name,row_no,row_type,raw_json,row_hash) values(?,?,?,?,?,?)",id,sheet,no,type,raw,sha256(raw.getBytes(StandardCharsets.UTF_8)));return lastId();}

  private void validateBatch(Long id){
    String kind=db.queryForObject("select import_type from history_import_batch where id=?",String.class,id);var rows=db.queryForList("select * from history_import_row where batch_id=? order by id",id);Map<String,Map<String,Object>> evals=new HashMap<>();Map<String,List<Map<String,Object>>> cs=new HashMap<>();
    for(var r:rows){Map<String,Object>d=jsonMap(String.valueOf(r.get("raw_json")));if("EVALUATION".equals(r.get("row_type"))){evals.put(key(d),d);}else if("EVALUATION_COMPONENT".equals(r.get("row_type"))){cs.computeIfAbsent(key(d),x->new ArrayList<>()).add(d);}}
    int errors=0,warnings=0,valid=0;Set<String> seen=new HashSet<>();
    for(var r:rows){Map<String,Object>d=jsonMap(String.valueOf(r.get("raw_json")));List<String>es=new ArrayList<>(),ws=new ArrayList<>();String type=String.valueOf(r.get("row_type"));String dup=switch(type){case"EXAM_RESULT"->"RESULT|"+text(d.get("employeeNo"));case"EVALUATION"->"EVAL|"+key(d);case"EVALUATION_COMPONENT"->"COMP|"+key(d)+"|"+text(d.get("componentCode"))+"|"+text(d.get("sourceKey"))+"|"+text(d.get("evaluatorName"));default->type+"|"+r.get("id");};if(!seen.add(dup))es.add("文件内存在重复记录");
      if("EXAM".equals(kind)&&"EXAM_RESULT".equals(type))validateExamResult(d,es,ws);else if("EVALUATION".equals(kind)&&"EVALUATION".equals(type)){validateEvaluation(d,es);var list=cs.getOrDefault(key(d),List.of());if(list.isEmpty())es.add("评价记录至少需要一条评分明细");else{BigDecimal total=list.stream().map(x->decimalNullable(x,"weightedScore")).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal f=decimalNullable(d,"finalScore");if(f!=null&&total.subtract(f).abs().compareTo(new BigDecimal("0.02"))>0)es.add("评分明细加权合计与最终分数不一致");}}else if("EVALUATION".equals(kind)&&"EVALUATION_COMPONENT".equals(type))validateComponent(d,evals,es);else es.add("导入行类型与批次类型不一致");
      String state=es.isEmpty()?(ws.isEmpty()?"VALID":"WARNING"):"ERROR";if(!es.isEmpty())errors++;else{valid++;if(!ws.isEmpty())warnings++;}String no=text(d.get("employeeNo"));db.update("update history_import_row set normalized_json=?,employee_no=?,employee_id=?,state=?,error_message=?,warning_message=? where id=?",toJson(d),blank(no,null),findEmployee(no),state,join(es),join(ws),r.get("id"));}
    db.update("update history_import_batch set row_count=?,valid_count=?,error_count=?,warning_count=? where id=?",rows.size(),valid,errors,warnings,id);
  }
  private void validateExamResult(Map<String,Object>d,List<String>es,List<String>ws){req(d,"employeeNo","工号",es);Long eid=findEmployee(text(d.get("employeeNo")));if(eid==null)es.add("工号不存在");else if(!text(d.get("employeeName")).isBlank()&&!Objects.equals(employeeName(eid),text(d.get("employeeName"))))ws.add("姓名与员工档案不一致");String s=text(d.get("score"));if(s.isBlank()){d.put("resultStatus","ABSENT");d.put("score",null);}else{BigDecimal n=decimal(d,"score",es);if(n!=null&&(n.signum()<0||n.compareTo(BigDecimal.valueOf(100))>0))es.add("成绩必须在0至100之间");d.put("resultStatus","COMPLETED");}}
  private void validateEvaluation(Map<String,Object>d,List<String>es){req(d,"employeeNo","工号",es);req(d,"summaryType","评价类型",es);req(d,"periodKey","期间",es);if(!Set.of("MONTH","QUARTER").contains(text(d.get("summaryType"))))es.add("评价类型必须为 MONTH 或 QUARTER");String p=text(d.get("periodKey"));if("MONTH".equals(text(d.get("summaryType")))&&!p.matches("20\\d{2}-[01]\\d"))es.add("月度期间格式应为 yyyy-MM");if("QUARTER".equals(text(d.get("summaryType")))&&!p.matches("20\\d{2}-Q[1-4]"))es.add("季度期间格式应为 yyyy-Q1 至 yyyy-Q4");BigDecimal f=decimal(d,"finalScore",es);if(f!=null&&(f.signum()<0||f.compareTo(BigDecimal.valueOf(100))>0))es.add("最终分数必须在0至100之间");if(findEmployee(text(d.get("employeeNo")))==null)es.add("工号不存在");}
  private void validateComponent(Map<String,Object>d,Map<String,Map<String,Object>>evals,List<String>es){if(!evals.containsKey(key(d)))es.add("找不到对应的历史评价");if(!COMPONENTS.contains(text(d.get("componentCode"))))es.add("评分项编码不合法");BigDecimal raw=decimal(d,"rawScore",es),max=decimal(d,"maxScore",es),w=decimal(d,"weight",es),weighted=decimal(d,"weightedScore",es);if(raw!=null&&max!=null&&(max.signum()<=0||raw.compareTo(max)>0))es.add("原始分不能超过满分");if(w!=null&&(w.signum()<0||w.compareTo(BigDecimal.valueOf(100))>0))es.add("权重必须在0至100之间");if(raw!=null&&max!=null&&w!=null&&weighted!=null&&raw.multiply(w).divide(max,2,java.math.RoundingMode.HALF_UP).subtract(weighted).abs().compareTo(new BigDecimal("0.02"))>0)es.add("加权分与原始分、满分、权重不一致");}

  private void publishExams(Long id,Map<String,Object>b){deactivateRevision(b);String key=text(b.get("generated_exam_key"));Integer v=db.queryForObject("select coalesce(max(version),0)+1 from legacy_exam where external_exam_key=?",Integer.class,key);db.update("insert into legacy_exam(batch_id,external_exam_key,name,exam_date,score_month,max_score,remark,version) values(?,?,?,?,?,?,?,?)",id,key,text(b.get("exam_name")),date(b.get("exam_date")),date(b.get("score_month")),BigDecimal.valueOf(100),blank(text(b.get("remark")),null),v);Long exam=lastId();for(var r:db.queryForList("select raw_json,employee_id from history_import_row where batch_id=? and row_type='EXAM_RESULT' and state in ('VALID','WARNING')",id)){Map<String,Object>d=jsonMap(String.valueOf(r.get("raw_json")));Long eid=((Number)r.get("employee_id")).longValue();db.update("insert into legacy_exam_result(exam_id,batch_id,employee_id,employee_no_snapshot,employee_name_snapshot,result_status,score,objective_score,subjective_score,attempt_no,taken_at,remark) values(?,?,?,?,?,?,?,?,?,?,?,?)",exam,id,eid,text(d.get("employeeNo")),employeeName(eid),text(d.get("resultStatus")),decimalNullable(d,"score"),decimalNullable(d,"score"),null,1,null,blank(text(b.get("remark")),null));}}
  private void publishEvaluations(Long id,Map<String,Object>b)throws Exception{deactivateRevision(b);for(var r:db.queryForList("select raw_json,employee_id from history_import_row where batch_id=? and row_type='EVALUATION' and state in ('VALID','WARNING')",id)){Map<String,Object>d=jsonMap(String.valueOf(r.get("raw_json")));Long eid=((Number)r.get("employee_id")).longValue();String type=text(d.get("summaryType")),period=text(d.get("periodKey"));db.update("update legacy_evaluation set active=false where employee_id=? and summary_type=? and period_key=? and active=true",eid,type,period);db.update("update score_summary set status='REVOKED' where employee_id=? and summary_type=? and period_key=? and source_type='HISTORICAL' and status='PUBLISHED'",eid,type,period);db.update("insert into legacy_evaluation(batch_id,employee_id,employee_no_snapshot,summary_type,period_key,original_final_score,remark) values(?,?,?,?,?,?,?)",id,eid,text(d.get("employeeNo")),type,period,decimalNullable(d,"finalScore"),null);Long legacy=lastId();List<Map<String,Object>>components=new ArrayList<>();for(var cr:db.queryForList("select raw_json from history_import_row where batch_id=? and row_type='EVALUATION_COMPONENT' and employee_no=? and state in ('VALID','WARNING')",id,text(d.get("employeeNo")))){Map<String,Object>c=jsonMap(String.valueOf(cr.get("raw_json")));if(!type.equals(text(c.get("summaryType")))||!period.equals(text(c.get("periodKey"))))continue;db.update("insert into legacy_evaluation_component(evaluation_id,component_code,source_key,source_name,raw_score,max_score,weight,weighted_score,evaluator_name,comment,snapshot_json) values(?,?,?,?,?,?,?,?,?,?,cast(? as json))",legacy,text(c.get("componentCode")),blank(text(c.get("sourceKey")),null),blank(text(c.get("sourceName")),null),decimalNullable(c,"rawScore"),decimalNullable(c,"maxScore"),decimalNullable(c,"weight"),decimalNullable(c,"weightedScore"),blank(text(c.get("evaluatorName")),null),blank(text(c.get("comment")),null),toJson(c));components.add(c);}int version=db.queryForObject("select coalesce(max(version),0)+1 from score_summary where employee_id=? and summary_type=? and period_key=?",Integer.class,eid,type,period);Map<String,Object>snap=new LinkedHashMap<>();snap.put("source","HISTORICAL");snap.put("components",components);snap.put("originalFinalScore",decimalNullable(d,"finalScore"));db.update("insert into score_summary(employee_id,summary_type,period_key,version,source_type,source_batch_id,read_only,exam_score,task_score,mentor_score,station_score,training_score,bonus,deduction,final_score,status,component_snapshot,generated_at,published_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as json),now(),now())",eid,type,period,version,"HISTORICAL",id,true,componentScore(components,"EXAM"),componentScore(components,"TASK"),componentScore(components,"MENTOR"),componentScore(components,"STATION"),componentScore(components,"TRAINING"),componentScore(components,"BONUS"),componentScore(components,"DEDUCTION"),decimalNullable(d,"finalScore"),"PUBLISHED",toJson(snap));}}
  private void deactivateRevision(Map<String,Object>b){Long old=nullableLong(b.get("revision_of_batch_id"));if(old==null)return;db.update("update legacy_exam set active=false where batch_id=?",old);db.update("update legacy_exam_result set active=false where batch_id=?",old);db.update("update legacy_evaluation set active=false where batch_id=?",old);db.update("update score_summary set status='REVOKED' where source_type='HISTORICAL' and source_batch_id=?",old);}

  private Metadata parseMetadata(String type,Map<String,Object>m){if("EXAM".equals(type)){String n=text(m.get("examName")),raw=text(m.get("examDate"));if(n.isBlank())throw new BusinessException(400,"考试名称不能为空");if(raw.isBlank())throw new BusinessException(400,"考试日期不能为空");List<String>e=new ArrayList<>();LocalDate d=date(raw,e);if(d==null)throw new BusinessException(400,join(e));return new Metadata(n,d,d.withDayOfMonth(1),null,null,blank(text(m.get("remark")),null));}String st=text(m.get("summaryType")),period=text(m.get("periodKey"));if(!Set.of("MONTH","QUARTER").contains(st))throw new BusinessException(400,"评价类型必须为 MONTH 或 QUARTER");if(("MONTH".equals(st)&&!period.matches("20\\d{2}-[01]\\d"))||("QUARTER".equals(st)&&!period.matches("20\\d{2}-Q[1-4]")))throw new BusinessException(400,"评价期间格式不正确");return new Metadata(null,null,null,st,period,blank(text(m.get("remark")),null));}
  private void requireType(String t){permissions.require(Permissions.HISTORY_IMPORT);if(!TYPES.contains(t))throw new BusinessException(400,"不支持的历史数据类型");}
  private void ensureDraft(Long id){String s=db.queryForObject("select status from history_import_batch where id=?",String.class,id);if(s==null)throw new BusinessException(404,"导入批次不存在");if(!Set.of("DRAFT","SUBMITTED").contains(s))throw new BusinessException(409,"当前批次不可修改");}
  private void requireAdmin(){if(!Set.of("ADMIN","SUPER_ADMIN").contains(SecurityUtils.current().role()))throw new BusinessException(403,"仅管理员可发布或撤销历史数据");}
  private void validateRowType(String t){if(!Set.of("EXAM_RESULT","EVALUATION","EVALUATION_COMPONENT").contains(t))throw new BusinessException(400,"不支持的行类型");}
  private int nextRow(Long id,String sheet){Integer n=db.queryForObject("select coalesce(max(row_no),0)+1 from history_import_row where batch_id=? and sheet_name=?",Integer.class,id,sheet);return n==null?1:n;}
  private Long findEmployee(String no){if(no==null||no.isBlank())return null;var r=db.queryForList("select id from employee where employee_no=?",no.trim());return r.isEmpty()?null:((Number)r.get(0).get("id")).longValue();}
  private String employeeName(Long id){return db.queryForObject("select name from employee where id=?",String.class,id);}
  private static String text(Object x){return x==null?"":String.valueOf(x).trim();} private static String blank(String x,String f){return x==null||x.isBlank()?f:x.trim();} private static String join(List<String>x){return x.isEmpty()?null:String.join("；",x);} private static int number(Object x){return x==null?0:((Number)x).intValue();}
  private static String key(Map<String,Object>d){return text(d.get("employeeNo"))+"|"+text(d.get("summaryType"))+"|"+text(d.get("periodKey"));} private static void req(Map<String,Object>d,String k,String l,List<String>e){if(text(d.get(k)).isBlank())e.add(l+"不能为空");}
  private static BigDecimal decimal(Map<String,Object>d,String k,List<String>e){String x=text(d.get(k));if(x.isBlank()){e.add(k+"不能为空");return null;}try{return new BigDecimal(x);}catch(Exception ex){e.add(k+"必须为数字");return null;}}
  private static BigDecimal decimalNullable(Map<String,Object>d,String k){String x=text(d.get(k));if(x.isBlank())return null;try{return new BigDecimal(x);}catch(Exception e){return null;}}
  private static LocalDate date(Object x){return date(text(x),new ArrayList<>());} private static LocalDate date(String x,List<String>e){try{return LocalDate.parse(x.length()>=10?x.substring(0,10):x);}catch(Exception ex){e.add("日期格式应为 yyyy-MM-dd");return null;}}
  private static Map<String,Object> map(Object x){return x instanceof Map<?,?>m?(Map<String,Object>)m:new LinkedHashMap<>();}
  private Map<String,Object> jsonMap(String x){try{return mapper.readValue(x==null?"{}":x,new TypeReference<>(){});}catch(Exception e){return new LinkedHashMap<>();}}
  private String toJson(Object x){try{return mapper.writeValueAsString(x);}catch(Exception e){throw new BusinessException(500,"历史数据序列化失败");}} private Long lastId(){return db.queryForObject("select last_insert_id()",Long.class);}
  private static Long nullableLong(Object x){return x instanceof Number n?n.longValue():null;} private static BigDecimal componentScore(List<Map<String,Object>>cs,String code){for(var c:cs)if(code.equals(text(c.get("componentCode"))))return decimalNullable(c,"rawScore");return null;}
  private static String sha256(byte[]b){try{var md=MessageDigest.getInstance("SHA-256");var o=md.digest(b);var s=new StringBuilder();for(byte x:o)s.append(String.format("%02x",x));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
