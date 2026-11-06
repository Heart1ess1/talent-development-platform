package com.talent.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;import com.talent.platform.common.*; import com.talent.platform.persistence.*;import com.talent.platform.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid; import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
  private final JdbcTemplate db;private final SysUserMapper users; private final PasswordEncoder encoder; private final JwtService jwt;private final PermissionService permissions;private final AuditService audit;
  public AuthController(JdbcTemplate db,SysUserMapper users,PasswordEncoder encoder,JwtService jwt,PermissionService permissions,AuditService audit){this.db=db;this.users=users;this.encoder=encoder;this.jwt=jwt;this.permissions=permissions;this.audit=audit;}
  public record LoginRequest(@NotBlank String username,@NotBlank String password){}
  public record ChangePasswordRequest(@NotBlank String oldPassword,@Size(min=8,max=64) String newPassword){}
  public record SwitchRoleRequest(@NotBlank String role){}
  @PostMapping("/login") public ApiResponse<Map<String,Object>> login(@Valid @RequestBody LoginRequest q){
    var r=users.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername,q.username()));
    if(r==null||!Boolean.TRUE.equals(r.getEnabled())||!encoder.matches(q.password(),r.getPasswordHash()))throw new BusinessException(401,"用户名或密码错误");
    var u=permissions.load(r.getId(),null);var roles=permissions.roles(r.getId());audit.logAs(r.getId(),"LOGIN","USER",r.getId(),null,Map.of("username",r.getUsername()));
    return ApiResponse.ok(authView(jwt.create(u),u,roles));
  }
  @GetMapping("/me") public ApiResponse<Map<String,Object>> me(){
    var u=SecurityUtils.current();
    return ApiResponse.ok(userView(u,permissions.roles(u.id())));
  }
  @PostMapping("/change-password") public ApiResponse<Map<String,Object>> change(@Valid @RequestBody ChangePasswordRequest q){var u=SecurityUtils.current();String hash=db.queryForObject("select password_hash from sys_user where id=?",String.class,u.id());if(!encoder.matches(q.oldPassword(),hash))throw new BusinessException(400,"原密码不正确");db.update("update sys_user set password_hash=?,must_change_password=false,version=version+1,security_version=security_version+1 where id=?",encoder.encode(q.newPassword()),u.id());var changed=permissions.load(u.id(),u.role());audit.log("CHANGE_PASSWORD","USER",u.id(),null,null);return ApiResponse.ok(Map.of("token",jwt.create(changed),"user",changed));}
  @PostMapping("/switch-role") public ApiResponse<Map<String,Object>> switchRole(@Valid @RequestBody SwitchRoleRequest q,HttpServletRequest request){
    var current=SecurityUtils.current();
    if(!PermissionService.KNOWN_ROLES.contains(q.role()))throw new BusinessException(400,"未知角色，无法切换");
    var roles=permissions.roles(current.id());
    if(!roles.contains(q.role()))throw new BusinessException(403,"该身份不属于当前账号，无法切换");
    if(!permissions.eligible(current.id(),q.role()))throw new BusinessException(400,"该身份还缺少"+missingConfig(q.role())+"配置，请联系管理员");
    if(q.role().equals(current.role())){
      String header=request.getHeader("Authorization");
      String token=header!=null&&header.startsWith("Bearer ")?header.substring(7):jwt.create(current);
      return ApiResponse.ok(authView(token,current,roles));
    }
    var switched=permissions.load(current.id(),q.role());
    audit.log("SWITCH_ROLE","USER",current.id(),Map.of("role",current.role()),Map.of("role",switched.role()));
    return ApiResponse.ok(authView(jwt.create(switched),switched,roles));
  }
  private Map<String,Object> authView(String token,CurrentUser u,List<String> roles){
    var m=new LinkedHashMap<String,Object>();
    m.put("token",token);m.put("user",u);m.put("activeRole",u.role());m.put("availableRoles",roles);
    return m;
  }
  private Map<String,Object> userView(CurrentUser u,List<String> roles){
    var m=new LinkedHashMap<String,Object>();
    m.put("id",u.id());m.put("username",u.username());m.put("displayName",u.displayName());m.put("role",u.role());
    m.put("mustChangePassword",u.mustChangePassword());m.put("securityVersion",u.securityVersion());
    m.put("permissions",u.permissions());m.put("dataScope",u.dataScope());m.put("avatarToken",u.avatarToken());
    m.put("availableRoles",roles);
    return m;
  }
  private String missingConfig(String role){return switch(role){case "EMPLOYEE"->"员工档案";case "STATION_MANAGER","AREA_MANAGER"->"服务站范围";default->"必要";};}
}
