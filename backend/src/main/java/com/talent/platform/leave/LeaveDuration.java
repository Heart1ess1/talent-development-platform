package com.talent.platform.leave;

import com.talent.platform.common.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public final class LeaveDuration {
  private LeaveDuration() {}
  public record Result(int halfDays, String level, List<String> periods) {}

  public static Result calculate(LocalDateTime start, LocalDateTime end) {
    if (start == null || end == null || !end.isAfter(start)) throw new BusinessException(400, "请假结束时间必须晚于开始时间");
    if (end.isAfter(start.plusYears(1))) throw new BusinessException(400, "单次请假不能超过一年");
    List<String> periods = new ArrayList<>();
    for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
      LocalDateTime morning = day.atStartOfDay();
      LocalDateTime noon = day.atTime(LocalTime.NOON);
      LocalDateTime midnight = day.plusDays(1).atStartOfDay();
      if (start.isBefore(noon) && end.isAfter(morning)) periods.add(day + " 上午");
      if (start.isBefore(midnight) && end.isAfter(noon)) periods.add(day + " 下午");
    }
    int count = periods.size();
    return new Result(count, count == 1 ? "STATION_MANAGER" : count == 2 ? "AREA_MANAGER" : "TRAINING_DIRECTOR", periods);
  }
}
