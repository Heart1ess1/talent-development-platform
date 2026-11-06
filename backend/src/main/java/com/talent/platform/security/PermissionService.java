package com.talent.platform.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class PermissionService {
  /** 系统已知角色码：切换/登录只接受这些值，避免任意字符串进入权限矩阵。 */
  public static final List<String> KNOWN_ROLES=List.of("EMPLOYEE","MENTOR","STATION_MANAGER","AREA_MANAGER","TRAINING_DIRECTOR","TRAINING_ADMIN","ADMIN","SUPER_ADMIN");
  private final JdbcTemplate db;
  public PermissionService(JdbcTemplate db){this.db=db;}

  /** 兼容旧调用：按“默认身份”加载（主角色优先，前置条件不满足则回退第一个可用角色）。 */
  public CurrentUser load(Long id){return load(id,null);}

  /**
   * 按指定生效角色加载当前用户；activeRole 为空时自动选择默认身份。
   * 角色归属以 sys_user_role 为准，不再无条件使用 sys_user.role。
   */
  public CurrentUser load(Long id,String activeRole){
    var rows=db.queryForList("select id,username,display_name,role,enabled,must_change_password,security_version,avatar_token from sys_user where id=?",id);
    if(rows.isEmpty()||!Boolean.TRUE.equals(rows.get(0).get("enabled")))throw new AccessDeniedException("账号已停用");
    var r=rows.get(0);
    String mirroredRole=r.get("role")==null?null:String.valueOf(r.get("role"));
    var owned=roles(id, mirroredRole);
    if(owned.isEmpty())throw new AccessDeniedException("账号未分配任何角色");
    String role;
    if(activeRole==null||activeRole.isBlank()){
      role=defaultRole(id,owned);
    }else{
      if(!owned.contains(activeRole))throw new AccessDeniedException("身份已变更，请重新登录");
      role=activeRole;
    }
    return new CurrentUser(((Number)r.get("id")).longValue(),String.valueOf(r.get("username")),String.valueOf(r.get("display_name")),role,
      Boolean.TRUE.equals(r.get("must_change_password")),((Number)r.get("security_version")).intValue(),permissions(role),scope(role),
      r.get("avatar_token")==null?null:String.valueOf(r.get("avatar_token")));
  }

  /** 该账号拥有的全部角色：主角色在前，其余按分配时间；表里没有行时回退 sys_user.role。 */
  public List<String> roles(Long id){return roles(id,null);}

  private List<String> roles(Long id,String mirroredRole){
    var rows=db.queryForList("select role_code from sys_user_role where user_id=? order by is_primary desc,assigned_at asc,role_code asc",id);
    var owned=rows.stream().map(x->x.get("role_code")==null?null:String.valueOf(x.get("role_code")))
      .filter(x->x!=null&&KNOWN_ROLES.contains(x)).distinct().toList();
    if(!owned.isEmpty())return owned;
    String fallback=mirroredRole!=null?mirroredRole:db.queryForObject("select role from sys_user where id=?",String.class,id);
    return fallback==null||fallback.isBlank()?List.of():List.of(fallback);
  }

  /** 角色前置条件：EMPLOYEE 需员工档案；STATION_MANAGER / AREA_MANAGER 需对应服务站范围。 */
  public boolean eligible(Long id,String role){
    return switch(role){
      case "EMPLOYEE"->exists("select count(*) from employee where user_id=?",id);
      case "STATION_MANAGER"->exists("select count(*) from station_manager_scope where user_id=?",id);
      case "AREA_MANAGER"->exists("select count(*) from area_manager_scope where user_id=?",id);
      default->true;
    };
  }
  private boolean exists(String sql,Object...args){Integer n=db.queryForObject(sql,Integer.class,args);return n!=null&&n>0;}

  private String defaultRole(Long id,List<String> owned){
    for(String role:owned)if(eligible(id,role))return role;
    return owned.get(0);
  }

  public Set<String> permissions(String role){
    if(!KNOWN_ROLES.contains(role))return Set.of();
    var p=new LinkedHashSet<String>();
    if(KNOWN_ROLES.contains(role))p.add(Permissions.EMPLOYEE_READ);
    if(!List.of("AREA_MANAGER","TRAINING_DIRECTOR").contains(role))p.add(Permissions.EVALUATION_VIEW);
    if(List.of("MENTOR","STATION_MANAGER","AREA_MANAGER","TRAINING_DIRECTOR","TRAINING_ADMIN","ADMIN","SUPER_ADMIN").contains(role))p.add(Permissions.EMPLOYEE_PORTRAIT_VIEW);
    if(List.of("MENTOR","STATION_MANAGER","TRAINING_ADMIN","ADMIN","SUPER_ADMIN").contains(role))p.add(Permissions.TASK_SCORE);
    if(List.of("STATION_MANAGER","AREA_MANAGER","TRAINING_DIRECTOR","TRAINING_ADMIN","ADMIN","SUPER_ADMIN").contains(role))p.add(Permissions.LEAVE_VIEW);
    if(List.of("MENTOR","STATION_MANAGER","TRAINING_ADMIN").contains(role))p.add(Permissions.EVALUATION_SUBMIT);
    if(List.of("TRAINING_ADMIN","ADMIN","SUPER_ADMIN").contains(role))p.addAll(List.of(Permissions.EMPLOYEE_UPDATE,Permissions.EMPLOYEE_EXPORT,Permissions.COURSE_MANAGE,Permissions.ATTENDANCE_MANAGE,Permissions.TASK_MANAGE,Permissions.TASK_REVIEW,Permissions.EVALUATION_MANAGE,Permissions.EXAM_MANAGE,Permissions.HISTORY_IMPORT));
    if(List.of("ADMIN","SUPER_ADMIN").contains(role))p.addAll(List.of(Permissions.EMPLOYEE_WRITE,Permissions.USER_EMPLOYEE_MANAGE,Permissions.USER_OPS_ROLE_MANAGE,Permissions.MASTER_MANAGE,Permissions.AUDIT_READ));
    if("SUPER_ADMIN".equals(role))p.add(Permissions.USER_ADMIN_MANAGE);
    return Collections.unmodifiableSet(p);
  }
  private String scope(String role){return switch(role){case "EMPLOYEE"->"SELF";case "MENTOR"->"MENTORED";case "STATION_MANAGER"->"STATION";case "AREA_MANAGER"->"AREA";case "TRAINING_DIRECTOR","TRAINING_ADMIN","ADMIN","SUPER_ADMIN"->"ALL";default->"NONE";};}
  public void require(String permission){if(!SecurityUtils.current().can(permission))throw new AccessDeniedException("无此操作权限");}
  public void requireEmployee(Long employeeId){
    var u=SecurityUtils.current();if("ALL".equals(u.dataScope()))return;
    String sql=switch(u.dataScope()){case "SELF"->"select count(*) from employee where id=? and user_id=?";case "MENTORED"->"select count(*) from employee where id=? and (mentor_user_id=? or skill_mentor_user_id=?)";case "STATION"->"select count(*) from employee e join station_manager_scope s on s.station_id=e.station_id where e.id=? and s.user_id=?";case "AREA"->"select count(*) from employee e join area_manager_scope s on s.station_id=e.station_id where e.id=? and s.user_id=?";default->null;};
    Object[] args="MENTORED".equals(u.dataScope())?new Object[]{employeeId,u.id(),u.id()}:new Object[]{employeeId,u.id()};
    if(sql==null||db.queryForObject(sql,Integer.class,args)==0)throw new AccessDeniedException("无权访问该员工");
  }
  public ScopeFilter employeeFilter(String alias){
    var u=SecurityUtils.current();return switch(u.dataScope()){
      case "SELF"->new ScopeFilter(" and "+alias+".user_id=?",List.of(u.id()));
      case "MENTORED"->new ScopeFilter(" and ("+alias+".mentor_user_id=? or "+alias+".skill_mentor_user_id=?)",List.of(u.id(),u.id()));
      case "STATION"->new ScopeFilter(" and exists(select 1 from station_manager_scope sms where sms.station_id="+alias+".station_id and sms.user_id=?)",List.of(u.id()));
      case "AREA"->new ScopeFilter(" and exists(select 1 from area_manager_scope ams where ams.station_id="+alias+".station_id and ams.user_id=?)",List.of(u.id()));
      case "ALL"->new ScopeFilter("",List.of());
      default->new ScopeFilter(" and 1=0",List.of());};
  }
  public record ScopeFilter(String sql,List<Object> args){}
}
