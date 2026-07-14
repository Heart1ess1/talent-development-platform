package com.talent.platform.security;

import org.springframework.security.access.AccessDeniedException; import org.springframework.security.core.context.SecurityContextHolder;
public final class SecurityUtils {
  private SecurityUtils(){}
  public static CurrentUser current(){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||!(a.getPrincipal() instanceof CurrentUser u))throw new AccessDeniedException("未登录");return u;}
  public static boolean admin(CurrentUser u){return "ADMIN".equals(u.role())||"SUPER_ADMIN".equals(u.role());}
  public static boolean trainingManager(CurrentUser u){return admin(u)||"TRAINING_ADMIN".equals(u.role());}
}
