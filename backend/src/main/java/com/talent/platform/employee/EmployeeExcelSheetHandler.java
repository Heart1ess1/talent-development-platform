package com.talent.platform.employee;

import com.alibaba.excel.write.handler.SheetWriteHandler;
import com.alibaba.excel.write.metadata.holder.WriteSheetHolder;
import com.alibaba.excel.write.metadata.holder.WriteWorkbookHolder;
import org.apache.poi.ss.util.CellRangeAddress;

public class EmployeeExcelSheetHandler implements SheetWriteHandler {
  private final int lastColumnIndex;

  public EmployeeExcelSheetHandler(int lastColumnIndex) {
    this.lastColumnIndex = lastColumnIndex;
  }

  @Override
  public void afterSheetCreate(
      WriteWorkbookHolder writeWorkbookHolder,
      WriteSheetHolder writeSheetHolder) {
    var sheet = writeSheetHolder.getSheet();
    sheet.createFreezePane(0, 1);
    sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, lastColumnIndex));
  }
}
