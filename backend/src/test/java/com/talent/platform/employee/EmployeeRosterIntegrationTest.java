package com.talent.platform.employee;

import com.alibaba.excel.EasyExcel;
import com.talent.platform.common.BusinessException;
import com.talent.platform.exam.ExamController;
import com.talent.platform.history.HistoryImportController;
import com.talent.platform.importer.ImportController;
import com.talent.platform.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "ROLE_TEST_DB_URL", matches = ".+")
class EmployeeRosterIntegrationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("ROLE_TEST_DB_URL"));
    properties.add("app.bootstrap.demo-users-enabled", () -> false);
  }

  @Autowired JdbcTemplate db;
  @Autowired ExamController exams;
  @Autowired HistoryImportController history;
  @Autowired ImportController imports;

  @AfterEach
  void clearLogin() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void databaseRejectsSameEmployeeNumberWithDifferentName() {
    String number = UUID.randomUUID().toString();
    db.update("insert into employee(employee_no,name) values(?,'甲')", number);
    assertThatThrownBy(() -> db.update("insert into employee(employee_no,name) values(?,'乙')", number))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void attendanceChecksProvidedNameAndAcceptsOldTemplate() throws Exception {
    String tag = UUID.randomUUID().toString();
    long operator = loginAdmin(tag);
    long employee = insert("insert into employee(employee_no,name) values(?,?)", tag, "员工甲");
    long course = insert("insert into course(name,created_by) values(?,?)", tag, operator);
    long session = insert("""
        insert into course_session(course_id,title,starts_at,ends_at,checkin_starts_at,checkin_ends_at,checkin_code,created_by)
        values(?,?,now(),now(),now(),now(),?,?)
        """, course, tag, tag.substring(0, 12), operator);
    var wrong = imports.attendance(workbook("签到记录", List.of("场次ID", "员工工号", "备注", "员工姓名"),
        List.of(List.of(session, tag, "补录", "员工乙")))).data();
    assertThat(wrong.imported()).isZero();
    assertThat(wrong.errors()).singleElement().satisfies(error -> assertThat(error.field()).isEqualTo("员工姓名"));
    assertThat(db.queryForObject("select count(*) from attendance where session_id=?", Integer.class, session)).isZero();

    var old = imports.attendance(workbook("签到记录", List.of("场次ID", "员工工号", "备注"),
        List.of(List.of(session, tag, "补录")))).data();
    assertThat(old.errors()).isEmpty();
    assertThat(old.imported()).isOne();
    assertThat(db.queryForObject("select employee_id from attendance where session_id=?", Long.class, session))
        .isEqualTo(employee);
  }

  @Test
  void historyExamRejectsMissingOrMismatchedNamesAndRevalidatesBeforePublishing() throws Exception {
    String tag = UUID.randomUUID().toString();
    loginAdmin(tag);
    long employee = insert("insert into employee(employee_no,name) values(?,?)", tag, "员工甲");
    String secondNo = tag + "-second";
    insert("insert into employee(employee_no,name) values(?,?)", secondNo, "员工乙");
    var batch = history.create("EXAM", examMetadata(), workbook("历史成绩", List.of("工号", "姓名", "成绩"),
        List.of(List.of(tag, "姓名有误", "91"), List.of(secondNo, "", "70")))).data();
    assertThat(batch.errorCount()).isEqualTo(2);
    assertThat(batch.warningCount()).isZero();
    var rows = db.queryForList("select id,employee_id,error_message from history_import_row where batch_id=? order by id",
        batch.batchId());
    assertThat(rows).extracting(row -> row.get("employee_id"), row -> row.get("error_message"))
        .containsExactly(tuple(null, "姓名与员工档案不一致"), tuple(null, "姓名不能为空"));
    history.updateRow(batch.batchId(), ((Number) rows.get(0).get("id")).longValue(),
        Map.of("employeeNo", tag, "employeeName", "员工甲", "score", "91"));
    history.updateRow(batch.batchId(), ((Number) rows.get(1).get("id")).longValue(),
        Map.of("employeeNo", secondNo, "employeeName", "员工乙", "score", "70"));
    history.submit(batch.batchId());

    db.update("update employee set name=? where id=?", "更正后姓名", employee);
    assertThatThrownBy(() -> history.publish(batch.batchId())).isInstanceOf(BusinessException.class)
        .hasMessageContaining("未修复的导入错误");
    assertThat(db.queryForObject("select count(*) from legacy_exam where batch_id=?", Integer.class, batch.batchId())).isZero();
  }

  @Test
  void historyExamPublishesValidatedIdentityAndNormalizesBlankScores() throws Exception {
    String tag = UUID.randomUUID().toString();
    loginAdmin(tag);
    long employee = insert("insert into employee(employee_no,name) values(?,?)", tag, "员工甲");
    long absent = insert("insert into employee(employee_no,name) values(?,?)", tag + "-absent", "员工乙");
    var batch = history.create("EXAM", examMetadata(), workbook("历史成绩", List.of("工号", "姓名", "成绩"),
        List.of(List.of(tag, "员工甲", "92.50")))).data();
    history.addRow(batch.batchId(), new HistoryImportController.RowInput("EXAM_RESULT",
        Map.of("employeeNo", tag + "-absent", "employeeName", "员工乙")));
    history.publish(batch.batchId());

    assertThat(db.queryForList("select * from legacy_exam_result where batch_id=? order by employee_id", batch.batchId()))
        .extracting(row -> row.get("employee_id"), row -> row.get("employee_name_snapshot"), row -> row.get("result_status"))
        .containsExactly(tuple(employee, "员工甲", "COMPLETED"), tuple(absent, "员工乙", "ABSENT"));
  }

  @Test
  void mentorExamResultsUseDatesFromEachSourceAndRespectScope() {
    String tag = UUID.randomUUID().toString();
    long mentor = insert("""
        insert into sys_user(username,display_name,password_hash,role) values(?,?,'test','MENTOR')
        """, tag, tag);
    long apprentice = insert("insert into employee(employee_no,name,skill_mentor_user_id) values(?,?,?)",
        tag, "同名学徒", mentor);
    long other = insert("insert into employee(employee_no,name) values(?,?)", tag + "-other", "同名学徒");
    long paper = insert("insert into exam_paper(name,created_by) values(?,?)", tag, mentor);
    long completed = plan(paper, mentor, "已完成考试");
    long absent = plan(paper, mentor, "缺考考试");
    db.update("""
        insert into exam_assignment(plan_id,employee_id,assigned_by,window_starts_at,window_ends_at,score_month)
        values(?,?,?,'2020-01-04','2020-01-05','2020-02-01'),
              (?,?,?,'2020-01-04','2020-01-05','2020-02-01')
        """, absent, apprentice, mentor, absent, other, mentor);
    db.update("""
        insert into exam_attempt(plan_id,employee_id,attempt_no,status,started_at,deadline_at,submitted_at,published)
        values(?,?,1,'GRADED','2020-01-02 23:50:00','2020-01-03 00:30:00','2020-01-03 00:10:00',true)
        """, completed, apprentice);
    long batch = insert("""
        insert into history_import_batch(import_type,status,original_filename,file_sha256,created_by)
        values('EXAM','PUBLISHED','test.xlsx',repeat('0',64),?)
        """, mentor);
    long historical = insert("""
        insert into legacy_exam(batch_id,external_exam_key,name,exam_date,score_month,max_score)
        values(?,?,'历史考试','2019-12-20','2020-01-01',100)
        """, batch, tag);
    db.update("""
        insert into legacy_exam_result(exam_id,batch_id,employee_id,employee_no_snapshot,employee_name_snapshot,result_status,score)
        values(?,?,?,?,?,'COMPLETED',90)
        """, historical, batch, apprentice, tag, "同名学徒");
    var user = new CurrentUser(mentor, tag, tag, "MENTOR", false, 0, Set.of(), "MENTORED");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));

    assertThat(exams.results(null).data())
        .extracting(row -> row.get("employee_no"), row -> row.get("exam_name"), row -> row.get("exam_date"))
        .containsExactlyInAnyOrder(
            tuple(tag, "已完成考试", Date.valueOf("2020-01-02")),
            tuple(tag, "缺考考试", Date.valueOf("2020-01-04")),
            tuple(tag, "历史考试", Date.valueOf("2019-12-20")));
  }

  private long plan(long paper, long mentor, String name) {
    return insert("""
        insert into exam_plan(paper_id,name,starts_at,ends_at,duration_minutes,score_month,status,created_by)
        values(?,?,'2020-01-01','2020-01-03',40,'2020-01-01','PUBLISHED',?)
        """, paper, name, mentor);
  }

  private long loginAdmin(String tag) {
    long id = insert("insert into sys_user(username,display_name,password_hash,role) values(?,?,'test','ADMIN')", tag, tag);
    var user = new CurrentUser(id, tag, tag, "ADMIN", false, 0, Set.of("attendance:manage", "history:import"), "ALL");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    return id;
  }

  private String examMetadata() {
    return """
        {"examName":"历史成绩回归","examDate":"2020-01-20"}
        """;
  }

  private MockMultipartFile workbook(String sheet, List<String> headers, List<List<Object>> rows) {
    var output = new ByteArrayOutputStream();
    EasyExcel.write(output).head(headers.stream().map(List::of).toList()).sheet(sheet).doWrite(rows);
    return new MockMultipartFile("file", "test.xlsx", "application/octet-stream", output.toByteArray());
  }

  private long insert(String sql, Object... args) {
    db.update(sql, args);
    return db.queryForObject("select last_insert_id()", Long.class);
  }
}
