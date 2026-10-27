package com.talent.platform.leave;

import com.talent.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class LeaveDurationTest {
  private LocalDateTime at(String value) {return LocalDateTime.parse(value);}

  @Test void noonBoundaryAndLevels() {
    var half=LeaveDuration.calculate(at("2026-10-28T08:00"),at("2026-10-28T12:00"));
    assertThat(half.halfDays()).isEqualTo(1);
    assertThat(half.level()).isEqualTo("STATION_MANAGER");
    assertThat(LeaveDuration.calculate(at("2026-10-28T08:00"),at("2026-10-28T18:00")).level()).isEqualTo("AREA_MANAGER");
    assertThat(LeaveDuration.calculate(at("2026-10-28T12:00"),at("2026-10-29T12:00")).level()).isEqualTo("AREA_MANAGER");
  }

  @Test void countsWeekendsAndMidnight() {
    var longLeave=LeaveDuration.calculate(at("2026-10-30T12:00"),at("2026-11-02T12:00"));
    assertThat(longLeave.halfDays()).isEqualTo(6);
    assertThat(longLeave.level()).isEqualTo("TRAINING_DIRECTOR");
    assertThat(LeaveDuration.calculate(at("2026-10-28T23:00"),at("2026-10-29T00:00")).halfDays()).isEqualTo(1);
  }

  @Test void rejectsInvalidRange() {
    assertThatThrownBy(() -> LeaveDuration.calculate(at("2026-10-28T12:00"),at("2026-10-28T12:00"))).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> LeaveDuration.calculate(at("2026-10-29T12:00"),at("2026-10-28T12:00"))).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> LeaveDuration.calculate(null,at("2026-10-28T12:00"))).isInstanceOf(BusinessException.class);
  }

  @Test void countsCrossYearAndMinuteIntersections() {
    assertThat(LeaveDuration.calculate(at("2026-12-31T23:59"),at("2027-01-01T00:01")).halfDays()).isEqualTo(2);
    assertThat(LeaveDuration.calculate(at("2026-10-28T11:59"),at("2026-10-28T12:01")).halfDays()).isEqualTo(2);
    assertThat(LeaveDuration.calculate(at("2026-10-28T12:00"),at("2026-10-28T12:01")).halfDays()).isEqualTo(1);
  }
}
