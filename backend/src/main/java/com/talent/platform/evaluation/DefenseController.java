package com.talent.platform.evaluation;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.format.NumberFormat;
import com.talent.platform.common.*;
import com.talent.platform.security.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/v1/evaluation/defenses")
public class DefenseController {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final EvaluationService evaluation;
  private final EvaluationRevisionService revisions;
  private final AuditService audit;
  public DefenseController(JdbcTemplate db,PermissionService permissions,EvaluationService evaluation,EvaluationRevisionService revisions,AuditService audit){this.db=db;this.permissions=permissions;this.evaluation=evaluation;this.revisions=revisions;this.audit=audit;}

  public record Entry(@NotNull Long employeeId,@NotNull Long sessionId,@NotNull @Digits(integer=5,fraction=2) @DecimalMin("0") BigDecimal score,@Size(max=1000) String comment,Long revisionOf){}
  public record SessionRequest(@NotNull @Pattern(regexp="[0-9]{4}-Q[1-4]") String periodKey,@NotNull @Min(0) Integer version){}
  @Data public static class ExcelRow {
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("姓名") private String employeeName;
    @ExcelProperty("答辩成绩") private String score;
    @ExcelProperty("评价意见") private String comment;
  }
  @Data public static class ExportRow {
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("姓名") private String employeeName;
    @ExcelProperty("培养批次") private String batch;
    @ExcelProperty("答辩场次") private String stage;
    @ExcelProperty("对应评价季度") private String quarter;
    @ExcelProperty("答辩成绩") @NumberFormat("0.00") private BigDecimal score;
    @ExcelProperty("满分") @NumberFormat("0.00") private BigDecimal fullScore;
    @ExcelProperty("状态") private String status;
    @ExcelProperty("评价意见") private String comment;
  }
  private String label(int stage){return "第"+List.of("一","二","三","四").get(stage-1)+"季度答辩";}
  private void requireBatch(Long batch){
    var filter=permissions.employeeFilter("e");
    if(!"ALL".equals(SecurityUtils.current().dataScope())){
      var args=new ArrayList<Object>();args.add(batch);args.addAll(filter.args());
      if(db.queryForObject("select count(*) from employee e where e.batch_id=?"+filter.sql(),Integer.class,args.toArray())==0)throw new BusinessException(403,"无权访问该培养批次");
    }
  }
  private Map<String,Object> session(Long id){var rows=db.queryForList("select s.*,b.name batch_name from defense_session s join talent_batch b on b.id=s.talent_batch_id where s.id=?",id);if(rows.isEmpty())throw new BusinessException(404,"答辩场次未配置");var row=new LinkedHashMap<>(rows.get(0));requireBatch(((Number)row.get("talent_batch_id")).longValue());row.put("label",label(((Number)row.get("stage_no")).intValue()));return row;}
  private Map<String,Object> lockSession(Long id){db.queryForMap("select id from defense_session where id=? for update",id);return session(id);}
  private BigDecimal fullScore(Long employee,Map<String,Object> session){
    var scheme=evaluation.quarterSchemeFor(employee,session.get("period_key").toString());
    if(!"QUARTER".equals(scheme.get("defense_scope")))throw new BusinessException(400,"请先发布包含季度答辩设置的新评价方案；原月度方案仅保留历史规则");
    return new BigDecimal(scheme.get("defense_max_score").toString());
  }
  @GetMapping("/sessions") public ApiResponse<?> sessions(@RequestParam Long batchId){
    permissions.require(Permissions.EVALUATION_VIEW);requireBatch(batchId);db.queryForMap("select id from talent_batch where id=?",batchId);
    var saved=db.queryForList("select * from defense_session where talent_batch_id=? order by stage_no",batchId);var result=new ArrayList<Map<String,Object>>();
    for(int stage=1;stage<=4;stage++){final int current=stage;var item=new LinkedHashMap<String,Object>(saved.stream().filter(x->((Number)x.get("stage_no")).intValue()==current).findFirst().orElse(Map.of()));item.put("stage_no",stage);item.put("label",label(stage));item.putIfAbsent("version",0);result.add(item);}return ApiResponse.ok(result);
  }
  @PutMapping("/sessions/{batch}/{stage}") @Transactional public ApiResponse<?> configure(@PathVariable Long batch,@PathVariable int stage,@Valid @RequestBody SessionRequest request){
    permissions.require(Permissions.EVALUATION_MANAGE);requireBatch(batch);if(stage<1||stage>4)throw new BusinessException(400,"每届仅设第一至第四季度答辩");
    db.queryForMap("select id from talent_batch where id=? for update",batch);
    var old=db.queryForList("select * from defense_session where talent_batch_id=? and stage_no=? for update",batch,stage);Long id=old.isEmpty()?null:((Number)old.get(0).get("id")).longValue();
    if((old.isEmpty()?0:((Number)old.get(0).get("version")).intValue())!=request.version())throw new BusinessException(409,"答辩场次已被修改，请刷新");
    if(db.queryForObject("select count(*) from defense_session where talent_batch_id=? and period_key=? and stage_no<>?",Integer.class,batch,request.periodKey(),stage)>0)throw new BusinessException(400,"该届已有答辩对应此评价季度，请选择其他季度");
    if(id!=null&&!request.periodKey().equals(old.get(0).get("period_key"))&&db.queryForObject("select count(*) from defense_result where session_id=? and status in ('PUBLISHED','SUPERSEDED')",Integer.class,id)>0)throw new BusinessException(409,"该场次已有发布记录，对应季度已锁定；成绩更正请使用修订");
    if(id==null){db.update("insert into defense_session(talent_batch_id,stage_no,period_key,version,created_by) values(?,?,?,1,?)",batch,stage,request.periodKey(),SecurityUtils.current().id());id=db.queryForObject("select last_insert_id()",Long.class);}
    else{db.update("update defense_session set period_key=?,version=version+1 where id=?",request.periodKey(),id);db.update("update defense_result set period_month=?,status='DRAFT',error_message=null where session_id=? and status in ('DRAFT','VALID','ERROR')",EvaluationService.quarterEnd(request.periodKey()).atDay(1),id);db.update("update defense_import_batch set period_month=? where session_id=?",EvaluationService.quarterEnd(request.periodKey()).atDay(1),id);}
    audit.log("CONFIGURE_QUARTER_DEFENSE","DEFENSE_SESSION",id,old,request);return ApiResponse.ok(session(id));
  }

  @GetMapping public ApiResponse<?> list(@RequestParam Long sessionId,@RequestParam(required=false)Long businessUnitId,@RequestParam(required=false)Long classId,@RequestParam(required=false)String keyword,@RequestParam(required=false)String status){permissions.require(Permissions.EVALUATION_VIEW);return ApiResponse.ok(rows(session(sessionId),businessUnitId,classId,keyword,status));}
  private List<Map<String,Object>> rows(Map<String,Object> session,Long business,Long classId,String keyword,String status){
    var scope=permissions.employeeFilter("e");var args=new ArrayList<Object>();args.add(session.get("id"));args.add(session.get("talent_batch_id"));args.addAll(scope.args());
    String sql="select e.id employee_id,e.employee_no,e.name employee_name,e.batch_id,b.name batch_name,bu.name business_unit_name,c.label class_name,d.id,d.score,d.full_score,d.comment,d.version,d.import_batch_id,coalesce(d.status,'NOT_ENTERED') status,d.updated_at from employee e left join talent_batch b on b.id=e.batch_id left join business_unit bu on bu.id=e.business_unit_id left join dictionary_item c on c.id=e.class_id left join defense_result d on d.id=(select x.id from defense_result x where x.employee_id=e.id and x.session_id=?";
    sql+="EMPLOYEE".equals(SecurityUtils.current().role())?" and x.status='PUBLISHED'":" and x.status in ('DRAFT','VALID','PUBLISHED')";
    sql+=" order by x.version desc,x.id desc limit 1) where e.status='ACTIVE' and e.batch_id=?"+scope.sql();
    if(business!=null){sql+=" and e.business_unit_id=?";args.add(business);}if(classId!=null){sql+=" and e.class_id=?";args.add(classId);}
    if(keyword!=null&&!keyword.isBlank()){sql+=" and (e.name like ? or e.employee_no like ?)";args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
    if(status!=null&&!status.isBlank()){sql+=" and coalesce(d.status,'NOT_ENTERED')=?";args.add(status);}
    var result=db.queryForList(sql+" order by e.employee_no",args.toArray());
    for(var row:result){row.put("session_id",session.get("id"));row.put("stage_label",session.get("label"));row.put("period_key",session.get("period_key"));if(row.get("full_score")==null)try{row.put("full_score",fullScore(((Number)row.get("employee_id")).longValue(),session));}catch(BusinessException error){row.put("configuration_error",error.getMessage());}}
    return result;
  }
  @GetMapping("/history") public ApiResponse<?> history(@RequestParam Long employeeId,@RequestParam(required=false)Long sessionId,@RequestParam(required=false)YearMonth month){
    permissions.require(Permissions.EVALUATION_VIEW);permissions.requireEmployee(employeeId);
    if(sessionId==null&&month==null)throw new BusinessException(400,"请选择答辩场次");if(sessionId!=null)session(sessionId);
    return ApiResponse.ok(db.queryForList("select * from defense_result where employee_id=? and "+(sessionId!=null?"session_id=?":"session_id is null and period_month=?")+("EMPLOYEE".equals(SecurityUtils.current().role())?" and status in ('PUBLISHED','SUPERSEDED')":"")+" order by version desc,id desc",employeeId,sessionId!=null?sessionId:month.atDay(1)));
  }
  @PostMapping @Transactional public ApiResponse<?> save(@Valid @RequestBody Entry entry){
    permissions.require(Permissions.EVALUATION_MANAGE);permissions.requireEmployee(entry.employeeId());var session=lockSession(entry.sessionId());
    var employee=db.queryForMap("select id,employee_no,name,batch_id from employee where id=? for update",entry.employeeId());
    if(!Objects.equals(employee.get("batch_id"),session.get("talent_batch_id")))throw new BusinessException(400,"员工不属于该届答辩范围");
    var current=db.queryForList("select id from defense_result where employee_id=? and session_id=? and status='PUBLISHED'",entry.employeeId(),entry.sessionId());
    if(!current.isEmpty()&&!Objects.equals(((Number)current.get(0).get("id")).longValue(),entry.revisionOf()))throw new BusinessException(409,"该员工本季度已有有效成绩，请明确选择修订");
    BigDecimal full=fullScore(entry.employeeId(),session);ScoreValues.require(entry.score(),full);
    var draft=db.queryForList("select id from defense_result where employee_id=? and session_id=? and import_batch_id is null and status in ('DRAFT','VALID') order by id desc limit 1",entry.employeeId(),entry.sessionId());Long id;
    if(draft.isEmpty()){
      int version=db.queryForObject("select coalesce(max(version),0)+1 from defense_result where employee_id=? and session_id=?",Integer.class,entry.employeeId(),entry.sessionId());
      db.update("insert into defense_result(employee_id,employee_no,employee_name,period_month,session_id,score,raw_score,full_score,comment,revision_of,version,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?)",entry.employeeId(),employee.get("employee_no"),employee.get("name"),EvaluationService.quarterEnd(session.get("period_key").toString()).atDay(1),entry.sessionId(),entry.score(),entry.score().toPlainString(),full,entry.comment(),entry.revisionOf(),version,SecurityUtils.current().id());id=db.queryForObject("select last_insert_id()",Long.class);
    }else{id=((Number)draft.get(0).get("id")).longValue();db.update("update defense_result set score=?,raw_score=?,full_score=?,comment=?,revision_of=?,status='DRAFT',error_message=null where id=?",entry.score(),entry.score().toPlainString(),full,entry.comment(),entry.revisionOf(),id);}
    audit.log("SAVE_DEFENSE_DRAFT","DEFENSE",id,null,entry);return ApiResponse.ok(id);
  }
  @PostMapping("/{id}/validate") @Transactional public ApiResponse<?> validate(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(validateRow(id,new HashSet<>()));}
  private Map<String,Object> validateRow(Long id,Set<String> seen){
    var binding=db.queryForMap("select session_id from defense_result where id=?",id);if(binding.get("session_id")==null)throw new BusinessException(400,"历史月度答辩记录只读，请进入季度答辩场次录入");var session=lockSession(((Number)binding.get("session_id")).longValue());var row=db.queryForMap("select * from defense_result where id=? for update",id);
    if(!Set.of("DRAFT","VALID","ERROR").contains(row.get("status")))throw new BusinessException(409,"当前记录不可校验或发布");
    List<String> errors=new ArrayList<>();String no=row.get("employee_no").toString().trim();var employees=db.queryForList("select id,name,batch_id from employee where employee_no=? and status='ACTIVE'",no);Long employee=null;BigDecimal score=null,full=new BigDecimal("100");
    if(!seen.add(no))errors.add("同一导入批次工号重复");
    if(employees.isEmpty())errors.add("工号不存在或员工已停用");else{
      var e=employees.get(0);employee=((Number)e.get("id")).longValue();permissions.requireEmployee(employee);
      if(!Objects.equals(e.get("batch_id"),session.get("talent_batch_id")))errors.add("员工不属于该届答辩范围");
      String name=Objects.toString(row.get("employee_name"),"").trim();if(!name.isEmpty()&&!name.equals(e.get("name")))errors.add("姓名与工号不一致");
      try{full=fullScore(employee,session);}catch(BusinessException ex){errors.add(ex.getMessage());}
      var published=db.queryForList("select id from defense_result where employee_id=? and session_id=? and status='PUBLISHED'",employee,row.get("session_id"));if(!published.isEmpty()&&!Objects.equals(row.get("revision_of"),published.get(0).get("id")))errors.add("已有有效成绩，需明确修订");
    }
    try{String raw=Objects.toString(row.get("raw_score"),"").trim();if(raw.isEmpty())throw new BusinessException(400,"答辩成绩不能为空");score=ScoreValues.require(new BigDecimal(raw),full);}catch(Exception ex){errors.add(ex instanceof BusinessException?ex.getMessage():"成绩不是有效数字");}
    db.update("update defense_result set employee_id=?,score=?,full_score=?,status=?,error_message=? where id=?",employee,score,full,errors.isEmpty()?"VALID":"ERROR",errors.isEmpty()?null:String.join("；",errors),id);return db.queryForMap("select * from defense_result where id=?",id);
  }
  @PostMapping("/{id}/publish") @Transactional public ApiResponse<?> publish(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);var source=db.queryForMap("select import_batch_id from defense_result where id=?",id);if(source.get("import_batch_id")!=null)throw new BusinessException(400,"请在导入校验中整批发布该成绩");var row=validateRow(id,new HashSet<>());if(!"VALID".equals(row.get("status")))throw new BusinessException(400,Objects.toString(row.get("error_message")));publishRow(row);return ApiResponse.ok(null);}
  private void publishRow(Map<String,Object> row){
    long employee=((Number)row.get("employee_id")).longValue();db.queryForMap("select id from employee where id=? for update",employee);var session=session(((Number)row.get("session_id")).longValue());
    var old=db.queryForList("select id from defense_result where employee_id=? and session_id=? and status='PUBLISHED' for update",employee,row.get("session_id"));if(!old.isEmpty()&&!Objects.equals(old.get(0).get("id"),row.get("revision_of")))throw new BusinessException(409,"有效成绩已变化，请刷新后修订");
    int version=db.queryForObject("select coalesce(max(version),0)+1 from defense_result where employee_id=? and session_id=? and status in ('PUBLISHED','SUPERSEDED')",Integer.class,employee,row.get("session_id"));
    db.update("update defense_result set status='SUPERSEDED' where employee_id=? and session_id=? and status='PUBLISHED'",employee,row.get("session_id"));db.update("update defense_result set status='PUBLISHED',published_at=now(),version=? where id=?",version,row.get("id"));
    revisions.quarterChanged(employee,session.get("period_key").toString(),"发布季度答辩成绩 #"+row.get("id"));audit.log("PUBLISH_DEFENSE","DEFENSE",((Number)row.get("id")).longValue(),null,row);
  }
  @GetMapping("/template") public void template(HttpServletResponse response)throws Exception{permissions.require(Permissions.EVALUATION_MANAGE);excel(response,"季度答辩成绩导入模板.xlsx");EasyExcel.write(response.getOutputStream(),ExcelRow.class).sheet("答辩成绩").doWrite(List.of());}
  @PostMapping("/imports") @Transactional public ApiResponse<?> upload(@RequestParam Long sessionId,@RequestParam(defaultValue="false")boolean revision,@RequestParam MultipartFile file)throws Exception{
    permissions.require(Permissions.EVALUATION_MANAGE);var session=lockSession(sessionId);if(file.isEmpty()||file.getSize()>20*1024*1024)throw new BusinessException(400,"请选择20MB以内的Excel文件");byte[] bytes=file.getBytes();String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    db.queryForMap("select id from defense_session where id=? for update",sessionId);
    var old=db.queryForList("select id from defense_import_batch where session_id=? and file_sha256=?",sessionId,sha);
    if(!old.isEmpty()){Long existing=((Number)old.get(0).get("id")).longValue();if(revision&&"DRAFT".equals(db.queryForObject("select status from defense_import_batch where id=?",String.class,existing))){for(var row:db.queryForList("select id,employee_no from defense_result where import_batch_id=?",existing))db.update("update defense_result set revision_of=? where id=?",currentGrade(row.get("employee_no"),sessionId),row.get("id"));validateImport(existing);}return ApiResponse.ok(importDetail(existing));}
    List<ExcelRow> rows=EasyExcel.read(new java.io.ByteArrayInputStream(bytes)).head(ExcelRow.class).sheet(0).doReadSync();if(rows.isEmpty()||rows.size()>10000)throw new BusinessException(400,"导入文件为空或超过10000行");
    db.update("insert into defense_import_batch(talent_batch_id,period_month,session_id,file_sha256,original_filename,created_by) values(?,?,?,?,?,?)",session.get("talent_batch_id"),EvaluationService.quarterEnd(session.get("period_key").toString()).atDay(1),sessionId,sha,file.getOriginalFilename(),SecurityUtils.current().id());Long id=db.queryForObject("select last_insert_id()",Long.class);int number=1;
    for(var r:rows)db.update("insert into defense_result(employee_no,employee_name,period_month,session_id,raw_score,comment,import_batch_id,row_no,revision_of,created_by) values(?,?,?,?,?,?,?,?,?,?)",Objects.toString(r.employeeNo,"").trim(),r.employeeName,EvaluationService.quarterEnd(session.get("period_key").toString()).atDay(1),sessionId,r.score,r.comment,id,++number,revision?currentGrade(Objects.toString(r.employeeNo,"").trim(),sessionId):null,SecurityUtils.current().id());
    validateImport(id);return ApiResponse.ok(importDetail(id));
  }
  private Object currentGrade(Object employeeNo,Long session){var rows=db.queryForList("select d.id from defense_result d join employee e on e.id=d.employee_id where e.employee_no=? and d.session_id=? and d.status='PUBLISHED'",employeeNo,session);return rows.isEmpty()?null:rows.get(0).get("id");}
  @GetMapping("/imports/{id}") public ApiResponse<?> imported(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);return ApiResponse.ok(importDetail(id));}
  private Map<String,Object> importDetail(Long id){var result=new LinkedHashMap<>(db.queryForMap("select * from defense_import_batch where id=?",id));if(result.get("session_id")!=null){var session=session(((Number)result.get("session_id")).longValue());result.put("stage_label",session.get("label"));result.put("period_key",session.get("period_key"));}result.put("rows",db.queryForList("select * from defense_result where import_batch_id=? order by row_no",id));return result;}
  @PutMapping("/imports/{batch}/rows/{id}") @Transactional public ApiResponse<?> editRow(@PathVariable Long batch,@PathVariable Long id,@RequestBody Map<String,String> body){permissions.require(Permissions.EVALUATION_MANAGE);requireImportDraft(batch);if(db.queryForObject("select count(*) from defense_result where id=? and import_batch_id=?",Integer.class,id,batch)!=1)throw new BusinessException(404,"导入行不存在");db.update("update defense_result set employee_no=?,employee_name=?,raw_score=?,comment=?,status='DRAFT' where id=?",body.get("employee_no"),body.get("employee_name"),body.get("raw_score"),body.get("comment"),id);validateImport(batch);return ApiResponse.ok(importDetail(batch));}
  @PostMapping("/imports/{id}/validate") @Transactional public ApiResponse<?> validateImport(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);requireImportDraft(id);Set<String> seen=new HashSet<>();for(var row:db.queryForList("select id from defense_result where import_batch_id=? order by row_no",id))validateRow(((Number)row.get("id")).longValue(),seen);return ApiResponse.ok(importDetail(id));}
  @PostMapping("/imports/{id}/publish") @Transactional public ApiResponse<?> publishImport(@PathVariable Long id){permissions.require(Permissions.EVALUATION_MANAGE);requireImportDraft(id);validateImport(id);var rows=db.queryForList("select * from defense_result where import_batch_id=? order by employee_id",id);if(rows.stream().anyMatch(r->!"VALID".equals(r.get("status"))))throw new BusinessException(400,"导入仍有错误，请修正后发布");for(var row:rows)publishRow(row);db.update("update defense_import_batch set status='PUBLISHED' where id=?",id);return ApiResponse.ok(null);}
  private Map<String,Object> requireImportDraft(Long id){var binding=db.queryForMap("select session_id from defense_import_batch where id=?",id);if(binding.get("session_id")!=null)lockSession(((Number)binding.get("session_id")).longValue());var row=db.queryForMap("select * from defense_import_batch where id=? for update",id);if(row.get("session_id")==null)throw new BusinessException(400,"历史月度答辩导入只读，请选择季度答辩场次重新导入");session(((Number)row.get("session_id")).longValue());if(!"DRAFT".equals(row.get("status")))throw new BusinessException(409,"该导入批次已经发布");return row;}
  @GetMapping("/export") public void export(@RequestParam Long sessionId,@RequestParam(required=false)Long businessUnitId,@RequestParam(required=false)Long classId,@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,HttpServletResponse response)throws Exception{
    permissions.require(Permissions.EVALUATION_VIEW);var session=session(sessionId);List<ExportRow> out=new ArrayList<>();
    for(var row:rows(session,businessUnitId,classId,keyword,status)){var item=new ExportRow();item.employeeNo=row.get("employee_no").toString();item.employeeName=row.get("employee_name").toString();item.batch=session.get("batch_name").toString();item.stage=session.get("label").toString();item.quarter=session.get("period_key").toString();item.score=(BigDecimal)row.get("score");item.fullScore=(BigDecimal)row.get("full_score");item.status=switch(row.get("status").toString()){case "PUBLISHED"->"已发布";case "DRAFT"->"草稿";case "VALID"->"校验通过";default->"未录入";};item.comment=Objects.toString(row.get("comment"),"");out.add(item);}
    excel(response,"季度答辩成绩.xlsx");EasyExcel.write(response.getOutputStream(),ExportRow.class).sheet("答辩成绩").doWrite(out);
  }
  private void excel(HttpServletResponse response,String name){response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename*=UTF-8\'\'"+java.net.URLEncoder.encode(name,StandardCharsets.UTF_8));}
}
