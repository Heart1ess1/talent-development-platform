package com.talent.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ApiResponse<Void>> business(BusinessException e) { var status=HttpStatus.resolve(e.getCode());if(status==null||!status.is4xxClientError())status=HttpStatus.BAD_REQUEST;return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(),e.getMessage())); }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiResponse<Void> validation(MethodArgumentNotValidException e) { return ApiResponse.error(400, e.getBindingResult().getFieldErrors().stream().findFirst().map(x -> x.getField()+": "+x.getDefaultMessage()).orElse("参数错误")); }
  @ExceptionHandler(DuplicateKeyException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  ApiResponse<Void> duplicate() { return ApiResponse.error(409, "数据已存在，请勿重复提交"); }
  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  ApiResponse<Void> denied() { return ApiResponse.error(403, "无权访问该数据"); }
  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  ApiResponse<Void> notFound() { return ApiResponse.error(404, "接口或资源不存在"); }
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  ApiResponse<Void> unknown(Exception e) {
    log.error("Unhandled server exception", e);
    return ApiResponse.error(500, "服务器内部错误");
  }
}
