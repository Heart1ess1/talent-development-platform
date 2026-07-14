package com.talent.platform.security;

import jakarta.servlet.*; import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException; import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt;private final PermissionService permissions; public JwtFilter(JwtService jwt,PermissionService permissions){this.jwt=jwt;this.permissions=permissions;}
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
    String h=req.getHeader("Authorization");
    if(h!=null&&h.startsWith("Bearer ")) try { var tokenUser=jwt.parse(h.substring(7));var u=permissions.load(tokenUser.id());if(u.securityVersion()!=tokenUser.securityVersion())throw new IllegalArgumentException("token revoked");var authorities=new java.util.ArrayList<SimpleGrantedAuthority>();authorities.add(new SimpleGrantedAuthority("ROLE_"+u.role()));u.permissions().forEach(x->authorities.add(new SimpleGrantedAuthority(x)));var a=new UsernamePasswordAuthenticationToken(u,null,authorities); SecurityContextHolder.getContext().setAuthentication(a); } catch(Exception ignored){SecurityContextHolder.clearContext();}
    chain.doFilter(req,res);
  }
}
