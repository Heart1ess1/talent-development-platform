package com.talent.platform.exam;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.format.NumberFormat;
import lombok.Data;

import java.math.BigDecimal;

/** 每日练习成绩导出单元格。 */
@Data
public class DailyPracticeResultExportRow {
  @ExcelProperty("工号")
  private String employeeNo;

  @ExcelProperty("姓名")
  private String employeeName;

  @ExcelProperty("批次")
  private String batchName;

  @ExcelProperty("练习日期")
  private String practiceDate;

  @ExcelProperty("题数")
  private Integer questionCount;

  @ExcelProperty("正确数")
  private Integer correctCount;

  @ExcelProperty("得分")
  @NumberFormat("0.00")
  private BigDecimal totalScore;

  @ExcelProperty("状态")
  private String status;

  @ExcelProperty("提交时间")
  private String submittedAt;
}
