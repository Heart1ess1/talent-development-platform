package com.talent.platform.employee;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.ExcelIgnoreUnannotated;
import com.alibaba.excel.annotation.format.DateTimeFormat;
import com.alibaba.excel.annotation.format.NumberFormat;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadFontStyle;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import com.alibaba.excel.annotation.write.style.HeadStyle;
import com.alibaba.excel.enums.BooleanEnum;
import com.alibaba.excel.enums.poi.FillPatternTypeEnum;
import com.alibaba.excel.enums.poi.HorizontalAlignmentEnum;
import com.alibaba.excel.enums.poi.VerticalAlignmentEnum;
import lombok.Data;

import java.time.LocalDate;

@Data
@ExcelIgnoreUnannotated
@ColumnWidth(16)
@HeadRowHeight(30)
@ContentRowHeight(22)
@HeadFontStyle(bold = BooleanEnum.TRUE, color = 9, fontHeightInPoints = 11)
@HeadStyle(
    fillForegroundColor = 12,
    fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND,
    horizontalAlignment = HorizontalAlignmentEnum.CENTER,
    verticalAlignment = VerticalAlignmentEnum.CENTER)
public class EmployeeDirectoryExportRow {
  @ExcelProperty(value = "序号", index = 0)
  @NumberFormat("0")
  @ColumnWidth(8)
  private Integer serialNo;
  @ExcelProperty(value = "工号", index = 1)
  private String employeeNo;
  @ExcelProperty(value = "姓名", index = 2)
  @ColumnWidth(22)
  private String name;
  @ExcelProperty(value = "性别", index = 3)
  @ColumnWidth(10)
  private String gender;
  @ExcelProperty(value = "批次", index = 4)
  @ColumnWidth(12)
  private String batchName;
  @ExcelProperty(value = "班级", index = 5)
  @ColumnWidth(14)
  private String className;
  @ExcelProperty(value = "班级职务", index = 6)
  @ColumnWidth(14)
  private String classPositionName;
  @ExcelProperty(value = "所属板块", index = 7)
  private String businessUnitName;
  @ExcelProperty(value = "服务站点", index = 8)
  @ColumnWidth(20)
  private String stationName;
  @ExcelProperty(value = "指导老师（技术）", index = 9)
  @ColumnWidth(20)
  private String technicalMentorName;
  @ExcelProperty(value = "指导老师（技能）", index = 10)
  @ColumnWidth(20)
  private String skillMentorName;
  @ExcelProperty(value = "身份证号码", index = 11)
  @ColumnWidth(22)
  private String idCard;
  @ExcelProperty(value = "毕业学校", index = 12)
  @ColumnWidth(20)
  private String school;
  @ExcelProperty(value = "所学专业", index = 13)
  private String major;
  @ExcelProperty(value = "学历", index = 14)
  @ColumnWidth(10)
  private String education;
  @ExcelProperty(value = "出生日期", index = 15)
  @DateTimeFormat("yyyy-MM-dd")
  @ColumnWidth(14)
  private LocalDate birthDate;
  @ExcelProperty(value = "籍贯", index = 16)
  private String nativePlace;
  @ExcelProperty(value = "政治面貌", index = 17)
  @ColumnWidth(12)
  private String politicalStatus;
  @ExcelProperty(value = "住址（公司）", index = 18)
  @ColumnWidth(24)
  private String residence;
  @ExcelProperty(value = "兴趣爱好", index = 19)
  @ColumnWidth(20)
  private String hobbies;
  @ExcelProperty(value = "特长", index = 20)
  @ColumnWidth(20)
  private String speciality;
  @ExcelProperty(value = "私人邮箱", index = 21)
  @ColumnWidth(26)
  private String email;
  @ExcelProperty(value = "联系方式", index = 22)
  private String phone;
  @ExcelProperty(value = "入职日期", index = 23)
  @DateTimeFormat("yyyy-MM-dd")
  @ColumnWidth(14)
  private LocalDate onboardDate;
  @ExcelProperty(value = "状态", index = 24)
  @ColumnWidth(10)
  private String status;
}
