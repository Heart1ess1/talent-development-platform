package com.talent.platform.history;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

public final class HistoryImportRows {
  private HistoryImportRows() {}

  @Data
  public static class Exam {
    @ExcelProperty("外部考试编号") private String externalExamKey;
    @ExcelProperty("考试名称") private String name;
    @ExcelProperty("考试日期") private String examDate;
    @ExcelProperty("成绩月份") private String scoreMonth;
    @ExcelProperty("满分") private String maxScore;
    @ExcelProperty("来源系统") private String sourceSystem;
    @ExcelProperty("备注") private String remark;
  }

  @Data
  public static class Result {
    @ExcelProperty("外部考试编号") private String externalExamKey;
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("姓名") private String employeeName;
    @ExcelProperty("完成状态") private String resultStatus;
    @ExcelProperty("成绩") private String score;
    @ExcelProperty("客观题分") private String objectiveScore;
    @ExcelProperty("主观题分") private String subjectiveScore;
    @ExcelProperty("考试次数") private String attemptNo;
    @ExcelProperty("考试日期") private String takenAt;
    @ExcelProperty("备注") private String remark;
  }

  @Data
  public static class Evaluation {
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("姓名") private String employeeName;
    @ExcelProperty("评价类型") private String summaryType;
    @ExcelProperty("期间") private String periodKey;
    @ExcelProperty("最终分数") private String finalScore;
    @ExcelProperty("来源系统") private String sourceSystem;
    @ExcelProperty("备注") private String remark;
  }

  @Data
  public static class Component {
    @ExcelProperty("工号") private String employeeNo;
    @ExcelProperty("期间") private String periodKey;
    @ExcelProperty("评价类型") private String summaryType;
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
