package com.talent.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration @EnableMethodSecurity
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
  @Bean CorsConfigurationSource cors(@Value("${app.cors-origins}") List<String> origins){var c=new CorsConfiguration();c.setAllowedOrigins(origins);c.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("*"));c.setExposedHeaders(List.of("Content-Disposition"));var s=new UrlBasedCorsConfigurationSource();s.registerCorsConfiguration("/**",c);return s;}
  @Bean SecurityFilterChain filter(HttpSecurity h,JwtFilter f,PasswordChangeFilter p,ObjectMapper om)throws Exception{return h.csrf(x->x.disable()).cors(x->{}).sessionManagement(x->x.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(x->x.requestMatchers("/api/v1/auth/login","/actuator/health","/","/index.html","/assets/**","/login","/dashboard","/employees","/employee-directory","/courses","/training-plans","/tasks","/evaluation","/exams","/users","/profile").permitAll().requestMatchers(HttpMethod.OPTIONS,"/**").permitAll().anyRequest().authenticated()).exceptionHandling(x->x.authenticationEntryPoint((q,r,e)->{r.setStatus(401);r.setContentType("application/json;charset=UTF-8");om.writeValue(r.getWriter(),ApiResponse.error(401,"请先登录或登录已过期"));})).addFilterBefore(f,UsernamePasswordAuthenticationFilter.class).addFilterAfter(p,JwtFilter.class).build();}
}
