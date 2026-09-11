package com.talent.platform.task;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

@Data
@ColumnWidth(20)
public class TaskScoreExportRow {
  @ExcelProperty("任务名称") private String taskTitle;
  @ExcelProperty("员工姓名") private String employeeName;
  @ExcelProperty("工号") private String employeeNo;
  @ExcelProperty("批次") private String batchName;
  @ExcelProperty("板块") private String businessUnitName;
  @ExcelProperty("班级") private String className;
  @ExcelProperty("评分人") private String reviewerNames;
  @ExcelProperty("提交版本") private String submissionVersion;
  @ExcelProperty("提交时间") private String submittedAt;
  @ExcelProperty("评分状态") private String status;
  @ExcelProperty("已评分人数") private Integer submittedReviewCount;
  @ExcelProperty("应评分人数") private Integer reviewerCount;
  @ExcelProperty("最终平均分") private BigDecimal finalScore;

  static TaskScoreExportRow from(String title, Map<String, Object> row) {
    var out = new TaskScoreExportRow();
    out.taskTitle = title;
    out.employeeName = text(row, "employee_name");
    out.employeeNo = text(row, "employee_no");
    out.batchName = text(row, "batch_name");
    out.businessUnitName = text(row, "business_unit_name");
    out.className = text(row, "class_name");
    out.reviewerNames = text(row, "reviewer_names");
    out.submissionVersion = text(row, "submission_version");
    out.submittedAt = text(row, "submitted_at");
    out.submittedReviewCount = number(row, "submittedReviewCount");
    out.reviewerCount = number(row, "reviewerCount");
    out.status = switch (text(row, "status")) {
      case "NOT_SUBMITTED" -> "未提交";
      case "PENDING_REVIEW" -> out.reviewerCount == 0 ? "待分配评分人" : "待评分";
      case "APPROVED" -> "已通过";
      case "RETURNED" -> "已退回";
      case "OVERDUE" -> "已逾期";
      default -> text(row, "status");
    };
    out.finalScore = row.get("final_score") instanceof Number n ? new BigDecimal(n.toString()) : null;
    return out;
  }

  private static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private static int number(Map<String, Object> row, String key) {
    return row.get(key) instanceof Number n ? n.intValue() : 0;
  }
}
