package com.talent.platform.schedule;
import com.talent.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class TimeWindowTest {
  private final LocalDateTime published=LocalDateTime.of(2026,10,15,12,0);
  @Test void immediateUsesPublicationRatherThanDraftTime(){var w=TimeWindow.resolve("IMMEDIATE",published.minusDays(1),published.plusDays(2),null,null,published);assertEquals(published,w.startsAt());}
  @Test void offsetCrossesMonthsAndEndsAtLastSecond(){var w=TimeWindow.resolve("OFFSET",null,null,LocalDate.of(2026,9,30),20,published);assertEquals(LocalDateTime.of(2026,10,20,23,59,59),w.endsAt());}
  @Test void manualHasInclusiveStartAndExclusiveEnd(){var w=TimeWindow.resolve("MANUAL",published,published.plusHours(1),null,null,published);assertFalse(w.open(published.minusNanos(1)));assertTrue(w.open(published));assertTrue(w.open(published.plusHours(1).minusNanos(1)));assertFalse(w.open(published.plusHours(1)));}
  @Test void expiredOffsetAndInvalidModesAreRejected(){assertThrows(BusinessException.class,()->TimeWindow.resolve("OFFSET",null,null,LocalDate.of(2026,9,1),2,published));assertThrows(BusinessException.class,()->TimeWindow.resolve("OTHER",published,published.plusHours(1),null,null,published));}
}
