package com.talent.platform.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuarterDefenseCalculationTest {
  private final JdbcTemplate db=mock(JdbcTemplate.class);
  private final EvaluationService service=new EvaluationService(db,new ObjectMapper());
  private Map<String,Object> scheme(String scope,boolean enabled,String weight){return Map.of("id",1L,"defense_scope",scope,"defense_enabled",enabled,"defense_weight",new BigDecimal(weight),"quarter_month1_weight",new BigDecimal("33.33"),"quarter_month2_weight",new BigDecimal("33.33"),"quarter_month3_weight",new BigDecimal("33.34"));}
  private void months(){when(db.queryForList(startsWith("select id,final_score,version"),any(Object[].class))).thenAnswer(call->{String month=call.getArgument(2);String score=month.endsWith("10")?"80":month.endsWith("11")?"90":"70";return List.of(Map.of("id",1L,"final_score",new BigDecimal(score)));});}
  private void defense(){when(db.queryForList(startsWith("select d.id,d.score"),eq(7L),eq("2030-Q4"))).thenReturn(List.of(Map.of("id",2L,"score",new BigDecimal("90.25"),"full_score",new BigDecimal("100"),"version",1,"session_id",3L,"stage_no",1)));}
  @Test void defenseIsWeightedOnceAfterMonthlyAggregation(){months();defense();var value=service.calculateQuarter(7L,"2030-Q4",scheme("QUARTER",true,"20"),null,null);assertEquals(new BigDecimal("82.05"),value.finalScore());assertTrue(value.missing().isEmpty());assertEquals(new BigDecimal("90.25"),value.defenseScore());}
  @Test void missingQuarterlyDefenseBlocksTheQuarterOnly(){months();var value=service.calculateQuarter(7L,"2030-Q4",scheme("QUARTER",true,"20"),null,null);assertNull(value.finalScore());assertEquals(List.of("DEFENSE"),value.missing());}
  @Test void legacyMonthlySchemeRetainsItsOriginalQuarterFormula(){months();var value=service.calculateQuarter(7L,"2030-Q4",scheme("MONTH",true,"50"),null,null);assertEquals(new BigDecimal("80.00"),value.finalScore());verify(db,never()).queryForList(startsWith("select d.id,d.score"),any(Object[].class));}
}
