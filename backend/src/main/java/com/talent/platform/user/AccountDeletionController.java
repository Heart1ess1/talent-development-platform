package com.talent.platform.user;

import com.talent.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountDeletionController {
  private final AccountDeletionService service;
  public AccountDeletionController(AccountDeletionService service) {this.service=service;}

  @DeleteMapping("/api/v1/users/{id}")
  public ApiResponse<Void> deleteAccount(@PathVariable long id) {
    service.deleteAccount(id);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/api/v1/employees/{id}")
  public ApiResponse<Void> deleteEmployee(@PathVariable long id) {
    service.deleteEmployee(id);
    return ApiResponse.ok(null);
  }
}
