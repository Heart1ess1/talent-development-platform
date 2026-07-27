package com.talent.platform.employee;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeDirectoryExportRow {
  @ExcelProperty("姓名")
  private String name;
  @ExcelProperty("工号")
  private String employeeNo;
  @ExcelProperty("批次")
  private String batchName;
  @ExcelProperty("所属板块")
  private String businessUnitName;
  @ExcelProperty("服务站点")
  private String stationName;
  @ExcelProperty("指导老师（技术）")
  private String technicalMentorName;
  @ExcelProperty("指导老师（技能）")
  private String skillMentorName;
  @ExcelProperty("身份证号码")
  private String idCard;
  @ExcelProperty("毕业学校")
  private String school;
  @ExcelProperty("所学专业")
  private String major;
  @ExcelProperty("学历")
  private String education;
  @ExcelProperty("籍贯")
  private String nativePlace;
  @ExcelProperty("政治面貌")
  private String politicalStatus;
  @ExcelProperty("住址（公司）")
  private String residence;
  @ExcelProperty("兴趣爱好")
  private String hobbies;
  @ExcelProperty("特长")
  private String speciality;
  @ExcelProperty("私人邮箱")
  private String email;
  @ExcelProperty("联系方式")
  private String phone;
  @ExcelProperty("状态")
  private String status;
  @ExcelProperty("出生日期")
  private LocalDate birthDate;
  @ExcelProperty("入职日期")
  private LocalDate onboardDate;
}
