package com.talent.platform.exam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 每日练习定时任务：
 * 1. 每 30 秒扫描已发布计划，到推送时刻后为参与人生成当天练习（job_run 唯一键保证幂等）；
 * 2. 每 60 秒扫描超过截止时间仍未提交的练习，置为 EXPIRED。
 */
@Component
public class DailyPracticeScheduler {
  private static final Logger log = LoggerFactory.getLogger(DailyPracticeScheduler.class);
  private final DailyPracticeService service;

  public DailyPracticeScheduler(DailyPracticeService service) {
    this.service = service;
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 20000)
  public void generateDailySessions() {
    try {
      Map<String, Object> result = service.generateDueSessions();
      int generated = result.get("generated") instanceof Number number ? number.intValue() : 0;
      int failed = result.get("failed") instanceof Number number ? number.intValue() : 0;
      if (generated > 0 || failed > 0)
        log.info("每日练习生成完成：计划 {} 个，成功 {} 人，失败 {} 人", result.get("plans"), generated, failed);
    } catch (Exception e) {
      log.error("每日练习生成任务异常", e);
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 45000)
  public void expireOverdueSessions() {
    try {
      int expired = service.expireOverdueSessions();
      if (expired > 0) log.info("每日练习过期扫描：{} 条未提交练习已置为 EXPIRED", expired);
    } catch (Exception e) {
      log.error("每日练习过期扫描任务异常", e);
    }
  }
}
