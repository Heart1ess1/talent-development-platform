package com.talent.platform.schedule;
import com.talent.platform.common.BusinessException;
import com.talent.platform.evaluation.EvaluationRevisionService;
import com.talent.platform.security.*;
import com.talent.platform.task.TaskStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ScheduleServiceTest {
  private final JdbcTemplate db=mock(JdbcTemplate.class);
  private final ScheduleService service=new ScheduleService(db,mock(PermissionService.class),mock(AuditService.class),mock(EvaluationRevisionService.class),mock(TaskStatusService.class));
  private void assignment(String status,boolean future){var row=new HashMap<String,Object>();row.put("task_id",1L);row.put("employee_id",2L);row.put("status",status);row.put("starts_at",future?TimeWindow.now().plusDays(1):TimeWindow.now().minusDays(1));row.put("deadline",TimeWindow.now().plusDays(2));when(db.queryForMap(contains("task_assignment"),eq(3L))).thenReturn(row);}
  @Test void scheduledStartBlocksSubmissionEvenWithFutureDeadline(){assignment("NOT_SUBMITTED",true);assertThrows(BusinessException.class,()->service.assertTaskOpen(3L));}
  @Test void approvedTaskRequiresAnExplicitPersonalOpportunity(){assignment("APPROVED",false);assertThrows(BusinessException.class,()->service.assertTaskOpen(3L));}
  @Test void personalOpportunityPermitsApprovedTaskWithoutErasingTheScore(){assignment("APPROVED",false);when(db.queryForList(contains("submission_opportunity"),eq("TASK"),eq(1L),eq(2L))).thenReturn(List.of(Map.of("id",9L)));assertEquals(9L,service.assertTaskOpen(3L).get("opportunityId"));verify(db,never()).update(anyString(),any(Object[].class));}
  @Test void consumptionIsConditionalAndDuplicateConsumptionFails(){when(db.update(contains("set status='CONSUMED'"),eq(9L))).thenReturn(1,0);assertDoesNotThrow(()->service.consume(9L));assertThrows(BusinessException.class,()->service.consume(9L));}
}
