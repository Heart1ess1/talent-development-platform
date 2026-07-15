package com.talent.platform.task;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

class TaskStatusServiceTest {
  @Test
  void persistsOverdueAssignmentsWithZeroScore() {
    var db = mock(JdbcTemplate.class);
    var service = new TaskStatusService(db, mock(TaskScheduler.class));

    service.refreshOverdueAssignments();

    verify(db).update(contains("set a.status='OVERDUE',a.final_score=0"));
    verify(db).update(contains("where a.status='NOT_SUBMITTED' and t.deadline<now()"));
  }

  @Test
  void schedulesTheNearestUnsubmittedDeadline() {
    var db = mock(JdbcTemplate.class);
    var scheduler = mock(TaskScheduler.class);
    when(db.queryForObject(startsWith("select min(t.deadline)"), eq(Object.class)))
        .thenReturn(LocalDateTime.now().plusHours(1));
    var service = new TaskStatusService(db, scheduler);

    service.rescheduleNextDeadline();

    verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
  }
}
