package com.talent.platform.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExamPlanBusinessUnitScopeTest {
  @Test
  void candidateFilterUsesBusinessUnitsInsteadOfServiceStations() {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    var controller = new ExamController(
        db,
        mock(PermissionService.class),
        mock(AuditService.class),
        new ObjectMapper(),
        mock(ExamScoringService.class));

    controller.planCandidates(null, "3,5", null, null);

    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), aryEq(new Object[]{3L, 5L}));
    assertThat(sql.getValue())
        .contains("left join business_unit bu on bu.id=e.business_unit_id")
        .contains("e.business_unit_id in (?,?)")
        .contains("bu.name business_unit_name")
        .doesNotContain("e.station_id in")
        .doesNotContain("service_station");
  }
}
