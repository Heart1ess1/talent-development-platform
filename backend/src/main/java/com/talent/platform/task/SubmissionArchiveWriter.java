package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.storage.FileStorageService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Snapshots metadata only. Each physical attachment is opened and closed in turn. */
final class SubmissionArchiveWriter {
  record Attachment(String id, String name, String key, long size) {}
  record Submission(Map<String, Object> row, List<Attachment> files) {}
  record Progress(int processedFiles, int missingFiles, String currentFile) {}

  static List<Submission> snapshot(JdbcTemplate db, List<Map<String, Object>> rows) {
    return rows.stream().map(row -> new Submission(row, db.queryForList(
        "select id,original_name,storage_key,size from stored_file where submission_id=? order by id",
        row.get("id")).stream().map(file -> new Attachment(text(file.get("id")),
            text(file.get("original_name")), text(file.get("storage_key")),
            file.get("size") instanceof Number size ? size.longValue() : 0L)).toList())).toList();
  }

  static void write(OutputStream output, List<Submission> submissions, FileStorageService storage,
                    Consumer<Progress> progress) throws IOException {
    int processed = 0;
    int missing = 0;
    try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      for (var submission : submissions) {
        var row = submission.row();
        String employeeNo = text(row.get("employee_no"));
        String version = row.get("submission_version") instanceof Number number ? number.toString() : "1";
        String folder = safePart(row.get("employee_name"))
            + (employeeNo.isBlank() ? "" : "（" + safePart(employeeNo) + "）") + "/第" + version + "版/";
        if (!text(row.get("content")).isBlank()) putText(zip, folder + "提交说明.txt", text(row.get("content")));
        for (var file : submission.files()) {
          if (Thread.currentThread().isInterrupted()) throw new IOException("Archive interrupted");
          progress.accept(new Progress(processed, missing, file.name()));
          String entry = folder + file.id() + "-" + safePart(file.name());
          // Resolve before opening the ZIP entry so a missing file can be described safely.
          org.springframework.core.io.Resource resource;
          try {
            resource = storage.load(file.key());
          } catch (BusinessException exception) {
            if (exception.getCode() != 404) throw exception;
            putText(zip, entry + ".缺失说明.txt",
                "原附件“" + file.name() + "”的物理文件已不存在，请联系系统管理员核查存储或备份。");
            progress.accept(new Progress(++processed, ++missing, file.name()));
            continue;
          }
          try (var input = resource.getInputStream()) {
            zip.putNextEntry(new ZipEntry(entry));
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
              if (Thread.currentThread().isInterrupted()) throw new IOException("Archive interrupted");
              copied += length;
              if (file.size() > 0 && copied > file.size()) throw new IOException("Attachment size changed");
              zip.write(buffer, 0, length);
            }
            if (file.size() > 0 && copied != file.size()) throw new IOException("Incomplete attachment");
            zip.closeEntry();
          }
          progress.accept(new Progress(++processed, missing, file.name()));
        }
        putText(zip, folder + "人员信息.txt", "姓名：" + text(row.get("employee_name")) + "\n"
            + "工号：" + employeeNo + "\n批次：" + text(row.get("batch_name"))
            + "\n板块：" + text(row.get("business_unit_name")) + "\n班级：" + text(row.get("class_name")) + "\n");
      }
    }
  }

  static String safePart(Object value) {
    String result = text(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").replace("..", "_").trim();
    if (result.isBlank()) return "未命名";
    return result.length() > 80 ? result.substring(0, 80) : result;
  }

  private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

  private static void putText(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
