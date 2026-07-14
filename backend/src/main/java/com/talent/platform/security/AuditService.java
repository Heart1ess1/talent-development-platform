package com.talent.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcTemplate db;private final ObjectMapper mapper;private final HttpServletRequest request;
  public AuditService(JdbcTemplate db,ObjectMapper mapper,HttpServletRequest request){this.db=db;this.mapper=mapper;this.request=request;}
  public void log(String action,String type,Object id,Object before,Object after){
    CurrentUser u=null;try{u=SecurityUtils.current();}catch(Exception ignored){}
    write(u==null?null:u.id(),action,type,id,before,after);
  }
  public void logAs(Long userId,String action,String type,Object id,Object before,Object after){write(userId,action,type,id,before,after);}
  private void write(Long userId,String action,String type,Object id,Object before,Object after){
    db.update("insert into operation_log(user_id,action,target_type,target_id,detail,request_id,before_value,after_value) values(?,?,?,?,?,?,?,?)",
      userId,action,type,id instanceof Number n?n.longValue():null,null,request.getHeader("X-Request-Id"),json(before),json(after));
  }
  private String json(Object value){try{return value==null?null:mapper.writeValueAsString(value);}catch(Exception e){return null;}}
}
