package com.talent.platform.importer;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.*;
import com.talent.platform.security.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        sample.setEmployeeNo("20260001");
        sample.setName("示例员工");
        sample.setBatch("2026届");
        sample.setSchool("示例大学");
        sample.setMajor("示例专业");
        sample.setEducation("本科");
        EasyExcel.write(response.getOutputStream(), EmployeeImportRow.class)
                .sheet("新员工").doWrite(List.of(sample));
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
            if (r.getBatch() == null || r.getBatch().isBlank())
                errors.add(new RowError(line, "批次", "不能为空"));
            if (id("talent_batch", r.getBatch()) == null)
                errors.add(new RowError(line, "批次", "不存在"));
        }

        if (!errors.isEmpty())
            return ApiResponse.ok(new ImportResult(0, errors));

        for (var r : rows) {
            db.update("""
                insert into sys_user(username, password_hash, display_name, role, enabled, must_change_password)
                values(?,?,?,'EMPLOYEE',false,true)
                """, r.getEmployeeNo(), encoder.encode(UUID.randomUUID().toString()), r.getName());
            Long uid = db.queryForObject("select last_insert_id()", Long.class);

            db.update("""
                insert into employee(
                    user_id, employee_no, name,
                    batch_id,
                    school, major, education,
                    native_place, political_status, residence,
                    hobbies, speciality,
                    email, id_card, phone
                ) values(?,?,?, ?, ?,?,?, ?,?,?, ?,?, ?,?,?)
                """,
                    uid,
                    r.getEmployeeNo(), r.getName(),
                    id("talent_batch", r.getBatch()),
                    r.getSchool(), r.getMajor(), r.getEducation(),
                    r.getNativePlace(), r.getPoliticalStatus(), r.getResidence(),
                    r.getHobbies(), r.getSpeciality(),
                    r.getEmail(), r.getIdCard(), r.getPhone());
        }

        audit.log("IMPORT_EMPLOYEES", "EMPLOYEE", null, null, Map.of("count", rows.size()));
        return ApiResponse.ok(new ImportResult(rows.size(), List.of()));
    }

    private Long id(String table, String name) {
        if (name == null || name.isBlank()) return null;
        var x = db.queryForList("select id from " + table + " where name=?", Long.class, name);
        return x.isEmpty() ? null : x.get(0);
    }
}