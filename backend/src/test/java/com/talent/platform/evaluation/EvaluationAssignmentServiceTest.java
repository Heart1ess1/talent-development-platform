package com.talent.platform.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationAssignmentServiceTest {
  @Test void allScopeMatchesEveryGeneratedEmployeeTask() {
    assertTrue(EvaluationAssignmentService.scopeMatches("ALL",0L,12L,23L));
    assertTrue(EvaluationAssignmentService.scopeMatches("ALL",0L,null,null));
  }

  @Test void batchScopeOnlyMatchesTheSelectedBatch() {
    assertTrue(EvaluationAssignmentService.scopeMatches("BATCH",12L,12L,23L));
    assertFalse(EvaluationAssignmentService.scopeMatches("BATCH",12L,13L,23L));
  }

  @Test void businessUnitScopeOnlyMatchesTheSelectedUnit() {
    assertTrue(EvaluationAssignmentService.scopeMatches("BUSINESS_UNIT",23L,12L,23L));
    assertFalse(EvaluationAssignmentService.scopeMatches("BUSINESS_UNIT",23L,12L,24L));
  }
}
