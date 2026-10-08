package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.SecurityUtils;
import com.talent.platform.storage.FileStorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class TaskArchiveService {
  private static final Logger log = LoggerFactory.getLogger(TaskArchiveService.class);
  private static final Duration RETENTION = Duration.ofMinutes(30);
  private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(15);
  private static final long MAX_JOB_BYTES = 2L * 1024 * 1024 * 1024;
  private static final long MAX_RESERVED_BYTES = 4L * 1024 * 1024 * 1024;
  private final JdbcTemplate db;
  private final FileStorageService storage;
  private final PermissionService permissions;
  private final TaskScoringService scoring;
  private final Path root;
  private final Map<String, Job> jobs = new LinkedHashMap<>();
  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(2), runnable -> {
        var thread = new Thread(runnable, "task-archive");
        thread.setDaemon(true);
        return thread;
      }, new ThreadPoolExecutor.AbortPolicy());

  public TaskArchiveService(JdbcTemplate db, FileStorageService storage, PermissionService permissions,
      TaskScoringService scoring,
      @Value("${app.archive.cache-root:${app.preview.cache-root}/task-archives}") String cacheRoot) {
    this.db = db;
    this.storage = storage;
    this.permissions = permissions;
    this.scoring = scoring;
    this.root = Path.of(cacheRoot).toAbsolutePath().normalize();
  }

  public record Status(String jobId, Long taskId, String state, int totalFiles, int processedFiles,
                       int missingFiles, String currentFile, String filename, long size,
                       String message, Instant expiresAt) {}

  private static final class Job {
    final String id = java.util.UUID.randomUUID().toString();
    final Long taskId;
    final CurrentUser owner;
    final Set<Long> employees;
    final List<SubmissionArchiveWriter.Submission> submissions;
    final String filename;
    final int total;
    final long sourceBytes;
    final Instant createdAt = Instant.now();
    Instant expiresAt = createdAt.plus(RETENTION);
    String state = "QUEUED", message = "等待打包", currentFile = "";
    int processed, missing, readers;
    long size;
    Future<?> future;
    boolean workerRunning;

    Job(Long taskId, CurrentUser owner, List<SubmissionArchiveWriter.Submission> submissions, String filename) {
      this.taskId = taskId;
      this.owner = owner;
      this.submissions = submissions;
      this.filename = filename;
      this.employees = submissions.stream().map(item -> ((Number) item.row().get("employee_id")).longValue())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      this.total = submissions.stream().mapToInt(item -> item.files().size()).sum();
      this.sourceBytes = submissions.stream().flatMap(item -> item.files().stream())
          .mapToLong(SubmissionArchiveWriter.Attachment::size).sum();
    }
  }

  @PostConstruct
  void initialize() throws IOException {
    Files.createDirectories(root);
    // Jobs are process-local. Artifacts from a previous process cannot be resumed.
    try (var files = Files.list(root)) {
      for (var path : files.filter(this::isArtifact).toList()) Files.deleteIfExists(path);
    }
  }

  public Status create(Long taskId) {
    requireTaskAccess(taskId);
    var tasks = db.queryForList("select title from challenge_task where id=?", taskId);
    if (tasks.isEmpty()) throw new BusinessException(404, "任务不存在");
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>();
    args.add(taskId);
    args.addAll(scope.args());
    var rows = db.queryForList("""
        select s.id,s.submission_version,s.content,e.id employee_id,e.name employee_name,e.employee_no,
               a.batch_name_snapshot batch_name,a.business_unit_name_snapshot business_unit_name,
               a.class_name_snapshot class_name
        from task_submission s join task_assignment a on a.id=s.assignment_id
        join employee e on e.id=a.employee_id where a.task_id=?
        """ + scope.sql() + " order by e.employee_no,e.id,s.submission_version", args.toArray());
    if (rows.isEmpty()) throw new BusinessException(400, "当前任务暂无可导出的提交资料");
    var job = new Job(taskId, SecurityUtils.current(), SubmissionArchiveWriter.snapshot(db, rows),
        SubmissionArchiveWriter.safePart(tasks.get(0).get("title")) + "-全部提交文件.zip");
    synchronized (jobs) {
      cleanup();
      for (var existing : jobs.values()) {
        if (existing.owner.id().equals(job.owner.id()) && existing.taskId.equals(taskId) && active(existing)) {
          authorize(existing);
          return status(existing);
        }
      }
      if (jobs.size() >= 8 || jobs.values().stream().filter(item -> active(item) || "READY".equals(item.state))
          .mapToLong(item -> item.sourceBytes).sum() + job.sourceBytes > MAX_RESERVED_BYTES) {
        throw new BusinessException(429, "打包任务较多，请稍后重试");
      }
      if (job.sourceBytes > MAX_JOB_BYTES) throw new BusinessException(400, "附件总量超过 2 GiB，请分任务下载");
      try {
        if (Files.getFileStore(root).getUsableSpace() < job.sourceBytes + 256L * 1024 * 1024)
          throw new BusinessException(503, "临时存储空间不足，请稍后重试");
      } catch (IOException exception) {
        throw new BusinessException(503, "临时存储不可用，请稍后重试");
      }
      jobs.put(job.id, job);
      try {
        job.future = executor.submit(() -> build(job));
      } catch (RejectedExecutionException exception) {
        jobs.remove(job.id);
        throw new BusinessException(429, "打包任务正在排队，请稍后重试");
      }
      return status(job);
    }
  }

  public Status get(String id) {
    synchronized (jobs) { return status(authorizedJob(id)); }
  }

  public void download(String id, HttpServletResponse response) throws IOException {
    Job job;
    synchronized (jobs) {
      job = authorizedJob(id);
      if (!"READY".equals(job.state)) throw new BusinessException(409, "文件尚未打包完成，请稍后下载");
      job.readers++;
    }
    try (var input = Files.newInputStream(path(job, ".zip"))) {
      response.setContentType("application/zip");
      response.setContentLengthLong(job.size);
      response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
      response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
          + URLEncoder.encode(job.filename, StandardCharsets.UTF_8).replace("+", "%20"));
      input.transferTo(response.getOutputStream());
    } finally {
      synchronized (jobs) { job.readers--; }
    }
  }

  private void build(Job job) {
    synchronized (jobs) { job.workerRunning = true; job.state = "RUNNING"; job.message = "正在打包"; }
    try {
      try (var output = Files.newOutputStream(path(job, ".part"))) {
        SubmissionArchiveWriter.write(output, job.submissions, storage, progress -> {
          if (Instant.now().isAfter(job.createdAt.plus(BUILD_TIMEOUT))) throw new BusinessException(408, "打包超时");
          synchronized (jobs) {
            job.processed = progress.processedFiles();
            job.missing = progress.missingFiles();
            job.currentFile = progress.currentFile();
          }
        });
      }
      synchronized (jobs) {
        if (Thread.currentThread().isInterrupted() || "FAILED".equals(job.state)) throw new IOException("Archive cancelled");
        Files.move(path(job, ".part"), path(job, ".zip"), StandardCopyOption.ATOMIC_MOVE);
        job.size = Files.size(path(job, ".zip"));
        job.state = "READY";
        job.message = job.missing == 0 ? "打包完成" : "打包完成，部分附件缺失，请查看 ZIP 内的缺失说明";
        job.currentFile = "";
        job.expiresAt = Instant.now().plus(RETENTION);
      }
    } catch (Exception exception) {
      log.warn("Task archive {} failed for task {}", job.id, job.taskId, exception);
      synchronized (jobs) {
        job.state = "FAILED";
        job.message = "打包失败，请重试；若仍失败请联系管理员";
        job.expiresAt = Instant.now().plus(RETENTION);
        deleteArtifacts(job);
      }
    } finally {
      synchronized (jobs) { job.workerRunning = false; }
    }
  }

  private Job authorizedJob(String id) {
    var job = jobs.get(id);
    if (job == null || !Instant.now().isBefore(job.expiresAt))
      throw new BusinessException(410, "打包任务已过期或服务已重启，请重新打包");
    authorize(job);
    return job;
  }

  private void authorize(Job job) {
    var user = SecurityUtils.current();
    if (!job.owner.id().equals(user.id()) || job.owner.securityVersion() != user.securityVersion()
        || !job.owner.role().equals(user.role()) || !job.owner.dataScope().equals(user.dataScope()))
      throw new AccessDeniedException("无权访问该打包任务");
    requireTaskAccess(job.taskId);
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>(job.employees);
    args.addAll(scope.args());
    var placeholders = String.join(",", java.util.Collections.nCopies(job.employees.size(), "?"));
    var count = db.queryForObject("select count(*) from employee e where e.id in (" + placeholders + ")"
        + scope.sql(), Integer.class, args.toArray());
    if (count == null || count != job.employees.size()) throw new AccessDeniedException("人员数据范围已变化，请重新打包");
  }

  private void requireTaskAccess(Long taskId) {
    if (scoring.canViewTask(taskId) || "ALL".equals(SecurityUtils.current().dataScope())) return;
    var scope = permissions.employeeFilter("e");
    var args = new ArrayList<Object>();
    args.add(taskId);
    args.addAll(scope.args());
    var count = db.queryForObject("select count(*) from task_assignment a join employee e on e.id=a.employee_id where a.task_id=?"
        + scope.sql(), Integer.class, args.toArray());
    if (count == null || count == 0) throw new AccessDeniedException("无权访问该任务");
  }

  private Status status(Job job) {
    return new Status(job.id, job.taskId, job.state, job.total, job.processed, job.missing,
        job.currentFile, job.filename, job.size, job.message, job.expiresAt);
  }

  @Scheduled(fixedDelay = 60000)
  public void cleanup() {
    synchronized (jobs) {
      var iterator = jobs.values().iterator();
      while (iterator.hasNext()) {
        var job = iterator.next();
        if (active(job) && Instant.now().isAfter(job.createdAt.plus(BUILD_TIMEOUT))) {
          job.state = "FAILED";
          job.message = "打包超时，请重试";
          job.expiresAt = Instant.now().plus(RETENTION);
          if (job.future != null) job.future.cancel(true);
          executor.purge();
        }
        if (!active(job) && !job.workerRunning && job.readers == 0 && Instant.now().isAfter(job.expiresAt)
            && (job.future == null || job.future.isDone())) {
          if (deleteArtifacts(job)) iterator.remove();
        }
      }
    }
  }

  private boolean active(Job job) { return "QUEUED".equals(job.state) || "RUNNING".equals(job.state); }
  private Path path(Job job, String extension) { return root.resolve(job.id + extension); }
  private boolean isArtifact(Path path) {
    return Files.isRegularFile(path) && path.getFileName().toString()
        .matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(zip|part)");
  }
  private boolean deleteArtifacts(Job job) {
    boolean deleted = true;
    for (String extension : List.of(".part", ".zip")) {
      try { Files.deleteIfExists(path(job, extension)); }
      catch (IOException exception) { deleted = false; log.warn("Cannot remove expired archive {}", job.id, exception); }
    }
    return deleted;
  }

  @PreDestroy
  void shutdown() { executor.shutdownNow(); }
}
