package com.talent.platform.leave;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LeaveScheduler {
  private final LeaveService service;
  public LeaveScheduler(LeaveService service) {this.service=service;}
  @Scheduled(cron="0 * * * * *",zone="Asia/Shanghai")
  public void overdue() {service.scanOverdue();}
}
