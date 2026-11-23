package com.talent.platform.importer;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
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
    public record CreatedAccount(String employeeNo, String name, String username) {}
    public record EmployeeImportResult(
            int imported, int inserted, int updated, int unchanged, List<RowError> errors,
            List<CreatedAccount> createdAccounts) {}
    private record EmployeeSheetRow(int line, EmployeeImportRow data) {}

    @GetMapping("/employees/template")
    public void template(HttpServletResponse response) throws Exception {
        permissions.require(Permissions.EMPLOYEE_WRITE);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode("人员批量导入模板.xlsx", StandardCharsets.UTF_8));
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
        sample.setMentorPhone("13800000001");
        sample.setSkillMentor(firstEnabledMentor());
        sample.setSkillMentorPhone("13800000002");
        sample.setTrainingCycleName(firstEnabledDictionaryLabel("TRAINING_CYCLE"));
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
            var employeeSheet = EasyExcel.writerSheet(0, "人员导入")
                    .head(EmployeeImportRow.class)
                    .registerWriteHandler(new EmployeeExcelSheetHandler(27))
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
    public ApiResponse<EmployeeImportResult> employees(@RequestParam MultipartFile file) throws Exception {
        permissions.require(Permissions.EMPLOYEE_WRITE);
        var errors = new ArrayList<RowError>();
        var rows = new ArrayList<EmployeeSheetRow>();
        var headers = new HashSet<String>();
        EasyExcel.read(file.getInputStream(), EmployeeImportRow.class,
                new AnalysisEventListener<EmployeeImportRow>() {
                    @Override
                    public void invokeHeadMap(Map<Integer, String> headerMap, AnalysisContext context) {
                        headerMap.values().stream().map(ImportController.this::text).forEach(headers::add);
                    }

                    @Override
                    public void invoke(EmployeeImportRow row, AnalysisContext context) {
                        rows.add(new EmployeeSheetRow(context.readRowHolder().getRowIndex() + 1, row));
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {}
                }).sheet().doRead();
        if (rows.isEmpty()) errors.add(new RowError(2, "人员数据", "未读取到人员数据"));

        var seen = new HashSet<String>();
        var existingRows = new ArrayList<Map<String, Object>>();

        for (var row : rows) {
            var r = row.data();
            int line = row.line();
            String employeeNo = text(r.getEmployeeNo());
            String name = text(r.getName());
            r.setEmployeeNo(employeeNo);
            r.setName(name);
            if (employeeNo == null) errors.add(new RowError(line, "工号", "不能为空"));
            if (name == null) errors.add(new RowError(line, "姓名", "不能为空"));

            Map<String, Object> existing = null;
            if (employeeNo != null) {
                if (!seen.add(employeeNo.toLowerCase(Locale.ROOT)))
                    errors.add(new RowError(line, "工号", "导入文件中重复，每个工号只能填写一行"));
                var found = db.queryForList(
                        "select * from employee where employee_no=?", employeeNo);
                if (!found.isEmpty()) {
                    existing = found.get(0);
                    if (name != null && !name.equals(text((String) existing.get("name"))))
                        errors.add(new RowError(line, "姓名", "与该工号的人员档案不一致，请核对工号和姓名"));
                }
            }
            existingRows.add(existing);

            if (text(r.getGender()) != null && normalizeGender(r.getGender()) == null)
                errors.add(new RowError(line, "性别", "仅支持“男”或“女”"));
            if (existing == null && text(r.getIdCard()) == null)
                errors.add(new RowError(line, "身份证号码", "新增人员不能为空；用于生成首次登录密码"));
            else if (text(r.getIdCard()) != null && !EmployeeInitialPassword.supports(r.getIdCard().trim()))
                errors.add(new RowError(line, "身份证号码", "必须为18位，末位仅支持数字或X/x"));
            if (existing == null && text(r.getBatch()) == null)
                errors.add(new RowError(line, "批次", "新增人员不能为空"));
            else if (text(r.getBatch()) != null && id("talent_batch", r.getBatch()) == null)
                errors.add(new RowError(line, "批次", "不存在"));
            if (text(r.getClassName()) != null && dictionaryItemId("CLASS", r.getClassName()) == null)
                errors.add(new RowError(line, "班级", "不存在或已停用"));
            if (text(r.getClassPositionName()) != null
                    && dictionaryItemId("CLASS_POSITION", r.getClassPositionName()) == null)
                errors.add(new RowError(line, "班级职务", "不存在或已停用"));
            if (text(r.getTrainingCycleName()) != null
                    && dictionaryItemId("TRAINING_CYCLE", r.getTrainingCycleName()) == null)
                errors.add(new RowError(line, "培养周期", "不存在或已停用"));
            if (text(r.getBusinessUnit()) != null && id("business_unit", r.getBusinessUnit()) == null)
                errors.add(new RowError(line, "所属板块", "不存在"));
            if (text(r.getStation()) != null && id("service_station", r.getStation()) == null)
                errors.add(new RowError(line, "服务站", "不存在"));
            validateMentor(r.getTechnicalMentor(), line, "指导老师（技术）", errors);
            validateMentor(r.getSkillMentor(), line, "指导老师（技能）", errors);
            tryDate(r.getBirthDate(), line, "出生日期", errors);
            tryDate(r.getOnboardDate(), line, "入职日期", errors);
            if (text(r.getStatus()) != null && normalizeStatus(r.getStatus()) == null)
                errors.add(new RowError(line, "状态", "仅支持“在职”或“停用”"));
        }

        if (!errors.isEmpty())
            return ApiResponse.ok(new EmployeeImportResult(0, 0, 0, 0, errors, List.of()));

        var createdAccounts = new ArrayList<CreatedAccount>();
        int updated = 0;
        int unchanged = 0;
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i).data();
            var values = employeeValues(r, headers);
            var existing = existingRows.get(i);
            if (existing == null) {
                values.putIfAbsent("status", "ACTIVE");
                createdAccounts.add(insertEmployee(r, values));
                continue;
            }

            var changed = new LinkedHashMap<String, Object>();
            for (var entry : values.entrySet()) {
                if (!sameValue(entry.getValue(), existing.get(entry.getKey())))
                    changed.put(entry.getKey(), entry.getValue());
            }
            if (changed.isEmpty()) {
                unchanged++;
                continue;
            }

            var args = new ArrayList<>(changed.values());
            args.add(existing.get("id"));
            args.add(existing.get("version"));
            String assignments = changed.keySet().stream()
                    .map(column -> column + "=?")
                    .reduce((a, b) -> a + "," + b).orElseThrow();
            int affected = db.update("update employee set " + assignments
                    + ",version=version+1 where id=? and version=?", args.toArray());
            if (affected == 0)
                throw new BusinessException(409, "工号 " + r.getEmployeeNo() + " 的人员档案已变化，请重新核对后导入");
            if (changed.containsKey("status") && existing.get("user_id") instanceof Number userId) {
                syncEmployeeAccount(userId.longValue(), "ACTIVE".equals(changed.get("status")));
            }
            updated++;
        }

        int inserted = createdAccounts.size();
        audit.log("IMPORT_EMPLOYEES", "EMPLOYEE", null, null,
                Map.of("inserted", inserted, "updated", updated, "unchanged", unchanged));
        return ApiResponse.ok(new EmployeeImportResult(
                inserted + updated, inserted, updated, unchanged, List.of(), createdAccounts));
    }

    private CreatedAccount insertEmployee(EmployeeImportRow r, Map<String, Object> values) {
        String status = (String) values.get("status");
        String initialPassword = EmployeeInitialPassword.fromIdCard(r.getIdCard().trim());
        String username = availableUsername(r.getEmployeeNo());
        db.update("""
            insert into sys_user(username, password_hash, display_name, role, enabled, must_change_password)
            values(?,?,?,'EMPLOYEE',?,true)
            """, username, encoder.encode(initialPassword), r.getName(),
                "ACTIVE".equals(status));
        Long uid = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert ignore into sys_user_role(user_id,role_code,is_primary,assigned_by) values(?,'EMPLOYEE',true,?)",
                uid, uid);

        var args = new ArrayList<>();
        args.add(uid);
        args.add(r.getEmployeeNo());
        args.add(r.getName());
        args.addAll(values.values());
        String marks = String.join(",", Collections.nCopies(values.size(), "?"));
        db.update("insert into employee(user_id,employee_no,name,"
                + String.join(",", values.keySet()) + ") values(?,?,?," + marks + ")",
                args.toArray());
        return new CreatedAccount(r.getEmployeeNo(), r.getName(), username);
    }

    private Map<String, Object> employeeValues(EmployeeImportRow r, Set<String> headers) {
        var values = new LinkedHashMap<String, Object>();
        if (headers.contains("性别")) values.put("gender", normalizeGender(r.getGender()));
        if (headers.contains("批次")) values.put("batch_id", id("talent_batch", r.getBatch()));
        if (headers.contains("班级")) values.put("class_id", dictionaryItemId("CLASS", r.getClassName()));
        if (headers.contains("班级职务"))
            values.put("class_position_id", dictionaryItemId("CLASS_POSITION", r.getClassPositionName()));
        if (headers.contains("所属板块")) values.put("business_unit_id", id("business_unit", r.getBusinessUnit()));
        if (headers.contains("服务站点")) values.put("station_id", id("service_station", r.getStation()));
        if (headers.contains("指导老师（技术）")) values.put("mentor_user_id", mentorId(r.getTechnicalMentor()));
        if (headers.contains("技术老师联系方式")) values.put("mentor_phone", text(r.getMentorPhone()));
        if (headers.contains("指导老师（技能）")) values.put("skill_mentor_user_id", mentorId(r.getSkillMentor()));
        if (headers.contains("技能老师联系方式")) values.put("skill_mentor_phone", text(r.getSkillMentorPhone()));
        if (headers.contains("培养周期"))
            values.put("training_cycle_id", dictionaryItemId("TRAINING_CYCLE", r.getTrainingCycleName()));
        if (headers.contains("毕业学校")) values.put("school", text(r.getSchool()));
        if (headers.contains("所学专业")) values.put("major", text(r.getMajor()));
        if (headers.contains("学历")) values.put("education", text(r.getEducation()));
        if (headers.contains("出生日期")) values.put("birth_date", date(r.getBirthDate()));
        if (headers.contains("籍贯")) values.put("native_place", text(r.getNativePlace()));
        if (headers.contains("政治面貌")) values.put("political_status", text(r.getPoliticalStatus()));
        if (headers.contains("住址（公司）")) values.put("residence", text(r.getResidence()));
        if (headers.contains("兴趣爱好")) values.put("hobbies", text(r.getHobbies()));
        if (headers.contains("特长")) values.put("speciality", text(r.getSpeciality()));
        if (headers.contains("私人邮箱")) values.put("email", text(r.getEmail()));
        if (headers.contains("身份证号码")) values.put("id_card", text(r.getIdCard()));
        if (headers.contains("联系方式")) values.put("phone", text(r.getPhone()));
        if (headers.contains("入职日期")) values.put("onboard_date", date(r.getOnboardDate()));
        if (text(r.getStatus()) != null) values.put("status", normalizeStatus(r.getStatus()));
        return values;
    }

    private boolean sameValue(Object desired, Object actual) {
        if (desired == null || actual == null) return desired == actual;
        if (desired instanceof Number left && actual instanceof Number right)
            return left.longValue() == right.longValue();
        if (desired instanceof LocalDate left) {
            if (actual instanceof java.sql.Date right) return left.equals(right.toLocalDate());
            if (actual instanceof LocalDate right) return left.equals(right);
        }
        return Objects.equals(desired, actual);
    }

    private void syncEmployeeAccount(long userId, boolean enabled) {
        db.update("""
            update sys_user u
            set enabled=?,security_version=security_version+1
            where u.id=?
              and ((u.role='EMPLOYEE' and not exists(select 1 from sys_user_role r where r.user_id=u.id))
                   or (exists(select 1 from sys_user_role r where r.user_id=u.id and r.role_code='EMPLOYEE')
                       and (select count(*) from sys_user_role r where r.user_id=u.id)=1))
            """, enabled, userId);
    }

    private String availableUsername(String employeeNo) {
        String base = employeeNo.trim();
        if (db.queryForList(
                "select username from sys_user where username=?", String.class, base).isEmpty()) {
            return base;
        }
        while (true) {
            String candidate = base.substring(0, Math.min(base.length(), 55)) + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
            if (db.queryForList(
                    "select username from sys_user where username=?", String.class, candidate).isEmpty()) {
                return candidate;
            }
        }
    }

    private String text(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
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
        sample.setEmployeeName("张三");
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
                        "select id,name from employee where employee_no=? for share", row.getEmployeeNo().trim());
                if (found.isEmpty()) errors.add(new RowError(line, "员工工号", "不存在"));
                else if (found.size() != 1) errors.add(new RowError(line, "员工工号", "对应多名员工，无法确定归属"));
                else {
                    var employee = found.get(0);
                    String name = row.getEmployeeName();
                    if (name != null && !name.isBlank() && !name.trim().equals(((String) employee.get("name")).trim())) {
                        errors.add(new RowError(line, "员工姓名", "与员工台账不一致"));
                    } else {
                        employeeId = ((Number) employee.get("id")).longValue();
                    }
                }
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
                where enabled=true
                  and exists(select 1 from sys_user_role r where r.user_id=sys_user.id and r.role_code='MENTOR')
                order by id limit 1
                """, String.class);
        return usernames.isEmpty() ? null : usernames.get(0);
    }

    private List<EmployeeImportInstructionRow> employeeImportInstructions() {
        return List.of(
                instruction("序号", "否", "仅用于阅读排序，不参与系统校验。", "1"),
                instruction("工号", "是", "工号全系统唯一；同一文件中每个工号只能填写一行。按文本填写以保留前导零。", "20260001"),
                instruction("姓名", "是", "工号和姓名都匹配才更新；新工号新增；已有工号的姓名不一致时整批拒绝。姓名更正请编辑档案。旧模板缺少的列保留原值。", "张三"),
                instruction("性别", "否", "仅填写“男”或“女”；该选项固定，不在字典值管理中维护。", "男"),
                instruction("批次", "新增必填", "必须与系统中已启用的培养批次名称完全一致；更新时留空会清空批次。", "2026届"),
                instruction("班级", "否", "必须与字典管理中已启用的班级名称完全一致；留空表示暂不分配班级。", "2026届1班"),
                instruction("班级职务", "否", "必须与字典管理中已启用的班级职务名称完全一致；留空表示暂无班级职务。", "班长"),
                instruction("所属板块", "否", "必须与系统中已启用的所属板块名称完全一致。", "机动车"),
                instruction("服务站点", "否", "必须与系统中已启用的服务站点名称完全一致。", "示例服务站"),
                instruction("指导老师（技术）", "否", "填写已启用导师的用户名；也可填写不重名的导师姓名。", "mentor"),
                instruction("技术老师联系方式", "否", "填写技术指导老师的联系电话。", "13800000001"),
                instruction("指导老师（技能）", "否", "填写已启用导师的用户名；也可填写不重名的导师姓名。", "mentor2"),
                instruction("技能老师联系方式", "否", "填写技能指导老师的联系电话。", "13800000002"),
                instruction("培养周期", "否", "必须与字典值管理中已启用的培养周期完全一致，如3个月、6个月、1年。", "3个月"),
                instruction("身份证号码", "新增必填", "必须为18位并按文本填写；新增人员初始密码为后六位（X/x登录时统一输入大写X），更新不会重置密码。", "11010120020101000X"),
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
                instruction("状态", "否", "仅填写“在职”或“停用”；留空时新增默认为在职，更新保留原状态。", "在职"));
    }

    private EmployeeImportInstructionRow instruction(
            String field, String required, String rule, String example) {
        return new EmployeeImportInstructionRow(field, required, rule, example);
    }

    private String normalizeStatus(String value) {
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
            where enabled=true and username=?
              and exists(select 1 from sys_user_role r where r.user_id=sys_user.id and r.role_code='MENTOR')
            """, Long.class, normalized);
        if (usernames.size() == 1) return usernames.get(0);
        var matches = db.queryForList("""
            select id from sys_user
            where enabled=true and display_name=?
              and exists(select 1 from sys_user_role r where r.user_id=sys_user.id and r.role_code='MENTOR')
            """, Long.class, normalized);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void tryDate(String value, int row, String field, List<RowError> errors) {
        if (text(value) == null) return;
        try {
            LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            errors.add(new RowError(row, field, "格式应为 yyyy-MM-dd"));
        }
    }

    private LocalDate date(String value) {
        return text(value) == null ? null : LocalDate.parse(value.trim());
    }
}
