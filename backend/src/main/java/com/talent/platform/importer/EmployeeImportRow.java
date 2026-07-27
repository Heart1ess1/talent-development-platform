package com.talent.platform.importer;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class EmployeeImportRow {

    @ExcelProperty("序号")
    private Integer serialNo;

    @ExcelProperty("工号")
    private String employeeNo;

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("批次")
    private String batch;

    @ExcelProperty("毕业学校")
    private String school;

    @ExcelProperty("所学专业")
    private String major;

    @ExcelProperty("学历")
    private String education;

    @ExcelProperty("籍贯")
    private String nativePlace;

    @ExcelProperty("政治面貌")
    private String politicalStatus;

    @ExcelProperty("住址（公司）")
    private String residence;

    @ExcelProperty("兴趣爱好")
    private String hobbies;

    @ExcelProperty("特长")
    private String speciality;

    @ExcelProperty("私人邮箱")
    private String email;

    @ExcelProperty("身份证号码")
    private String idCard;

    @ExcelProperty("联系方式")
    private String phone;
}