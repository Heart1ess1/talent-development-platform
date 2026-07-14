package com.talent.platform.employee;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.PageResult;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/employee-directory")
public class EmployeeDirectoryController {
  private static final String SELECT = "select e.id,e.employee_no,e.name,e.school,e.major,e.education,e.birth_date,e.native_place,e.residence,e.phone,e.onboard_date,e.status,b.name batch_name,s.name station_name,m.display_name mentor_name";
  private static final String FROM = " from employee e left join talent_batch b on b.id=e.batch_id left join service_station s on s.id=e.station_id left join sys_user m on m.id=e.mentor_user_id";
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public EmployeeDirectoryController(JdbcTemplate db,PermissionService permissions,AuditService audit){this.db=db;this.permissions=permissions;this.audit=audit;}

  @GetMapping
  public ApiResponse<PageResult<Map<String,Object>>> list(
      @RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue="20") int size,
      @RequestParam(required=false) String keyword,
      @RequestParam(required=false) Long batchId,
      @RequestParam(required=false) Long stationId,
      @RequestParam(required=false) Long mentorId,
      @RequestParam(required=false) String education,
      @RequestParam(required=false) String status){
    permissions.require(Permissions.EMPLOYEE_READ);var query=filters(keyword,batchId,stationId,mentorId,education,status);
    long total=db.queryForObject("select count(*)"+FROM+query.sql(),Long.class,query.args().toArray());
    var args=new ArrayList<>(query.args());
    args.add(Math.min(Math.max(size,1),100));
    args.add(Math.max(0,(page-1)*size));
    var rows=db.queryForList(SELECT+FROM+query.sql()+" order by e.id desc limit ? offset ?",args.toArray());
    return ApiResponse.ok(new PageResult<>(rows,total,page,Math.min(Math.max(size,1),100)));
  }

  @GetMapping("/export")
  public void export(
      @RequestParam(required=false) String keyword,
      @RequestParam(required=false) Long batchId,
      @RequestParam(required=false) Long stationId,
      @RequestParam(required=false) Long mentorId,
      @RequestParam(required=false) String education,
      @RequestParam(required=false) String status,
      HttpServletResponse response)throws Exception{
    permissions.require(Permissions.EMPLOYEE_EXPORT);var query=filters(keyword,batchId,stationId,mentorId,education,status);
    var rows=db.queryForList(SELECT+FROM+query.sql()+" order by e.id desc",query.args().toArray());
    var output=rows.stream().map(this::toExportRow).toList();
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode("人员信息.xlsx",StandardCharsets.UTF_8));
    audit.log("EXPORT_EMPLOYEES","EMPLOYEE",null,null,Map.of("count",output.size()));EasyExcel.write(response.getOutputStream(),EmployeeDirectoryExportRow.class).sheet("人员信息").doWrite(output);
  }

  private FilterQuery filters(String keyword,Long batchId,Long stationId,Long mentorId,String education,String status){
    var where=new StringBuilder(" where 1=1");
    var args=new ArrayList<Object>();
    if(keyword!=null&&!keyword.isBlank()){where.append(" and (e.name like ? or e.employee_no like ?)");String value="%"+keyword.trim()+"%";args.add(value);args.add(value);}
    if(batchId!=null){where.append(" and e.batch_id=?");args.add(batchId);}
    if(stationId!=null){where.append(" and e.station_id=?");args.add(stationId);}
    if(mentorId!=null){where.append(" and e.mentor_user_id=?");args.add(mentorId);}
    if(education!=null&&!education.isBlank()){where.append(" and e.education=?");args.add(education);}
    if(status!=null&&!status.isBlank()){where.append(" and e.status=?");args.add(status);}
    return new FilterQuery(where.toString(),args);
  }

  private EmployeeDirectoryExportRow toExportRow(Map<String,Object> row){
    var out=new EmployeeDirectoryExportRow();
    out.setEmployeeNo(string(row,"employee_no"));out.setName(string(row,"name"));out.setBatchName(string(row,"batch_name"));out.setStationName(string(row,"station_name"));out.setMentorName(string(row,"mentor_name"));out.setSchool(string(row,"school"));out.setMajor(string(row,"major"));out.setEducation(string(row,"education"));out.setBirthDate(date(row.get("birth_date")));out.setNativePlace(string(row,"native_place"));out.setResidence(string(row,"residence"));out.setPhone(string(row,"phone"));out.setOnboardDate(date(row.get("onboard_date")));out.setStatus(string(row,"status"));
    return out;
  }

  private String string(Map<String,Object> row,String key){var value=row.get(key);return value==null?null:String.valueOf(value);}
  private java.time.LocalDate date(Object value){return value instanceof Date date?date.toLocalDate():null;}
  private record FilterQuery(String sql,List<Object> args){}
}
