package com.talent.platform.evaluation;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/evaluation/defenses/tasks")
public class DefenseTaskController {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final EvaluationRevisionService revisions;
  private final AuditService audit;
  public DefenseTaskController(JdbcTemplate db,PermissionService permissions,EvaluationRevisionService revisions,AuditService audit){
    this.db=db;this.permissions=permissions;this.revisions=revisions;this.audit=audit;
  }
  public record TaskRequest(@NotBlank @Size(max=128) String name,@NotNull Long batchId,@NotNull YearMonth month,
    @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("999.99") @Digits(integer=3,fraction=2) BigDecimal fullScore,
    LocalDate defenseDate,@Size(max=1000) String note,@NotEmpty List<@NotNull Long> employeeIds,@NotNull @Min(0) Integer version){}
  public record GradeRequest(@NotNull Long employeeId,@NotNull @Digits(integer=3,fraction=2) @DecimalMin("0") BigDecimal score,
    @Size(max=1000) String comment,Long revisionOf,@Size(max=1000) String revisionReason){}
  public record VersionRequest(@NotNull @Min(0) Integer version){}
  @Data public static class ExcelGrade {
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("姓名") private String employeeName;
    @ExcelProperty("答辩成绩") private String score;
    @ExcelProperty("评价意见") private String comment;
  }
  private long number(Object value){return ((Number)value).longValue();}
  private void manage(){permissions.require(Permissions.EVALUATION_MANAGE);}
  private Map<String,Object> task(Long id,boolean lock){
    var rows=db.queryForList("select t.*,b.name batch_name from defense_task t join talent_batch b on b.id=t.talent_batch_id where t.id=?"+(lock?" for update":""),id);
    if(rows.isEmpty())throw new BusinessException(404,"答辩任务不存在");
    var filter=permissions.employeeFilter("e");var args=new ArrayList<Object>();args.add(id);args.addAll(filter.args());
    if(!"ALL".equals(SecurityUtils.current().dataScope())&&db.queryForObject("select count(*) from defense_task_participant p join employee e on e.id=p.employee_id where p.task_id=?"+filter.sql(),Integer.class,args.toArray())==0)throw new BusinessException(403,"无权访问该答辩任务");
    if(lock)for(Long employee:db.queryForList("select employee_id from defense_task_participant where task_id=?",Long.class,id))permissions.requireEmployee(employee);
    return rows.get(0);
  }
  private void editableGrades(Map<String,Object> t){
    if(!Set.of("ACTIVE","ENDED").contains(t.get("status")))throw new BusinessException(409,"请先启用任务；取消任务的成绩已失效");
  }
  private List<Map<String,Object>> people(Long id){
    var filter=permissions.employeeFilter("e");var args=new ArrayList<Object>();args.add(id);args.addAll(filter.args());
    return db.queryForList("select e.id,e.employee_no,e.name employee_name,bu.name business_unit_name,c.label class_name,p.active_flag from defense_task_participant p join employee e on e.id=p.employee_id left join business_unit bu on bu.id=e.business_unit_id left join dictionary_item c on c.id=e.class_id where p.task_id=?"+filter.sql()+" order by e.employee_no",args.toArray());
  }
  @GetMapping public ApiResponse<?> list(@RequestParam(required=false)YearMonth month,@RequestParam(required=false)Long batchId){
    manage();var filter=permissions.employeeFilter("e");var args=new ArrayList<Object>();
    String sql="select t.*,b.name batch_name,(select count(*) from defense_task_participant p where p.task_id=t.id and p.active_flag=1) participant_count,(select count(*) from defense_result d where d.task_id=t.id and d.status='PUBLISHED') published_count,(select count(distinct employee_id) from defense_result d where d.task_id=t.id and d.status in ('DRAFT','VALID','ERROR')) draft_count,(select count(*) from defense_task_participant p where p.task_id=t.id and p.active_flag=1 and not exists(select 1 from defense_result d where d.task_id=t.id and d.employee_id=p.employee_id)) not_entered_count from defense_task t join talent_batch b on b.id=t.talent_batch_id where exists(select 1 from defense_task_participant p join employee e on e.id=p.employee_id where p.task_id=t.id"+filter.sql()+")";args.addAll(filter.args());
    if(month!=null){sql+=" and t.period_month=?";args.add(month.atDay(1));}
    if(batchId!=null){sql+=" and t.talent_batch_id=?";args.add(batchId);}
    return ApiResponse.ok(db.queryForList(sql+" order by t.period_month desc,t.id desc",args.toArray()));
  }
  @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable Long id){manage();return ApiResponse.ok(task(id,false));}
  @GetMapping("/employees") public ApiResponse<?> employees(@RequestParam Long batchId,@RequestParam YearMonth month,
    @RequestParam(required=false)Long taskId,@RequestParam(required=false)String keyword,@RequestParam(required=false)Long businessUnitId,
    @RequestParam(required=false)Long classId,@RequestParam(required=false)Long stationId){
    manage();if(taskId!=null)task(taskId,false);
    var args=new ArrayList<Object>(Arrays.asList(month.atDay(1),taskId,taskId,batchId));
    String sql="select e.id,e.employee_no,e.name employee_name,bu.name business_unit_name,c.label class_name,s.name station_name,t.id conflict_task_id,t.name conflict_task_name,(t.id is not null) conflict from employee e left join business_unit bu on bu.id=e.business_unit_id left join dictionary_item c on c.id=e.class_id left join service_station s on s.id=e.station_id left join defense_task_participant p on p.employee_id=e.id and p.period_month=? and p.active_flag=1 and (? is null or p.task_id<>?) left join defense_task t on t.id=p.task_id and t.status<>'CANCELLED' where e.status='ACTIVE' and e.batch_id=?";
    if(businessUnitId!=null){sql+=" and e.business_unit_id=?";args.add(businessUnitId);}
    if(classId!=null){sql+=" and e.class_id=?";args.add(classId);}
    if(stationId!=null){sql+=" and e.station_id=?";args.add(stationId);}
    if(keyword!=null&&!keyword.isBlank()){sql+=" and (e.name like ? or e.employee_no like ?)";args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
    var scope=permissions.employeeFilter("e");args.addAll(scope.args());
    return ApiResponse.ok(db.queryForList(sql+scope.sql()+" order by e.employee_no",args.toArray()));
  }
  private List<Long> validatePeople(TaskRequest r,Long except){
    ScoreValues.require(r.fullScore(),new BigDecimal("999.99"));var ids=r.employeeIds().stream().distinct().sorted().toList();
    if(ids.isEmpty())throw new BusinessException(400,"请选择答辩人员");
    for(Long employee:ids){
      permissions.requireEmployee(employee);db.queryForMap("select id from employee where id=? for update",employee);
      if(db.queryForObject("select count(*) from employee where id=? and batch_id=? and status='ACTIVE'",Integer.class,employee,r.batchId())!=1)throw new BusinessException(400,"人员不属于所选批次或已停用");
      var conflict=db.queryForList("select t.name from defense_task_participant p join defense_task t on t.id=p.task_id where p.employee_id=? and p.active_flag=1 and p.period_month=? and (? is null or t.id<>?)",employee,r.month().atDay(1),except,except);
      if(!conflict.isEmpty())throw new BusinessException(409,"员工存在同月答辩冲突："+conflict.get(0).get("name"));
    }return ids;
  }
  private void savePeople(Long id,YearMonth month,List<Long> ids){
    db.update("update defense_task_participant set active_flag=0 where task_id=?",id);
    for(Long employee:ids)db.update("insert into defense_task_participant(task_id,employee_id,period_month,active_flag) values(?,?,?,1) on duplicate key update period_month=values(period_month),active_flag=1",id,employee,month.atDay(1));
  }
  @PostMapping @Transactional public ApiResponse<Long> create(@Valid @RequestBody TaskRequest r){
    manage();var ids=validatePeople(r,null);
    db.update("insert into defense_task(name,talent_batch_id,period_month,full_score,defense_date,note,created_by) values(?,?,?,?,?,?,?)",r.name().trim(),r.batchId(),r.month().atDay(1),r.fullScore(),r.defenseDate(),r.note(),SecurityUtils.current().id());
    Long id=db.queryForObject("select last_insert_id()",Long.class);savePeople(id,r.month(),ids);
    audit.log("CREATE_DEFENSE_TASK","DEFENSE_TASK",id,null,r);return ApiResponse.ok(id);
  }
  @PutMapping("/{id}") @Transactional public ApiResponse<Void> update(@PathVariable Long id,@Valid @RequestBody TaskRequest r){
    manage();var old=task(id,true);if(!"DRAFT".equals(old.get("status")))throw new BusinessException(409,"只有草稿任务可以编辑");
    version(old,r.version());var ids=validatePeople(r,id);
    db.update("update defense_task set name=?,talent_batch_id=?,period_month=?,full_score=?,defense_date=?,note=?,version=version+1 where id=?",r.name().trim(),r.batchId(),r.month().atDay(1),r.fullScore(),r.defenseDate(),r.note(),id);savePeople(id,r.month(),ids);
    audit.log("UPDATE_DEFENSE_TASK","DEFENSE_TASK",id,old,r);return ApiResponse.ok(null);
  }
  private void version(Map<String,Object> t,int version){if(number(t.get("version"))!=version)throw new BusinessException(409,"任务已更新，请刷新");}
  @DeleteMapping("/{id}") @Transactional public ApiResponse<Void> delete(@PathVariable Long id,@RequestParam int version){
    manage();var old=task(id,true);version(old,version);if(!"DRAFT".equals(old.get("status")))throw new BusinessException(409,"仅草稿任务可以删除");
    db.update("delete from defense_task_participant where task_id=?",id);db.update("delete from defense_task where id=?",id);audit.log("DELETE_DEFENSE_TASK","DEFENSE_TASK",id,old,null);return ApiResponse.ok(null);
  }
  @PostMapping("/{id}/{action}") @Transactional public ApiResponse<Void> action(@PathVariable Long id,@PathVariable String action,@Valid @RequestBody VersionRequest request){
    manage();var t=task(id,true);version(t,request.version());
    String status=switch(action){
      case "activate"->{if(!"DRAFT".equals(t.get("status")))throw new BusinessException(409,"仅草稿可启用");yield "ACTIVE";}
      case "end"->{if(!"ACTIVE".equals(t.get("status")))throw new BusinessException(409,"仅启用任务可结束");
        if(db.queryForObject("select count(*) from defense_task_participant p where p.task_id=? and p.active_flag=1 and not exists(select 1 from defense_result d where d.task_id=p.task_id and d.employee_id=p.employee_id and d.status='PUBLISHED')",Integer.class,id)>0)throw new BusinessException(409,"全员成绩发布后方可结束");yield "ENDED";}
      case "cancel"->{if(!Set.of("DRAFT","ACTIVE").contains(t.get("status")))throw new BusinessException(409,"当前任务不可取消");
        if(db.queryForObject("select count(*) from defense_result where task_id=? and status='PUBLISHED'",Integer.class,id)>0)throw new BusinessException(409,"已有正式成绩的任务不能取消");
        db.update("update defense_task_participant set active_flag=0 where task_id=?",id);yield "CANCELLED";}
      default->throw new BusinessException(404,"操作不存在");
    };db.update("update defense_task set status=?,version=version+1 where id=?",status,id);
    audit.log("CHANGE_DEFENSE_TASK_STATUS","DEFENSE_TASK",id,t,Map.of("status",status));return ApiResponse.ok(null);
  }
  @GetMapping("/{id}/participants") public ApiResponse<?> participants(@PathVariable Long id){manage();task(id,false);return ApiResponse.ok(people(id));}
  private List<Map<String,Object>> gradeRows(Long id){
    var scope=permissions.employeeFilter("e");var args=new ArrayList<Object>();args.add(id);args.addAll(scope.args());
    return db.queryForList("select e.id employee_id,e.employee_no,e.name employee_name,d.id,d.score,d.full_score,d.comment,d.version,d.status,d.updated_at,d.revision_of,d.revision_reason,d.import_batch_id,d.error_message from defense_task_participant p join employee e on e.id=p.employee_id left join defense_result d on d.id=(select x.id from defense_result x where x.task_id=p.task_id and x.employee_id=p.employee_id order by (x.status in ('DRAFT','VALID','ERROR')) desc,x.version desc,x.id desc limit 1) where p.task_id=? and p.active_flag=1"+scope.sql()+" order by e.employee_no",args.toArray());
  }
  @GetMapping("/{id}/grades") public ApiResponse<?> grades(@PathVariable Long id){manage();task(id,false);return ApiResponse.ok(gradeRows(id));}
  @GetMapping("/{id}/history") public ApiResponse<?> history(@PathVariable Long id,@RequestParam Long employeeId){
    manage();task(id,false);permissions.requireEmployee(employeeId);
    audit.log("VIEW_DEFENSE_HISTORY","DEFENSE_TASK",id,null,Map.of("employeeId",employeeId));
    return ApiResponse.ok(db.queryForList("select d.*,u.display_name creator_name from defense_result d left join sys_user u on u.id=d.created_by where d.task_id=? and d.employee_id=? order by d.version desc,d.id desc",id,employeeId));
  }
  private void excel(HttpServletResponse response,String name){response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+java.net.URLEncoder.encode(name,StandardCharsets.UTF_8));}
  @GetMapping("/template") public void template(HttpServletResponse response)throws Exception{manage();excel(response,"答辩成绩导入模板.xlsx");EasyExcel.write(response.getOutputStream(),ExcelGrade.class).sheet("答辩成绩").doWrite(List.of());}
  @GetMapping("/{id}/export") public void export(@PathVariable Long id,HttpServletResponse response)throws Exception{
    manage();var t=task(id,false);var rows=new ArrayList<List<Object>>();
    for(var row:gradeRows(id))rows.add(Arrays.asList(row.get("employee_no"),row.get("employee_name"),t.get("name"),t.get("period_month").toString().substring(0,7),row.get("score"),t.get("full_score"),row.get("status"),row.get("comment")));
    excel(response,"答辩任务成绩.xlsx");EasyExcel.write(response.getOutputStream()).head(List.of(List.of("工号"),List.of("姓名"),List.of("任务"),List.of("计分月份"),List.of("答辩成绩"),List.of("满分"),List.of("状态"),List.of("评价意见"))).sheet("答辩成绩").doWrite(rows);
    audit.log("EXPORT_DEFENSE","DEFENSE_TASK",id,null,Map.of("rowCount",rows.size()));
  }
  private Long current(Long task,Long employee){var rows=db.queryForList("select id from defense_result where task_id=? and employee_id=? and status='PUBLISHED'",task,employee);return rows.isEmpty()?null:number(rows.get(0).get("id"));}
  private void reason(String reason){if(reason==null||reason.isBlank())throw new BusinessException(400,"请填写成绩修订原因");}
  @PostMapping("/{id}/grades") @Transactional public ApiResponse<Long> saveGrade(@PathVariable Long id,@Valid @RequestBody GradeRequest r){
    manage();var t=task(id,true);editableGrades(t);permissions.requireEmployee(r.employeeId());
    var person=db.queryForList("select e.employee_no,e.name from defense_task_participant p join employee e on e.id=p.employee_id where p.task_id=? and p.employee_id=? and p.active_flag=1",id,r.employeeId());
    if(person.isEmpty())throw new BusinessException(400,"员工不属于该任务");ScoreValues.require(r.score(),(BigDecimal)t.get("full_score"));
    Long current=current(id,r.employeeId());if(!Objects.equals(current,r.revisionOf()))throw new BusinessException(409,"正式成绩已变化，请明确选择当前版本修订");if(current!=null)reason(r.revisionReason());
    var drafts=db.queryForList("select id from defense_result where task_id=? and employee_id=? and status in ('DRAFT','VALID','ERROR') and import_batch_id is null order by id desc limit 1",id,r.employeeId());Long grade;
    if(drafts.isEmpty()){
      int version=db.queryForObject("select coalesce(max(version),0)+1 from defense_result where task_id=? and employee_id=?",Integer.class,id,r.employeeId());
      db.update("insert into defense_result(task_id,employee_id,employee_no,employee_name,period_month,score,full_score,raw_score,comment,version,revision_of,revision_reason,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,r.employeeId(),person.get(0).get("employee_no"),person.get(0).get("name"),t.get("period_month"),r.score(),t.get("full_score"),r.score().toPlainString(),r.comment(),version,r.revisionOf(),r.revisionReason(),SecurityUtils.current().id());
      grade=db.queryForObject("select last_insert_id()",Long.class);
    }else{grade=number(drafts.get(0).get("id"));db.update("update defense_result set score=?,raw_score=?,comment=?,revision_of=?,revision_reason=?,status='DRAFT',error_message=null where id=?",r.score(),r.score().toPlainString(),r.comment(),r.revisionOf(),r.revisionReason(),grade);}
    audit.log("SAVE_DEFENSE_DRAFT","DEFENSE",grade,null,r);return ApiResponse.ok(grade);
  }
  private void publishRow(Map<String,Object> t,Map<String,Object> d){
    editableGrades(t);Long id=number(t.get("id")),employee=number(d.get("employee_id"));permissions.requireEmployee(employee);
    if(!Set.of("DRAFT","VALID").contains(d.get("status")))throw new BusinessException(409,"只能发布有效成绩草稿");
    if(db.queryForObject("select count(*) from defense_task_participant where task_id=? and employee_id=? and active_flag=1",Integer.class,id,employee)!=1)throw new BusinessException(409,"参与人员已失效");
    ScoreValues.require((BigDecimal)d.get("score"),(BigDecimal)t.get("full_score"));
    Long current=current(id,employee),prior=d.get("revision_of")==null?null:number(d.get("revision_of"));
    if(!Objects.equals(current,prior))throw new BusinessException(409,"正式成绩已变化，请重新修订");if(prior!=null)reason(Objects.toString(d.get("revision_reason"),""));
    db.update("update defense_result set status='SUPERSEDED' where task_id=? and employee_id=? and status='PUBLISHED'",id,employee);
    int version=db.queryForObject("select coalesce(max(version),0)+1 from defense_result where task_id=? and employee_id=? and status='SUPERSEDED'",Integer.class,id,employee);
    db.update("update defense_result set status='PUBLISHED',version=?,published_at=now() where id=?",version,d.get("id"));
    revisions.changed(Map.of(employee,Set.of(month(t))),"发布月度答辩成绩 #"+d.get("id")+(prior==null?"":"："+d.get("revision_reason")));
    audit.log("PUBLISH_DEFENSE","DEFENSE",number(d.get("id")),null,d);
  }
  private YearMonth month(Map<String,Object> t){return YearMonth.parse(t.get("period_month").toString().substring(0,7));}
  @PostMapping("/grades/{gradeId}/publish") @Transactional public ApiResponse<Void> publishGrade(@PathVariable Long gradeId){
    manage();var binding=db.queryForList("select task_id from defense_result where id=? and task_id is not null",gradeId);
    if(binding.isEmpty())throw new BusinessException(404,"成绩不存在");var t=task(number(binding.get(0).get("task_id")),true);
    var d=db.queryForMap("select * from defense_result where id=? for update",gradeId);
    if(d.get("import_batch_id")!=null)throw new BusinessException(400,"导入成绩请整批发布");publishRow(t,d);return ApiResponse.ok(null);
  }
  private Map<String,Object> importBatch(Long task,Long batch){
    var rows=db.queryForList("select * from defense_import_batch where id=? and task_id=? for update",batch,task);if(rows.isEmpty())throw new BusinessException(404,"导入批次不存在");return rows.get(0);
  }
  private Map<String,Object> importDetail(Long batch){
    var result=new LinkedHashMap<>(db.queryForMap("select * from defense_import_batch where id=?",batch));
    result.put("rows",db.queryForList("select * from defense_result where import_batch_id=? order by row_no",batch));return result;
  }
  @GetMapping("/{id}/imports") public ApiResponse<?> imports(@PathVariable Long id){manage();task(id,false);return ApiResponse.ok(db.queryForList("select * from defense_import_batch where task_id=? order by id desc",id));}
  @GetMapping("/{id}/imports/{batch}") @Transactional public ApiResponse<?> imported(@PathVariable Long id,@PathVariable Long batch){manage();task(id,false);importBatch(id,batch);return ApiResponse.ok(importDetail(batch));}
  private void validateImport(Map<String,Object> t,Map<String,Object> batch,boolean bindSources){
    if(!"DRAFT".equals(batch.get("status")))throw new BusinessException(409,"导入批次已发布");editableGrades(t);
    boolean revision=Boolean.TRUE.equals(batch.get("revision_mode"))||batch.get("revision_mode") instanceof Number n&&n.intValue()==1;
    Set<String> seen=new HashSet<>();Long id=number(t.get("id"));
    for(var row:db.queryForList("select * from defense_result where import_batch_id=? order by row_no for update",batch.get("id"))){
      String no=Objects.toString(row.get("employee_no"),"").trim();var errors=new ArrayList<String>();Long employee=null;
      if(no.isBlank())errors.add("工号不能为空");if(!seen.add(no))errors.add("重复工号");
      var people=db.queryForList("select e.id,e.name from defense_task_participant p join employee e on e.id=p.employee_id where p.task_id=? and p.active_flag=1 and e.employee_no=?",id,no);
      if(people.isEmpty())errors.add("工号不在任务参与人员中");else{employee=number(people.get(0).get("id"));permissions.requireEmployee(employee);
        String name=Objects.toString(row.get("employee_name"),"").trim();if(!name.isEmpty()&&!name.equals(people.get(0).get("name")))errors.add("姓名与工号不一致");}
      BigDecimal score=null;try{String raw=Objects.toString(row.get("raw_score"),"").trim();if(raw.isEmpty())throw new IllegalArgumentException();score=ScoreValues.require(new BigDecimal(raw),(BigDecimal)t.get("full_score"));}catch(Exception ex){errors.add("成绩为空、格式错误、超过满分或两位小数");}
      Long current=employee==null?null:current(id,employee);
      Long prior=row.get("revision_of")==null?null:number(row.get("revision_of"));
      if(current!=null&&!revision)errors.add("已有正式成绩，请选择修订导入");
      // A validated revision always binds the reviewed version; concurrent publication is rejected.
      if(revision&&!bindSources&&!Objects.equals(prior,current))errors.add("正式成绩已变化，请重新导入核对");
      if(revision&&current!=null)reason(Objects.toString(batch.get("revision_reason"),""));
      db.update("update defense_result set employee_id=?,score=?,status=?,error_message=?,revision_of=?,revision_reason=? where id=?",employee,score,errors.isEmpty()?"VALID":"ERROR",errors.isEmpty()?null:String.join("；",errors),revision?(bindSources?current:prior):null,batch.get("revision_reason"),row.get("id"));
    }
  }
  @PostMapping("/{id}/imports") @Transactional public ApiResponse<?> upload(@PathVariable Long id,@RequestParam(defaultValue="false")boolean revision,@RequestParam(required=false)String revisionReason,@RequestParam MultipartFile file)throws Exception{
    manage();var t=task(id,true);editableGrades(t);if(revision)reason(revisionReason);
    if(file.isEmpty()||file.getSize()>20*1024*1024)throw new BusinessException(400,"请选择20MB以内的Excel文件");
    byte[] bytes=file.getBytes();String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var old=db.queryForList("select id from defense_import_batch where task_id=? and file_sha256=?",id,sha);if(!old.isEmpty())return ApiResponse.ok(importDetail(number(old.get(0).get("id"))));
    List<ExcelGrade> input=EasyExcel.read(new java.io.ByteArrayInputStream(bytes)).head(ExcelGrade.class).sheet(0).doReadSync();
    if(input.isEmpty()||input.size()>10000)throw new BusinessException(400,"导入文件为空或超过10000行");
    db.update("insert into defense_import_batch(talent_batch_id,period_month,task_id,file_sha256,original_filename,revision_mode,revision_reason,created_by) values(?,?,?,?,?,?,?,?)",t.get("talent_batch_id"),t.get("period_month"),id,sha,file.getOriginalFilename(),revision,revisionReason,SecurityUtils.current().id());
    Long batch=db.queryForObject("select last_insert_id()",Long.class);int row=1;
    for(var line:input)db.update("insert into defense_result(task_id,employee_no,employee_name,period_month,full_score,raw_score,comment,import_batch_id,row_no,created_by) values(?,?,?,?,?,?,?,?,?,?)",id,Objects.toString(line.getEmployeeNo(),"").trim(),line.getEmployeeName(),t.get("period_month"),t.get("full_score"),line.getScore(),line.getComment(),batch,++row,SecurityUtils.current().id());
    validateImport(t,importBatch(id,batch),true);audit.log("IMPORT_DEFENSE","DEFENSE_TASK",id,null,Map.of("batchId",batch,"rowCount",input.size()));return ApiResponse.ok(importDetail(batch));
  }
  @PutMapping("/{id}/imports/{batch}/rows/{row}") @Transactional public ApiResponse<?> editImport(@PathVariable Long id,@PathVariable Long batch,@PathVariable Long row,@RequestBody Map<String,String> body){
    manage();var t=task(id,true);var imp=importBatch(id,batch);if(!"DRAFT".equals(imp.get("status")))throw new BusinessException(409,"导入批次已发布");
    var before=db.queryForList("select * from defense_result where id=? and import_batch_id=?",row,batch);if(before.isEmpty())throw new BusinessException(404,"导入行不存在");
    // Changing an employee requires binding a new current version during validation.
    db.update("update defense_result set employee_no=?,employee_name=?,raw_score=?,comment=?,revision_of=null where id=?",body.get("employee_no"),body.get("employee_name"),body.get("raw_score"),body.get("comment"),row);
    validateImport(t,imp,true);audit.log("EDIT_DEFENSE_IMPORT_ROW","DEFENSE",row,before,body);return ApiResponse.ok(importDetail(batch));
  }
  @PostMapping("/{id}/imports/{batch}/validate") @Transactional public ApiResponse<?> validate(@PathVariable Long id,@PathVariable Long batch){
    manage();var t=task(id,true);validateImport(t,importBatch(id,batch),false);return ApiResponse.ok(importDetail(batch));
  }
  @PostMapping("/{id}/imports/{batch}/publish") @Transactional public ApiResponse<Void> publishImport(@PathVariable Long id,@PathVariable Long batch){
    manage();var t=task(id,true);var imp=importBatch(id,batch);validateImport(t,imp,false);
    var rows=db.queryForList("select * from defense_result where import_batch_id=? order by employee_id for update",batch);
    if(rows.isEmpty()||rows.stream().anyMatch(r->!"VALID".equals(r.get("status"))))throw new BusinessException(400,"导入存在错误，请逐行修正并重新校验");
    for(var row:rows)publishRow(t,row);db.update("update defense_import_batch set status='PUBLISHED' where id=?",batch);
    audit.log("PUBLISH_DEFENSE_IMPORT","DEFENSE_TASK",id,imp,Map.of("batchId",batch));return ApiResponse.ok(null);
  }
}
