package com.talent.platform.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TaskSchedulingConfiguration {
  private static final Logger log = LoggerFactory.getLogger(TaskSchedulingConfiguration.class);

  /**
   * 调度线程池大小与 spring.task.scheduling.pool.size 保持一致（默认 4）。
   * 自建 bean 会覆盖 Spring Boot 自动配置，因此必须读取同一配置，否则每日练习生成长任务会阻塞其它定时任务。
   */
  @Bean
  public ThreadPoolTaskScheduler taskScheduler(@Value("${spring.task.scheduling.pool.size:4}") int poolSize) {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(Math.max(1, poolSize));
    scheduler.setThreadNamePrefix("task-deadline-");
    scheduler.setRemoveOnCancelPolicy(true);
    log.info("定时任务调度线程池已启用，poolSize={}", Math.max(1, poolSize));
    return scheduler;
  }
}
