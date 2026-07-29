package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.SecurityUtils;
import com.talent.platform.storage.FileStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class TaskAttachmentService {
  private static final long MAX_SIZE = 50L * 1024 * 1024;

  private final JdbcTemplate db;
  private final FileStorageService storage;

  public TaskAttachmentService(JdbcTemplate db, FileStorageService storage) {
    this.db = db;
    this.storage = storage;
  }

  public List<Map<String, Object>> listForPlanTask(Long planTaskId) {
    return db.queryForList("""
        select a.id,a.original_name,a.content_type,a.size,a.created_at,u.display_name uploader_name
        from task_attachment a
        join sys_user u on u.id=a.uploaded_by
        where a.training_plan_task_id=?
        order by a.created_at,a.id
        """, planTaskId);
  }

  public List<Map<String, Object>> listForTask(Long taskId) {
    return db.queryForList("""
        select a.id,a.original_name,a.content_type,a.size,a.created_at,u.display_name uploader_name
        from task_attachment a
        join sys_user u on u.id=a.uploaded_by
        where a.challenge_task_id=?
        order by a.created_at,a.id
        """, taskId);
  }

  public Map<String, Object> attachment(Long id) {
    var rows = db.queryForList("select * from task_attachment where id=?", id);
    if (rows.isEmpty()) throw new BusinessException(404, "任务附件不存在");
    return rows.get(0);
  }

  public FileStorageService storage() {
    return storage;
  }

  @Transactional
  public Long uploadForPlanTask(Long planTaskId, MultipartFile file) {
    validate(file);
    return store(file, planTaskId, null);
  }

  @Transactional
  public Long uploadForTask(Long taskId, MultipartFile file) {
    validate(file);
    return store(file, null, taskId);
  }

  @Transactional
  public Map<String, Object> delete(Long attachmentId) {
    var before = attachment(attachmentId);
    db.update("delete from task_attachment where id=?", attachmentId);
    String storageKey = String.valueOf(before.get("storage_key"));
    Integer references = db.queryForObject(
        "select count(*) from task_attachment where storage_key=?", Integer.class, storageKey);
    if (references == null || references == 0) storage.delete(storageKey);
    return before;
  }

  public void snapshotPlanTaskAttachments(Long planTaskId, Long taskId) {
    db.update("""
        insert ignore into task_attachment(
          challenge_task_id,source_attachment_id,original_name,content_type,size,storage_key,uploaded_by
        )
        select ?,a.id,a.original_name,a.content_type,a.size,a.storage_key,a.uploaded_by
        from task_attachment a
        where a.training_plan_task_id=?
        """, taskId, planTaskId);
  }

  public void copyPlanTaskAttachments(Long sourcePlanTaskId, Long targetPlanTaskId) {
    db.update("""
        insert into task_attachment(
          training_plan_task_id,original_name,content_type,size,storage_key,uploaded_by
        )
        select ?,a.original_name,a.content_type,a.size,a.storage_key,a.uploaded_by
        from task_attachment a
        where a.training_plan_task_id=?
        """, targetPlanTaskId, sourcePlanTaskId);
  }

  @Transactional
  public void deleteForPlanTask(Long planTaskId) {
    deleteOwnerRows("training_plan_task_id", planTaskId);
  }

  @Transactional
  public void deleteForTask(Long taskId) {
    deleteOwnerRows("challenge_task_id", taskId);
  }

  private Long store(MultipartFile file, Long planTaskId, Long taskId) {
    var stored = storage.store(file);
    try {
      db.update("""
          insert into task_attachment(
            training_plan_task_id,challenge_task_id,original_name,content_type,size,storage_key,uploaded_by
          ) values(?,?,?,?,?,?,?)
          """,
          planTaskId, taskId, file.getOriginalFilename(), stored.contentType(), stored.size(),
          stored.key(), SecurityUtils.current().id());
      return db.queryForObject("select last_insert_id()", Long.class);
    } catch (RuntimeException exception) {
      storage.delete(stored.key());
      throw exception;
    }
  }

  private void deleteOwnerRows(String ownerColumn, Long ownerId) {
    var rows = db.queryForList(
        "select id,storage_key from task_attachment where " + ownerColumn + "=?", ownerId);
    for (var row : rows) delete(((Number) row.get("id")).longValue());
  }

  private void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new BusinessException(400, "不能上传空文件");
    if (file.getSize() > MAX_SIZE) throw new BusinessException(400, "单个任务附件不能超过 50MB");
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
    if (!name.matches(".*\\.(pdf|doc|docx|xls|xlsx|ppt|pptx|txt|md|png|jpg|jpeg|zip)$")) {
      throw new BusinessException(400, "支持 PDF、Office、图片、文本和 ZIP 格式附件");
    }
  }
}
