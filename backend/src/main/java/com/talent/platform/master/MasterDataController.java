package com.talent.platform.master;

import com.talent.platform.common.*; import com.talent.platform.security.*;import jakarta.validation.Valid; import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class MasterDataController {
  private final JdbcTemplate db;private final PermissionService permissions;public MasterDataController(JdbcTemplate db,PermissionService permissions){this.db=db;this.permissions=permissions;}
  public record NameRequest(@NotBlank String name){}
  @GetMapping("/batches") public ApiResponse<List<Map<String,Object>>> batches(){return ApiResponse.ok(db.queryForList("select id,name,enabled from talent_batch order by id desc"));}
  @PostMapping("/batches") public ApiResponse<Long> addBatch(@Valid @RequestBody NameRequest q){permissions.require(Permissions.MASTER_MANAGE);db.update("insert into talent_batch(name) values(?)",q.name());return ApiResponse.ok(db.queryForObject("select last_insert_id()",Long.class));}
  @GetMapping("/stations") public ApiResponse<List<Map<String,Object>>> stations(){return ApiResponse.ok(db.queryForList("select id,name,enabled from service_station order by name"));}
  @PostMapping("/stations") public ApiResponse<Long> addStation(@Valid @RequestBody NameRequest q){permissions.require(Permissions.MASTER_MANAGE);db.update("insert into service_station(name) values(?)",q.name());return ApiResponse.ok(db.queryForObject("select last_insert_id()",Long.class));}
  @GetMapping("/mentors") public ApiResponse<List<Map<String,Object>>> mentors(){permissions.require(Permissions.EMPLOYEE_READ);return ApiResponse.ok(db.queryForList("select id,display_name from sys_user where role='MENTOR' and enabled=true order by display_name"));}
}
