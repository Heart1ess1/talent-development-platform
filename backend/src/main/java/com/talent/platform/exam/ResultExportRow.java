package com.talent.platform.exam;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ResultExportRow {
    @ExcelProperty("员工姓名")
    private String employeeName;

    @ExcelProperty("专业方向")
    private String major;

    @ExcelProperty("考试名称")
    private String examName;

    @ExcelProperty("成绩月份")
    private String scoreMonth;

    @ExcelProperty("考试次数")
    private Integer attemptNo;

    @ExcelProperty("客观题得分")
    private BigDecimal objectiveScore;

    @ExcelProperty("主观题得分")
    private BigDecimal subjectiveScore;

    @ExcelProperty("总分")
    private BigDecimal totalScore;

    @ExcelProperty("状态")
    private String status;

    @ExcelProperty("提交时间")
    private String submittedAt;
}
