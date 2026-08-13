package com.talent.platform.exam;

import com.alibaba.excel.EasyExcel;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionBankTemplateTest {
  private static final String TEMPLATE = "templates/question-bank-template.xlsx";
  private static final List<String> HEADERS = List.of(
      "题型", "题干", "选项A", "选项B", "选项C", "选项D", "选项E", "选项F",
      "正确答案", "答案解析", "默认分值", "专业标签"
  );

  @Test
  void templateContainsImportSheetGuideExamplesAndValidations() throws Exception {
    var resource = new ClassPathResource(TEMPLATE);
    try (var workbook = new XSSFWorkbook(resource.getInputStream())) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      assertThat(workbook.getSheetName(0)).isEqualTo("题库导入");
      assertThat(workbook.getSheetName(1)).isEqualTo("填写示例与说明");

      var importSheet = workbook.getSheetAt(0);
      var header = importSheet.getRow(0);
      assertThat(IntStream.range(0, HEADERS.size())
          .mapToObj(index -> header.getCell(index).getStringCellValue())
          .toList()).isEqualTo(HEADERS);

      var validations = importSheet.getDataValidations();
      assertThat(validations).hasSizeGreaterThanOrEqualTo(3);
      assertThat(validations.stream()
          .filter(validation -> validation.getValidationConstraint().getValidationType()
              == DataValidationConstraint.ValidationType.LIST)
          .count()).isEqualTo(2);
      var typeValidation = validations.stream()
          .filter(validation -> validation.getRegions().getCellRangeAddresses()[0].getFirstColumn() == 0)
          .findFirst().orElseThrow();
      assertThat(typeValidation.getValidationConstraint().getExplicitListValues())
          .containsExactly("单选题", "多选题", "判断题");
      var tagValidation = validations.stream()
          .filter(validation -> validation.getRegions().getCellRangeAddresses()[0].getFirstColumn() == 11)
          .findFirst().orElseThrow();
      assertThat(tagValidation.getValidationConstraint().getExplicitListValues())
          .contains("机动车", "城轨", "安全管理", "应急处置");

      var guide = workbook.getSheetAt(1);
      assertThat(guide.getRow(0).getCell(0).getStringCellValue()).contains("题库导入模板");
      assertThat(guide.getRow(28).getCell(0).getStringCellValue()).isEqualTo("单选题");
      assertThat(guide.getRow(29).getCell(0).getStringCellValue()).isEqualTo("多选题");
      assertThat(guide.getRow(30).getCell(0).getStringCellValue()).isEqualTo("判断题");
      for (var sheet : workbook)
        for (var row : sheet)
          for (var cell : row)
            assertThat(cell.toString()).doesNotContain("简答");
    }
  }

  @Test
  void blankImportSheetRemainsCompatibleWithEasyExcelRowMapping() throws Exception {
    var resource = new ClassPathResource(TEMPLATE);
    List<QuestionImportRow> rows;
    try (var input = resource.getInputStream()) {
      rows = EasyExcel.read(input).head(QuestionImportRow.class).sheet(0).doReadSync();
    }
    assertThat(rows).isEmpty();
  }
}
