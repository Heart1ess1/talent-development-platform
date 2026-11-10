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
  public record RolesRequest(List<String> roles,String primaryRole,List<Long> stationIds,
                             List<Long> stationManagerStationIds,List<Long> areaManagerStationIds){}
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
    if(!SecurityUtils.current().can(Permissions.USER_ADMIN_MANAGE))where += " and not exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code in ('ADMIN','SUPER_ADMIN'))";
    if (role != null) { where += " and exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code=?)"; args.add(role); }
    var rows = db.queryForList("""
        select u.id,u.username,u.display_name,u.role,u.enabled,u.must_change_password,
               (select group_concat(ur.role_code order by ur.is_primary desc,ur.assigned_at,ur.role_code separator ',')
                  from sys_user_role ur where ur.user_id=u.id) role_codes,
               (select ur.role_code from sys_user_role ur where ur.user_id=u.id and ur.is_primary=true) primary_role,
               u.created_at,u.avatar_token,(e.id is not null) has_employee_profile,
               e.id employee_id,e.employee_no,e.name employee_name,
               e.batch_id,b.name batch_name,e.business_unit_id,bu.name business_unit_name,
               e.class_id,cls.label class_name,ss.station_ids,ss.station_names,
               (select group_concat(station_id order by station_id) from station_manager_scope where user_id=u.id) station_manager_station_ids,
               (select group_concat(station_id order by station_id) from area_manager_scope where user_id=u.id) area_manager_station_ids,
               (select group_concat(s.name order by s.name separator '、') from station_manager_scope x join service_station s on s.id=x.station_id where x.user_id=u.id) station_manager_station_names,
               (select group_concat(s.name order by s.name separator '、') from area_manager_scope x join service_station s on s.id=x.station_id where x.user_id=u.id) area_manager_station_names
        from sys_user u
        left join employee e on e.user_id=u.id
        left join talent_batch b on b.id=e.batch_id
        left join business_unit bu on bu.id=e.business_unit_id
        left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
        left join (
          select sms.user_id,group_concat(distinct sms.station_id order by sms.station_id) station_ids,
                 group_concat(distinct s.name order by s.name separator '、') station_names
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
  @PostMapping("/bulk/preview") public ApiResponse<Map<String,Object>> bulkPreview(@RequestBody BulkRequest request){validateBulkRequest(request);permissions.require(Permissions.USER_EMPLOYEE_MANAGE);var ids=resolveBulkIds(request.selection());validateBulkChanges(ids,request);var versions=userVersions(ids);var operationId=UUID.randomUUID().toString();int changed=countChanged(ids,request.changes());return ApiResponse.ok(Map.of("operationId",operationId,"selectionHash",selectionHash(versions),"matched",ids.size(),"changed",changed,"unchanged",ids.size()-changed,"blocked",List.of(),"status","PREVIEWED"));}
  @PostMapping("/bulk/execute") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Map<String,Object>> bulkExecute(@RequestBody BulkRequest request){validateBulkRequest(request);permissions.require(Permissions.USER_EMPLOYEE_MANAGE);ensureRequestNotUsed(request.requestId(),"BULK_UPDATE_USERS");var ids=resolveBulkIds(request.selection());lockBulkUsers(ids);validateBulkChanges(ids,request);var versions=userVersions(ids);if(request.expectedCount()!=null&&request.expectedCount()!=ids.size())throw new BusinessException(409,"账号筛选结果已变化，请重新预览");if(request.selectionHash()!=null&&!request.selectionHash().equals(selectionHash(versions)))throw new BusinessException(409,"账号数据已变化，请重新预览");var operationId=UUID.randomUUID().toString();int changed=0;for(Long id:ids)if(updateBulkUser(id,request))changed++;audit.logWithRequestId(request.requestId(),"BULK_UPDATE_USERS","USER",null,Map.of("operationId",operationId,"ids",ids,"count",ids.size()),Map.of("operationId",operationId,"changes",request.changes(),"changed",changed));return ApiResponse.ok(Map.of("operationId",operationId,"matched",ids.size(),"changed",changed,"unchanged",ids.size()-changed,"blocked",List.of(),"status","COMPLETED"));}
  @PostMapping @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Map<String,Object>> create(@Valid @RequestBody UserRequest q){requireRoleManage(q.role());String pwd=temp();db.update("insert into sys_user(username,password_hash,display_name,role,enabled,must_change_password) values(?,?,?,?,true,true)",q.username(),encoder.encode(pwd),q.displayName(),q.role());Long id=db.queryForObject("select last_insert_id()",Long.class);db.update("insert ignore into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,?,true,?)",id,q.role(),SecurityUtils.current().id());saveStations(id,q.role(),q.stationIds());audit.log("CREATE_USER","USER",id,null,Map.of("role",q.role()));return ApiResponse.ok(Map.of("id",id,"temporaryPassword",pwd));}
  @PutMapping("/{id}/enabled") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> enabled(@PathVariable Long id,@RequestBody EnableRequest q){var current=SecurityUtils.current();String role=db.queryForObject("select role from sys_user where id=?",String.class,id);requireAccountManage(id,role);if(id.equals(current.id())&&!q.enabled())throw new BusinessException(400,"不能停用当前账号");if(!q.enabled()&&hasPendingTaskScores(id))throw new BusinessException(409,"该账号仍有未完成的任务评分，请先完成或转交后再停用");if(!q.enabled())ensureLeaveTransfer(id);if(!q.enabled()&&ownsSuperAdmin(id,role)&&wouldLeaveNoSuperAdmin(id))throw new BusinessException(400,"必须保留至少一个启用的超级管理员");db.update("update sys_user set enabled=?,version=version+1,security_version=security_version+1 where id=?",q.enabled(),id);audit.log("SET_USER_ENABLED","USER",id,Map.of("enabled",!q.enabled()),Map.of("enabled",q.enabled()));return ApiResponse.ok(null);}
  /** 旧接口保留：语义降级为“设为唯一角色”，内部与角色集合写入共用同一套事务逻辑。 */
  @PutMapping("/{id}/role") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> role(@PathVariable Long id,@RequestBody Map<String,Object> q){
    permissions.require(Permissions.USER_ADMIN_MANAGE);
    db.queryForList("select id from sys_user where id=? for update",id);
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
    ensureRoleRemoval(id,before,List.of(role));
    writeRoleSet(id,old,before,before.isEmpty()?null:before.get(0),List.of(role),role,stations);
    return ApiResponse.ok(null);
  }
  /** 新接口：整体设置角色集合与主身份（同一事务内增删角色行、改主角色、同步 sys_user.role 镜像并递增 security_version）。 */
  @PutMapping("/{id}/roles") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> roleSet(@PathVariable Long id,@RequestBody RolesRequest q){
    permissions.require(Permissions.USER_ADMIN_MANAGE);
    db.queryForList("select id from sys_user where id=? for update",id);
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
    ensureRoleRemoval(id,before,new ArrayList<>(roles));
    var scopes=roleScopes(roles,q.stationIds(),q.stationManagerStationIds(),q.areaManagerStationIds());
    for(String role:List.of("STATION_MANAGER","AREA_MANAGER"))
      if(roles.contains(role)&&scopes.containsKey(role)&&!stationScopeEquals(id,role,scopes.get(role)))ensureLeaveTransfer(id,role);
    if(ownsSuperAdmin(id, mirrored)&&!roles.contains("SUPER_ADMIN")&&wouldLeaveNoSuperAdmin(id))throw new BusinessException(400,"必须保留至少一个启用的超级管理员");
    writeRoleSet(id, mirrored, before, before.isEmpty()?null:before.get(0), new ArrayList<>(roles), q.primaryRole(),scopes);
    return ApiResponse.ok(null);
  }
  @PutMapping("/{id}/display-name") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> displayName(@PathVariable Long id,@Valid @RequestBody DisplayNameRequest q){permissions.require(Permissions.USER_ADMIN_MANAGE);var before=db.queryForMap("select id,display_name from sys_user where id=?",id);db.update("update sys_user set display_name=?,version=version+1 where id=?",q.displayName(),id);if(hasEmployeeProfile(id))db.update("update employee set name=?,version=version+1 where user_id=?",q.displayName(),id);audit.log("CHANGE_DISPLAY_NAME","USER",id,before,Map.of("displayName",q.displayName()));return ApiResponse.ok(null);}
  @PutMapping("/{id}/username") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Void> username(@PathVariable Long id,@Valid @RequestBody UsernameRequest q){
    permissions.require(Permissions.USER_ADMIN_MANAGE);
    var before=db.queryForMap("select id,username,role from sys_user where id=?",id);
    if(ownsSuperAdmin(id,String.valueOf(before.get("role"))))throw new BusinessException(403,"不能修改超级管理员账号的用户名");
    String username=q.username().trim();
    if(username.equals(before.get("username")))return ApiResponse.ok(null);
    db.update("update sys_user set username=?,version=version+1,security_version=security_version+1 where id=?",username,id);
    if(hasEmployeeProfile(id))db.update("update employee set employee_no=?,version=version+1 where user_id=?",username,id);
    audit.log("CHANGE_USERNAME","USER",id,before,Map.of("username",username));
    return ApiResponse.ok(null);
  }
  @PostMapping("/{id}/reset-password") @Transactional(isolation=Isolation.READ_COMMITTED) public ApiResponse<Map<String,String>> reset(@PathVariable Long id){
    String role=db.queryForObject("select role from sys_user where id=?",String.class,id);
    requireAccountManage(id,role);
    String pwd=temp();
    db.update("update sys_user set password_hash=?,must_change_password=true,version=version+1,security_version=security_version+1 where id=?",encoder.encode(pwd),id);
    audit.log("RESET_PASSWORD","USER",id,null,null);
    return ApiResponse.ok(Map.of("temporaryPassword",pwd));
  }
  public record StationScopeRequest(String role,List<Long> stationIds){}
  @PutMapping("/{id}/stations") @Transactional(isolation=Isolation.READ_COMMITTED)
  public ApiResponse<Void> stations(@PathVariable Long id,@RequestBody StationScopeRequest q){
    String mirrored=db.queryForObject("select role from sys_user where id=?",String.class,id);
    requireAccountManage(id,mirrored);
    String role=scopeRole(id,mirrored,q.role());
    if(q.stationIds()==null||q.stationIds().isEmpty())throw new BusinessException(400,"服务站范围不能为空");
    ensureLeaveTransfer(id,role);
    if(replaceStationScope(id,scopeTable(role),q.stationIds()))db.update("update sys_user set version=version+1,security_version=security_version+1 where id=?",id);
    audit.log("SET_STATION_SCOPE","USER",id,null,Map.of("role",role,"stationIds",q.stationIds()));
    return ApiResponse.ok(null);
  }
  private void validateBulkRequest(BulkRequest request){
    if(request==null||request.selection()==null||request.changes()==null||request.changes().isEmpty())throw new BusinessException(400,"请选择账号和至少一个修改字段");
    if(!Set.of("IDS","FILTER").contains(request.selection().mode()))throw new BusinessException(400,"非法选择方式");
    if("IDS".equals(request.selection().mode())&&(request.selection().ids()==null||request.selection().ids().isEmpty()))throw new BusinessException(400,"请选择至少一个账号");
    if(request.selection().ids()!=null&&request.selection().ids().size()>500)throw new BusinessException(400,"单次最多修改500个账号");
    var changes=request.changes();
    if(changes.keySet().stream().anyMatch(key->!Set.of("enabled","role","roleAction","stationIds","scopeRole").contains(key)))throw new BusinessException(400,"包含不支持的批量字段");
    if(changes.containsKey("role")||changes.containsKey("roleAction")){
      if(!changes.containsKey("role")||!Set.of("GRANT","REVOKE").contains(Objects.toString(changes.get("roleAction"),"")))throw new BusinessException(400,"请选择授予或撤销身份");
      if(!CHANGE_ROLES.contains(Objects.toString(changes.get("role"),"")))throw new BusinessException(400,"非法角色");
      if(changes.containsKey("enabled")||changes.containsKey("scopeRole"))throw new BusinessException(400,"身份调整请单独执行");
      if("REVOKE".equals(changes.get("roleAction"))&&changes.containsKey("stationIds"))throw new BusinessException(400,"撤销身份无需设置服务站");
    }else if(!changes.containsKey("enabled")&&!changes.containsKey("stationIds"))throw new BusinessException(400,"请选择需要修改的字段");
  }
  private List<Long> resolveBulkIds(BulkSelection selection){var allowed=allowedRoles(SecurityUtils.current());var args=new ArrayList<Object>(allowed);var marks=String.join(",",Collections.nCopies(allowed.size(),"?"));var where=new StringBuilder(" where exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code in ("+marks+"))");if("IDS".equals(selection.mode())){var ids=new LinkedHashSet<>(selection.ids()==null?List.<Long>of():selection.ids());if(selection.excludedIds()!=null)ids.removeAll(selection.excludedIds());if(ids.isEmpty())throw new BusinessException(400,"没有可修改的账号");if(ids.size()>500)throw new BusinessException(400,"单次最多修改500个账号");where.append(" and u.id in (").append(String.join(",",Collections.nCopies(ids.size(),"?"))).append(")");args.addAll(ids);}else applyUserFilters(where,args,selection.filters());var rows=db.queryForList("select u.id from sys_user u left join employee e on e.user_id=u.id left join talent_batch b on b.id=e.batch_id left join business_unit bu on bu.id=e.business_unit_id"+where+" order by u.id",args.toArray());if(rows.isEmpty())throw new BusinessException(400,"筛选结果为空");if(rows.size()>500)throw new BusinessException(400,"单次最多修改500个账号，请缩小筛选范围");return rows.stream().map(row->((Number)row.get("id")).longValue()).toList();}
  private void applyUserFilters(StringBuilder where,List<Object> args,Map<String,Object> filters){if(filters==null)return;Object keyword=filters.get("keyword");if(keyword!=null&&!String.valueOf(keyword).isBlank()){where.append(" and (u.username like ? or u.display_name like ? or e.employee_no like ? or e.name like ? or b.name like ? or bu.name like ?)");String value="%"+String.valueOf(keyword).trim()+"%";for(int i=0;i<6;i++)args.add(value);}Object role=filters.get("role");if(role!=null&&!String.valueOf(role).isBlank()){where.append(" and exists(select 1 from sys_user_role ur where ur.user_id=u.id and ur.role_code=?)");args.add(role);}Object enabled=filters.get("enabled");if(enabled!=null&&!String.valueOf(enabled).isBlank()){where.append(" and u.enabled=?");args.add("enabled".equals(String.valueOf(enabled)));}addUserEquals(where,args,filters,"batchId","e.batch_id");addUserEquals(where,args,filters,"businessUnitId","e.business_unit_id");addUserEquals(where,args,filters,"classId","e.class_id");Object linked=filters.get("linked");if(linked!=null&&!String.valueOf(linked).isBlank())where.append("linked".equals(String.valueOf(linked))?" and e.id is not null":" and e.id is null");}
  private void addUserEquals(StringBuilder where,List<Object> args,Map<String,Object> filters,String key,String column){Object value=filters.get(key);if(value==null||String.valueOf(value).isBlank())return;where.append(" and ").append(column).append("=?");args.add(value);}
  private Map<Long,Integer> userVersions(List<Long> ids){var marks=String.join(",",Collections.nCopies(ids.size(),"?"));var rows=db.queryForList("select id,version from sys_user where id in ("+marks+") order by id",ids.toArray());if(rows.size()!=ids.size())throw new BusinessException(404,"部分账号不存在或无权访问");var result=new LinkedHashMap<Long,Integer>();rows.forEach(row->result.put(((Number)row.get("id")).longValue(),((Number)row.get("version")).intValue()));return result;}
  private String selectionHash(Map<Long,Integer> versions){try{var digest=MessageDigest.getInstance("SHA-256");var value=versions.entrySet().stream().map(entry->entry.getKey()+":"+entry.getValue()).reduce((a,b)->a+","+b).orElse("");return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
  private void validateBulkChanges(List<Long> ids,BulkRequest request){
    var changes=request.changes();
    var losingSuperAdmin=new ArrayList<Long>();
    for(Long id:ids){
      String mirrored=db.queryForObject("select role from sys_user where id=?",String.class,id);
      requireAccountManage(id,mirrored);
      var owned=ownedRoles(id,mirrored);
      if(changes.containsKey("roleAction")){
        permissions.require(Permissions.USER_ADMIN_MANAGE);
        String target=String.valueOf(changes.get("role"));
        var desired=bulkRoles(owned,target,String.valueOf(changes.get("roleAction")));
        if(!owned.equals(desired)){
          if(id.equals(SecurityUtils.current().id()))throw new BusinessException(400,"不能修改当前账号角色");
          if(desired.isEmpty())throw new BusinessException(400,"账号必须保留至少一个身份");
          boolean linked=hasEmployeeProfile(id);
          if(linked&&!desired.contains("EMPLOYEE"))throw new BusinessException(400,"关联员工档案的账号必须保留员工角色");
          if(!linked&&desired.contains("EMPLOYEE"))throw new BusinessException(400,"该账号未关联员工档案，不能设为员工角色");
          if("GRANT".equals(changes.get("roleAction"))&&isScopeRole(target)&&stationValues(changes.get("stationIds")).isEmpty())throw new BusinessException(400,"请为新增身份选择负责的服务站");
          if("REVOKE".equals(changes.get("roleAction")))ensureRoleRemoval(id,owned,desired);
          if(owned.contains("SUPER_ADMIN")&&!desired.contains("SUPER_ADMIN"))losingSuperAdmin.add(id);
        }
      }
      if(changes.containsKey("enabled")){
        boolean enabled=Boolean.parseBoolean(String.valueOf(changes.get("enabled")));
        if(id.equals(SecurityUtils.current().id())&&!enabled)throw new BusinessException(400,"不能停用当前账号");
        if(!enabled&&hasPendingTaskScores(id))throw new BusinessException(409,"该账号仍有未完成的任务评分，请先完成或转交后再停用");
        if(!enabled)ensureLeaveTransfer(id);
        if(!enabled&&owned.contains("SUPER_ADMIN"))losingSuperAdmin.add(id);
      }
      if(changes.containsKey("stationIds")&&!changes.containsKey("roleAction")){
        permissions.require(Permissions.USER_OPS_ROLE_MANAGE);
        if(stationValues(changes.get("stationIds")).isEmpty())throw new BusinessException(400,"服务站范围不能为空");
        String role=scopeRole(id,mirrored,Objects.toString(changes.get("scopeRole"),null));
        ensureLeaveTransfer(id,role);
      }
    }
    if(!losingSuperAdmin.isEmpty()){
      String marks=String.join(",",Collections.nCopies(losingSuperAdmin.size(),"?"));
      Integer remaining=db.queryForObject("select count(distinct u.id) from sys_user u join sys_user_role r on r.user_id=u.id where u.enabled=true and r.role_code='SUPER_ADMIN' and u.id not in ("+marks+")",Integer.class,losingSuperAdmin.toArray());
      if(remaining!=null&&remaining==0)throw new BusinessException(400,"必须保留至少一个启用的超级管理员");
    }
  }
  private List<String> bulkRoles(List<String> owned,String role,String action){
    var desired=new ArrayList<>(owned);
    if("GRANT".equals(action)){if(!desired.contains(role))desired.add(role);}
    else desired.remove(role);
    return desired;
  }
  private int countChanged(List<Long> ids,Map<String,Object> changes){
    int changed=0;
    for(Long id:ids){
      var row=db.queryForMap("select id,enabled,role from sys_user where id=?",id);
      String mirrored=String.valueOf(row.get("role"));
      boolean roleChanged=false;
      if(changes.containsKey("roleAction")){
        var owned=ownedRoles(id,mirrored);
        roleChanged=!owned.equals(bulkRoles(owned,String.valueOf(changes.get("role")),String.valueOf(changes.get("roleAction"))));
      }
      boolean enabledChanged=changes.containsKey("enabled")&&!Objects.equals(Boolean.valueOf(String.valueOf(row.get("enabled"))),Boolean.valueOf(String.valueOf(changes.get("enabled"))));
      boolean scopeChanged=changes.containsKey("stationIds")&&!changes.containsKey("roleAction")&&!stationScopeEquals(id,scopeRole(id,mirrored,Objects.toString(changes.get("scopeRole"),null)),stationValues(changes.get("stationIds")));
      if(roleChanged||enabledChanged||scopeChanged)changed++;
    }
    return changed;
  }
  private boolean stationScopeEquals(Long id,String role,List<Long> desired){
    var sorted=new ArrayList<>(new LinkedHashSet<>(desired));sorted.sort(Long::compareTo);
    return db.queryForList("select station_id from "+scopeTable(role)+" where user_id=? order by station_id",Long.class,id).equals(sorted);
  }
  private boolean updateBulkUser(Long id,BulkRequest request){
    var changes=request.changes();
    var before=db.queryForMap("select id,role,enabled from sys_user where id=? for update",id);
    String mirrored=String.valueOf(before.get("role"));
    requireAccountManage(id,mirrored);
    boolean changed=false;
    if(changes.containsKey("roleAction")){
      var owned=ownedRoles(id,mirrored);
      String target=String.valueOf(changes.get("role"));
      var desired=bulkRoles(owned,target,String.valueOf(changes.get("roleAction")));
      if(!owned.equals(desired)){
        if("REVOKE".equals(changes.get("roleAction")))ensureRoleRemoval(id,owned,desired);
        var scopes=new HashMap<String,List<Long>>();
        if("GRANT".equals(changes.get("roleAction"))&&isScopeRole(target))scopes.put(target,stationValues(changes.get("stationIds")));
        writeRoleSet(id,mirrored,owned,owned.get(0),desired,desired.get(0),scopes);
        changed=true;
      }
    }
    if(changes.containsKey("enabled")){
      boolean enabled=Boolean.parseBoolean(String.valueOf(changes.get("enabled")));
      if(!Objects.equals(Boolean.valueOf(String.valueOf(before.get("enabled"))),enabled)){
        if(!enabled)ensureLeaveTransfer(id);
        db.update("update sys_user set enabled=?,version=version+1,security_version=security_version+1 where id=?",enabled,id);
        if(request.syncLinkedEmployeeStatus()&&hasEmployeeProfile(id))db.update("update employee set status=?,version=version+1 where user_id=?",enabled?"ACTIVE":"INACTIVE",id);
        changed=true;
      }
    }
    if(changes.containsKey("stationIds")&&!changes.containsKey("roleAction")){
      String role=scopeRole(id,mirrored,Objects.toString(changes.get("scopeRole"),null));
      if(!stationScopeEquals(id,role,stationValues(changes.get("stationIds")))){
        ensureLeaveTransfer(id,role);
        replaceStationScope(id,scopeTable(role),stationValues(changes.get("stationIds")));
        db.update("update sys_user set version=version+1,security_version=security_version+1 where id=?",id);
        changed=true;
      }
    }
    return changed;
  }
  private List<Long> stationValues(Object value){
    if(value==null)return List.of();
    if(!(value instanceof List<?> values))throw new BusinessException(400,"服务站范围格式无效");
    try{return values.stream().map(x->Long.valueOf(String.valueOf(x))).distinct().toList();}
    catch(RuntimeException error){throw new BusinessException(400,"服务站范围格式无效");}
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
  private boolean isScopeRole(String role){return List.of("STATION_MANAGER","AREA_MANAGER").contains(role);}
  private String scopeTable(String role){return "AREA_MANAGER".equals(role)?"area_manager_scope":"station_manager_scope";}
  private String scopeRole(Long id,String mirrored,String requested){
    var scopes=ownedRoles(id,mirrored).stream().filter(this::isScopeRole).toList();
    if(requested!=null){if(!isScopeRole(requested)||!scopes.contains(requested))throw new BusinessException(400,"账号未拥有所选服务站身份");return requested;}
    if(scopes.size()!=1)throw new BusinessException(400,"请选择要调整范围的站长或区域经理身份");
    return scopes.get(0);
  }
  /** 对账号整体的操作必须获得全部身份的管理权限，主身份不能降低保护级别。 */
  private void requireAccountManage(Long id,String mirrored){
    db.queryForList("select id from sys_user where id=? for update",id);
    String latest=db.queryForObject("select role from sys_user where id=?",String.class,id);
    for(String role:ownedRoles(id,latest==null?mirrored:latest))requireRoleManage(role);
  }
  private void lockBulkUsers(List<Long> ids){
    String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
    db.queryForList("select id from sys_user where id in ("+marks+") order by id for update",ids.toArray());
  }
  private Map<String,List<Long>> roleScopes(Collection<String> roles,List<Long> legacy,List<Long> station,List<Long> area){
    if(legacy!=null&&roles.containsAll(List.of("STATION_MANAGER","AREA_MANAGER"))&&station==null&&area==null)throw new BusinessException(400,"请分别设置站长和区域经理的服务站范围");
    var scopes=new HashMap<String,List<Long>>();
    if(station!=null)scopes.put("STATION_MANAGER",station);
    else if(legacy!=null&&roles.contains("STATION_MANAGER"))scopes.put("STATION_MANAGER",legacy);
    if(area!=null)scopes.put("AREA_MANAGER",area);
    else if(legacy!=null&&roles.contains("AREA_MANAGER"))scopes.put("AREA_MANAGER",legacy);
    return scopes;
  }
  /** 同一事务内替换角色集合、主角色与服务站范围，并同步 sys_user.role 镜像、递增 security_version。 */
  private void writeRoleSet(Long id,String mirrored,List<String> before,String beforePrimary,List<String> roles,String primaryRole,List<Long> stationIds){
    writeRoleSet(id,mirrored,before,beforePrimary,roles,primaryRole,roleScopes(roles,stationIds,null,null));
  }
  private void writeRoleSet(Long id,String mirrored,List<String> before,String beforePrimary,List<String> roles,String primaryRole,Map<String,List<Long>> scopes){
    boolean rolesChanged=!new HashSet<>(before).equals(new HashSet<>(roles));
    boolean primaryChanged=!Objects.equals(primaryRole,beforePrimary);
    boolean scopeChanged=saveRoleStations(id,roles,scopes);
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
  /** 两种身份的范围独立维护，撤销一种身份只清理它自己的范围。 */
  private boolean saveRoleStations(Long id,Collection<String> roles,Map<String,List<Long>> scopes){
    boolean changed=false;
    for(String role:List.of("STATION_MANAGER","AREA_MANAGER")){
      String table=scopeTable(role);
      if(!roles.contains(role)){
        changed|=db.update("delete from "+table+" where user_id=?",id)>0;
      }else if(scopes.containsKey(role)){
        var desired=scopes.get(role);
        if(desired==null||desired.isEmpty())throw new BusinessException(400,"保留身份时服务站范围不能为空，请撤销该身份以解除职责");
        changed|=replaceStationScope(id,table,desired);
      }else if(!hasStationScope(id,table))throw new BusinessException(400,"请为"+("AREA_MANAGER".equals(role)?"区域经理":"站长")+"选择负责的服务站");
    }
    return changed;
  }
  private boolean hasStationScope(Long id,String table){Integer n=db.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,id);return n!=null&&n>0;}
  private boolean replaceStationScope(Long id,String table,List<Long> stationIds){
    var current=db.queryForList("select station_id from "+table+" where user_id=? order by station_id",Long.class,id);
    var desired=new ArrayList<>(new LinkedHashSet<>(stationIds));desired.sort(Long::compareTo);
    if(current.equals(desired))return false;
    db.update("delete from "+table+" where user_id=?",id);
    for(Long stationId:desired)db.update("insert into "+table+"(user_id,station_id) values(?,?)",id,stationId);
    return true;
  }
  private void ensureRoleRemoval(Long id,List<String> before,List<String> desired){
    for(String role:before)if(!desired.contains(role)){
      ensureLeaveTransfer(id,role);
      String component=switch(role){case "MENTOR"->"MENTOR";case "STATION_MANAGER"->"STATION";case "TRAINING_ADMIN"->"TRAINING";default->null;};
      if(component!=null){
        Integer count=db.queryForObject("select count(*) from evaluation_rating_reviewer r join evaluation_rating_task t on t.id=r.task_id where r.reviewer_user_id=? and r.status='ACTIVE' and t.status='ACTIVE' and t.component_type=?",Integer.class,id,component);
        if(count!=null&&count>0)throw new BusinessException(409,"该身份仍有评价评分职责，请先完成或转交");
      }
      if("MENTOR".equals(role)){
        Integer count=db.queryForObject("select count(*) from employee where mentor_user_id=? or skill_mentor_user_id=?",Integer.class,id,id);
        if(count!=null&&count>0)throw new BusinessException(409,"该身份仍担任导师，请先转交学员");
      }
    }
    boolean keepsScoring=desired.stream().anyMatch(r->List.of("MENTOR","STATION_MANAGER","TRAINING_ADMIN","ADMIN","SUPER_ADMIN").contains(r));
    if(!keepsScoring&&hasPendingTaskScores(id))throw new BusinessException(409,"该账号仍有未完成的任务评分，请先完成或转交");
  }
  private void ensureLeaveTransfer(Long id,String role){
    String level=switch(role){case "STATION_MANAGER","AREA_MANAGER"->role;case "TRAINING_ADMIN"->"TRAINING_DIRECTOR";default->null;};
    if(level==null)return;
    Integer count=db.queryForObject("select count(*) from leave_request_version v join leave_request r on r.id=v.request_id where v.approver_user_id=? and v.approver_level=? and (v.status='PENDING' or (v.version_no=r.current_version and r.status='APPROVED'))",Integer.class,id,level);
    if(count!=null&&count>0)throw new BusinessException(409,"该身份仍有请假审批或未销假责任，请先转交");
  }
  private void ensureLeaveTransfer(Long id){db.queryForList("select id from sys_user where id=? for update",id);Integer n=db.queryForObject("select count(*) from leave_request_version v join leave_request r on r.id=v.request_id where v.approver_user_id=? and (v.status='PENDING' or (v.version_no=r.current_version and r.status='APPROVED'))",Integer.class,id);if(n!=null&&n>0)throw new BusinessException(409,"该账号仍有请假审批或未销假责任，请先转交");}
  private String temp(){return "Tmp"+(100000+random.nextInt(900000))+"!a";}
}
