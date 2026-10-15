package com.talent.platform.schedule;

import com.talent.platform.common.BusinessException;
import java.sql.Timestamp;
import java.time.*;

public record TimeWindow(LocalDateTime startsAt, LocalDateTime endsAt, String mode) {
  public static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0); }
  public static LocalDateTime time(Object value) {
    if(value == null) return null;
    if(value instanceof Timestamp v) return v.toLocalDateTime();
    if(value instanceof LocalDateTime v) return v;
    return LocalDateTime.parse(value.toString().replace(' ', 'T'));
  }
  public static TimeWindow resolve(String mode, LocalDateTime starts, LocalDateTime ends,
                                   LocalDate base, Integer days, LocalDateTime publishedAt) {
    String type = mode == null || mode.equals("ABSOLUTE") ? "IMMEDIATE" : mode;
    LocalDateTime from = type.equals("MANUAL") ? starts : publishedAt;
    LocalDateTime to = ends;
    if(type.equals("OFFSET")) {
      if(base == null || days == null || days < 0) throw new BusinessException(400,"请填写基准日期和非负偏移天数");
      to=base.plusDays(days).atTime(23,59,59);
    } else if(!type.equals("IMMEDIATE") && !type.equals("MANUAL")) throw new BusinessException(400,"不支持的时限类型");
    if(from == null || to == null || !to.isAfter(from)) throw new BusinessException(400,"截止时间必须晚于开始时间");
    return new TimeWindow(from,to,type);
  }
  public boolean open(LocalDateTime at) { return !at.isBefore(startsAt) && at.isBefore(endsAt); }
  public void requireOpen() { if(!open(now())) throw new BusinessException(400,"不在开放时间，请联系管理员调整时限或开放个人补交"); }
}
