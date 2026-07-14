package com.talent.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;import com.talent.platform.persistence.*;import org.springframework.beans.factory.annotation.Value; import org.springframework.boot.ApplicationArguments; import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
  private final SysUserMapper users;private final PasswordEncoder encoder;private final String username,password;
  public BootstrapAdmin(SysUserMapper users,PasswordEncoder encoder,@Value("${app.bootstrap.username}")String username,@Value("${app.bootstrap.password}")String password){this.users=users;this.encoder=encoder;this.username=username;this.password=password;}
  public void run(ApplicationArguments args){Long n=users.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole,"SUPER_ADMIN"));if(n==0){var u=new SysUser();u.setUsername(username);u.setPasswordHash(encoder.encode(password));u.setDisplayName("超级管理员");u.setRole("SUPER_ADMIN");u.setEnabled(true);u.setMustChangePassword(true);u.setVersion(0);users.insert(u);}}
}
