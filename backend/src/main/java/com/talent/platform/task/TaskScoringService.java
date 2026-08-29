package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class TaskScoringService {
  private static final Set<String> GLOBAL_ROLES = Set.of("TRAINING_ADMIN", "ADMIN", "SUPER_ADMIN");
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public TaskScoringService(JdbcTemplate db, PermissionService permissions, AuditService audit) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
  }

  public boolean isGlobalViewer() {
    return GLOBAL_ROLES.contains(SecurityUtils.current().role());
  }

  public List<Map<String, Object>> reviewerOptions() {
    permissions.require(Permissions.TASK_MANAGE);
    return db.queryForList("""
        select id,username,display_name,role
        from sys_user
        where enabled=true and role<>'EMPLOYEE'
        order by display_name,username,id
        """);
  }

  public List<Long> normalizeAndValidateReviewers(Collection<Long> requested) {
    var ids = new ArrayList<>(new LinkedHashSet<>(requested == null ? List.of() : requested));
    if (ids.isEmpty()) return ids;
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    Integer count = db.queryForObject(
        "select count(*) from sys_user where enabled=true and role<>'EMPLOYEE' and id in (" + marks + ")",
        Integer.class, ids.toArray());
    if (count == null || count != ids.size()) {
      throw new BusinessException(400, "评分人中包含员工、已停用或不存在的账号");
    }
    return ids;
  }

  public List<Long> reviewerIds(Long taskId) {
    return db.queryForList(
        "select reviewer_user_id from task_reviewer where task_id=? order by reviewer_user_id",
        Long.class, taskId);
  }

  public void requireCompatibleReviewers(Long taskId, Collection<Long> requested) {
    var expected = normalizeAndValidateReviewers(requested);
    var existing = reviewerIds(taskId);
    if (!new LinkedHashSet<>(existing).equals(new LinkedHashSet<>(expected))) {
      throw new BusinessException(409, "复用任务已有不同的评分人配置，请先到任务评分页面调整");
    }
  }

  @Transactional
  public void setReviewers(Long taskId, Collection<Long> requested) {
    permissions.require(Permissions.TASK_MANAGE);
    requireTask(taskId);
    var reviewerIds = normalizeAndValidateReviewers(requested);
    Integer submitted = db.queryForObject("""
        select count(*)
        from task_submission_review r
        join task_submission s on s.id=r.submission_id
        join task_assignment a on a.id=s.assignment_id
        where a.task_id=? and r.status='SUBMITTED'
        """, Integer.class, taskId);
    if (submitted != null && submitted > 0) {
      throw new BusinessException(409, "该任务已经产生评分，不能再调整评分人");
    }
    var before = reviewerIds(taskId);
    db.update("delete from task_reviewer where task_id=?", taskId);
    for (Long reviewerId : reviewerIds) {
      db.update("insert into task_reviewer(task_id,reviewer_user_id,assigned_by) values(?,?,?)",
          taskId, reviewerId, SecurityUtils.current().id());
    }
    synchronizePendingReviews(taskId, reviewerIds);
    audit.log("SET_TASK_REVIEWERS", "TASK", taskId,
        Map.of("reviewerIds", before), Map.of("reviewerIds", reviewerIds));
  }

  private void synchronizePendingReviews(Long taskId, List<Long> reviewerIds) {
    var submissionIds = db.queryForList("""
        select s.id
        from task_submission s
        join task_assignment a on a.id=s.assignment_id
        where a.task_id=? and s.status='PENDING_REVIEW'
          and s.submission_version=(select max(s2.submission_version) from task_submission s2 where s2.assignment_id=s.assignment_id)
        """, Long.class, taskId);
    for (Long submissionId : submissionIds) {
      if (reviewerIds.isEmpty()) {
        db.update("update task_submission_review set status='VOIDED' where submission_id=? and status='PENDING'",
            submissionId);
        continue;
      }
      String marks = String.join(",", Collections.nCopies(reviewerIds.size(), "?"));
      var args = new ArrayList<Object>();
      args.add(submissionId);
      args.addAll(reviewerIds);
      db.update("update task_submission_review set status='VOIDED' where submission_id=? and status='PENDING' and reviewer_user_id not in (" + marks + ")",
          args.toArray());
      for (Long reviewerId : reviewerIds) {
        db.update("""
            insert into task_submission_review(submission_id,reviewer_user_id,status)
            values(?,?,'PENDING')
            on duplicate key update status='PENDING',decision=null,score=null,comment=null,submitted_at=null
            """, submissionId, reviewerId);
      }
    }
  }

  public void initializeSubmissionReviews(Long submissionId, Long taskId) {
    for (Long reviewerId : reviewerIds(taskId)) {
      db.update("insert ignore into task_submission_review(submission_id,reviewer_user_id,status) values(?,?,'PENDING')",
          submissionId, reviewerId);
    }
  }

  public void assertCanResubmit(Long assignmentId) {
    Integer count = db.queryForObject("""
        select count(*)
        from task_submission_review r
        join task_submission s on s.id=r.submission_id
        where s.assignment_id=? and s.status='PENDING_REVIEW' and r.status='SUBMITTED'
        """, Integer.class, assignmentId);
    if (count != null && count > 0) {
      throw new BusinessException(409, "评分已开始，不能主动重提；请等待评分完成或退回");
    }
  }

  public void voidPendingSubmissionReviews(Long assignmentId) {
    db.update("""
        update task_submission_review r
        join task_submission s on s.id=r.submission_id
        set r.status='VOIDED'
        where s.assignment_id=? and s.status='PENDING_REVIEW' and r.status='PENDING'
        """, assignmentId);
  }

  public List<Map<String, Object>> pendingForCurrentUser() {
    permissions.require(Permissions.TASK_SCORE);
    return db.queryForList("""
        select a.id,a.assigned_at,t.id task_id,t.title,t.deadline,e.id employee_id,e.name employee_name,e.employee_no,
               s.id submission_id,s.submission_version,s.submitted_at
        from task_submission_review r
        join task_submission s on s.id=r.submission_id and s.status='PENDING_REVIEW'
        join task_assignment a on a.id=s.assignment_id and a.status='PENDING_REVIEW'
        join challenge_task t on t.id=a.task_id
        join employee e on e.id=a.employee_id
        where r.reviewer_user_id=? and r.status='PENDING'
        order by s.submitted_at,a.id
        """, SecurityUtils.current().id());
  }

  public List<Map<String, Object>> taskList(String status, String keyword) {
    permissions.require(Permissions.TASK_SCORE);
    var user = SecurityUtils.current();
    var args = new ArrayList<Object>();
    String visibility = "";
    if (!isGlobalViewer()) {
      visibility = " where exists(select 1 from task_reviewer mine where mine.task_id=t.id and mine.reviewer_user_id=?)";
      args.add(user.id());
    }
    var rows = db.queryForList("""
        select t.id,t.title,t.description,t.requirements,t.deadline,t.created_at,u.display_name creator_name,
          (select count(*) from task_reviewer tr where tr.task_id=t.id) reviewer_count,
          (select group_concat(su.display_name order by su.display_name separator '、') from task_reviewer tr join sys_user su on su.id=tr.reviewer_user_id where tr.task_id=t.id) reviewer_names,
          (select count(*) from task_reviewer tr where tr.task_id=t.id and tr.reviewer_user_id=?) my_reviewer,
          (select count(*) from task_assignment a where a.task_id=t.id) assignment_count,
          (select count(*) from task_assignment a where a.task_id=t.id and a.status='PENDING_REVIEW') pending_assignment_count,
          (select count(*) from task_assignment a where a.task_id=t.id and a.status='APPROVED') approved_count,
          (select count(*) from task_submission s join task_assignment a on a.id=s.assignment_id where a.task_id=t.id and s.submission_version=(select max(s2.submission_version) from task_submission s2 where s2.assignment_id=s.assignment_id)) submitted_count,
          (select count(*) from task_submission_review r join task_submission s on s.id=r.submission_id join task_assignment a on a.id=s.assignment_id where a.task_id=t.id and r.reviewer_user_id=? and r.status='PENDING' and s.status='PENDING_REVIEW') my_pending_count
        from challenge_task t join sys_user u on u.id=t.created_by
        """ + visibility + " order by t.deadline desc,t.id desc", joinedArgs(user.id(), user.id(), args));
    String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    for (var row : rows) row.put("scoring_status", scoringStatus(row));
    return rows.stream()
        .filter(row -> normalizedKeyword.isBlank()
            || Objects.toString(row.get("title"), "").toLowerCase(Locale.ROOT).contains(normalizedKeyword)
            || Objects.toString(row.get("reviewer_names"), "").toLowerCase(Locale.ROOT).contains(normalizedKeyword))
        .filter(row -> normalizedStatus.isBlank() || normalizedStatus.equals(row.get("scoring_status")))
        .toList();
  }

  private Object[] joinedArgs(Long first, Long second, List<Object> rest) {
    var args = new ArrayList<Object>();
    args.add(first);
    args.add(second);
    args.addAll(rest);
    return args.toArray();
  }

  private String scoringStatus(Map<String, Object> row) {
    if (number(row.get("reviewer_count")) == 0) return "UNASSIGNED";
    if (number(row.get("my_pending_count")) > 0) return "MY_PENDING";
    if (number(row.get("pending_assignment_count")) > 0) return "SCORING";
    if (number(row.get("submitted_count")) > 0) return "COMPLETED";
    return "NOT_STARTED";
  }

  public Map<String, Object> taskDetail(Long taskId) {
    requireTaskView(taskId);
    var task = new LinkedHashMap<>(requireTask(taskId));
    task.put("reviewers", reviewers(taskId));
    task.put("attachments", db.queryForList("""
        select a.id,a.original_name,a.content_type,a.size,a.created_at,u.display_name uploader_name
        from task_attachment a join sys_user u on u.id=a.uploaded_by
        where a.challenge_task_id=? order by a.created_at,a.id
        """, taskId));
    task.put("reviewerLocked", reviewerLocked(taskId));
    task.put("canManageReviewers", SecurityUtils.current().can(Permissions.TASK_MANAGE));
    task.put("assignments", assignmentRows(taskId));
    return task;
  }

  private List<Map<String, Object>> assignmentRows(Long taskId) {
    var rows = db.queryForList("""
        select a.id,a.status,a.final_score,a.assigned_at,
          e.id employee_id,e.name employee_name,e.employee_no,
          e.class_id,cls.label class_name,e.class_position_id,cp.label class_position_name,
          s.id submission_id,s.submission_version,s.content,s.status submission_status,s.submitted_at,s.score,
          (select count(*) from stored_file f where f.submission_id=s.id) file_count
        from task_assignment a
        join employee e on e.id=a.employee_id
        left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
        left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION'
        left join task_submission s on s.id=(select s2.id from task_submission s2 where s2.assignment_id=a.id order by s2.submission_version desc limit 1)
        where a.task_id=? order by e.employee_no,e.id
        """, taskId);
    for (var row : rows) {
      Long submissionId = row.get("submission_id") instanceof Number n ? n.longValue() : null;
      if (submissionId == null) {
        row.put("reviews", List.of());
        row.put("reviewerCount", reviewerIds(taskId).size());
        row.put("submittedReviewCount", 0);
        row.put("canScore", false);
        continue;
      }
      var reviews = visibleReviews(submissionId, Objects.toString(row.get("submission_status"), ""));
      row.put("reviews", reviews);
      row.put("reviewerCount", reviews.stream().filter(r -> !"VOIDED".equals(r.get("status"))).count());
      row.put("submittedReviewCount", reviews.stream().filter(r -> "SUBMITTED".equals(r.get("status"))).count());
      row.put("canScore", reviews.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("mine")) && "PENDING".equals(r.get("status")))
          && "PENDING_REVIEW".equals(row.get("submission_status")));
    }
    return rows;
  }

  private List<Map<String, Object>> visibleReviews(Long submissionId, String submissionStatus) {
    boolean finished = Set.of("APPROVED", "RETURNED").contains(submissionStatus);
    Long currentId = SecurityUtils.current().id();
    var rows = db.queryForList("""
        select r.reviewer_user_id,u.display_name reviewer_name,u.role,r.status,r.decision,r.score,r.comment,r.submitted_at
        from task_submission_review r join sys_user u on u.id=r.reviewer_user_id
        where r.submission_id=? order by u.display_name,u.id
        """, submissionId);
    for (var row : rows) {
      boolean mine = currentId.equals(((Number) row.get("reviewer_user_id")).longValue());
      row.put("mine", mine);
      if (!finished && !mine) {
        row.put("decision", null);
        row.put("score", null);
        row.put("comment", null);
      }
    }
    return rows;
  }

  public Map<String, Object> submissionDetail(Long submissionId) {
    var rows = db.queryForList("""
        select s.id,s.assignment_id,s.submission_version,s.content,s.status,s.submitted_at,s.score,
               a.task_id,a.employee_id,a.final_score,t.title task_title,t.description,t.requirements,t.deadline,
               e.name employee_name,e.employee_no
        from task_submission s
        join task_assignment a on a.id=s.assignment_id
        join challenge_task t on t.id=a.task_id
        join employee e on e.id=a.employee_id
        where s.id=?
        """, submissionId);
    if (rows.isEmpty()) throw new BusinessException(404, "提交记录不存在");
    var result = new LinkedHashMap<>(rows.get(0));
    requireTaskView(((Number) result.get("task_id")).longValue());
    result.put("files", db.queryForList(
        "select id,original_name,size,content_type from stored_file where submission_id=? order by id", submissionId));
    result.put("reviews", visibleReviews(submissionId, Objects.toString(result.get("status"), "")));
    result.put("canScore", canScore(submissionId));
    return result;
  }

  public boolean canReadSubmission(Long submissionId) {
    var rows = db.queryForList("""
        select a.task_id,a.employee_id
        from task_submission s join task_assignment a on a.id=s.assignment_id where s.id=?
        """, submissionId);
    if (rows.isEmpty()) return false;
    var row = rows.get(0);
    if (isGlobalViewer()) return true;
    Long taskId = ((Number) row.get("task_id")).longValue();
    if (isReviewer(taskId, SecurityUtils.current().id())) return true;
    try {
      permissions.requireEmployee(((Number) row.get("employee_id")).longValue());
      return true;
    } catch (AccessDeniedException exception) {
      return false;
    }
  }

  public boolean canViewTask(Long taskId) {
    return isGlobalViewer() || isReviewer(taskId, SecurityUtils.current().id());
  }

  public void requireSubmissionRead(Long submissionId) {
    if (!canReadSubmission(submissionId)) throw new AccessDeniedException("无权访问该任务成果");
  }

  private boolean canScore(Long submissionId) {
    Integer count = db.queryForObject("""
        select count(*) from task_submission_review r
        join task_submission s on s.id=r.submission_id
        where r.submission_id=? and r.reviewer_user_id=? and r.status='PENDING' and s.status='PENDING_REVIEW'
        """, Integer.class, submissionId, SecurityUtils.current().id());
    return count != null && count > 0;
  }

  @Transactional
  public void submitReview(Long submissionId, String decision, String comment, Integer score) {
    permissions.require(Permissions.TASK_SCORE);
    if (!Set.of("APPROVE", "RETURN").contains(decision)) throw new BusinessException(400, "评分结论无效");
    if ("APPROVE".equals(decision) && (score == null || score < 0 || score > 100)) {
      throw new BusinessException(400, "通过时必须填写 0 到 100 的整数评分");
    }
    if ("RETURN".equals(decision) && (comment == null || comment.isBlank())) {
      throw new BusinessException(400, "退回时必须填写意见");
    }
    var rows = db.queryForList("""
        select s.assignment_id,s.status,a.task_id
        from task_submission s join task_assignment a on a.id=s.assignment_id
        where s.id=? for update
        """, submissionId);
    if (rows.isEmpty()) throw new BusinessException(404, "提交记录不存在");
    var submission = rows.get(0);
    if (!"PENDING_REVIEW".equals(submission.get("status"))) throw new BusinessException(409, "本轮评分已经结束");
    Long taskId = ((Number) submission.get("task_id")).longValue();
    Long userId = SecurityUtils.current().id();
    if (!isReviewer(taskId, userId)) throw new AccessDeniedException("只能评分分配给自己的任务");
    int updated = db.update("""
        update task_submission_review
        set status='SUBMITTED',decision=?,score=?,comment=?,submitted_at=now()
        where submission_id=? and reviewer_user_id=? and status='PENDING'
        """, decision, "APPROVE".equals(decision) ? score : null, comment, submissionId, userId);
    if (updated == 0) throw new BusinessException(409, "评分已提交，不能重复修改");
    Long assignmentId = ((Number) submission.get("assignment_id")).longValue();
    if ("RETURN".equals(decision)) {
      db.update("update task_submission_review set status='VOIDED' where submission_id=? and status='PENDING'", submissionId);
      db.update("update task_submission set status='RETURNED',reviewed_by=?,reviewed_at=now(),review_comment=?,score=null where id=?",
          userId, comment, submissionId);
      db.update("update task_assignment set status='RETURNED',final_score=null,version=version+1 where id=?", assignmentId);
    } else {
      Integer remaining = db.queryForObject(
          "select count(*) from task_submission_review where submission_id=? and status='PENDING'",
          Integer.class, submissionId);
      if (remaining != null && remaining == 0) {
        BigDecimal average = db.queryForObject("""
            select round(avg(score),1) from task_submission_review
            where submission_id=? and status='SUBMITTED' and decision='APPROVE'
            """, BigDecimal.class, submissionId);
        if (average == null) throw new BusinessException(409, "评分结果不完整");
        average = average.setScale(1, RoundingMode.HALF_UP);
        db.update("update task_submission set status='APPROVED',reviewed_by=?,reviewed_at=now(),score=? where id=?",
            userId, average, submissionId);
        db.update("update task_assignment set status='APPROVED',final_score=?,version=version+1 where id=?",
            average, assignmentId);
      }
    }
    audit.log("SCORE_TASK_SUBMISSION", "TASK_SUBMISSION", submissionId, null,
        Map.of("decision", decision, "score", score == null ? "" : score, "comment", comment == null ? "" : comment));
  }

  @Transactional
  public void resetReview(Long submissionId) {
    permissions.require(Permissions.TASK_MANAGE);
    var rows = db.queryForList("""
        select s.assignment_id,a.employee_id,t.deadline,s.status
        from task_submission s
        join task_assignment a on a.id=s.assignment_id
        join challenge_task t on t.id=a.task_id
        where s.id=? for update
        """, submissionId);
    if (rows.isEmpty()) throw new BusinessException(404, "提交记录不存在");
    var row = rows.get(0);
    LocalDate deadline = date(row.get("deadline"));
    String month = YearMonth.from(deadline).toString();
    Integer published = db.queryForObject("""
        select count(*) from score_summary
        where employee_id=? and summary_type='MONTH' and period_key=? and status='PUBLISHED'
        """, Integer.class, row.get("employee_id"), month);
    if (published != null && published > 0) throw new BusinessException(409, "对应月份评价已发布，不能重置任务评分");
    Integer reviewCount = db.queryForObject(
        "select count(*) from task_submission_review where submission_id=?",
        Integer.class, submissionId);
    if (reviewCount == null || reviewCount == 0) throw new BusinessException(400, "该提交尚未配置评分人");
    db.update("""
        update task_submission_review
        set status='PENDING',decision=null,score=null,comment=null,submitted_at=null
        where submission_id=?
        """, submissionId);
    db.update("update task_submission set status='PENDING_REVIEW',reviewed_by=null,reviewed_at=null,review_comment=null,score=null where id=?",
        submissionId);
    db.update("update task_assignment set status='PENDING_REVIEW',final_score=null,version=version+1 where id=?",
        row.get("assignment_id"));
    audit.log("RESET_TASK_SUBMISSION_SCORE", "TASK_SUBMISSION", submissionId,
        Map.of("status", row.get("status")), Map.of("status", "PENDING_REVIEW"));
  }

  public List<Map<String, Object>> reviewers(Long taskId) {
    return db.queryForList("""
        select u.id,u.username,u.display_name,u.role,u.enabled,tr.assigned_at
        from task_reviewer tr join sys_user u on u.id=tr.reviewer_user_id
        where tr.task_id=? order by u.display_name,u.id
        """, taskId);
  }

  public boolean reviewerLocked(Long taskId) {
    Integer count = db.queryForObject("""
        select count(*) from task_submission_review r
        join task_submission s on s.id=r.submission_id
        join task_assignment a on a.id=s.assignment_id
        where a.task_id=? and r.status='SUBMITTED'
        """, Integer.class, taskId);
    return count != null && count > 0;
  }

  private void requireTaskView(Long taskId) {
    permissions.require(Permissions.TASK_SCORE);
    if (isGlobalViewer() || isReviewer(taskId, SecurityUtils.current().id())) return;
    throw new AccessDeniedException("只能查看分配给自己的评分任务");
  }

  private boolean isReviewer(Long taskId, Long userId) {
    Integer count = db.queryForObject(
        "select count(*) from task_reviewer where task_id=? and reviewer_user_id=?",
        Integer.class, taskId, userId);
    return count != null && count > 0;
  }

  private Map<String, Object> requireTask(Long taskId) {
    var rows = db.queryForList("select * from challenge_task where id=?", taskId);
    if (rows.isEmpty()) throw new BusinessException(404, "任务不存在");
    return rows.get(0);
  }

  private long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private LocalDate date(Object value) {
    if (value instanceof LocalDateTime dateTime) return dateTime.toLocalDate();
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
    if (value instanceof LocalDate localDate) return localDate;
    return LocalDate.parse(String.valueOf(value).substring(0, 10));
  }
}
