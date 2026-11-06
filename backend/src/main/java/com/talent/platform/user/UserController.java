package com.talent.platform.user;

import com.talent.platform.common.*;import com.talent.platform.security.*;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.security.access.AccessDeniedException;import org.springframework.security.crypto.password.PasswordEncoder;import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.security.SecureRandom;import java.util.*;

@RestController @RequestMapping("/api/v1/users")
public class UserController {
  private static final List<String> OPS_ROLES=List.of("MENTOR","STATION_MANAGER","AREA_MANAGER","TRAINING_DIRECTOR","TRAINING_ADMIN");
  private static final List<String> SYSTEM_ROLES=List.of("ADMIN","SUPER_ADMIN");
  private static final List<String> CHANGE_ROLES=List.of("EMPLOYEE","MENTOR","STATION_MANAGER","AREA_MANAGER","TRAINING_ADMIN","ADMIN","SUPER_ADMIN");
  private final JdbcTemplate db;private final PasswordEncoder encoder;private final PermissionService permissions;private final AuditService audit;private final SecureRandom random=new SecureRandom();public UserController(JdbcTemplate db,PasswordEncoder encoder,PermissionService permissions,AuditService audit){this.db=db;this.encoder=encoder;this.permissions=permissions;this.audit=audit;}
  public record UserRequest(@NotBlank String username,@NotBlank String displayName,@Pattern(regexp="MENTOR|STATION_MANAGER|AREA_MANAGER|TRAINING_ADMIN|ADMIN") String role,List<Long> stationIds){}
  public record EnableRequest(boolean enabled){}
  public record DisplayNameRequest(@NotBlank String displayName){}
  public record UsernameRequest(@NotBlank @Size(max=64) @Pattern(regexp="[A-Za-z0-9._-]+",message="用户名仅支持字母、数字、点、下划线和连字符") String username){}
  public record RolesRequest(List<String> roles,String primaryRole,List<Long> stationIds){}
  public record BulkSelection(String mode,List<Long> ids,Map<String,Object> filters,List<Long> excludedIds){}
  public record BulkRequest(BulkSelection selection,Map<String,Object> changes,boolean syncLinkedEmployeeStatus,Integer expectedCount,String selectionHash,String requestId){}
  @GetMapping
  public ApiResponse<List<Map<String,Object>>> list(@RequestParam(required=false) String role) {
    permissions.require(Permissions.USER_EMPLOYEE_MANAGE);
    var allowed = allowedRoles(SecurityUtils.current());
    if (role != null && !allowed.contains(role)) return ApiResponse.ok(List.of());
    String marks = String.join(",", Collections.nCopies(allowed.size(), "?"));
    var args = new ArrayList<Object>(allowed);
    String where = " where exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code in (" + marks + "))";
    if (role != null) { where += " and exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code=?)"; args.add(role); }
    var rows = db.queryForList("""
        select u.id,u.username,u.display_name,u.role,u.enabled,u.must_change_password,
               (select group_concat(ur.role_code order by ur.is_primary desc,ur.assigned_at,ur.role_code separator ',')
                  from sys_user_role ur where ur.user_id=u.id) role_codes,
               (select ur.role_code from sys_user_role ur where ur.user_id=u.id and ur.is_primary=true) primary_role,
               u.created_at,u.avatar_token,(e.id is not null) has_employee_profile,
               e.id employee_id,e.employee_no,e.name employee_name,
               e.batch_id,b.name batch_name,e.business_unit_id,bu.name business_unit_name,
               e.class_id,cls.label class_name,ss.station_ids,ss.station_names
        from sys_user u
        left join employee e on e.user_id=u.id
        left join talent_batch b on b.id=e.batch_id
        left join business_unit bu on bu.id=e.business_unit_id
        left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
        left join (
          select sms.user_id,group_concat(sms.station_id order by sms.station_id) station_ids,
                 group_concat(s.name order by s.name separator '、') station_names
          from (select user_id,station_id from station_manager_scope union all select user_id,station_id from area_manager_scope) sms
          left join service_station s on s.id=sms.station_id
          group by sms.user_id
        ) ss on ss.user_id=u.id
        """ + where + " order by u.id desc", args.toArray());
    for (var row : rows) {
      String codes = Objects.toString(row.remove("role_codes"), "");
      row.put("roles", codes.isBlank() ? List.of(String.valueOf(row.get("role"))) : List.of(codes.split(",")));
      if (row.get("primary_role") == null) row.put("primary_role", row.get("role"));
    }
    return ApiResponse.ok(rows);
  }
  @PostMapping("/bulk/preview") public ApiResponse<Map<String,Object>> bulkPreview(@RequestBody BulkRequest request){validateBulkRequest(request);permissions.require(Permissions.USER_EMPLOYEE_MANAGE);var ids=resolveBulkIds(request.selection());validateBulkChanges(ids,request);var versions=userVersions(ids);var operationId=UUID.randomUUID().toString();return ApiResponse.ok(Map.of("operationId",operationId,"selectionHash",selectionHash(versions),"matched",ids.size(),"changed",countChanged(ids,request.changes()),"unchanged",ids.size()-countChanged(ids,request.changes()),"blocked",List.of(),"status","PREVIEWED"));}
  @PostMapping("/bulk/execute") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Map<String,Object>> bulkExecute(@RequestBody BulkRequest request){validateBulkRequest(request);permissions.require(Permissions.USER_EMPLOYEE_MANAGE);ensureRequestNotUsed(request.requestId(),"BULK_UPDATE_USERS");var ids=resolveBulkIds(request.selection());validateBulkChanges(ids,request);var versions=userVersions(ids);if(request.expectedCount()!=null&&request.expectedCount()!=ids.size())throw new BusinessException(409,"账号筛选结果已变化，请重新预览");if(request.selectionHash()!=null&&!request.selectionHash().equals(selectionHash(versions)))throw new BusinessException(409,"账号数据已变化，请重新预览");var operationId=UUID.randomUUID().toString();int changed=0;for(Long id:ids)if(updateBulkUser(id,request))changed++;audit.logWithRequestId(request.requestId(),"BULK_UPDATE_USERS","USER",null,Map.of("operationId",operationId,"ids",ids,"count",ids.size()),Map.of("operationId",operationId,"changes",request.changes(),"changed",changed));return ApiResponse.ok(Map.of("operationId",operationId,"matched",ids.size(),"changed",changed,"unchanged",ids.size()-changed,"blocked",List.of(),"status","COMPLETED"));}
  @PostMapping @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Map<String,Object>> create(@Valid @RequestBody UserRequest q){requireRoleManage(q.role());String pwd=temp();db.update("insert into sys_user(username,password_hash,display_name,role,enabled,must_change_password) values(?,?,?,?,true,true)",q.username(),encoder.encode(pwd),q.displayName(),q.role());Long id=db.queryForObject("select last_insert_id()",Long.class);db.update("insert ignore into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,true,?)",id,q.role(),SecurityUtils.current().id());saveStations(id,q.role(),q.stationIds());audit.log("CREATE_USER","USER",id,null,Map.of("role",q.role()));return ApiResponse.ok(Map.of("id",id,"temporaryPassword",pwd));}
  @PutMapping("/{id}/enabled") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> enabled(@PathVariable Long id,@RequestBody EnableRequest q){var current=SecurityUtils.current();String role=db.queryForObject("select role from sys_user where id=?",String.class,id);requireRoleManage(role);if(id.equals(current.id())&&!q.enabled())throw new BusinessException(400,"不能停用当前账号");if(!q.enabled()&&hasPendingTaskScores(id))throw new BusinessException(409,"该账号仍有未完成的任务评分，请先完成或转交后再停用");if(!q.enabled())ensureLeaveTransfer(id);if(!q.enabled()&&ownsSuperAdmin(id,role)&&wouldLeaveNoSuperAdmin(id))throw new BusinessException(400,"必须保留至少一个启用的超级管理员");db.update("update sys_user set enabled=?,version=version+1,security_version=security_version+1 where id=?",q.enabled(),id);audit.log("SET_USER_ENABLED","USER",id,Map.of("enabled",!q.enabled()),Map.of("enabled",q.enabled()));return ApiResponse.ok(null);}
  /** 旧接口保留：语义降级为“设为唯一角色”，内部与角色集合写入共用同一套事务逻辑。 */
  @PutMapping("/{id}/role") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> role(@PathVariable Long id,@RequestBody Map<String,Object> q){
    permissions.require(Permissions.USER_ADMIN_MANAGE);
    String role=Objects.toString(q.get("role"),"");
    if(!CHANGE_ROLES.contains(role))throw new BusinessException(400,"非法角色");
    String old=db.queryForObject("select role from sys_user where id=?",String.class,id);
    if(id.equals(SecurityUtils.current().id()))throw new BusinessException(400,"不能修改当前账号角色");
    boolean linkedEmployee=hasEmployeeProfile(id);
    if(linkedEmployee&&!"EMPLOYEE".equals(role))throw new BusinessException(400,"员工账号角色不可修改");
    if("EMPLOYEE".equals(role)&&!linkedEmployee)throw new BusinessException(400,"该账号未关联员工档案，不能设为员工角色");
    if(!old.equals(role)||List.of("STATION_MANAGER","AREA_MANAGER").contains(role))ensureLeaveTransfer(id);
    if(ownsSuperAdmin(id,old)&&!"SUPER_ADMIN".equals(role)&&wouldLeaveNoSuperAdmin(id))throw new BusinessException(400,"必须保留至少一个超级管理员");
    List<Long> stations=q.get("stationIds") instanceof List<?> values?values.stream().map(x->Long.valueOf(String.valueOf(x))).toList():List.of();
    if(List.of("STATION_MANAGER","AREA_MANAGER").contains(role)&&stations.isEmpty())throw new BusinessException(400,"请为新角色选择负责的服务站");
    var before=ownedRoles(id,old);
    writeRoleSet(id,old,before,before.isEmpty()?null:before.get(0),List.of(role),role,stations);
    return ApiResponse.ok(null);
  }
  /** 新接口：整体设置角色集合与主身份（同一事务内增删角色行、改主角色、同步 sys_user.role 镜像并递增 security_version）。 */
  @PutMapping("/{id}/roles") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> roleSet(@PathVariable Long id,@RequestBody RolesRequest q){
    permissions.require(Permissions.USER_ADMIN_MANAGE);
    var target=db.queryForList("select id,role from sys_user where id=?",id);
    if(target.isEmpty())throw new BusinessException(404,"账号不存在");
    String mirrored=Objects.toString(target.get(0).get("role"),"");
    var roles=new LinkedHashSet<String>();
    if(q.roles()!=null)for(String role:q.roles()){
      if(role==null||!CHANGE_ROLES.contains(role))throw new BusinessException(400,"非法角色："+(role==null?"空":role));
      roles.add(role);
    }
    if(roles.isEmpty())throw new BusinessException(400,"请至少选择一个角色");
    if(q.primaryRole()==null||!roles.contains(q.primaryRole()))throw new BusinessException(400,"主身份必须是已分配角色之一");
    if(id.equals(SecurityUtils.current().id()))throw new BusinessException(400,"不能修改当前账号角色");
    var allowed=allowedRoles(SecurityUtils.current());
    for(String role:roles)if(!allowed.contains(role))throw new BusinessException(403,"无权分配角色："+role);
    boolean linked=hasEmployeeProfile(id);
    if(linked&&!roles.contains("EMPLOYEE"))throw new BusinessException(400,"关联员工档案的账号必须保留员工角色");
    if(!linked&&roles.contains("EMPLOYEE"))throw new BusinessException(400,"该账号未关联员工档案，不能设为员工角色");
    var before=ownedRoles(id, mirrored);
    if(!new HashSet<>(before).equals(roles)||roles.contains("STATION_MANAGER")||roles.contains("AREA_MANAGER"))ensureLeaveTransfer(id);
    if(ownsSuperAdmin(id, mirrored)&&!roles.contains("SUPER_ADMIN")&&wouldLeaveNoSuperAdmin(id))throw new BusinessException(400,"必须保留至少一个启用的超级管理员");
    writeRoleSet(id, mirrored, before, before.isEmpty()?null:before.get(0), new ArrayList<>(roles), q.primaryRole(), q.stationIds());
    return ApiResponse.ok(null);
  }
  @PutMapping("/{id}/display-name") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> displayName(@PathVariable Long id,@Valid @RequestBody DisplayNameRequest q){permissions.require(Permissions.USER_ADMIN_MANAGE);var before=db.queryForMap("select id,display_name from sys_user where id=?",id);db.update("update sys_user set display_name=?,version=version+1 where id=?",q.displayName(),id);if(hasEmployeeProfile(id))db.update("update employee set name=?,version=version+1 where user_id=?",q.displayName(),id);audit.log("CHANGE_DISPLAY_NAME","USER",id,before,Map.of("displayName",q.displayName()));return ApiResponse.ok(null);}
  @PutMapping("/{id}/username") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> username(@PathVariable Long id,@Valid @RequestBody UsernameRequest q){
    permissions.require(Permissions.USER_ADMIN_MANAGE);
    var before=db.queryForMap("select id,username,role from sys_user where id=?",id);
    if("SUPER_ADMIN".equals(before.get("role")))throw new BusinessException(403,"不能修改超级管理员账号的用户名");
    String username=q.username().trim();
    if(username.equals(before.get("username")))return ApiResponse.ok(null);
    db.update("update sys_user set username=?,version=version+1,security_version=security_version+1 where id=?",username,id);
    if(hasEmployeeProfile(id))db.update("update employee set employee_no=?,version=version+1 where user_id=?",username,id);
    audit.log("CHANGE_USERNAME","USER",id,before,Map.of("username",username));
    return ApiResponse.ok(null);
  }
  @PostMapping("/{id}/reset-password") public ApiResponse<Map<String,String>> reset(@PathVariable Long id){
    String role=db.queryForObject("select role from sys_user where id=?",String.class,id);
    requireRoleManage(role);
    String pwd=temp();
    db.update("update sys_user set password_hash=?,must_change_password=true,version=version+1,security_version=security_version+1 where id=?",encoder.encode(pwd),id);
    audit.log("RESET_PASSWORD","USER",id,null,null);
    return ApiResponse.ok(Map.of("temporaryPassword",pwd));
  }
  @PutMapping("/{id}/stations") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> stations(@PathVariable Long id,@RequestBody Map<String,List<Long>> q){String role=db.queryForObject("select role from sys_user where id=?",String.class,id);if(!List.of("STATION_MANAGER","AREA_MANAGER").contains(role))throw new BusinessException(400,"该角色不使用服务站范围");requireRoleManage(role);ensureLeaveTransfer(id);saveStations(id,role,q.get("stationIds"));audit.log("SET_STATION_SCOPE","USER",id,null,q);return ApiResponse.ok(null);}
  private void validateBulkRequest(BulkRequest request){if(request==null||request.selection()==null||request.changes()==null||request.changes().isEmpty())throw new BusinessException(400,"请选择账号和至少一个修改字段");if(!Set.of("IDS","FILTER").contains(request.selection().mode()))throw new BusinessException(400,"非法选择方式");if("IDS".equals(request.selection().mode())&&(request.selection().ids()==null||request.selection().ids().isEmpty()))throw new BusinessException(400,"请选择至少一个账号");if(request.selection().ids()!=null&&request.selection().ids().size()>500)throw new BusinessException(400,"单次最多修改500个账号");if(request.changes().keySet().stream().anyMatch(key->!Set.of("enabled","role","stationIds").contains(key)))throw new BusinessException(400,"包含不支持的批量字段");}
  private List<Long> resolveBulkIds(BulkSelection selection){var allowed=allowedRoles(SecurityUtils.current());var args=new ArrayList<Object>(allowed);var marks=String.join(",",Collections.nCopies(allowed.size(),"?"));var where=new StringBuilder(" where exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code in ("+marks+"))");if("IDS".equals(selection.mode())){var ids=new LinkedHashSet<>(selection.ids()==null?List.<Long>of():selection.ids());if(selection.excludedIds()!=null)ids.removeAll(selection.excludedIds());if(ids.isEmpty())throw new BusinessException(400,"没有可修改的账号");if(ids.size()>500)throw new BusinessException(400,"单次最多修改500个账号");where.append(" and u.id in (").append(String.join(",",Collections.nCopies(ids.size(),"?"))).append(")");args.addAll(ids);}else applyUserFilters(where,args,selection.filters());var rows=db.queryForList("select u.id from sys_user u left join employee e on e.user_id=u.id left join talent_batch b on b.id=e.batch_id left join business_unit bu on bu.id=e.business_unit_id"+where+" order by u.id",args.toArray());if(rows.isEmpty())throw new BusinessException(400,"筛选结果为空");if(rows.size()>500)throw new BusinessException(400,"单次最多修改500个账号，请缩小筛选范围");return rows.stream().map(row->((Number)row.get("id")).longValue()).toList();}
  private void applyUserFilters(StringBuilder where,List<Object> args,Map<String,Object> filters){if(filters==null)return;Object keyword=filters.get("keyword");if(keyword!=null&&!String.valueOf(keyword).isBlank()){where.append(" and (u.username like ? or u.display_name like ? or e.employee_no like ? or e.name like ? or b.name like ? or bu.name like ?)");String value="%"+String.valueOf(keyword).trim()+"%";for(int i=0;i<6;i++)args.add(value);}Object role=filters.get("role");if(role!=null&&!String.valueOf(role).isBlank()){where.append(" and exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code=?)");args.add(role);}Object enabled=filters.get("enabled");if(enabled!=null&&!String.valueOf(enabled).isBlank()){where.append(" and u.enabled=?");args.add("enabled".equals(String.valueOf(enabled)));}addUserEquals(where,args,filters,"batchId","e.batch_id");addUserEquals(where,args,filters,"businessUnitId","e.business_unit_id");addUserEquals(where,args,filters,"classId","e.class_id");Object linked=filters.get("linked");if(linked!=null&&!String.valueOf(linked).isBlank())where.append("linked".equals(String.valueOf(linked))?" and e.id is not null":" and e.id is null");}
  private void addUserEquals(StringBuilder where,List<Object> args,Map<String,Object> filters,String key,String column){Object value=filters.get(key);if(value==null||String.valueOf(value).isBlank())return;where.append(" and ").append(column).append("=?");args.add(value);}
  private Map<Long,Integer> userVersions(List<Long> ids){var marks=String.join(",",Collections.nCopies(ids.size(),"?"));var rows=db.queryForList("select id,version from sys_user where id in ("+marks+") order by id",ids.toArray());if(rows.size()!=ids.size())throw new BusinessException(404,"部分账号不存在或无权访问");var result=new LinkedHashMap<Long,Integer>();rows.forEach(row->result.put(((Number)row.get("id")).longValue(),((Number)row.get("version")).intValue()));return result;}
  private String selectionHash(Map<Long,Integer> versions){try{var digest=MessageDigest.getInstance("SHA-256");var value=versions.entrySet().stream().map(entry->entry.getKey()+":"+entry.getValue()).reduce((a,b)->a+","+b).orElse("");return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
  private void validateBulkChanges(List<Long> ids,BulkRequest request){var changes=request.changes();if(changes.containsKey("role")&&List.of("STATION_MANAGER","AREA_MANAGER").contains(String.valueOf(changes.get("role")))&&(!(changes.get("stationIds") instanceof List<?> scopes)||scopes.isEmpty()))throw new BusinessException(400,"请为新角色选择服务站范围");if(changes.containsKey("enabled")){permissions.require(Permissions.USER_EMPLOYEE_MANAGE);for(Long id:ids){String role=db.queryForObject("select role from sys_user where id=?",String.class,id);requireRoleManage(role);boolean enabled=Boolean.parseBoolean(String.valueOf(changes.get("enabled")));if(id.equals(SecurityUtils.current().id())&&!enabled)throw new BusinessException(400,"不能停用当前账号");if(!enabled&&hasPendingTaskScores(id))throw new BusinessException(409,"该账号仍有未完成的任务评分，请先完成或转交后再停用");if(!enabled&&ownsSuperAdmin(id,role)&&wouldLeaveNoSuperAdmin(id))throw new BusinessException(400,"必须保留至少一个启用的超级管理员");}}
    if(changes.containsKey("role")){permissions.require(Permissions.USER_ADMIN_MANAGE);String target=String.valueOf(changes.get("role"));if(!CHANGE_ROLES.contains(target))throw new BusinessException(400,"非法角色");for(Long id:ids){String old=db.queryForObject("select role from sys_user where id=?",String.class,id);if(id.equals(SecurityUtils.current().id()))throw new BusinessException(400,"不能修改当前账号角色");boolean linked=hasEmployeeProfile(id);if(linked&&!"EMPLOYEE".equals(target))throw new BusinessException(400,"员工账号角色不可修改");if("EMPLOYEE".equals(target)&&!linked)throw new BusinessException(400,"该账号未关联员工档案，不能设为员工角色");if(ownsSuperAdmin(id,old)&&!"SUPER_ADMIN".equals(target)&&wouldLeaveNoSuperAdmin(id))throw new BusinessException(400,"必须保留至少一个启用的超级管理员");}}
    if(changes.containsKey("stationIds")){permissions.require(Permissions.USER_OPS_ROLE_MANAGE);if(!(changes.get("stationIds") instanceof List<?> stationIds)||stationIds.isEmpty())throw new BusinessException(400,"服务站范围不能为空");for(Long id:ids){String mirrored=String.valueOf(db.queryForObject("select role from sys_user where id=?",String.class,id));String role=changes.containsKey("role")?String.valueOf(changes.get("role")):stationRoleFor(id, mirrored);if(!List.of("STATION_MANAGER","AREA_MANAGER").contains(role))throw new BusinessException(400,"只有站长和区域经理可以设置服务站范围");requireRoleManage(role);}}
  }
  private int countChanged(List<Long> ids,Map<String,Object> changes){var marks=String.join(",",Collections.nCopies(ids.size(),"?"));var rows=db.queryForList("select id,enabled,role from sys_user where id in ("+marks+")",ids.toArray());return (int)rows.stream().filter(row->(changes.containsKey("enabled")&&!Objects.equals(Boolean.valueOf(String.valueOf(row.get("enabled"))),Boolean.valueOf(String.valueOf(changes.get("enabled")))))||(changes.containsKey("role")&&!Objects.equals(String.valueOf(row.get("role")),String.valueOf(changes.get("role"))))||(changes.containsKey("stationIds")&&!stationScopeEquals(((Number)row.get("id")).longValue(),changes.get("stationIds")))).count();}
  private boolean stationScopeEquals(Long userId,Object value){var desired=((List<?>)value).stream().map(item->Long.valueOf(String.valueOf(item))).sorted().toList();var current=db.queryForList("select station_id from (select user_id,station_id from station_manager_scope union all select user_id,station_id from area_manager_scope) s where user_id=? order by station_id",Long.class,userId);return current.equals(desired);}
  private boolean updateBulkUser(Long id,BulkRequest request){
    var changes=request.changes();
    var before=db.queryForMap("select id,role,enabled from sys_user where id=? for update",id);
    boolean changingRole=changes.containsKey("role")&&!Objects.equals(before.get("role"),changes.get("role"));
    boolean disabling=changes.containsKey("enabled")&&!Boolean.parseBoolean(String.valueOf(changes.get("enabled")));
    boolean changingScope=changes.containsKey("stationIds")&&!stationScopeEquals(id,changes.get("stationIds"));
    if(changingRole||disabling||changingScope)ensureLeaveTransfer(id);
    boolean changed=false;
    if(changes.containsKey("enabled")){
      boolean enabled=Boolean.parseBoolean(String.valueOf(changes.get("enabled")));
      if(!Objects.equals(Boolean.valueOf(String.valueOf(before.get("enabled"))),enabled)){
        db.update("update sys_user set enabled=?,version=version+1,security_version=security_version+1 where id=?",enabled,id);
        if(request.syncLinkedEmployeeStatus()&&hasEmployeeProfile(id))db.update("update employee set status=?,version=version+1 where user_id=?",enabled?"ACTIVE":"INACTIVE",id);
        changed=true;
      }
    }
    if(changingRole){syncSingleRole(id,String.valueOf(changes.get("role")));db.update("update sys_user set role=?,version=version+1,security_version=security_version+1 where id=?",changes.get("role"),id);changed=true;}
    if(changingScope||changingRole){
      String stationRole=changes.containsKey("role")?String.valueOf(changes.get("role")):stationRoleFor(id,String.valueOf(before.get("role")));
      saveStations(id,stationRole,changes.get("stationIds") instanceof List<?> values?values.stream().map(value->Long.valueOf(String.valueOf(value))).toList():List.of());
      db.update("update sys_user set version=version+1,security_version=security_version+1 where id=?",id);changed=true;
    }
    return changed;
  }
  private void ensureRequestNotUsed(String requestId,String action){if(requestId==null||requestId.isBlank())return;Integer count=db.queryForObject("select count(*) from operation_log where request_id=? and action=?",Integer.class,requestId,action);if(count!=null&&count>0)throw new BusinessException(409,"该批量请求已处理，请勿重复提交");}
  private List<String> allowedRoles(CurrentUser u){var roles=new ArrayList<String>();roles.add("EMPLOYEE");if(u.can(Permissions.USER_OPS_ROLE_MANAGE))roles.addAll(OPS_ROLES);if(u.can(Permissions.USER_ADMIN_MANAGE))roles.addAll(SYSTEM_ROLES);return roles;}
  private void requireRoleManage(String role){if("EMPLOYEE".equals(role))permissions.require(Permissions.USER_EMPLOYEE_MANAGE);else if(OPS_ROLES.contains(role))permissions.require(Permissions.USER_OPS_ROLE_MANAGE);else if(SYSTEM_ROLES.contains(role))permissions.require(Permissions.USER_ADMIN_MANAGE);else throw new BusinessException(400,"非法角色");}
  private boolean hasEmployeeProfile(Long id){Integer n=db.queryForObject("select count(*) from employee where user_id=?",Integer.class,id);return n!=null&&n>0;}
  private boolean hasPendingTaskScores(Long id){Integer n=db.queryForObject("select count(*) from task_reviewer_scope_member m join task_reviewer_scope rs on rs.id=m.scope_id and rs.status='ACTIVE' where m.reviewer_user_id=? and exists(select 1 from task_assignment a where a.scoring_scope_id=rs.id and a.status in ('NOT_SUBMITTED','PENDING_REVIEW','RETURNED'))",Integer.class,id);return n!=null&&n>0;}
  private void saveStations(Long id,String role,List<Long> ids){db.update("delete from station_manager_scope where user_id=?",id);db.update("delete from area_manager_scope where user_id=?",id);if(!List.of("STATION_MANAGER","AREA_MANAGER").contains(role))return;if(ids==null||ids.isEmpty())throw new BusinessException(400,"AREA_MANAGER".equals(role)?"区域经理至少需要绑定一个服务站":"站长至少需要绑定一个服务站");for(Long stationId:new LinkedHashSet<>(ids))db.update("insert into "+("AREA_MANAGER".equals(role)?"area_manager_scope":"station_manager_scope")+"(user_id,station_id) values(?,?)",id,stationId);}
  /** 账号当前的完整角色集合：主角色在前，其余按分配时间；没有任何角色行时回退 sys_user.role。 */
  private List<String> ownedRoles(Long id,String mirrored){
    var rows=db.queryForList("select role_code from sys_user_role where user_id=? order by is_primary desc,assigned_at,role_code",id);
    var roles=new ArrayList<String>();
    for(var row:rows){String code=Objects.toString(row.get("role_code"),"");if(!code.isBlank()&&!roles.contains(code))roles.add(code);}
    if(roles.isEmpty()&&mirrored!=null&&!mirrored.isBlank())roles.add(mirrored);
    return roles;
  }
  /** 是否拥有 SUPER_ADMIN 角色；表里没有任何角色行时回退 sys_user.role（兼容历史账号）。 */
  private boolean ownsSuperAdmin(Long id,String mirrored){
    Integer owned=db.queryForObject("select count(*) from sys_user_role where user_id=? and role_code='SUPER_ADMIN'",Integer.class,id);
    if(owned!=null&&owned>0)return true;
    Integer rows=db.queryForObject("select count(*) from sys_user_role where user_id=?",Integer.class,id);
    return (rows==null||rows==0)&&"SUPER_ADMIN".equals(mirrored);
  }
  /** 除该账号外，系统里还能登入的超管去重人数为 0 时返回 true。 */
  private boolean wouldLeaveNoSuperAdmin(Long id){
    Integer count=db.queryForObject("select count(distinct r.user_id) from sys_user_role r join sys_user u on u.id=r.user_id where r.role_code='SUPER_ADMIN' and u.enabled=true and r.user_id<>?",Integer.class,id);
    return count!=null&&count==0;
  }
  /** 批量设置服务站范围时，按账号实际拥有角色决定写入哪张范围表。 */
  private String stationRoleFor(Long id,String mirrored){
    if(List.of("STATION_MANAGER","AREA_MANAGER").contains(mirrored))return mirrored;
    var owned=ownedRoles(id, mirrored);
    if(owned.contains("AREA_MANAGER"))return "AREA_MANAGER";
    if(owned.contains("STATION_MANAGER"))return "STATION_MANAGER";
    return mirrored;
  }
  /** 同一事务内替换角色集合、主角色与服务站范围，并同步 sys_user.role 镜像、递增 security_version。 */
  private void writeRoleSet(Long id,String mirrored,List<String> before,String beforePrimary,List<String> roles,String primaryRole,List<Long> stationIds){
    boolean rolesChanged=!new HashSet<>(before).equals(new HashSet<>(roles));
    boolean primaryChanged=!Objects.equals(primaryRole,beforePrimary);
    boolean scopeChanged=saveRoleStations(id,roles,stationIds);
    boolean mirrorChanged=!Objects.equals(primaryRole, mirrored);
    if(!rolesChanged&&!primaryChanged&&!scopeChanged&&!mirrorChanged)return;
    if(rolesChanged||primaryChanged){
      String marks=String.join(",",Collections.nCopies(roles.size(),"?"));
      var args=new ArrayList<Object>();args.add(id);args.addAll(roles);
      db.update("delete from sys_user_role where user_id=? and role_code not in ("+marks+")",args.toArray());
      // 先清空主角色标记，避免插入新主角色时撞上 uk_user_role_primary(user_id,primary_slot)。
      db.update("update sys_user_role set is_primary=false where user_id=?",id);
      Long assignedBy=SecurityUtils.current().id();
      for(String role:roles)db.update("insert ignore into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,?,?)",id,role,role.equals(primaryRole),assignedBy);
      db.update("update sys_user_role set is_primary=true where user_id=? and role_code=?",id,primaryRole);
    }
    db.update("update sys_user set role=?,version=version+1,security_version=security_version+1 where id=?",primaryRole,id);
    var beforeSnapshot=new LinkedHashMap<String,Object>();beforeSnapshot.put("roles",before);beforeSnapshot.put("primaryRole",beforePrimary);
    var afterSnapshot=new LinkedHashMap<String,Object>();afterSnapshot.put("roles",roles);afterSnapshot.put("primaryRole",primaryRole);
    audit.log("CHANGE_USER_ROLES","USER",id,beforeSnapshot,afterSnapshot);
  }
  /** 按角色集合维护服务站范围；不再拥有站长/区域经理时清空两张范围表。 */
  private boolean saveRoleStations(Long id,Collection<String> roles,List<Long> stationIds){
    boolean stationManager=roles.contains("STATION_MANAGER"),areaManager=roles.contains("AREA_MANAGER");
    if(!stationManager&&!areaManager)return clearStationScopes(id);
    List<Long> desired=stationIds==null?List.of():new ArrayList<>(new LinkedHashSet<>(stationIds));
    if(desired.isEmpty()){
      if(stationManager&&!hasStationScope(id,"station_manager_scope"))throw new BusinessException(400,"站长至少需要绑定一个服务站");
      if(areaManager&&!hasStationScope(id,"area_manager_scope"))throw new BusinessException(400,"区域经理至少需要绑定一个服务站");
      return false;
    }
    boolean changed=false;
    if(stationManager)changed|=replaceStationScope(id,"station_manager_scope",desired);
    if(areaManager)changed|=replaceStationScope(id,"area_manager_scope",desired);
    return changed;
  }
  private boolean clearStationScopes(Long id){boolean existed=hasStationScope(id,"station_manager_scope")||hasStationScope(id,"area_manager_scope");db.update("delete from station_manager_scope where user_id=?",id);db.update("delete from area_manager_scope where user_id=?",id);return existed;}
  private boolean hasStationScope(Long id,String table){Integer n=db.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,id);return n!=null&&n>0;}
  private boolean replaceStationScope(Long id,String table,List<Long> stationIds){
    var current=db.queryForList("select station_id from "+table+" where user_id=? order by station_id",Long.class,id);
    var desired=new ArrayList<>(new LinkedHashSet<>(stationIds));desired.sort(Long::compareTo);
    if(current.equals(desired))return false;
    db.update("delete from "+table+" where user_id=?",id);
    for(Long stationId:desired)db.update("insert into "+table+"(user_id,station_id) values(?,?)",id,stationId);
    return true;
  }
  /** 批量“设为唯一角色”：删除其它角色行，保留/插入目标角色并置为主角色。 */
  private void syncSingleRole(Long id,String role){
    db.update("delete from sys_user_role where user_id=? and role_code<>?",id,role);
    db.update("insert ignore into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,true,?)",id,role,SecurityUtils.current().id());
    db.update("update sys_user_role set is_primary=true where user_id=? and role_code=?",id,role);
  }
  private void ensureLeaveTransfer(Long id){db.queryForList("select id from sys_user where id=? for update",id);Integer n=db.queryForObject("select count(*) from leave_request_version v join leave_request r on r.id=v.request_id where v.approver_user_id=? and (v.status='PENDING' or (v.version_no=r.current_version and r.status='APPROVED'))",Integer.class,id);if(n!=null&&n>0)throw new BusinessException(409,"该账号仍有请假审批或未销假责任，请先转交");}
  private String temp(){return "Tmp"+(100000+random.nextInt(900000))+"!a";}
}
