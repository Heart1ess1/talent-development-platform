package com.talent.platform.common;

import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data, String requestId) {
  public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "success", data, UUID.randomUUID().toString()); }
  public static <T> ApiResponse<T> error(int code, String message) { return new ApiResponse<>(code, message, null, UUID.randomUUID().toString()); }
}

