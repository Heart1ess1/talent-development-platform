package com.talent.platform.importer;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.*;
import com.talent.platform.employee.EmployeeExcelSheetHandler;
import com.talent.platform.security.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final PermissionService permissions;
    private final AuditService audit;

    public ImportController(JdbcTemplate db, PasswordEncoder encoder,
                            PermissionService permissions, AuditService audit) {
        this.db = db;
        this.encoder = encoder;
        this.permissions = permissions;
        this.audit = audit;
    }

    public record RowError(int row, String field, String message) {}
    public record ImportResult(int imported, List<RowError> errors) {}

    @GetMapping("/employees/template")
    public void template(HttpServletResponse response) throws Exception {
        permissions.require(Permissions.EMPLOYEE_WRITE);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode("新员工导入模板.xlsx", StandardCharsets.UTF_8));
        var sample = new EmployeeImportRow();
        sample.setSerialNo(1);
        sample.setEmployeeNo("20260001");
        sample.setName("示例员工（请删除）");
        sample.setGender("男");
        sample.setBatch(firstEnabledName("talent_batch"));
        sample.setClassName(firstEnabledDictionaryLabel("CLASS"));
        sample.setClassPositionName(firstEnabledDictionaryLabel("CLASS_POSITION"));
        sample.setBusinessUnit(firstEnabledName("business_unit"));
        sample.setStation(firstEnabledName("service_station"));
        sample.setTechnicalMentor(firstEnabledMentor());
        sample.setIdCard("11010120020101000X");
        sample.setSchool("示例大学");
        sample.setMajor("示例专业");
        sample.setEducation("本科");
        sample.setBirthDate("2002-01-01");
        sample.setNativePlace("示例省市");
        sample.setPoliticalStatus("群众");
        sample.setResidence("示例公司住址");
        sample.setEmail("example@example.com");
        sample.setPhone("13800000000");
        sample.setOnboardDate("2026-07-01");
        sample.setStatus("在职");
        try (var writer = EasyExcel.write(response.getOutputStream()).build()) {
            var employeeSheet = EasyExcel.writerSheet(0, "新员工导入")
                    .head(EmployeeImportRow.class)
                    .registerWriteHandler(new EmployeeExcelSheetHandler(24))
                    .build();
            writer.write(List.of(sample), employeeSheet);
            var instructionSheet = EasyExcel.writerSheet(1, "填写说明")
                    .head(EmployeeImportInstructionRow.class)
                    .registerWriteHandler(new EmployeeExcelSheetHandler(3))
                    .build();
            writer.write(employeeImportInstructions(), instructionSheet);
        }
    }

    @PostMapping("/employees")
    @Transactional
    public ApiResponse<ImportResult> employees(@RequestParam MultipartFile file) throws Exception {
        permissions.require(Permissions.EMPLOYEE_WRITE);
        List<EmployeeImportRow> rows = EasyExcel.read(file.getInputStream())
                .head(EmployeeImportRow.class)
                .headRowNumber(1)
                .sheet().doReadSync();

        var errors = new ArrayList<RowError>();
        var seen = new HashSet<String>();

        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            int line = i + 2;
            if (r.getEmployeeNo() == null || r.getEmployeeNo().isBlank())
                errors.add(new RowError(line, "工号", "不能为空"));
            else if (!seen.add(r.getEmployeeNo()) ||
                    !db.queryForList("select id from employee where employee_no=?",
                            r.getEmployeeNo()).isEmpty())
                errors.add(new RowError(line, "工号", "重复"));
            if (r.getName() == null || r.getName().isBlank())
                errors.add(new RowError(line, "姓名", "不能为空"));
            if (r.getGender() != null && !r.getGender().isBlank()
                    && normalizeGender(r.getGender()) == null)
                errors.add(new RowError(line, "性别", "仅支持“男”或“女”"));
            if (r.getIdCard() == null || r.getIdCard().isBlank())
                errors.add(new RowError(line, "身份证号码", "不能为空；用于生成首次登录密码"));
            else if (!EmployeeInitialPassword.supports(r.getIdCard()))
                errors.add(new RowError(line, "身份证号码", "必须为18位，末位仅支持数字或X/x"));
            if (r.getBatch() == null || r.getBatch().isBlank())
                errors.add(new RowError(line, "批次", "不能为空"));
            else if (id("talent_batch", r.getBatch()) == null)
                errors.add(new RowError(line, "批次", "不存在"));
            if (r.getClassName() != null && !r.getClassName().isBlank()
                    && dictionaryItemId("CLASS", r.getClassName()) == null)
                errors.add(new RowError(line, "班级", "不存在或已停用"));
            if (r.getClassPositionName() != null && !r.getClassPositionName().isBlank()
                    && dictionaryItemId("CLASS_POSITION", r.getClassPositionName()) == null)
                errors.add(new RowError(line, "班级职务", "不存在或已停用"));
            if (r.getBusinessUnit() != null && !r.getBusinessUnit().isBlank()
                    && id("business_unit", r.getBusinessUnit()) == null)
                errors.add(new RowError(line, "所属板块", "不存在"));
            if (r.getStation() != null && !r.getStation().isBlank()
                    && id("service_station", r.getStation()) == null)
                errors.add(new RowError(line, "服务站", "不存在"));
            validateMentor(r.getTechnicalMentor(), line, "指导老师（技术）", errors);
            validateMentor(r.getSkillMentor(), line, "指导老师（技能）", errors);
            tryDate(r.getBirthDate(), line, "出生日期", errors);
            tryDate(r.getOnboardDate(), line, "入职日期", errors);
            if (normalizeStatus(r.getStatus()) == null)
                errors.add(new RowError(line, "状态", "仅支持“在职”或“停用”"));
        }

        if (!errors.isEmpty())
            return ApiResponse.ok(new ImportResult(0, errors));

        for (var r : rows) {
            String status = normalizeStatus(r.getStatus());
            String initialPassword = EmployeeInitialPassword.fromIdCard(r.getIdCard());
            db.update("""
                insert into sys_user(username, password_hash, display_name, role, enabled, must_change_password)
                values(?,?,?,'EMPLOYEE',?,true)
                """, r.getEmployeeNo(), encoder.encode(initialPassword), r.getName(),
                    "ACTIVE".equals(status));
            Long uid = db.queryForObject("select last_insert_id()", Long.class);

            db.update("""
                insert into employee(
                    user_id, employee_no, name, gender,
                    batch_id, class_id, class_position_id, business_unit_id, station_id,
                    mentor_user_id, skill_mentor_user_id,
                    school, major, education,
                    birth_date, native_place, political_status, residence,
                    hobbies, speciality,
                    email, id_card, phone, onboard_date
                    ,status
                ) values(?,?,?,?, ?,?,?,?,?, ?,?, ?,?,?, ?,?,?,?, ?,?, ?,?,?,?, ?)
                """,
                    uid,
                    r.getEmployeeNo(), r.getName(), normalizeGender(r.getGender()),
                    id("talent_batch", r.getBatch()),
                    dictionaryItemId("CLASS", r.getClassName()),
                    dictionaryItemId("CLASS_POSITION", r.getClassPositionName()),
                    id("business_unit", r.getBusinessUnit()),
                    id("service_station", r.getStation()),
                    mentorId(r.getTechnicalMentor()),
                    mentorId(r.getSkillMentor()),
                    r.getSchool(), r.getMajor(), r.getEducation(),
                    date(r.getBirthDate()), r.getNativePlace(), r.getPoliticalStatus(), r.getResidence(),
                    r.getHobbies(), r.getSpeciality(),
                    r.getEmail(), r.getIdCard(), r.getPhone(), date(r.getOnboardDate()),
                    status);
        }

        audit.log("IMPORT_EMPLOYEES", "EMPLOYEE", null, null, Map.of("count", rows.size()));
        return ApiResponse.ok(new ImportResult(rows.size(), List.of()));
    }

    @GetMapping("/attendance/template")
    public void attendanceTemplate(HttpServletResponse response) throws Exception {
        permissions.require(Permissions.ATTENDANCE_MANAGE);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode("签到导入模板.xlsx", StandardCharsets.UTF_8));
        var sample = new AttendanceImportRow();
        sample.setSessionId(1L);
        sample.setEmployeeNo("20260001");
        sample.setRemark("补录");
        EasyExcel.write(response.getOutputStream(), AttendanceImportRow.class)
                .sheet("签到记录").doWrite(List.of(sample));
    }

    @PostMapping("/attendance")
    @Transactional
    public ApiResponse<ImportResult> attendance(@RequestParam MultipartFile file) throws Exception {
        permissions.require(Permissions.ATTENDANCE_MANAGE);
        List<AttendanceImportRow> rows = EasyExcel.read(file.getInputStream())
                .head(AttendanceImportRow.class).sheet().doReadSync();
        var errors = new ArrayList<RowError>();
        var employeeIds = new ArrayList<Long>();

        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int line = i + 2;
            Long employeeId = null;
            if (row.getSessionId() == null ||
                    db.queryForList("select id from course_session where id=?", row.getSessionId()).isEmpty())
                errors.add(new RowError(line, "场次ID", "不存在"));
            if (row.getEmployeeNo() == null || row.getEmployeeNo().isBlank()) {
                errors.add(new RowError(line, "员工工号", "不能为空"));
            } else {
                var found = db.queryForList(
                        "select id from employee where employee_no=?", Long.class, row.getEmployeeNo());
                if (found.isEmpty()) errors.add(new RowError(line, "员工工号", "不存在"));
                else employeeId = found.get(0);
            }
            employeeIds.add(employeeId);
        }
        if (!errors.isEmpty()) return ApiResponse.ok(new ImportResult(0, errors));

        var user = SecurityUtils.current();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            db.update("""
                insert into attendance(session_id,employee_id,status,source,checked_at,operator_user_id,remark)
                values(?,?,'MANUAL','IMPORT',now(),?,?)
                on duplicate key update status='MANUAL',source='IMPORT',checked_at=now(),
                  operator_user_id=values(operator_user_id),remark=values(remark)
                """, row.getSessionId(), employeeIds.get(i), user.id(), row.getRemark());
        }
        audit.log("IMPORT_ATTENDANCE", "ATTENDANCE", null, null, Map.of("count", rows.size()));
        return ApiResponse.ok(new ImportResult(rows.size(), List.of()));
    }

    private Long id(String table, String name) {
        if (name == null || name.isBlank()) return null;
        var x = db.queryForList(
                "select id from " + table + " where name=? and enabled=true",
                Long.class,
                name.trim());
        return x.isEmpty() ? null : x.get(0);
    }

    private String firstEnabledName(String table) {
        var names = db.queryForList(
                "select name from " + table + " where enabled=true order by id limit 1",
                String.class);
        return names.isEmpty() ? null : names.get(0);
    }

    private Long dictionaryItemId(String typeCode, String label) {
        if (label == null || label.isBlank()) return null;
        var ids = db.queryForList("""
                select id from dictionary_item
                where type_code=? and label=? and enabled=true
                """, Long.class, typeCode, label.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String firstEnabledDictionaryLabel(String typeCode) {
        var labels = db.queryForList("""
                select label from dictionary_item
                where type_code=? and enabled=true
                order by sort_order,id limit 1
                """, String.class, typeCode);
        return labels.isEmpty() ? null : labels.get(0);
    }

    private String firstEnabledMentor() {
        var usernames = db.queryForList("""
                select username from sys_user
                where role='MENTOR' and enabled=true
                order by id limit 1
                """, String.class);
        return usernames.isEmpty() ? null : usernames.get(0);
    }

    private List<EmployeeImportInstructionRow> employeeImportInstructions() {
        return List.of(
                instruction("序号", "否", "仅用于阅读排序，不参与系统校验。", "1"),
                instruction("工号", "是", "必须唯一；将同时作为员工登录用户名。建议按文本填写，避免前导零丢失。", "20260001"),
                instruction("姓名", "是", "填写员工真实姓名。", "张三"),
                instruction("性别", "否", "仅填写“男”或“女”；该选项固定，不在字典值管理中维护。", "男"),
                instruction("批次", "是", "必须与系统中已启用的培养批次名称完全一致。", "2026届"),
                instruction("班级", "否", "必须与字典管理中已启用的班级名称完全一致；留空表示暂不分配班级。", "2026届1班"),
                instruction("班级职务", "否", "必须与字典管理中已启用的班级职务名称完全一致；留空表示暂无班级职务。", "班长"),
                instruction("所属板块", "否", "必须与系统中已启用的所属板块名称完全一致。", "机动车"),
                instruction("服务站点", "否", "必须与系统中已启用的服务站点名称完全一致。", "示例服务站"),
                instruction("指导老师（技术）", "否", "填写已启用导师的用户名；也可填写不重名的导师姓名。", "mentor"),
                instruction("指导老师（技能）", "否", "填写已启用导师的用户名；也可填写不重名的导师姓名。", "mentor2"),
                instruction("身份证号码", "是", "必须为18位并按文本填写；初始密码为后六位，末位X/x登录时统一输入大写X。", "11010120020101000X"),
                instruction("毕业学校", "否", "填写毕业院校全称。", "示例大学"),
                instruction("所学专业", "否", "填写专业名称。", "车辆工程"),
                instruction("学历", "否", "建议使用高中、大专、本科、硕士、博士等统一名称。", "本科"),
                instruction("出生日期", "否", "格式必须为 yyyy-MM-dd。", "2002-01-01"),
                instruction("籍贯", "否", "按实际管理口径填写。", "北京市"),
                instruction("政治面貌", "否", "建议使用群众、共青团员、中共党员等统一名称。", "群众"),
                instruction("住址（公司）", "否", "填写公司住宿或常住联系地址。", "示例公司住址"),
                instruction("兴趣爱好", "否", "多个项目可使用顿号分隔。", "阅读、跑步"),
                instruction("特长", "否", "多个项目可使用顿号分隔。", "沟通、写作"),
                instruction("私人邮箱", "否", "填写员工常用私人邮箱。", "example@example.com"),
                instruction("联系方式", "否", "建议按文本填写，避免号码格式变化。", "13800000000"),
                instruction("入职日期", "否", "格式必须为 yyyy-MM-dd。", "2026-07-01"),
                instruction("状态", "否", "仅填写“在职”或“停用”；留空时默认为“在职”。", "在职"));
    }

    private EmployeeImportInstructionRow instruction(
            String field, String required, String rule, String example) {
        return new EmployeeImportInstructionRow(field, required, rule, example);
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return "ACTIVE";
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "在职", "ACTIVE" -> "ACTIVE";
            case "停用", "INACTIVE" -> "INACTIVE";
            default -> null;
        };
    }

    private String normalizeGender(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim()) {
            case "男" -> "男";
            case "女" -> "女";
            default -> null;
        };
    }

    private void validateMentor(
            String value, int row, String field, List<RowError> errors) {
        if (value == null || value.isBlank()) return;
        if (mentorId(value) == null)
            errors.add(new RowError(row, field, "导师用户名或姓名不存在、重复或未启用"));
    }

    private Long mentorId(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        var usernames = db.queryForList("""
            select id from sys_user
            where role='MENTOR' and enabled=true and username=?
            """, Long.class, normalized);
        if (usernames.size() == 1) return usernames.get(0);
        var matches = db.queryForList("""
            select id from sys_user
            where role='MENTOR' and enabled=true and display_name=?
            """, Long.class, normalized);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void tryDate(String value, int row, String field, List<RowError> errors) {
        if (value == null || value.isBlank()) return;
        try {
            LocalDate.parse(value);
        } catch (Exception ignored) {
            errors.add(new RowError(row, field, "格式应为 yyyy-MM-dd"));
        }
    }

    private LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
