package com.talent.platform.station;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.talent.platform.station.StationImportWorkbook.InputRow;
import static com.talent.platform.station.StationResponsibilityService.Assignment;

@Service
public class StationResponsibilityImportService {
  public record Diagnostic(int row,String field,String message,String suggestion){}
  public record Person(String ref,String employeeNo,String name,String role,Long userId,String action){}
  public record Group(Long stationId,String stationName,String level,List<String> refs,List<String> before,List<String> after,boolean changed,int unchangedRelations){}
  public record Impact(String key,String stationName,String level,String fromName,int count,List<String> candidateRefs){}
  public record Summary(int validRows,int skippedRows,int duplicateRelations,int createdAccounts,int reusedAccounts,int changedStations,int unchangedRelations,int affectedVersions){}
  public record Preview(String batchId,String status,String expiresAt,Summary summary,List<InputRow> rows,List<Diagnostic> errors,List<Diagnostic> warnings,List<Person> people,List<Group> groups,List<Impact> impacts){}
  public record Commit(String reason,Map<String,String> transfers){}
  record Analysis(List<InputRow> rows,List<Diagnostic> errors,List<Diagnostic> warnings,List<Person> people,List<Group> groups,List<Assignment> assignments,List<Impact> impacts,Summary summary,String baseline){}
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final PasswordEncoder encoder;
  private final AuditService audit;
  private final ObjectMapper mapper;
  private final StationResponsibilityService responsibilities;
  public StationResponsibilityImportService(JdbcTemplate db,PermissionService permissions,PasswordEncoder encoder,AuditService audit,ObjectMapper mapper,StationResponsibilityService responsibilities){this.db=db;this.permissions=permissions;this.encoder=encoder;this.audit=audit;this.mapper=mapper;this.responsibilities=responsibilities;}

  public byte[] template(){require();return StationImportWorkbook.template(db.queryForList("select name from service_station where enabled=true order by name,id",String.class));}

  @Transactional(isolation=Isolation.REPEATABLE_READ)
  public Preview preview(MultipartFile file){
    require();var rows=StationImportWorkbook.read(file);var analysis=analyze(rows);
    var id=UUID.randomUUID().toString();var expires=Instant.now().plusSeconds(1800);
    String status=analysis.errors().isEmpty()?"VALIDATED":"INVALID";
    var preview=new Preview(id,status,expires.toString(),analysis.summary(),rows,analysis.errors(),analysis.warnings(),analysis.people(),analysis.groups(),analysis.impacts());
    try{
      db.update("insert into station_responsibility_import(id,created_by,file_hash,payload,preview,baseline_hash,status,expires_at) values(?,?,?,?,?,?,?,date_add(now(),interval 30 minute))",
          id,SecurityUtils.current().id(),hash(file.getBytes()),json(rows),json(preview),analysis.baseline(),status);
    }catch(java.io.IOException e){throw bad("无法读取导入文件");}
    return preview;
  }

  public Map<String,Object> get(String id){var batch=batch(id,false);return response(batch);}
  public byte[] errors(String id){
    var batch=batch(id,false);var preview=read(String.valueOf(batch.get("preview")),Preview.class);
    var errors=new LinkedHashMap<Integer,List<String>>();
    for(var error:preview.errors())errors.computeIfAbsent(error.row(),x->new ArrayList<>()).add(error.field()+"："+error.message()+"；"+error.suggestion());
    return StationImportWorkbook.errors(preview.rows(),errors);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> commit(String id,Commit request){
    var batch=batch(id,true);
    if("COMPLETED".equals(batch.get("status")))return response(batch);
    if(!"VALIDATED".equals(batch.get("status")))throw bad("该批次存在错误，请修正文件并重新预览");
    if(((Number)batch.get("expired")).intValue()!=0)throw conflict("预览已过期，请重新上传并预览");
    if(request==null||request.reason()==null||request.reason().isBlank()||request.reason().strip().length()>500)throw bad("请填写调整原因（最多500字）");
    List<InputRow> rows;
    try{rows=mapper.readValue(String.valueOf(batch.get("payload")),new TypeReference<List<InputRow>>(){});}catch(Exception e){throw new IllegalStateException(e);}
    var initial=analyze(rows);
    verify(batch,initial);
    Set<Long> users=responsibilities.lockAssignments(initial.assignments());
    // The shared lock order is stations -> employees -> accounts. Lock identity matches
    // after employee locks too; never acquire account locks ahead of leave employees.
    for(var person:initial.people().stream().sorted(Comparator.comparing(Person::employeeNo)).toList())
      db.queryForList("select id from sys_user where username=? for update",person.employeeNo());
    var latest=analyze(rows);verify(batch,latest);
    var transfers=request.transfers()==null?Map.<String,String>of():request.transfers();
    var allowedKeys=new HashSet<String>();
    for(var impact:latest.impacts()){
      allowedKeys.add(impact.key());
      if(!impact.candidateRefs().contains(transfers.get(impact.key())))throw bad("请为每组受影响的请假责任选择新的同级负责人");
    }
    if(!allowedKeys.containsAll(transfers.keySet()))throw bad("交接选择包含无效项目，请重新预览");
    var resolved=new LinkedHashMap<String,Long>();
    try{
      for(var person:latest.people()){
        Long user=person.userId();
        if(user==null){
          db.update("insert into sys_user(username,display_name,password_hash,role,enabled,must_change_password) values(?,?,?,?,true,true)",person.employeeNo(),person.name(),encoder.encode(initialPassword(person.employeeNo())),person.role());
          user=db.queryForObject("select last_insert_id()",Long.class);
          db.update("insert ignore into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,true,?)",user,person.role(),SecurityUtils.current().id());
        }
        resolved.put(person.ref(),user);users.add(user);
      }
    }catch(DuplicateKeyException e){throw conflict("工号账号已被其他操作创建，请重新预览");}
    var assignments=latest.groups().stream().filter(Group::changed).map(g->new Assignment(g.stationId(),g.level(),g.refs().stream().map(resolved::get).toList())).toList();
    var resolvedTransfers=new LinkedHashMap<String,Long>();transfers.forEach((key,ref)->resolvedTransfers.put(key,resolved.get(ref)));
    Map<String,Object> applied;
    if(assignments.isEmpty())applied=Map.of("affectedVersions",0);
    else applied=responsibilities.applyVerified(new StationResponsibilityService.Change(assignments,resolvedTransfers,request.reason().strip(),null),responsibilities.inspect(assignments),users);
    var result=new LinkedHashMap<String,Object>();
    result.put("createdAccounts",latest.summary().createdAccounts());result.put("reusedAccounts",latest.summary().reusedAccounts());
    result.put("changedStations",latest.summary().changedStations());result.put("affectedVersions",applied.get("affectedVersions"));
    result.put("unchangedRelations",latest.summary().unchangedRelations());
    audit.log("IMPORT_STATION_RESPONSIBILITIES","SERVICE_STATION",null,Map.of("groups",latest.groups()),Map.of("batchId",id,"accounts",latest.people().stream().map(p->Map.of("id",resolved.get(p.ref()),"employeeNo",p.employeeNo(),"role",p.role(),"action",p.action())).toList(),"transfers",resolvedTransfers,"reason",request.reason().strip(),"result",result));
    db.update("update station_responsibility_import set status='COMPLETED',result=?,completed_at=now() where id=?",json(result),id);
    return Map.of("batchId",id,"status","COMPLETED","result",result);
  }

  Analysis analyze(List<InputRow> rows){
    var errors=new ArrayList<Diagnostic>();var warnings=new ArrayList<Diagnostic>();
    var people=new LinkedHashMap<String,Person>();var names=new HashMap<String,String>();var roles=new HashMap<String,String>();var spellings=new HashMap<String,String>();
    var groupRefs=new LinkedHashMap<String,LinkedHashSet<String>>();var groupStations=new LinkedHashMap<String,Map<String,Object>>();
    var identity=new ArrayList<Object>();int skipped=0,valid=0,duplicates=0;
    var context=responsibilities.context();
    @SuppressWarnings("unchecked") var scopes=(List<Map<String,Object>>)context.get("scopes");
    for(var row:rows){
      if(row.stationName().isBlank()&&row.stationNo().isBlank()&&row.areaName().isBlank()&&row.areaNo().isBlank()){skipped++;continue;}
      int before=errors.size();
      var stations=db.queryForList("select id,name,enabled from service_station where name=? order by id",row.station());
      identity.add(stations);
      Map<String,Object> station=stations.size()==1?stations.get(0):null;
      if(station==null||!row.station().equals(station.get("name"))||!Boolean.TRUE.equals(station.get("enabled")))errors.add(error(row.row(),"服务站","服务站不存在、名称不完全一致或已停用","请填写模板中已有且启用的服务站"));
      for(int part=0;part<2;part++){
        String name=part==0?row.stationName():row.areaName(),no=part==0?row.stationNo():row.areaNo();
        String role=part==0?"STATION_MANAGER":"AREA_MANAGER",field=part==0?"站长":"区域经理";
        if(name.isBlank()&&no.isBlank())continue;
        if(name.isBlank()||no.isBlank()){errors.add(error(row.row(),field,"姓名和工号必须成对填写","请补全姓名与工号，或同时留空"));continue;}
        if(name.length()>64||!no.matches("[A-Za-z0-9._-]{1,64}")){errors.add(error(row.row(),field,"姓名最多64字；工号为1～64位字母、数字、点、下划线或连字符","工号请按文本填写"));continue;}
        String folded=no.toLowerCase(Locale.ROOT);
        if(spellings.containsKey(folded)&&!spellings.get(folded).equals(no))errors.add(error(row.row(),field,"同一工号存在不同大小写写法","请统一工号，不自动改写大小写"));
        spellings.putIfAbsent(folded,no);
        if(names.containsKey(folded)&&!names.get(folded).equals(name))errors.add(error(row.row(),field,"同一工号填写了不同姓名","请统一姓名后重新上传"));
        if(roles.containsKey(folded)&&!roles.get(folded).equals(role))errors.add(error(row.row(),field,"同一工号不能同时导入站长与区域经理","请先核实角色，本次不支持兼任"));
        names.putIfAbsent(folded,name);roles.putIfAbsent(folded,role);
        String ref=role+":"+no;
        var matches=db.queryForList("select u.id,u.username,u.display_name,u.role,u.enabled,u.version,u.security_version,"
            +"(select group_concat(r.role_code order by r.is_primary desc,r.assigned_at,r.role_code separator ',') "
            +"from sys_user_role r where r.user_id=u.id) roles "
            +"from sys_user u where u.username=? order by u.id",no);
        var linked=db.queryForList("select e.id,e.user_id,e.employee_no,u.username from employee e join sys_user u on u.id=e.user_id where e.employee_no=? order by e.id",no);
        identity.add(List.of(no,matches,linked));Long user=null;
        if(!linked.isEmpty()&&linked.stream().anyMatch(x->!no.equals(x.get("username"))))errors.add(error(row.row(),field,"员工档案存在相同工号但关联账号不一致","请先核实员工与账号关联，避免重复建号"));
        if(!matches.isEmpty()){
          var account=matches.get(0);user=number(account.get("id"));
          if(!no.equals(account.get("username")))errors.add(error(row.row(),field,"工号与已有账号字符或大小写不完全一致","请核实账号，不自动改写工号"));
          if(!name.equals(account.get("display_name")))errors.add(error(row.row(),field,"姓名不一致，系统为“"+account.get("display_name")+"”，文件为“"+name+"”","请先核实并修正姓名"));
          String ownedRoles=Objects.toString(account.get("roles"),Objects.toString(account.get("role"),""));
          if(!List.of(ownedRoles.split(",")).contains(role))errors.add(error(row.row(),field,"账号角色冲突，现有角色为"+ownedRoles,"请通过账号管理核实处理后重新导入"));
          if(!Boolean.TRUE.equals(account.get("enabled")))errors.add(error(row.row(),field,"账号已停用","请先核实并启用账号"));
        }
        people.putIfAbsent(ref,new Person(ref,no,name,role,user,user==null?"CREATE":"REUSE"));
        if(station!=null){
          String key=station.get("id")+":"+role;groupStations.putIfAbsent(key,station);
          if(!groupRefs.computeIfAbsent(key,x->new LinkedHashSet<>()).add(ref)){duplicates++;warnings.add(error(row.row(),field,"重复的人员负责关系已合并","无需重复填写同一服务站的相同负责人"));}
        }
      }
      if(errors.size()==before)valid++;
    }
    if(groupRefs.isEmpty())errors.add(error(0,"文件","没有填写任何负责人","请至少填写一组姓名和工号"));
    if(groupRefs.size()>500)errors.add(error(0,"文件","超过500组站点及角色配置","请拆分文件"));
    var ids=new LinkedHashMap<String,Long>();long temporary=-1;
    for(var person:people.values())ids.put(person.ref(),person.userId()==null?temporary--:person.userId());
    var groups=new ArrayList<Group>();var assignments=new ArrayList<Assignment>();
    for(var entry:groupRefs.entrySet()){
      var station=groupStations.get(entry.getKey());long stationId=number(station.get("id"));String level=entry.getKey().substring(entry.getKey().indexOf(':')+1);
      var refs=new ArrayList<>(entry.getValue());var desired=refs.stream().map(ids::get).sorted().toList();
      var current=scopes.stream().filter(s->number(s.get("station_id"))==stationId&&level.equals(s.get("level"))).toList();
      var existing=current.stream().map(s->number(s.get("user_id"))).sorted().toList();
      boolean changed=!existing.equals(desired);
      groups.add(new Group(stationId,String.valueOf(station.get("name")),level,refs,current.stream().map(s->s.get("display_name")+"（"+s.get("username")+"）").toList(),refs.stream().map(ref->people.get(ref).name()+"（"+people.get(ref).employeeNo()+"）").toList(),changed,(int)desired.stream().filter(existing::contains).count()));
      if(refs.size()>50)errors.add(error(0,"负责人","单个站点同级负责人最多50人","请核实负责人配置"));
      if(changed)assignments.add(new Assignment(stationId,level,desired));
    }
    var inspected=responsibilities.inspect(assignments);var impacts=new ArrayList<Impact>();
    @SuppressWarnings("unchecked") var rawImpacts=(List<Map<String,Object>>)inspected.get("impacts");
    for(var impact:rawImpacts){
      var group=groups.stream().filter(g->g.stationId().equals(number(impact.get("stationId")))&&g.level().equals(impact.get("level"))).findFirst().orElseThrow();
      impacts.add(new Impact(String.valueOf(impact.get("key")),String.valueOf(impact.get("stationName")),group.level(),String.valueOf(impact.get("fromName")),((Number)impact.get("count")).intValue(),group.refs()));
    }
    var summary=new Summary(valid,skipped,duplicates,(int)people.values().stream().filter(p->p.userId()==null).count(),(int)people.values().stream().filter(p->p.userId()!=null).count(),(int)groups.stream().filter(Group::changed).map(Group::stationId).distinct().count(),groups.stream().mapToInt(Group::unchangedRelations).sum(),((Number)inspected.get("affectedVersions")).intValue());
    String baseline=hash(json(List.of(identity,inspected.get("baselineHash"),groups)).getBytes(StandardCharsets.UTF_8));
    return new Analysis(rows,errors,warnings,new ArrayList<>(people.values()),groups,assignments,impacts,summary,baseline);
  }

  @Scheduled(fixedDelay=3600000,initialDelay=3600000)
  public void cleanup(){db.update("delete from station_responsibility_import where created_at < date_sub(now(),interval 7 day)");}
  private void require(){permissions.require(Permissions.USER_OPS_ROLE_MANAGE);}
  private Map<String,Object> batch(String id,boolean lock){
    require();var rows=db.queryForList("select *, (expires_at <= now()) expired from station_responsibility_import where id=? and created_by=?"+(lock?" for update":""),id,SecurityUtils.current().id());
    if(rows.isEmpty())throw new BusinessException(404,"导入批次不存在或无权访问");return rows.get(0);
  }
  private Map<String,Object> response(Map<String,Object> batch){var response=new LinkedHashMap<String,Object>();response.put("batchId",batch.get("id"));response.put("status",batch.get("status"));response.put("preview",read(String.valueOf(batch.get("preview")),Preview.class));if(batch.get("result")!=null)response.put("result",read(String.valueOf(batch.get("result")),Map.class));return response;}
  private void verify(Map<String,Object> batch,Analysis analysis){if(!analysis.errors().isEmpty()||!analysis.baseline().equals(batch.get("baseline_hash")))throw conflict("账号、服务站、负责人或请假待办已变化，请重新预览");}
  public static String initialPassword(String no){return no.substring(Math.max(0,no.length()-6));}
  private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
  private <T>T read(String value,Class<T> type){try{return mapper.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
  private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
  private static long number(Object value){return ((Number)value).longValue();}
  private static Diagnostic error(int row,String field,String message,String suggestion){return new Diagnostic(row,field,message,suggestion);}
  private static BusinessException bad(String message){return new BusinessException(400,message);}
  private static BusinessException conflict(String message){return new BusinessException(409,message);}
}
