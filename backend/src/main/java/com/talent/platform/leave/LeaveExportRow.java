package com.talent.platform.leave;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

@Data
@ColumnWidth(20)
public class LeaveExportRow {
  @ExcelProperty("请假单号") private Long requestId;
  @ExcelProperty("版本") private Integer version;
  @ExcelProperty("工号") private String employeeNo;
  @ExcelProperty("姓名") private String employeeName;
  @ExcelProperty("服务站") private String station;
  @ExcelProperty("请假场景") private String scenario;
  @ExcelProperty("请假性质") private String leaveType;
  @ExcelProperty("开始时间") private String startAt;
  @ExcelProperty("结束及预计回段时间") private String endAt;
  @ExcelProperty("天数") private BigDecimal days;
  @ExcelProperty("请假去向") private String destination;
  @ExcelProperty("预计回段地点") private String returnLocation;
  @ExcelProperty("请假原因") @ColumnWidth(40) private String reason;
  @ExcelProperty("补提说明") @ColumnWidth(40) private String supplementalReason;
  @ExcelProperty("审批层级") private String level;
  @ExcelProperty("指定审批人") private String approver;
  @ExcelProperty("实际审批人") private String reviewer;
  @ExcelProperty("审批状态") private String approvalStatus;
  @ExcelProperty("请假单状态") private String requestStatus;
  @ExcelProperty("逾期未销假") private String overdue;
  @ExcelProperty("提交时间") private String createdAt;
  @ExcelProperty("审批时间") private String reviewedAt;
  @ExcelProperty("实际返回时间") private String actualReturnAt;
  @ExcelProperty("实际返回地点") private String actualReturnLocation;

  static LeaveExportRow from(Map<String,Object> row) {
    var item=new LeaveExportRow();
    item.requestId=((Number)row.get("request_id")).longValue();
    item.version=((Number)row.get("version_no")).intValue();
    item.employeeNo=text(row,"employee_no");item.employeeName=text(row,"employee_name");
    item.station=Objects.toString(row.get("station_name"),"未分配服务站");
    item.scenario=switch(text(row,"scenario")){case "FIELD_ABSENCE"->"现场临时不上班";case "RETURN_COMPANY"->"返司";case "RETURN_HOME"->"返家";default->"其他";};
    item.leaveType="OFFICIAL".equals(row.get("leave_type"))?"因公":"因私";
    item.startAt=time(row,"start_at");item.endAt=time(row,"end_at");
    item.days=BigDecimal.valueOf(((Number)row.get("day_units")).longValue()).divide(BigDecimal.valueOf(2));
    item.destination=text(row,"destination");item.returnLocation=text(row,"return_location");
    item.reason=text(row,"reason");item.supplementalReason=text(row,"supplemental_reason");
    item.level=switch(text(row,"approver_level")){case "STATION_MANAGER"->"站长";case "AREA_MANAGER"->"区域经理";default->"培训管理员";};
    item.approver=text(row,"approver_name");item.reviewer=text(row,"reviewer_name");
    item.approvalStatus=status(text(row,"status"));item.requestStatus=status(text(row,"request_status"));
    item.overdue=Boolean.TRUE.equals(row.get("overdue"))||"1".equals(text(row,"overdue"))?"是":"否";
    item.createdAt=time(row,"created_at");item.reviewedAt=time(row,"reviewed_at");
    item.actualReturnAt=time(row,"actual_return_at");item.actualReturnLocation=text(row,"actual_return_location");
    return item;
  }
  private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
  private static String time(Map<String,Object> row,String key){return text(row,key).replace('T',' ').replaceAll("\\.0$","");}
  private static String status(String code){return switch(code){case "PENDING"->"待审批";case "APPROVED"->"已批准";case "REJECTED"->"已驳回";case "WITHDRAWN"->"已撤回";case "CANCELLED"->"已取消";case "RETURNED"->"已销假";default->code;};}
}
