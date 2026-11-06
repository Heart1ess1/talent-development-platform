package com.talent.platform.station;

import com.talent.platform.common.BusinessException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.util.*;

/** A deliberately small, five-column format. Instruction rows are never imported. */
public final class StationImportWorkbook {
  public static final List<String> HEADERS=List.of("服务站","站长姓名","站长工号","区域经理姓名","区域经理工号");
  public record InputRow(int row,String station,String stationName,String stationNo,String areaName,String areaNo) {
    List<String> cells(){return List.of(station,stationName,stationNo,areaName,areaNo);}
  }
  private StationImportWorkbook(){}

  public static List<InputRow> read(MultipartFile file) {
    if(file==null||file.isEmpty())throw bad("请选择 Excel 文件");
    if(file.getSize()>5*1024*1024)throw bad("单文件不能超过5MB");
    if(file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw bad("仅支持.xlsx文件");
    try(var book=new XSSFWorkbook(file.getInputStream())) {
      if(book.getNumberOfSheets()==0)throw bad("文件缺少负责人配置工作表");
      var sheet=book.getSheetAt(0);
      if(!"负责人配置".equals(sheet.getSheetName()))throw bad("第一个工作表必须命名为“负责人配置”，请下载最新模板");
      var formatter=new DataFormatter(Locale.ROOT);
      int cells=0;
      for(var s:book)for(var r:s)for(var c:r){
        if(++cells>100000)throw bad("文件内容过多，请使用标准模板");
        if(c.getCellType()==CellType.FORMULA)throw bad("不支持公式单元格："+s.getSheetName()+"!"+c.getAddress());
        if(c.getCellType()==CellType.ERROR)throw bad("存在错误单元格："+s.getSheetName()+"!"+c.getAddress());
      }
      var header=sheet.getRow(0);
      for(int col=0;col<5;col++)if(!HEADERS.get(col).equals(value(header,col,formatter)))throw bad("模板表头不正确，应为："+String.join("、",HEADERS));
      for(var cell:header)if(cell.getColumnIndex()>=5&&!formatter.formatCellValue(cell).isBlank())throw bad("模板表头只能包含五列");
      var rows=new ArrayList<InputRow>();
      for(var row:sheet){
        if(row.getRowNum()==0)continue;
        for(var cell:row)if(cell.getColumnIndex()>=5&&!formatter.formatCellValue(cell).isBlank())throw bad("模板只能填写五列，第"+(row.getRowNum()+1)+"行存在额外内容");
        var values=new ArrayList<String>();
        for(int col=0;col<5;col++)values.add(value(row,col,formatter));
        if(values.stream().allMatch(String::isBlank))continue;
        if(rows.size()>=1000)throw bad("最多导入1000条非空数据行");
        for(int col:new int[]{2,4}){
          var cell=row.getCell(col);
          if(cell!=null&&cell.getCellType()==CellType.NUMERIC&&
              (cell.getNumericCellValue()!=Math.rint(cell.getNumericCellValue())||Math.abs(cell.getNumericCellValue())>=1e15))
            throw bad("第"+(row.getRowNum()+1)+"行工号请按文本填写，避免精度丢失");
        }
        rows.add(new InputRow(row.getRowNum()+1,values.get(0),values.get(1),values.get(2),values.get(3),values.get(4)));
      }
      if(rows.isEmpty())throw bad("文件没有可读取的数据行");
      return rows;
    }catch(BusinessException e){throw e;}catch(Exception e){throw bad("无法读取.xlsx文件，请检查文件是否损坏或加密");}
  }

  public static byte[] template(List<String> stations) {
    try(var book=new XSSFWorkbook()){
      var sheet=book.createSheet("负责人配置");
      header(sheet,HEADERS);
      var text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
      sheet.setDefaultColumnStyle(2,text);sheet.setDefaultColumnStyle(4,text);
      for(int i=0;i<stations.size();i++){
        var row=sheet.createRow(i+1);row.createCell(0).setCellValue(stations.get(i));
        for(int col=1;col<5;col++){var c=row.createCell(col,CellType.STRING);c.setCellStyle(text);c.setCellValue("");}
      }
      var notes=book.createSheet("填写说明");header(notes,List.of("事项","说明"));
      var instructions=List.of(
          List.of("填写方式","一行一个服务站；姓名和工号成对填写。工号同时为登录账号，请按文本填写以保留前导零。"),
          List.of("替换规则","填写内容替换该站同级负责人；同组两列都为空保持原配置，未涉及站点保持原配置。"),
          List.of("多人多站","一人负责多个站点可重复填写；一站多人可重复站点行，同级人员合并为完整名单。"),
          List.of("账号核对","已有账号必须姓名一致、角色一致且启用；冲突时先在账号管理中核实处理。"),
          List.of("初始密码","仅新账号：工号后六位，不足六位使用完整工号；首次登录必须修改为8～64位新密码。"),
          List.of("责任交接","替换负责人时一并交接待审批或未销假责任，预览确认前不会创建账号或修改范围。"),
          List.of("限制","仅.xlsx，最多5MB、1000条非空数据行、500组站点及角色；不支持公式。"),
          List.of("示例（不参与导入）","服务站：示例服务站；站长姓名：张三；站长工号：20260001；区域经理姓名：李四；区域经理工号：1234。"));
      for(int i=0;i<instructions.size();i++){var row=notes.createRow(i+1);for(int c=0;c<2;c++)row.createCell(c).setCellValue(instructions.get(i).get(c));}
      notes.setColumnWidth(0,24*256);notes.setColumnWidth(1,110*256);
      return bytes(book);
    }catch(IOException e){throw new IllegalStateException(e);}
  }

  public static byte[] errors(List<InputRow> rows,Map<Integer,List<String>> errors){
    try(var book=new XSSFWorkbook()){
      var headers=new ArrayList<>(HEADERS);headers.add("原始行号");headers.add("错误说明");
      var sheet=book.createSheet("错误明细");header(sheet,headers);int index=1;
      if(errors.containsKey(0)){var row=sheet.createRow(index++);row.createCell(6,CellType.STRING).setCellValue(String.join("；",errors.get(0)));}
      for(var input:rows){if(!errors.containsKey(input.row()))continue;var row=sheet.createRow(index++);var values=new ArrayList<>(input.cells());values.add(String.valueOf(input.row()));values.add(String.join("；",errors.get(input.row())));for(int c=0;c<values.size();c++)row.createCell(c,CellType.STRING).setCellValue(values.get(c));}
      sheet.setColumnWidth(6,100*256);return bytes(book);
    }catch(IOException e){throw new IllegalStateException(e);}
  }
  private static void header(Sheet sheet,List<String> names){var row=sheet.createRow(0);for(int c=0;c<names.size();c++){row.createCell(c).setCellValue(names.get(c));sheet.setColumnWidth(c,(c==0?36:22)*256);}sheet.createFreezePane(0,1);}
  private static String value(Row row,int col,DataFormatter formatter){return row==null?"":formatter.formatCellValue(row.getCell(col)).strip();}
  private static byte[] bytes(Workbook book)throws IOException{var out=new ByteArrayOutputStream();book.write(out);return out.toByteArray();}
  private static BusinessException bad(String message){return new BusinessException(400,message);}
}
