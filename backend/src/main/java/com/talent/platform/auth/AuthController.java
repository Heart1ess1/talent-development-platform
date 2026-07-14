package com.talent.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;import com.talent.platform.common.*; import com.talent.platform.persistence.*;import com.talent.platform.security.*;
import jakarta.validation.Valid; import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
  private final JdbcTemplate db;private final SysUserMapper users; private final PasswordEncoder encoder; private final JwtService jwt;private final PermissionService permissions;private final AuditService audit;
  public AuthController(JdbcTemplate db,SysUserMapper users,PasswordEncoder encoder,JwtService jwt,PermissionService permissions,AuditService audit){this.db=db;this.users=users;this.encoder=encoder;this.jwt=jwt;this.permissions=permissions;this.audit=audit;}
  public record LoginRequest(@NotBlank String username,@NotBlank String password){}
  public record ChangePasswordRequest(@NotBlank String oldPassword,@Size(min=8,max=64) String newPassword){}
  @PostMapping("/login") public ApiResponse<Map<String,Object>> login(@Valid @RequestBody LoginRequest q){
    var r=users.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername,q.username()));
    if(r==null||!Boolean.TRUE.equals(r.getEnabled())||!encoder.matches(q.password(),r.getPasswordHash()))throw new BusinessException(401,"用户名或密码错误");
    var u=permissions.load(r.getId());audit.logAs(r.getId(),"LOGIN","USER",r.getId(),null,Map.of("username",r.getUsername()));
    return ApiResponse.ok(Map.of("token",jwt.create(u),"user",u));
  }
  @GetMapping("/me") public ApiResponse<CurrentUser> me(){return ApiResponse.ok(SecurityUtils.current());}
  @PostMapping("/change-password") public ApiResponse<Map<String,Object>> change(@Valid @RequestBody ChangePasswordRequest q){var u=SecurityUtils.current();String hash=db.queryForObject("select password_hash from sys_user where id=?",String.class,u.id());if(!encoder.matches(q.oldPassword(),hash))throw new BusinessException(400,"原密码不正确");db.update("update sys_user set password_hash=?,must_change_password=false,version=version+1,security_version=security_version+1 where id=?",encoder.encode(q.newPassword()),u.id());var changed=permissions.load(u.id());audit.log("CHANGE_PASSWORD","USER",u.id(),null,null);return ApiResponse.ok(Map.of("token",jwt.create(changed),"user",changed));}
}
