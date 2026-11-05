package com.talent.platform.evaluation;
import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;import java.time.*;
@Component public class EvaluationScheduler {private final EvaluationService service;public EvaluationScheduler(EvaluationService service){this.service=service;}@Scheduled(cron="0 0 2 1 * *",zone="Asia/Shanghai") public void monthly(){service.generateMonth(YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1));}}
