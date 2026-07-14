package com.talent.platform.persistence;

import com.baomidou.mybatisplus.annotation.*;import lombok.Data;
@Data @TableName("sys_user")
public class SysUser {
  @TableId(type=IdType.AUTO) private Long id;private String username;private String passwordHash;private String displayName;private String role;private Boolean enabled;private Boolean mustChangePassword;@Version private Integer version;
}

