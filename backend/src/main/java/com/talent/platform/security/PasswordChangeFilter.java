package com.talent.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;import com.talent.platform.common.ApiResponse;import jakarta.servlet.*;import jakarta.servlet.http.*;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;import java.io.IOException;

@Component
public class PasswordChangeFilter extends OncePerRequestFilter {
  private final ObjectMapper mapper;public PasswordChangeFilter(ObjectMapper mapper){this.mapper=mapper;}
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{var auth=SecurityContextHolder.getContext().getAuthentication();if(auth!=null&&auth.getPrincipal() instanceof CurrentUser u&&u.mustChangePassword()&&!req.getRequestURI().startsWith("/api/v1/auth/")){res.setStatus(403);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),ApiResponse.error(403,"请先修改临时密码"));return;}chain.doFilter(req,res);}
}
