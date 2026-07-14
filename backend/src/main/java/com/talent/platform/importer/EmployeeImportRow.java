package com.talent.platform.importer;
import com.alibaba.excel.annotation.ExcelProperty;import lombok.*;
@Data @NoArgsConstructor
public class EmployeeImportRow {
 @ExcelProperty("工号") private String employeeNo; @ExcelProperty("姓名") private String name; @ExcelProperty("批次") private String batch;
 @ExcelProperty("服务站") private String station; @ExcelProperty("毕业学校") private String school; @ExcelProperty("专业") private String major;
 @ExcelProperty("学历") private String education; @ExcelProperty("出生日期") private String birthDate; @ExcelProperty("籍贯") private String nativePlace;
 @ExcelProperty("常住地") private String residence; @ExcelProperty("手机号") private String phone; @ExcelProperty("常用邮箱") private String email; @ExcelProperty("入职日期") private String onboardDate;
}
