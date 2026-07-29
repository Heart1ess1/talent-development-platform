package com.talent.platform.importer;

import com.alibaba.excel.annotation.ExcelIgnoreUnannotated;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadFontStyle;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import com.alibaba.excel.annotation.write.style.HeadStyle;
import com.alibaba.excel.enums.BooleanEnum;
import com.alibaba.excel.enums.poi.FillPatternTypeEnum;
import com.alibaba.excel.enums.poi.HorizontalAlignmentEnum;
import com.alibaba.excel.enums.poi.VerticalAlignmentEnum;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@ExcelIgnoreUnannotated
@HeadRowHeight(30)
@ContentRowHeight(24)
@HeadFontStyle(bold = BooleanEnum.TRUE, color = 9, fontHeightInPoints = 11)
@HeadStyle(
        fillForegroundColor = 12,
        fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND,
        horizontalAlignment = HorizontalAlignmentEnum.CENTER,
        verticalAlignment = VerticalAlignmentEnum.CENTER)
public class EmployeeImportInstructionRow {

    @ExcelProperty(value = "字段", index = 0)
    @ColumnWidth(22)
    private String field;

    @ExcelProperty(value = "是否必填", index = 1)
    @ColumnWidth(12)
    private String required;

    @ExcelProperty(value = "填写规则", index = 2)
    @ColumnWidth(58)
    private String rule;

    @ExcelProperty(value = "示例", index = 3)
    @ColumnWidth(28)
    private String example;
}
