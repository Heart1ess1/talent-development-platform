package com.talent.platform.employee;

import com.alibaba.excel.annotation.ExcelProperty;
import java.time.LocalDate;

public class EmployeeDirectoryExportRow {
  @ExcelProperty("工号") private String employeeNo;
  @ExcelProperty("姓名") private String name;
  @ExcelProperty("批次") private String batchName;
  @ExcelProperty("服务站") private String stationName;
  @ExcelProperty("导师") private String mentorName;
  @ExcelProperty("毕业学校") private String school;
  @ExcelProperty("专业") private String major;
  @ExcelProperty("学历") private String education;
  @ExcelProperty("出生日期") private LocalDate birthDate;
  @ExcelProperty("籍贯") private String nativePlace;
  @ExcelProperty("常住地") private String residence;
  @ExcelProperty("手机号") private String phone;
  @ExcelProperty("常用邮箱") private String email;
  @ExcelProperty("入职日期") private LocalDate onboardDate;
  @ExcelProperty("状态") private String status;

  public String getEmployeeNo(){return employeeNo;} public void setEmployeeNo(String v){employeeNo=v;}
  public String getName(){return name;} public void setName(String v){name=v;}
  public String getBatchName(){return batchName;} public void setBatchName(String v){batchName=v;}
  public String getStationName(){return stationName;} public void setStationName(String v){stationName=v;}
  public String getMentorName(){return mentorName;} public void setMentorName(String v){mentorName=v;}
  public String getSchool(){return school;} public void setSchool(String v){school=v;}
  public String getMajor(){return major;} public void setMajor(String v){major=v;}
  public String getEducation(){return education;} public void setEducation(String v){education=v;}
  public LocalDate getBirthDate(){return birthDate;} public void setBirthDate(LocalDate v){birthDate=v;}
  public String getNativePlace(){return nativePlace;} public void setNativePlace(String v){nativePlace=v;}
  public String getResidence(){return residence;} public void setResidence(String v){residence=v;}
  public String getPhone(){return phone;} public void setPhone(String v){phone=v;}
  public String getEmail(){return email;} public void setEmail(String v){email=v;}
  public LocalDate getOnboardDate(){return onboardDate;} public void setOnboardDate(LocalDate v){onboardDate=v;}
  public String getStatus(){return status;} public void setStatus(String v){status=v;}
}
