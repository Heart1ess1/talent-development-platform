package com.talent.platform.station;

import com.talent.platform.common.BusinessException;
import com.talent.platform.leave.LeaveService;
import com.talent.platform.security.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class StationResponsibilityService {
  public record Assignment(Long stationId,String level,List<Long> userIds) {}
  public record Change(List<Assignment> assignments,Map<String,Long> transfers,String reason,String baselineHash) {}
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final LeaveService leaves;
  private final AuditService audit;
  public StationResponsibilityService(JdbcTemplate db,PermissionService permissions,LeaveService leaves,AuditService audit){this.db=db;this.permissions=permissions;this.leaves=leaves;this.audit=audit;}

  public Map<String,Object> context(){
    permissions.require(Permissions.USER_OPS_ROLE_MANAGE);
    return Map.of("stations",db.queryForList("select id,name,enabled from service_station order by name,id"),
        "users",db.queryForList("select id,username,display_name,role,enabled from sys_user where role in ('STATION_MANAGER','AREA_MANAGER') order by display_name,id"),
        "scopes",scopes());
  }

  public Map<String,Object> preview(Change change){
    permissions.require(Permissions.USER_OPS_ROLE_MANAGE);
    validate(change);
    List<Map<String,Object>> current=scopes();
    List<Map<String,Object>> impacts=new ArrayList<>();
    List<Map<String,Object>> affectedRows=new ArrayList<>();
    for(Assignment assignment:change.assignments()){
      Set<Long> desired=new LinkedHashSet<>(assignment.userIds());
      for(var scope:current){
        long station=number(scope.get("station_id")),user=number(scope.get("user_id"));
        if(station!=assignment.stationId()||!assignment.level().equals(scope.get("level"))||desired.contains(user))continue;
        var rows=db.queryForList("""
            select v.request_id,v.version_no,v.approver_user_id,v.status,r.employee_id,r.current_version,r.status request_status
            from leave_request_version v join leave_request r on r.id=v.request_id
            where v.station_id=? and v.approver_user_id=? and v.approver_level=?
              and (v.status='PENDING' or (r.status='APPROVED' and r.current_version=v.version_no))
            order by v.request_id,v.version_no
            """,station,user,assignment.level());
        affectedRows.addAll(rows);
        if(!rows.isEmpty()){
          var item=new LinkedHashMap<String,Object>();
          item.put("key",key(assignment.level(),station,user));item.put("level",assignment.level());
          item.put("stationId",station);item.put("stationName",scope.get("station_name"));
          item.put("fromUserId",user);item.put("fromName",scope.get("display_name"));
          item.put("count",rows.size());item.put("candidateIds",assignment.userIds());
          impacts.add(item);
        }
      }
    }
    var users=db.queryForList("select id,role,enabled,security_version from sys_user where role in ('STATION_MANAGER','AREA_MANAGER') order by id");
    return Map.of("baselineHash",hash(current.toString()+users+affectedRows),"impacts",impacts,
        "affectedVersions",affectedRows.size(),"assignments",change.assignments());
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,Object> save(Change change){
    permissions.require(Permissions.USER_OPS_ROLE_MANAGE);
    if(change==null||change.reason()==null||change.reason().isBlank()||change.reason().length()>500)throw bad("请填写调整原因（最多500字）");
    Map<String,Object> initial=preview(change);
    // Station edits are serialized; leave operations lock employees before requests and accounts.
    var stationIds=change.assignments().stream().map(Assignment::stationId).distinct().sorted().toList();
    for(long station:stationIds)db.queryForList("select id from service_station where id=? for update",station);
    var employeeIds=new TreeSet<Long>();
    var userIds=new TreeSet<Long>();
    for(var assignment:change.assignments()){
      userIds.addAll(assignment.userIds());
      userIds.addAll(db.queryForList("select user_id from "+table(assignment.level())+" where station_id=?",Long.class,assignment.stationId()));
      for(var row:db.queryForList("select v.approver_user_id,r.employee_id from leave_request_version v join leave_request r on r.id=v.request_id where v.station_id=? and v.approver_level=? and (v.status='PENDING' or (r.status='APPROVED' and r.current_version=v.version_no))",assignment.stationId(),assignment.level())){
        employeeIds.add(number(row.get("employee_id")));userIds.add(number(row.get("approver_user_id")));
      }
    }
    for(long employee:employeeIds)db.queryForList("select id from employee where id=? for update",employee);
    for(long user:userIds)db.queryForList("select id from sys_user where id=? for update",user);
    Map<String,Object> latest=preview(change);
    if(change.baselineHash()==null||!change.baselineHash().equals(latest.get("baselineHash"))||!initial.get("baselineHash").equals(latest.get("baselineHash")))throw new BusinessException(409,"负责人或请假待办已变化，请重新预览");
    @SuppressWarnings("unchecked") var impacts=(List<Map<String,Object>>)latest.get("impacts");
    for(var impact:impacts){
      Long target=change.transfers()==null?null:change.transfers().get(String.valueOf(impact.get("key")));
      @SuppressWarnings("unchecked") var allowed=(List<Long>)impact.get("candidateIds");
      if(target==null||!allowed.contains(target))throw bad("请为每组受影响的请假责任选择新的同级负责人");
    }
    // Add incoming scopes before transferring, so the normal leave eligibility check remains authoritative.
    for(var assignment:change.assignments())for(long user:assignment.userIds())
      db.update("insert ignore into "+table(assignment.level())+"(user_id,station_id) values(?,?)",user,assignment.stationId());
    for(var impact:impacts){
      long target=change.transfers().get(String.valueOf(impact.get("key")));
      var rows=db.queryForList("select v.request_id,v.version_no from leave_request_version v join leave_request r on r.id=v.request_id where v.station_id=? and v.approver_user_id=? and v.approver_level=? and (v.status='PENDING' or (r.status='APPROVED' and r.current_version=v.version_no)) order by v.request_id,v.version_no",impact.get("stationId"),impact.get("fromUserId"),impact.get("level"));
      for(var row:rows)leaves.transfer(number(row.get("request_id")),((Number)row.get("version_no")).intValue(),new LeaveService.TransferForm(target,change.reason()));
    }
    for(var assignment:change.assignments()){
      String marks=String.join(",",Collections.nCopies(assignment.userIds().size(),"?"));
      var args=new ArrayList<Object>();args.add(assignment.stationId());args.addAll(assignment.userIds());
      db.update("delete from "+table(assignment.level())+" where station_id=?"+(assignment.userIds().isEmpty()?"":" and user_id not in ("+marks+")"),args.toArray());
    }
    for(long user:userIds)db.update("update sys_user set version=version+1 where id=?",user);
    audit.log("UPDATE_STATION_RESPONSIBILITIES","SERVICE_STATION",null,null,Map.of("assignments",change.assignments(),"transfers",change.transfers()==null?Map.of():change.transfers(),"reason",change.reason()));
    return Map.of("affectedVersions",latest.get("affectedVersions"),"context",context());
  }

  private List<Map<String,Object>> scopes(){return db.queryForList("""
      select x.level,x.user_id,x.station_id,u.display_name,s.name station_name from (
        select 'STATION_MANAGER' level,user_id,station_id from station_manager_scope
        union all select 'AREA_MANAGER' level,user_id,station_id from area_manager_scope
      ) x join sys_user u on u.id=x.user_id join service_station s on s.id=x.station_id
      order by x.level,x.station_id,x.user_id
      """);}
  private void validate(Change change){
    if(change==null||change.assignments()==null||change.assignments().isEmpty()||change.assignments().size()>500)throw bad("请选择需要调整的服务站");
    var seen=new HashSet<String>();
    for(var assignment:change.assignments()){
      if(assignment==null||assignment.stationId()==null||assignment.level()==null||!List.of("STATION_MANAGER","AREA_MANAGER").contains(assignment.level())||assignment.userIds()==null||assignment.userIds().size()>50||assignment.userIds().stream().anyMatch(Objects::isNull))throw bad("负责人配置无效");
      if(!seen.add(assignment.level()+":"+assignment.stationId()))throw bad("同一服务站的同级负责人不能重复配置");
      if(db.queryForObject("select count(*) from service_station where id=?",Integer.class,assignment.stationId())==0)throw bad("服务站不存在");
      for(Long user:new LinkedHashSet<>(assignment.userIds()))if(db.queryForObject("select count(*) from sys_user where id=? and role=? and enabled=true",Integer.class,user,assignment.level())==0)throw bad("负责人账号已停用或角色不匹配");
    }
  }
  private String key(String level,long station,long user){return level+":"+station+":"+user;}
  private String table(String level){return "STATION_MANAGER".equals(level)?"station_manager_scope":"area_manager_scope";}
  private long number(Object value){return ((Number)value).longValue();}
  private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  private BusinessException bad(String message){return new BusinessException(400,message);}
}
