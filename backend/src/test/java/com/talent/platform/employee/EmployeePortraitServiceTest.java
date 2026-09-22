package com.talent.platform.employee;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmployeePortraitServiceTest {
  @Test void overviewKeepsUnassignedAndRealZeroDistinct(){
    var q=mock(EmployeePortraitQuery.class);var service=new EmployeePortraitService(q);
    when(q.employee(1L)).thenReturn(new HashMap<>(Map.of("id",1L,"employee_no","001","name","张三","onboard_date",Date.valueOf(LocalDate.of(2026,1,1)))));
    when(q.latestLocation(1L)).thenReturn(Map.of());
    when(q.taskMetrics(1L)).thenReturn(Map.of("total",0L,"approved",0L,"not_submitted",0L,"pending_review",0L,"returned_count",0L,"overdue",0L));
    when(q.materialMetrics(1L)).thenReturn(Map.of("total",0L,"viewed",0L));
    when(q.examMetrics(1L)).thenReturn(Map.of("total",1L,"completed",1L,"absent",0L));
    when(q.publishedExamTrend(1L)).thenReturn(List.of(Map.of("plan_id",2L,"name","考试","attempt_no",1,"total_score",BigDecimal.ZERO,"max_score",new BigDecimal("100"),"score_month",Date.valueOf("2026-01-01"))));
    when(q.evaluationTrend(1L,"MONTH")).thenReturn(List.of());when(q.evaluationTrend(1L,"QUARTER")).thenReturn(List.of());when(q.courseParticipation(1L)).thenReturn(List.of());
    var result=service.overview(1L);var task=result.metrics().stream().filter(x->x.key().equals("taskCompletion")).findFirst().orElseThrow();var exam=result.metrics().stream().filter(x->x.key().equals("examAverage")).findFirst().orElseThrow();
    assertThat(task.state()).isEqualTo("UNASSIGNED");assertThat(task.value()).isNull();assertThat(exam.value()).isEqualByComparingTo(BigDecimal.ZERO);assertThat(exam.state()).isEqualTo("VALUE");
  }

  @Test void overdueTaskRemainsInMutuallyExclusivePendingBucket(){
    var q=mock(EmployeePortraitQuery.class);var service=new EmployeePortraitService(q);
    when(q.employee(2L)).thenReturn(new HashMap<>(Map.of("id",2L,"employee_no","002","name","李四")));
    when(q.latestLocation(2L)).thenReturn(Map.of());
    when(q.taskMetrics(2L)).thenReturn(Map.of("total",1L,"approved",0L,"not_submitted",1L,"pending_review",0L,"returned_count",0L,"overdue",1L));
    when(q.materialMetrics(2L)).thenReturn(Map.of("total",0L,"viewed",0L));
    when(q.examMetrics(2L)).thenReturn(Map.of("total",0L,"completed",0L,"absent",0L));
    when(q.publishedExamTrend(2L)).thenReturn(List.of());when(q.evaluationTrend(2L,"MONTH")).thenReturn(List.of());when(q.evaluationTrend(2L,"QUARTER")).thenReturn(List.of());when(q.courseParticipation(2L)).thenReturn(List.of());
    var taskStatus=service.overview(2L).taskStatus();
    assertThat(taskStatus).filteredOn(x->x.key().equals("NOT_SUBMITTED")).singleElement().extracting(EmployeePortraitDtos.ChartItem::value).isEqualTo(BigDecimal.ONE);
    assertThat(taskStatus).filteredOn(x->x.key().equals("OVERDUE")).singleElement().extracting(EmployeePortraitDtos.ChartItem::value).isEqualTo(BigDecimal.ONE);
    assertThat(taskStatus.stream().limit(4).map(EmployeePortraitDtos.ChartItem::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(BigDecimal.ONE);
  }
}
