package com.talent.platform.security;

import java.util.Set;

public record CurrentUser(Long id,String username,String displayName,String role,boolean mustChangePassword,
                          int securityVersion,Set<String> permissions,String dataScope,String avatarToken) {
  public CurrentUser(Long id,String username,String displayName,String role,boolean mustChangePassword){
    this(id,username,displayName,role,mustChangePassword,0,Set.of(),"SELF",null);
  }
  public CurrentUser(Long id,String username,String displayName,String role,boolean mustChangePassword,
                     int securityVersion,Set<String> permissions,String dataScope){
    this(id,username,displayName,role,mustChangePassword,securityVersion,permissions,dataScope,null);
  }
  public boolean can(String permission){return permissions.contains(permission);}
}
