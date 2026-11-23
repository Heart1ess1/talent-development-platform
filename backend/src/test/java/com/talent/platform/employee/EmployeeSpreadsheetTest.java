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
import java.util.ArrayList;
import java.util.HashMap;
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
      "序号", "工号", "姓名", "性别", "批次", "班级", "班级职务", "所属板块", "服务站点",
      "指导老师（技术）", "技术老师联系方式", "指导老师（技能）", "技能老师联系方式", "培养周期",
      "身份证号码", "毕业学校", "所学专业", "学历", "出生日期", "籍贯", "政治面貌", "住址（公司）",
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
        Map.entry("gender", "女"),
        Map.entry("batch_name", "2026届"),
        Map.entry("class_name", "2026届1班"),
        Map.entry("class_position_name", "班长"),
        Map.entry("business_unit_name", "机动车"),
        Map.entry("technical_mentor_name", "技术导师"),
        Map.entry("mentor_phone", "13800000001"),
        Map.entry("skill_mentor_name", "技能导师"),
        Map.entry("skill_mentor_phone", "13800000002"),
        Map.entry("training_cycle_name", "6个月"),
        Map.entry("status", "ACTIVE"),
        Map.entry("birth_date", Date.valueOf(LocalDate.of(2002, 1, 1))),
        Map.entry("onboard_date", Date.valueOf(LocalDate.of(2026, 7, 1))))));
    var controller = new EmployeeDirectoryController(
        db, permissions, mock(AuditService.class));
    var response = new MockHttpServletResponse();

    controller.export(null, null, null, null, null, null, null, null, null, null, response);

    try (var workbook = WorkbookFactory.create(
        new ByteArrayInputStream(response.getContentAsByteArray()))) {
      var sheet = workbook.getSheet("人员台账");
      assertThat(sheet).isNotNull();
      assertThat(headers(sheet.getRow(0))).containsExactlyElementsOf(HEADERS);
      assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1);
      assertThat(sheet.getRow(1).getCell(0).getCellStyle().getDataFormatString())
          .isEqualTo("0");
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("employee");
      assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("女");
      assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("2026届1班");
      assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("班长");
      assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).isEqualTo("13800000001");
      assertThat(sheet.getRow(1).getCell(12).getStringCellValue()).isEqualTo("13800000002");
      assertThat(sheet.getRow(1).getCell(13).getStringCellValue()).isEqualTo("6个月");
      assertThat(sheet.getRow(1).getCell(27).getStringCellValue()).isEqualTo("在职");
      assertThat(sheet.getPaneInformation()).isNotNull();
    }
  }

  @Test
  void importTemplateMatchesExportAndIncludesInstructions() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(anyString(), eq(String.class)))
        .thenReturn(List.of("2026届"));
    when(db.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("2026届1班"), List.of("班长"));
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
      var employeeSheet = workbook.getSheet("人员导入");
      assertThat(headers(employeeSheet.getRow(0))).containsExactlyElementsOf(HEADERS);
      assertThat(employeeSheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("男");
      assertThat(employeeSheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("2026届1班");
      assertThat(employeeSheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("班长");
      assertThat(employeeSheet.getRow(1).getCell(27).getStringCellValue()).isEqualTo("在职");
      assertThat(employeeSheet.getRow(1).getCell(14).getStringCellValue()).endsWith("X");
      assertThat(employeeSheet.getRow(1).getCell(0).getCellStyle().getDataFormatString())
          .isEqualTo("0");
      assertThat(employeeSheet.getPaneInformation()).isNotNull();
      var instructions = workbook.getSheet("填写说明");
      assertThat(instructions).isNotNull();
      assertThat(instructions.getLastRowNum()).isEqualTo(28);
      assertThat(instructions.getRow(1).getCell(0).getStringCellValue()).isEqualTo("序号");
      assertThat(instructions.getRow(4).getCell(0).getStringCellValue()).isEqualTo("性别");
      assertThat(instructions.getRow(6).getCell(0).getStringCellValue()).isEqualTo("班级");
      assertThat(instructions.getRow(7).getCell(0).getStringCellValue()).isEqualTo("班级职务");
      assertThat(instructions.getRow(15).getCell(1).getStringCellValue()).isEqualTo("新增必填");
      assertThat(instructions.getRow(15).getCell(2).getStringCellValue()).contains("大写X");
      assertThat(instructions.getRow(28).getCell(0).getStringCellValue()).isEqualTo("状态");
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
    row.setGender("女");
    row.setBatch("2026届");
    row.setClassName("2026届1班");
    row.setClassPositionName("班长");
    row.setIdCard("11010120020101000X");
    row.setStatus("停用");
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes, EmployeeImportRow.class)
        .sheet("人员导入")
        .doWrite(List.of(row));
    var file = new MockMultipartFile(
        "file",
        "人员导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.toByteArray());

    var result = controller.employees(file);

    assertThat(result.data().imported()).isEqualTo(1);
    verify(encoder).encode("01000X");
    var accountArguments = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(contains("insert into sys_user"), accountArguments.capture());
    assertThat(accountArguments.getValue()).endsWith(false);
    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(contains("insert into employee"), arguments.capture());
    assertThat(arguments.getValue()[3]).isEqualTo("女");
    assertThat(arguments.getValue()[5]).isEqualTo(1L);
    assertThat(arguments.getValue()[6]).isEqualTo(1L);
    assertThat(arguments.getValue()).endsWith("INACTIVE");
  }

  @Test
  void importEnablesActiveAccountAndNormalizesLowercaseXPassword() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("employee_no"), anyString())).thenReturn(List.of());
    when(db.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(8L);
    var encoder = mock(PasswordEncoder.class);
    when(encoder.encode(anyString())).thenReturn("hash");
    var controller = new ImportController(
        db, encoder, mock(PermissionService.class), mock(AuditService.class));
    var row = new EmployeeImportRow();
    row.setEmployeeNo("20260003");
    row.setName("在职员工");
    row.setBatch("2026届");
    row.setIdCard("11010120020101000x");
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes, EmployeeImportRow.class)
        .sheet("人员导入")
        .doWrite(List.of(row));
    var file = new MockMultipartFile(
        "file",
        "人员导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.toByteArray());

    var result = controller.employees(file);

    assertThat(result.data().imported()).isEqualTo(1);
    verify(encoder).encode("01000X");
    var accountArguments = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(contains("insert into sys_user"), accountArguments.capture());
    assertThat(accountArguments.getValue()).endsWith(true);
  }

  @Test
  void importRejectsUnknownOrDisabledClass() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("employee_no"), anyString())).thenReturn(List.of());
    when(db.queryForList(contains("from talent_batch"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(db.queryForList(contains("from dictionary_item"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of());
    var controller = new ImportController(
        db,
        mock(PasswordEncoder.class),
        mock(PermissionService.class),
        mock(AuditService.class));
    var row = new EmployeeImportRow();
    row.setEmployeeNo("20260004");
    row.setName("班级校验员工");
    row.setBatch("2026届");
    row.setClassName("不存在的班级");
    row.setIdCard("110101200201010004");
    row.setStatus("在职");
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes, EmployeeImportRow.class)
        .sheet("人员导入")
        .doWrite(List.of(row));
    var file = new MockMultipartFile(
        "file",
        "人员导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.toByteArray());

    var result = controller.employees(file);

    assertThat(result.data().imported()).isZero();
    assertThat(result.data().errors())
        .anySatisfy(error -> {
          assertThat(error.field()).isEqualTo("班级");
          assertThat(error.message()).contains("不存在或已停用");
        });
  }

  @Test
  void importRejectsInvalidGenderAndUnknownClassPosition() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("employee_no"), anyString())).thenReturn(List.of());
    when(db.queryForList(contains("from talent_batch"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(db.queryForList(contains("from dictionary_item"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of());
    var controller = new ImportController(
        db,
        mock(PasswordEncoder.class),
        mock(PermissionService.class),
        mock(AuditService.class));
    var row = new EmployeeImportRow();
    row.setEmployeeNo("20260006");
    row.setName("固定选项校验员工");
    row.setGender("未知");
    row.setBatch("2026届");
    row.setClassPositionName("不存在的职务");
    row.setIdCard("110101200201010006");
    row.setStatus("在职");
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes, EmployeeImportRow.class)
        .sheet("人员导入")
        .doWrite(List.of(row));
    var file = new MockMultipartFile(
        "file",
        "人员导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.toByteArray());

    var result = controller.employees(file);

    assertThat(result.data().imported()).isZero();
    assertThat(result.data().errors())
        .anySatisfy(error -> {
          assertThat(error.field()).isEqualTo("性别");
          assertThat(error.message()).contains("男").contains("女");
        })
        .anySatisfy(error -> {
          assertThat(error.field()).isEqualTo("班级职务");
          assertThat(error.message()).contains("不存在或已停用");
        });
  }

  @Test
  void importKeepsLegacyTemplateWithoutNewFieldsCompatible() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("employee_no"), anyString())).thenReturn(List.of());
    when(db.queryForList(contains("from talent_batch"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(9L);
    var encoder = mock(PasswordEncoder.class);
    when(encoder.encode(anyString())).thenReturn("hash");
    var controller = new ImportController(
        db, encoder, mock(PermissionService.class), mock(AuditService.class));
    var legacyHeaders = HEADERS.stream()
        .filter(header -> !"性别".equals(header) && !"班级职务".equals(header))
        .map(List::of)
        .toList();
    var values = new ArrayList<Object>();
    for (int i = 0; i < legacyHeaders.size(); i++) values.add("");
    values.set(0, 1);
    values.set(1, "20260005");
    values.set(2, "旧模板员工");
    values.set(3, "2026届");
    values.set(12, "110101200201010005");
    values.set(25, "在职");
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes)
        .head(legacyHeaders)
        .sheet("人员导入")
        .doWrite(List.of(values));
    var file = new MockMultipartFile(
        "file",
        "旧版人员导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.toByteArray());

    var result = controller.employees(file);

    assertThat(result.data().imported()).isEqualTo(1);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(db, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
    assertThat(sql.getAllValues()).filteredOn(statement -> statement.startsWith("insert into employee"))
        .singleElement().asString().doesNotContain("gender", "class_position_id");
  }

  @Test
  void importPreservesMissingColumnsAndClearsPresentBlankFields() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("select * from employee"), any(Object[].class))).thenReturn(List.of(
        Map.of("id", 41L, "version", 3, "name", "旧模板员工", "training_cycle_id", 7L, "phone", "13800000000")));
    when(db.update(contains("update employee set phone=?"), any(Object[].class))).thenReturn(1);
    var controller = new ImportController(db, mock(PasswordEncoder.class),
        mock(PermissionService.class), mock(AuditService.class));
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes).head(List.of(List.of("工号"), List.of("姓名"), List.of("联系方式")))
        .sheet("人员导入").doWrite(List.of(List.of("20260005", "旧模板员工", "")));
    var file = new MockMultipartFile("file", "旧版人员导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());

    var result = controller.employees(file);

    assertThat(result.data().updated()).isEqualTo(1);
    verify(db).update("update employee set phone=?,version=version+1 where id=? and version=?",
        (Object) null, 41L, 3);
  }

  @Test
  void importUpdatesOnlyChangedFieldsAndSkipsIdenticalRows() throws Exception {
    var db = mock(JdbcTemplate.class);
    var changed = new HashMap<String, Object>();
    changed.put("id", 41L);
    changed.put("name", "更新员工");
    changed.put("user_id", 51L);
    changed.put("version", 3);
    changed.put("batch_id", 1L);
    changed.put("id_card", "11010120020101000X");
    changed.put("phone", "13800000000");
    changed.put("status", "INACTIVE");
    var identical = new HashMap<String, Object>();
    identical.put("id", 42L);
    identical.put("name", "未变化员工");
    identical.put("user_id", 52L);
    identical.put("batch_id", 1L);
    identical.put("id_card", "110101200201010004");
    identical.put("phone", "13800000000");
    identical.put("status", "ACTIVE");
    when(db.queryForList(contains("select * from employee"), any(Object[].class)))
        .thenReturn(List.of(changed), List.of(identical));
    when(db.queryForList(contains("from talent_batch"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(db.update(contains("update employee set phone=?"), any(Object[].class))).thenReturn(1);
    var controller = new ImportController(
        db, mock(PasswordEncoder.class), mock(PermissionService.class), mock(AuditService.class));
    var first = new EmployeeImportRow();
    first.setEmployeeNo("20260010");
    first.setName("更新员工");
    first.setBatch("2026届");
    first.setIdCard("11010120020101000X");
    first.setPhone("13900000000");
    var second = new EmployeeImportRow();
    second.setEmployeeNo("20260011");
    second.setName("未变化员工");
    second.setBatch("2026届");
    second.setIdCard("110101200201010004");
    second.setPhone("13800000000");
    second.setStatus("在职");

    var result = controller.employees(employeeFile(first, second));

    assertThat(result.data().inserted()).isZero();
    assertThat(result.data().updated()).isEqualTo(1);
    assertThat(result.data().unchanged()).isEqualTo(1);
    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(contains("update employee set phone=?"), arguments.capture());
    assertThat(arguments.getValue()).containsExactly("13900000000", 41L, 3);
    verify(db, org.mockito.Mockito.never()).update(contains("update sys_user"), any(Object[].class));
  }

  @Test
  void importRejectsRepeatedEmployeeNoForDifferentNames() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("select * from employee"), any(Object[].class)))
        .thenReturn(List.of());
    when(db.queryForList(contains("from talent_batch"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    var controller = new ImportController(
        db, mock(PasswordEncoder.class), mock(PermissionService.class), mock(AuditService.class));
    var first = new EmployeeImportRow();
    first.setEmployeeNo("20260012");
    first.setName("甲员工");
    first.setBatch("2026届");
    first.setIdCard("11010120020101000X");
    var second = new EmployeeImportRow();
    second.setEmployeeNo("20260012");
    second.setName("乙员工");
    second.setBatch("2026届");
    second.setIdCard("110101200201010004");

    var result = controller.employees(employeeFile(first, second));

    assertThat(result.data().imported()).isZero();
    assertThat(result.data().errors()).singleElement().satisfies(error -> {
      assertThat(error.row()).isEqualTo(3);
      assertThat(error.field()).isEqualTo("工号");
      assertThat(error.message()).contains("重复");
    });
    verify(db, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
  }

  @Test
  void importRejectsWholeFileWhenEmployeeNameDoesNotMatch() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("select * from employee"), eq("20260012")))
        .thenReturn(List.of(Map.of("id", 10L, "name", "甲员工")));
    when(db.queryForList(contains("from talent_batch"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    var controller = new ImportController(
        db, mock(PasswordEncoder.class), mock(PermissionService.class), mock(AuditService.class));
    var valid = new EmployeeImportRow();
    valid.setEmployeeNo("20260014");
    valid.setName("新增员工");
    valid.setBatch("2026届");
    valid.setIdCard("110101200201010004");
    var conflict = new EmployeeImportRow();
    conflict.setEmployeeNo("20260012");
    conflict.setName("乙员工");

    var result = controller.employees(employeeFileWithBlankRow(valid, conflict));

    assertThat(result.data().imported()).isZero();
    assertThat(result.data().createdAccounts()).isEmpty();
    assertThat(result.data().errors()).singleElement().satisfies(error -> {
      assertThat(error.row()).isEqualTo(4);
      assertThat(error.field()).isEqualTo("姓名");
      assertThat(error.message()).contains("不一致");
    });
    verify(db, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
  }

  @Test
  void importGeneratesAnotherUsernameWhenEmployeeNoIsOccupied() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("select * from employee"), any(Object[].class)))
        .thenReturn(List.of());
    when(db.queryForList(contains("from talent_batch"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(db.queryForList(contains("select username from sys_user"), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("20260013"), List.of());
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(12L);
    var encoder = mock(PasswordEncoder.class);
    when(encoder.encode(anyString())).thenReturn("hash");
    var controller = new ImportController(
        db, encoder, mock(PermissionService.class), mock(AuditService.class));
    var row = new EmployeeImportRow();
    row.setEmployeeNo("20260013");
    row.setName("同名工号员工");
    row.setBatch("2026届");
    row.setIdCard("11010120020101000X");

    var result = controller.employees(employeeFile(row));

    assertThat(result.data().inserted()).isEqualTo(1);
    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(contains("insert into sys_user"), arguments.capture());
    assertThat(String.valueOf(arguments.getValue()[0]))
        .startsWith("20260013-")
        .isNotEqualTo("20260013");
    assertThat(result.data().createdAccounts()).containsExactly(
        new ImportController.CreatedAccount("20260013", "同名工号员工", (String) arguments.getValue()[0]));
  }

  private MockMultipartFile employeeFile(EmployeeImportRow... rows) {
    var bytes = new ByteArrayOutputStream();
    EasyExcel.write(bytes, EmployeeImportRow.class)
        .sheet("人员导入")
        .doWrite(List.of(rows));
    return new MockMultipartFile(
        "file",
        "人员批量导入模板.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.toByteArray());
  }

  private MockMultipartFile employeeFileWithBlankRow(EmployeeImportRow first, EmployeeImportRow second)
      throws Exception {
    var file = employeeFile(first, second);
    var bytes = new ByteArrayOutputStream();
    try (var workbook = WorkbookFactory.create(file.getInputStream())) {
      workbook.getSheetAt(0).shiftRows(2, 2, 1);
      workbook.write(bytes);
    }
    return new MockMultipartFile("file", file.getOriginalFilename(), file.getContentType(), bytes.toByteArray());
  }

  private List<String> headers(org.apache.poi.ss.usermodel.Row row) {
    return HEADERS.stream()
        .map(value -> row.getCell(HEADERS.indexOf(value)).getStringCellValue())
        .toList();
  }
}
