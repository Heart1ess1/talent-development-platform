package com.talent.platform.exam;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class QuestionImportRow {
  @ExcelProperty("题型") private String type;
  @ExcelProperty("题干") private String stem;
  @ExcelProperty("选项A") private String optionA;
  @ExcelProperty("选项B") private String optionB;
  @ExcelProperty("选项C") private String optionC;
  @ExcelProperty("选项D") private String optionD;
  @ExcelProperty("选项E") private String optionE;
  @ExcelProperty("选项F") private String optionF;
  @ExcelProperty("正确答案") private String answer;
  @ExcelProperty("答案解析") private String explanation;
  @ExcelProperty("默认分值") private BigDecimal score;
}
