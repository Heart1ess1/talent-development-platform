package com.talent.platform.importer;
import com.alibaba.excel.annotation.ExcelProperty;import lombok.*;
@Data @NoArgsConstructor
public class AttendanceImportRow {
 @ExcelProperty("场次ID") private Long sessionId; @ExcelProperty("员工工号") private String employeeNo; @ExcelProperty("备注") private String remark;
}
