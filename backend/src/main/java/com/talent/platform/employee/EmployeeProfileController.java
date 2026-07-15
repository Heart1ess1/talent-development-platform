package com.talent.platform.employee;

import com.talent.platform.common.*;import com.talent.platform.security.*;import jakarta.validation.Valid;import jakarta.validation.constraints.Email;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;import java.util.*;

@RestController @RequestMapping("/api/v1/profile/employee")
public class EmployeeProfileController {
  private final JdbcTemplate db;private final AuditService audit;
  public EmployeeProfileController(JdbcTemplate db,AuditService audit){this.db=db;this.audit=audit;}
  public record ProfileRequest(String phone,@Email String email,LocalDate birthDate,String nativePlace,String residence,String school,String major,String education){}

  @GetMapping public ApiResponse<Map<String,Object>> detail(){var u=requireEmployeeUser();return ApiResponse.ok(load(u.id()));}

  @PutMapping public ApiResponse<Void> update(@Valid @RequestBody ProfileRequest q){
    var u=requireEmployeeUser();var before=load(u.id());
    db.update("update employee set phone=?,email=?,birth_date=?,native_place=?,residence=?,school=?,major=?,education=?,version=version+1 where user_id=?",q.phone(),q.email(),q.birthDate(),q.nativePlace(),q.residence(),q.school(),q.major(),q.education(),u.id());
    audit.log("UPDATE_OWN_PROFILE","EMPLOYEE",before.get("id"),before,q);return ApiResponse.ok(null);
  }

  private CurrentUser requireEmployeeUser(){var u=SecurityUtils.current();if(!"EMPLOYEE".equals(u.role()))throw new BusinessException(403,"仅员工可维护个人资料");return u;}
  private Map<String,Object> load(Long userId){var rows=db.queryForList("select e.id,e.employee_no,e.name,e.batch_id,e.station_id,e.mentor_user_id,e.school,e.major,e.education,e.birth_date,e.native_place,e.residence,e.phone,e.email,e.onboard_date,e.status,b.name batch_name,s.name station_name,m.display_name mentor_name from employee e left join talent_batch b on b.id=e.batch_id left join service_station s on s.id=e.station_id left join sys_user m on m.id=e.mentor_user_id where e.user_id=?",userId);if(rows.isEmpty())throw new BusinessException(404,"员工信息不存在");return rows.get(0);}
}
