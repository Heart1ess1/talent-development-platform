package com.talent.platform.training;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import com.talent.platform.task.TaskAttachmentService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainingPlanControllerTest {
  private JdbcTemplate db;
  private TrainingPlanController controller;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    controller = new TrainingPlanController(
        db, mock(PermissionService.class), mock(AuditService.class), mock(TaskAttachmentService.class));
    var user = new CurrentUser(7L, "admin", "Admin", "TRAINING_ADMIN", false, 1,
        Set.of(Permissions.TASK_MANAGE), "ALL");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void createsDirectPlanTaskWithoutDeadlineRule() {
    when(db.queryForList(startsWith("select id from training_plan"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L)));
    when(db.queryForObject(startsWith("select coalesce(max(sort_order)"), eq(Integer.class), eq(1L))).thenReturn(2);
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(12L);

    var result = controller.createPlanTask(1L,
        new TrainingPlanController.PlanTaskRequest("阅读制度", "完成制度学习", "提交学习心得")).data();

    assertThat(result).isEqualTo(12L);
    verify(db).update(startsWith("insert into training_plan_task"), eq(1L), eq("阅读制度"),
        eq("完成制度学习"), eq("提交学习心得"), eq(2));
  }

  @Test
  void refusesToDeleteTaskThatHasBeenDispatched() {
    when(db.queryForList(startsWith("select * from training_plan_task"), eq(8L), eq(1L)))
        .thenReturn(List.of(Map.of("id", 8L, "plan_id", 1L)));
    when(db.queryForObject(startsWith("select count(*) from challenge_task"), eq(Integer.class), eq(8L))).thenReturn(1);

    assertThatThrownBy(() -> controller.deletePlanTask(1L, 8L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已下发");
    verify(db, never()).update(startsWith("delete from training_plan_task"), any(), any());
  }

  @Test
  void refusesToEnablePlanWithoutAnyTasks() {
    when(db.queryForList(startsWith("select * from training_plan"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L, "enabled", false)));
    when(db.queryForObject(startsWith("select count(*) from training_plan_task"), eq(Integer.class), eq(1L)))
        .thenReturn(0);

    assertThatThrownBy(() -> controller.enablePlan(1L, new TrainingPlanController.EnabledRequest(true)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("至少编排一项任务");
    verify(db, never()).update(startsWith("update training_plan set enabled"), any(), any());
  }

  @Test
  void requiresACompleteContinuousTaskOrder() {
    when(db.queryForList(startsWith("select id from training_plan"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L)));
    when(db.queryForObject(startsWith("select count(*) from training_plan_task"), eq(Integer.class), eq(1L)))
        .thenReturn(2);

    var request = new TrainingPlanController.ReplacePlanTaskOrderRequest(
        List.of(new TrainingPlanController.PlanTaskOrderItem(8L, 1)));

    assertThatThrownBy(() -> controller.reorderPlanTasks(1L, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("完整任务顺序");
  }

  @Test
  void refusesToDeletePlanThatHasDispatchHistory() {
    when(db.queryForList(startsWith("select * from training_plan"), eq(1L)))
        .thenReturn(List.of(Map.of("id", 1L, "name", "基础培养")));
    when(db.queryForObject(startsWith("select count(*) from challenge_task"), eq(Integer.class), eq(1L)))
        .thenReturn(3);

    assertThatThrownBy(() -> controller.deletePlan(1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("保留历史");
    verify(db, never()).update("delete from training_plan where id=?", 1L);
  }
}
