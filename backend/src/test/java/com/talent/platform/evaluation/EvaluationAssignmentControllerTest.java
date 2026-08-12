package com.talent.platform.evaluation;

import com.talent.platform.security.AuditService;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EvaluationAssignmentControllerTest {
  private EvaluationAssignmentService service;
  private EvaluationAssignmentController controller;

  @BeforeEach
  void setUp() {
    service=mock(EvaluationAssignmentService.class);
    controller=new EvaluationAssignmentController(service,mock(PermissionService.class),mock(AuditService.class));
    var user=new CurrentUser(17L,"mentor17","导师十七","MENTOR",false);
    SecurityContextHolder.getContext().setAuthentication(
      new UsernamePasswordAuthenticationToken(user,null,List.of()));
  }

  @AfterEach void clearSecurityContext(){SecurityContextHolder.clearContext();}

  @Test void myTasksAreAlwaysScopedToTheCurrentReviewer() {
    YearMonth month=YearMonth.of(2026,8);
    when(service.list(month,"MENTOR","PENDING",17L,null)).thenReturn(List.of());

    assertEquals(0,controller.mine(month,"PENDING","MENTOR").data().size());
    verify(service).list(month,"MENTOR","PENDING",17L,null);
  }

  @Test void generateDelegatesMonthAndDeadline() {
    YearMonth month=YearMonth.of(2026,8);
    LocalDateTime deadline=LocalDateTime.of(2026,8,28,18,0);
    when(service.generateMonth(month,deadline)).thenReturn(6);

    assertEquals(6,controller.generate(new EvaluationAssignmentController.GenerateRequest(month,deadline)).data());
    verify(service).generateMonth(month,deadline);
  }

  @Test void assigningMultipleReviewersPreservesTheRequestedMode() {
    var request=new EvaluationAssignmentController.AssignRequest(
      List.of(10L,11L),List.of(21L,22L),"ADD",null,"联合评分");

    controller.assign(request);

    verify(service).assign(List.of(10L,11L),List.of(21L,22L),"ADD",null,"联合评分");
  }

  @Test void savingScopeRuleDelegatesTheTaskFirstConfiguration() {
    YearMonth month=YearMonth.of(2026,8);
    var request=new EvaluationAssignmentController.ScopeRuleRequest(
      month,"MENTOR","BUSINESS_UNIT",9L,List.of(21L,22L),null,"板块联合评分");
    when(service.saveScopeRule(month,"MENTOR","BUSINESS_UNIT",9L,List.of(21L,22L),null,"板块联合评分")).thenReturn(31L);

    assertEquals(31L,controller.saveScopeRule(request).data());
    verify(service).saveScopeRule(month,"MENTOR","BUSINESS_UNIT",9L,List.of(21L,22L),null,"板块联合评分");
  }
}
