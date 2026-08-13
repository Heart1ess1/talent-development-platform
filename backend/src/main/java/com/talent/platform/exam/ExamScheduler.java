package com.talent.platform.exam;
import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;
@Component public class ExamScheduler {private final ExamScoringService scoring;public ExamScheduler(ExamScoringService scoring){this.scoring=scoring;}@Scheduled(fixedDelay=5000) public void settleExpiredExams(){scoring.scoreExpired();}@Scheduled(cron="0 * * * * *",zone="Asia/Shanghai") public void publishEndedResults(){scoring.publishEndedResults();}}
