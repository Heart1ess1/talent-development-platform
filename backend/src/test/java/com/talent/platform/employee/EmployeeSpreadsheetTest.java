package com.talent.platform.employee;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.importer.EmployeeImportRow;
import com.talent.platform.importer.ImportController;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeSpreadsheetTest {
  private static final List<String> HEADERS = List.of(
      "序号", "工号", "姓名", "批次", "所属板块", "服务站点",
      "指导老师（技术）", "指导老师（技能）", "身份证号码", "毕业学校",
      "所学专业", "学历", "出生日期", "籍贯", "政治面貌", "住址（公司）",
      "兴趣爱好", "特长", "私人邮箱", "联系方式", "入职日期", "状态");

  @Test
  void exportUsesCompleteChineseEmployeeSchema() throws Exception {
    var db = mock(JdbcTemplate.class);
    var permissions = mock(PermissionService.class);
    when(permissions.employeeFilter("e"))
        .thenReturn(new PermissionService.ScopeFilter("", List.of()));
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.ofEntries(
        Map.entry("employee_no", "employee"),
        Map.entry("name", "新员工"),
        Map.entry("batch_name", "2026届"),
        Map.entry("business_unit_name", "机动车"),
        Map.entry("technical_mentor_name", "技术导师"),
        Map.entry("skill_mentor_name", "技能导师"),
        Map.entry("status", "ACTIVE"),
        Map.entry("birth_date", Date.valueOf(LocalDate.of(2002, 1, 1))),
        Map.entry("onboard_date", Date.valueOf(LocalDate.of(2026, 7, 1))))));
    var controller = new EmployeeDirectoryController(
        db, permissions, mock(AuditService.class));
    var response = new MockHttpServletResponse();

    controller.export(null, null, null, null, null, null, null, null, null, response);

    try (var workbook = WorkbookFactory.create(
        new ByteArrayInputStream(response.getContentAsByteArray()))) {
      var sheet = workbook.getSheet("人员台账");
      assertThat(sheet).isNotNull();
      assertThat(headers(sheet.getRow(0))).containsExactlyElementsOf(HEADERS);
      assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1);
      assertThat(sheet.getRow(1).getCell(0).getCellStyle().getDataFormatString())
          .isEqualTo("0");
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("employee");
      assertThat(sheet.getRow(1).getCell(21).getStringCellValue()).isEqualTo("在职");
      assertThat(sheet.getPaneInformation()).isNotNull();
    }
  }

  @Test
  void importTemplateMatchesExportAndIncludesInstructions() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(anyString(), eq(String.class)))
        .thenReturn(List.of("2026届"));
    var controller = new ImportController(
        db,
        mock(PasswordEncoder.class),
        mock(PermissionService.class),
        mock(AuditService.class));
    var response = new MockHttpServletResponse();

    controller.template(response);

    try (var workbook = WorkbookFactory.create(
        new ByteArrayInputStream(response.getContentAsByteArray()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      var employeeSheet = workbook.getSheet("新员工导入");
      assertThat(headers(employeeSheet.getRow(0))).containsExactlyElementsOf(HEADERS);
      assertThat(employeeSheet.getRow(1).getCell(21).getStringCellValue()).isEqualTo("在职");
      assertThat(employeeSheet.getRow(1).getCell(0).getCellStyle().getDataFormatString())
          .isEqualTo("0");
      assertThat(employeeSheet.getPaneInformation()).isNotNull();
      var instructions = workbook.getSheet("填写说明");
      assertThat(instructions).isNotNull();
      assertThat(instructions.getLastRowNum()).isEqualTo(22);
      assertThat(instructions.getRow(1).getCell(0).getStringCellValue()).isEqualTo("序号");
      assertThat(instructions.getRow(22).getCell(0).getStringCellValue()).isEqualTo("状态");
    }
  }

  @Test
  void importAcceptsChineseStatusAndPersistsNormalizedValue() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("employee_no"), anyString())).thenReturn(List.of());
    when(db.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(7L);
    var encoder = mock(PasswordEncoder.class);
    when(encoder.encode(anyString())).thenReturn("hash");
    var controller = new ImportController(
        db, encoder, mock(PermissionService.class), mock(AuditService.class));
    var row = new EmployeeImportRow();
    row.setEmployeeNo("20260002");
    row.setName("导入员工");
    row.setBatch("2026届");
    row.setStatus("停用");
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes, EmployeeImportRow.class)
        .sheet("新员工导入")
        .doWrite(List.of(row));
    var file = new MockMultipartFile(
        "file",
        "新员工导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.toByteArray());

    var result = controller.employees(file);

    assertThat(result.data().imported()).isEqualTo(1);
    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(contains("insert into employee"), arguments.capture());
    assertThat(arguments.getValue()).endsWith("INACTIVE");
  }

  private List<String> headers(org.apache.poi.ss.usermodel.Row row) {
    return HEADERS.stream()
        .map(value -> row.getCell(HEADERS.indexOf(value)).getStringCellValue())
        .toList();
  }
}
