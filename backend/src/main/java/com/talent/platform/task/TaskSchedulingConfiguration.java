package com.talent.platform.task;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TaskSchedulingConfiguration {
  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("task-deadline-");
    return scheduler;
  }
}
