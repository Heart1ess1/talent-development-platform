package com.talent.platform.task;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;

@Service
public class TaskStatusService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private final JdbcTemplate db;
  private final TaskScheduler scheduler;
  private ScheduledFuture<?> nextDeadlineJob;

  public TaskStatusService(JdbcTemplate db, TaskScheduler scheduler) {
    this.db = db;
    this.scheduler = scheduler;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void initialize() {
    rescheduleNextDeadline();
  }

  public int refreshOverdueAssignments() {
    return db.update("update task_assignment a join challenge_task t on t.id=a.task_id "
        + "set a.status='OVERDUE',a.final_score=0,a.version=a.version+1 "
        + "where a.status='NOT_SUBMITTED' and t.deadline<now()");
  }

  public synchronized void rescheduleNextDeadline() {
    refreshOverdueAssignments();
    if (nextDeadlineJob != null) nextDeadlineJob.cancel(false);
    Object value = db.queryForObject("select min(t.deadline) from task_assignment a "
        + "join challenge_task t on t.id=a.task_id where a.status='NOT_SUBMITTED' and t.deadline>now()", Object.class);
    LocalDateTime deadline = asLocalDateTime(value);
    nextDeadlineJob = deadline == null ? null : scheduler.schedule(this::rescheduleNextDeadline,
        Instant.from(deadline.atZone(ZONE)));
  }

  private LocalDateTime asLocalDateTime(Object value) {
    if (value instanceof LocalDateTime dateTime) return dateTime;
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
    if (value instanceof java.util.Date date) return new Timestamp(date.getTime()).toLocalDateTime();
    return null;
  }
}
