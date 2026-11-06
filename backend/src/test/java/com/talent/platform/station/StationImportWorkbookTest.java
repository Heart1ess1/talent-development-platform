package com.talent.platform.station;

import com.talent.platform.common.BusinessException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StationImportWorkbookTest {
  static MockMultipartFile file(byte[] bytes){return new MockMultipartFile("file","负责人.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes);}
  static byte[] workbook(String... values)throws Exception{
    try(var book=new XSSFWorkbook()){
      var sheet=book.createSheet("负责人配置");var header=sheet.createRow(0);
      for(int c=0;c<5;c++)header.createCell(c).setCellValue(StationImportWorkbook.HEADERS.get(c));
      var row=sheet.createRow(1);for(int c=0;c<values.length;c++)row.createCell(c,CellType.STRING).setCellValue(values[c]);
      var out=new ByteArrayOutputStream();book.write(out);return out.toByteArray();
    }
  }
  @ParameterizedTest @CsvSource({"20260001,260001","001234,001234","1234,1234","A,A","emp-0001,p-0001"})
  void initialPasswordUsesLastSixOrWholeShortNumber(String no,String expected){assertThat(StationResponsibilityImportService.initialPassword(no)).isEqualTo(expected);}

  @Test void templateHasOnlyFiveDataColumnsAndInstructionsOutsideData()throws Exception{
    byte[] bytes=StationImportWorkbook.template(List.of("服务站甲","服务站乙"));
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
      assertThat(book.getNumberOfSheets()).isEqualTo(2);var sheet=book.getSheetAt(0);
      assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(5);
      assertThat(sheet.getColumnStyle(2).getDataFormatString()).isEqualTo("@");
      assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("服务站甲");
    }
    assertThat(StationImportWorkbook.read(file(bytes))).hasSize(2).allMatch(r->r.stationNo().isEmpty());
  }
  @Test void preservesLeadingZerosAndTrimsOnlyOuterWhitespace()throws Exception{
    var row=StationImportWorkbook.read(file(workbook(" 服务站甲 "," 张三 "," 001234 ","李四","1234"))).get(0);
    assertThat(row.stationNo()).isEqualTo("001234");assertThat(row.areaNo()).isEqualTo("1234");assertThat(row.row()).isEqualTo(2);
  }
  @Test void rejectsFormulaEvenInInstructionSheet()throws Exception{
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook("甲","张三","001234","","")))){
      book.createSheet("填写说明").createRow(0).createCell(0).setCellFormula("1+1");var out=new ByteArrayOutputStream();book.write(out);
      assertThatThrownBy(()->StationImportWorkbook.read(file(out.toByteArray()))).isInstanceOf(BusinessException.class).hasMessageContaining("公式");
    }
  }
  @Test void rejectsCorruptWrongExtensionOversizeAndAdditionalColumns()throws Exception{
    assertThatThrownBy(()->StationImportWorkbook.read(file(new byte[]{1,2}))).hasMessageContaining("无法读取");
    assertThatThrownBy(()->StationImportWorkbook.read(new MockMultipartFile("file","a.xls",null,new byte[]{1}))).hasMessageContaining("仅支持");
    assertThatThrownBy(()->StationImportWorkbook.read(file(new byte[5*1024*1024+1]))).hasMessageContaining("5MB");
    byte[] extra=workbook("甲","张三","001234","","","额外列");
    assertThatThrownBy(()->StationImportWorkbook.read(file(extra))).hasMessageContaining("额外内容");
  }
  @Test void rejectsWrongHeaderAndOverLimitRows()throws Exception{
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook("甲","张三","001234","","")))){
      book.getSheetAt(0).getRow(0).getCell(0).setCellValue("站点");var out=new ByteArrayOutputStream();book.write(out);
      assertThatThrownBy(()->StationImportWorkbook.read(file(out.toByteArray()))).hasMessageContaining("表头");
      book.getSheetAt(0).getRow(0).getCell(0).setCellValue("服务站");
      for(int i=2;i<=1001;i++)book.getSheetAt(0).createRow(i).createCell(0).setCellValue("甲");out.reset();book.write(out);
      assertThatThrownBy(()->StationImportWorkbook.read(file(out.toByteArray()))).hasMessageContaining("1000");
    }
  }
  @Test void errorExportWritesPotentialFormulaAsTextAndIncludesGlobalErrors()throws Exception{
    var row=new StationImportWorkbook.InputRow(2,"=1+1","张三","001234","","");
    byte[] bytes=StationImportWorkbook.errors(List.of(row),Map.of(0,List.of("全局错误"),2,List.of("=错误说明")));
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
      assertThat(book.getSheetAt(0).getRow(1).getCell(6).getStringCellValue()).contains("全局错误");
      assertThat(book.getSheetAt(0).getRow(2).getCell(0).getCellType()).isEqualTo(CellType.STRING);
      assertThat(book.getSheetAt(0).getRow(2).getCell(2).getStringCellValue()).isEqualTo("001234");
    }
  }
}
