package com.talent.platform.employee;

import com.talent.platform.common.*;
import com.talent.platform.security.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import static com.talent.platform.employee.EmployeePortraitDtos.*;

@RestController
@RequestMapping("/api/v1/employees/{employeeId}/portrait")
public class EmployeePortraitController {
  private final EmployeePortraitService service;private final PermissionService permissions;
  public EmployeePortraitController(EmployeePortraitService service,PermissionService permissions){this.service=service;this.permissions=permissions;}
  private void access(long id){permissions.require(Permissions.EMPLOYEE_PORTRAIT_VIEW);permissions.requireEmployee(id);}
  @GetMapping("/overview") public ApiResponse<Overview> overview(@PathVariable long employeeId){access(employeeId);return ApiResponse.ok(service.overview(employeeId));}
  @GetMapping("/courses") public ApiResponse<Courses> courses(@PathVariable long employeeId,@RequestParam(defaultValue="SESSIONS")String type,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo){access(employeeId);return ApiResponse.ok(service.courses(employeeId,type,page,size,dateFrom,dateTo));}
  @GetMapping("/tasks") public ApiResponse<PageResult<Task>> tasks(@PathVariable long employeeId,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo){access(employeeId);return ApiResponse.ok(service.tasks(employeeId,status,page,size,dateFrom,dateTo));}
  @GetMapping("/exams") public ApiResponse<PageResult<Exam>> exams(@PathVariable long employeeId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo){access(employeeId);return ApiResponse.ok(service.exams(employeeId,page,size,dateFrom,dateTo));}
  @GetMapping("/evaluations") public ApiResponse<PageResult<Evaluation>> evaluations(@PathVariable long employeeId,@RequestParam(defaultValue="MONTH")String type,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo){access(employeeId);return ApiResponse.ok(service.evaluations(employeeId,type,page,size,dateFrom,dateTo));}
  @GetMapping("/stations") public ApiResponse<Stations> stations(@PathVariable long employeeId){access(employeeId);return ApiResponse.ok(service.stations(employeeId));}
  @GetMapping("/locations") public ApiResponse<Locations> locations(@PathVariable long employeeId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo){access(employeeId);return ApiResponse.ok(service.locations(employeeId,page,size,dateFrom,dateTo));}
  @GetMapping("/timeline") public ApiResponse<PageResult<TimelineEvent>> timeline(@PathVariable long employeeId,@RequestParam(required=false)String type,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateFrom,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate dateTo){access(employeeId);return ApiResponse.ok(service.timeline(employeeId,type,page,size,dateFrom,dateTo));}
}
