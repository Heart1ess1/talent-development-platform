package com.talent.platform.task;

import com.alibaba.excel.annotation.ExcelIgnoreUnannotated;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
@ExcelIgnoreUnannotated
public class TaskProgressExportRow {
  @ExcelProperty(value = "员工姓名", index = 0)
  private String employeeName;

  @ExcelProperty(value = "工号", index = 1)
  private String employeeNo;

  @ExcelProperty(value = "下发时间", index = 2)
  private String assignedAt;

  @ExcelProperty(value = "最近提交时间", index = 3)
  private String submittedAt;

  @ExcelProperty(value = "完成状态", index = 4)
  private String status;

  @ExcelProperty(value = "评分", index = 5)
  private Integer score;

  @ExcelProperty(value = "最新版本", index = 6)
  private Integer submissionVersion;

  @ExcelProperty(value = "附件数量", index = 7)
  private Integer fileCount;

  @ExcelProperty(value = "审核意见", index = 8)
  private String reviewComment;
}
