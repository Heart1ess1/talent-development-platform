package com.talent.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;import com.talent.platform.persistence.*;import org.springframework.beans.factory.annotation.Value; import org.springframework.boot.ApplicationArguments; import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
  private final SysUserMapper users;private final JdbcTemplate db;private final PasswordEncoder encoder;private final String username,password;private final boolean demoUsersEnabled;
  public BootstrapAdmin(SysUserMapper users,JdbcTemplate db,PasswordEncoder encoder,@Value("${app.bootstrap.username}")String username,@Value("${app.bootstrap.password}")String password,@Value("${app.bootstrap.demo-users-enabled}")boolean demoUsersEnabled){this.users=users;this.db=db;this.encoder=encoder;this.username=username;this.password=password;this.demoUsersEnabled=demoUsersEnabled;}
  public void run(ApplicationArguments args){if(demoUsersEnabled){seedDemoUsers();return;}Long n=users.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole,"SUPER_ADMIN"));if(n==0){var u=new SysUser();u.setUsername(username);u.setPasswordHash(encoder.encode(password));u.setDisplayName("超级管理员");u.setRole("SUPER_ADMIN");u.setEnabled(true);u.setMustChangePassword(true);u.setVersion(0);users.insert(u);}}
  private void seedDemoUsers(){
    upsertUser("superadmin","superadmin","超级管理员","SUPER_ADMIN");
    upsertUser("admin","12345678","管理员","ADMIN");
    upsertUser("trainadmin","12345678","培训管理员","TRAINING_ADMIN");
    upsertUser("mentor","12345678","导师","MENTOR");
    Long employeeUserId=upsertUser("employee","12345678","新员工","EMPLOYEE");
    Integer n=db.queryForObject("select count(*) from employee where user_id=?",Integer.class,employeeUserId);
    if(n==null||n==0)db.update("insert into employee(user_id,employee_no,name,batch_id,status) values(?,?,?,(select id from talent_batch order by id limit 1),'ACTIVE')",employeeUserId,"employee","新员工");
  }
  private Long upsertUser(String username,String password,String displayName,String role){
    var rows=db.queryForList("select id from sys_user where username=?",username);
    String hash=encoder.encode(password);
    if(rows.isEmpty()){db.update("insert into sys_user(username,password_hash,display_name,role,enabled,must_change_password) values(?,?,?,?,true,false)",username,hash,displayName,role);return db.queryForObject("select last_insert_id()",Long.class);}
    Long id=((Number)rows.get(0).get("id")).longValue();
    db.update("update sys_user set password_hash=?,display_name=?,role=?,enabled=true,must_change_password=false,security_version=security_version+1,version=version+1 where id=?",hash,displayName,role,id);
    return id;
  }
}
