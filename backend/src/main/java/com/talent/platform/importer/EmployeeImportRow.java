package com.talent.platform.importer;

import com.alibaba.excel.annotation.ExcelIgnoreUnannotated;
import com.alibaba.excel.annotation.ExcelProperty;
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
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
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
public class EmployeeImportRow {

    @ExcelProperty(value = "序号", order = 0)
    @NumberFormat("0")
    @ColumnWidth(8)
    private Integer serialNo;

    @ExcelProperty(value = "工号", order = 1)
    private String employeeNo;

    @ExcelProperty(value = "姓名", order = 2)
    @ColumnWidth(22)
    private String name;

    @ExcelProperty(value = "性别", order = 3)
    @ColumnWidth(10)
    private String gender;

    @ExcelProperty(value = "批次", order = 4)
    @ColumnWidth(12)
    private String batch;

    @ExcelProperty(value = "班级", order = 5)
    @ColumnWidth(14)
    private String className;

    @ExcelProperty(value = "班级职务", order = 6)
    @ColumnWidth(14)
    private String classPositionName;

    @ExcelProperty(value = "所属板块", order = 7)
    private String businessUnit;

    @ExcelProperty(value = "服务站点", order = 8)
    @ColumnWidth(20)
    private String station;

    @ExcelProperty(value = "指导老师（技术）", order = 9)
    @ColumnWidth(20)
    private String technicalMentor;

    @ExcelProperty(value = "指导老师（技能）", order = 10)
    @ColumnWidth(20)
    private String skillMentor;

    @ExcelProperty(value = "身份证号码", order = 11)
    @ColumnWidth(22)
    private String idCard;

    @ExcelProperty(value = "毕业学校", order = 12)
    @ColumnWidth(20)
    private String school;

    @ExcelProperty(value = "所学专业", order = 13)
    private String major;

    @ExcelProperty(value = "学历", order = 14)
    @ColumnWidth(10)
    private String education;

    @ExcelProperty(value = "出生日期", order = 15)
    @ColumnWidth(14)
    private String birthDate;

    @ExcelProperty(value = "籍贯", order = 16)
    private String nativePlace;

    @ExcelProperty(value = "政治面貌", order = 17)
    @ColumnWidth(12)
    private String politicalStatus;

    @ExcelProperty(value = "住址（公司）", order = 18)
    @ColumnWidth(24)
    private String residence;

    @ExcelProperty(value = "兴趣爱好", order = 19)
    @ColumnWidth(20)
    private String hobbies;

    @ExcelProperty(value = "特长", order = 20)
    @ColumnWidth(20)
    private String speciality;

    @ExcelProperty(value = "私人邮箱", order = 21)
    @ColumnWidth(26)
    private String email;

    @ExcelProperty(value = "联系方式", order = 22)
    private String phone;

    @ExcelProperty(value = "入职日期", order = 23)
    @ColumnWidth(14)
    private String onboardDate;

    @ExcelProperty(value = "状态", order = 24)
    @ColumnWidth(10)
    private String status;
}
