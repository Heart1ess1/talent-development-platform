package com.talent.platform.history;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

public final class HistoryImportRows {
  private HistoryImportRows() {}

  @Data
  public static class ExamResult {
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("姓名") private String employeeName;
    @ExcelProperty("成绩") private String score;
  }

  /** One row is one employee/period/component; summary fields repeat by design. */
  @Data
  public static class EvaluationFlat {
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("姓名") private String employeeName;
    @ExcelProperty("评价类型") private String summaryType;
    @ExcelProperty("期间") private String periodKey;
    @ExcelProperty("最终分数") private String finalScore;
    @ExcelProperty("评分项") private String componentCode;
    @ExcelProperty("来源编号") private String sourceKey;
    @ExcelProperty("来源名称") private String sourceName;
    @ExcelProperty("原始分") private String rawScore;
    @ExcelProperty("满分") private String maxScore;
    @ExcelProperty("权重") private String weight;
    @ExcelProperty("加权分") private String weightedScore;
    @ExcelProperty("评分人") private String evaluatorName;
    @ExcelProperty("评分意见") private String comment;
  }
}
