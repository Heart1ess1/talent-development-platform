package com.talent.platform.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class PermissionService {
  private final JdbcTemplate db;
  public PermissionService(JdbcTemplate db){this.db=db;}

  public CurrentUser load(Long id){
    var rows=db.queryForList("select id,username,display_name,role,enabled,must_change_password,security_version from sys_user where id=?",id);
    if(rows.isEmpty()||!Boolean.TRUE.equals(rows.get(0).get("enabled")))throw new AccessDeniedException("账号已停用");
    var r=rows.get(0);String role=String.valueOf(r.get("role"));
    return new CurrentUser(((Number)r.get("id")).longValue(),String.valueOf(r.get("username")),String.valueOf(r.get("display_name")),role,
      Boolean.TRUE.equals(r.get("must_change_password")),((Number)r.get("security_version")).intValue(),permissions(role),scope(role));
  }
  public Set<String> permissions(String role){
    var p=new LinkedHashSet<String>();p.add(Permissions.EMPLOYEE_READ);p.add(Permissions.EVALUATION_VIEW);
    if(List.of("MENTOR","STATION_MANAGER","TRAINING_ADMIN").contains(role))p.add(Permissions.EVALUATION_SUBMIT);
    if(List.of("TRAINING_ADMIN","ADMIN","SUPER_ADMIN").contains(role))p.addAll(List.of(Permissions.EMPLOYEE_EXPORT,Permissions.COURSE_MANAGE,Permissions.ATTENDANCE_MANAGE,Permissions.TASK_MANAGE,Permissions.TASK_REVIEW,Permissions.EVALUATION_MANAGE,Permissions.EXAM_MANAGE));
    if(List.of("ADMIN","SUPER_ADMIN").contains(role))p.addAll(List.of(Permissions.EMPLOYEE_WRITE,Permissions.USER_EMPLOYEE_MANAGE,Permissions.MASTER_MANAGE,Permissions.AUDIT_READ));
    if("SUPER_ADMIN".equals(role))p.add(Permissions.USER_ADMIN_MANAGE);
    return Collections.unmodifiableSet(p);
  }
  private String scope(String role){return switch(role){case "EMPLOYEE"->"SELF";case "MENTOR"->"MENTORED";case "STATION_MANAGER"->"STATION";default->"ALL";};}
  public void require(String permission){if(!SecurityUtils.current().can(permission))throw new AccessDeniedException("无此操作权限");}
  public void requireEmployee(Long employeeId){
    var u=SecurityUtils.current();if("ALL".equals(u.dataScope()))return;
    String sql=switch(u.dataScope()){case "SELF"->"select count(*) from employee where id=? and user_id=?";case "MENTORED"->"select count(*) from employee where id=? and mentor_user_id=?";case "STATION"->"select count(*) from employee e join station_manager_scope s on s.station_id=e.station_id where e.id=? and s.user_id=?";default->null;};
    if(sql==null||db.queryForObject(sql,Integer.class,employeeId,u.id())==0)throw new AccessDeniedException("无权访问该员工");
  }
  public ScopeFilter employeeFilter(String alias){
    var u=SecurityUtils.current();return switch(u.dataScope()){
      case "SELF"->new ScopeFilter(" and "+alias+".user_id=?",List.of(u.id()));
      case "MENTORED"->new ScopeFilter(" and "+alias+".mentor_user_id=?",List.of(u.id()));
      case "STATION"->new ScopeFilter(" and exists(select 1 from station_manager_scope sms where sms.station_id="+alias+".station_id and sms.user_id=?)",List.of(u.id()));
      default->new ScopeFilter("",List.of());};
  }
  public record ScopeFilter(String sql,List<Object> args){}
}
