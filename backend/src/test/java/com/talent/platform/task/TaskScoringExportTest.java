package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskScoringExportTest {
  @Test
  void exportsOnlyDetailRowsAndPreservesIdentityAndZeroScore() throws Exception {
    var service = mock(TaskScoringService.class);
    var controller = new TaskScoringController(service, mock(TaskReviewerScopeService.class));
    var row = new HashMap<String, Object>(Map.of("employee_name", "员工甲", "employee_no", "001234567890",
        "batch_name", "2026届", "business_unit_name", "售后", "class_name", "一班",
        "status", "APPROVED", "final_score", 0));
    var pending = new HashMap<>(row);
    pending.remove("final_score");
    pending.put("status", "NOT_SUBMITTED");
    when(service.taskDetail(1L)).thenReturn(Map.of("title", "月报", "assignments", List.of(row, pending)));
    var response = new MockHttpServletResponse();
    controller.exportScores(1L, response);
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
      var sheet = book.getSheetAt(0);
      assertThat(sheet.getLastRowNum()).isEqualTo(2);
      assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("001234567890");
      assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("2026届");
      assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("售后");
      assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("一班");
      assertThat(sheet.getRow(1).getCell(12).getNumericCellValue()).isZero();
      assertThat(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(sheet.getRow(2).getCell(12))).isEmpty();
    }
    verify(service).taskDetail(1L);
  }

  @Test
  void deniesExportBeforeWritingWorkbook() {
    var service = mock(TaskScoringService.class);
    when(service.taskDetail(2L)).thenThrow(new BusinessException(403, "无权查看"));
    var response = new MockHttpServletResponse();
    assertThatThrownBy(() -> new TaskScoringController(service, mock(TaskReviewerScopeService.class))
        .exportScores(2L, response)).isInstanceOf(BusinessException.class);
    assertThat(response.getContentAsByteArray()).isEmpty();
  }

  @Test
  void allPersonnelExportSchemasContainRequiredIdentityFields() {
    for (var type : List.of(TaskScoreExportRow.class, TaskProgressExportRow.class,
        com.talent.platform.exam.ResultExportRow.class,
        com.talent.platform.employee.EmployeeDirectoryExportRow.class)) {
      var fields = Arrays.stream(type.getDeclaredFields()).map(java.lang.reflect.Field::getName).toList();
      assertThat(fields).contains("employeeNo", "batchName", "businessUnitName", "className");
    }
  }
}
