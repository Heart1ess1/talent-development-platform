package com.talent.platform.security;

import java.util.Set;

public record CurrentUser(Long id,String username,String displayName,String role,boolean mustChangePassword,
                          int securityVersion,Set<String> permissions,String dataScope) {
  public CurrentUser(Long id,String username,String displayName,String role,boolean mustChangePassword){
    this(id,username,displayName,role,mustChangePassword,0,Set.of(),"SELF");
  }
  public boolean can(String permission){return permissions.contains(permission);}
}
