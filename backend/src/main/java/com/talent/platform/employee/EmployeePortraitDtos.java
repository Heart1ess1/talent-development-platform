package com.talent.platform.employee;

import com.talent.platform.common.PageResult;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class EmployeePortraitDtos {
  private EmployeePortraitDtos() {}

  public record EmployeeSummary(Long id,String employeeNo,String name,String avatarToken,String batchName,
      String className,String businessUnitName,String stationName,String technicalMentorName,
      String skillMentorName,LocalDate onboardDate) {}
  public record LatestLocation(String location,LocalDateTime occurredAt,LocalDateTime reportedAt) {}
  public record Metric(String key,String label,BigDecimal value,Long numerator,Long denominator,
      String unit,String state,String note) {}
  public record ChartItem(String key,String label,BigDecimal value,BigDecimal maxValue,String meta) {}
  public record Overview(EmployeeSummary employee,LatestLocation latestLocation,List<Metric> metrics,
      List<ChartItem> taskStatus,List<ChartItem> courseParticipation,List<ChartItem> examTrend,
      List<ChartItem> monthlyEvaluationTrend,List<ChartItem> quarterlyEvaluationTrend,
      LocalDateTime fetchedAt) {}

  public record CourseSession(Long id,String courseName,String sessionTitle,LocalDateTime startsAt,
      LocalDateTime endsAt,String location,String participationStatus,String attendanceStatus,
      LocalDateTime checkedAt,String attendanceSource) {}
  public record CourseMaterial(Long id,String courseName,String materialName,boolean read,
      long viewCount,long durationSeconds,LocalDateTime lastViewedAt) {}
  public record Courses(PageResult<?> page,String type,LocalDateTime fetchedAt) {}

  public record Task(Long assignmentId,String taskName,String trainingPlanName,LocalDateTime assignedAt,
      LocalDateTime deadline,String status,Integer latestVersion,LocalDateTime submittedAt,
      long reviewerCount,long reviewedCount,BigDecimal finalScore,LocalDateTime reviewedAt,
      String scoringScopeLabel,String reviewerNames,
      boolean overdue,boolean completedLate,String batchSnapshot,String businessUnitSnapshot,
      String classSnapshot,List<TaskSubmission> submissions) {}
  public record SubmissionFile(Long id,String originalName,String contentType,long size) {}
  public record TaskSubmission(Long id,int version,String status,LocalDateTime submittedAt,
      LocalDateTime reviewedAt,BigDecimal score,String reviewComment,long reviewerCount,
      long reviewedCount,List<SubmissionFile> files,List<TaskReview> reviews) {}
  public record TaskReview(Long reviewerId,String reviewerName,String status,String decision,
      BigDecimal score,String comment,LocalDateTime submittedAt) {}
  public record ExamAttempt(Long id,int attemptNo,String status,LocalDateTime startedAt,
      LocalDateTime submittedAt,BigDecimal score,BigDecimal maxScore,boolean published) {}
  public record Exam(Long planId,String examName,LocalDateTime startsAt,LocalDateTime endsAt,
      String planPhase,String participationStatus,int maxAttempts,List<ExamAttempt> attempts) {}
  public record Evaluation(Long id,String type,String period,int version,String status,
      BigDecimal examScore,BigDecimal taskScore,BigDecimal mentorScore,BigDecimal stationScore,
      BigDecimal trainingScore,BigDecimal bonus,BigDecimal deduction,BigDecimal finalScore,
      String missingItems,String componentSnapshot,String quarterSnapshot,LocalDateTime generatedAt,
      LocalDateTime publishedAt,boolean provisional) {}
  public record StationHistory(Long id,String fromStation,String toStation,LocalDateTime effectiveAt,
      String source,String comment) {}
  public record Stations(EmployeeSummary employee,List<StationHistory> history,LocalDateTime fetchedAt) {}
  public record Location(Long id,String fromLocation,String toLocation,String reason,
      LocalDateTime occurredAt,LocalDateTime expectedReturnAt,String source,LocalDateTime reportedAt) {}
  public record Locations(LatestLocation latest,PageResult<Location> page,LocalDateTime fetchedAt) {}
  public record TimelineEvent(String id,String type,LocalDateTime occurredAt,String title,String status,
      Long sourceId,boolean timeMissing) {}
}
