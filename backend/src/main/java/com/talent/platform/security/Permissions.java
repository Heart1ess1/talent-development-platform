package com.talent.platform.security;

public final class Permissions {
  private Permissions(){}
  public static final String EMPLOYEE_READ="employee:read";
  public static final String EMPLOYEE_WRITE="employee:write";
  public static final String EMPLOYEE_EXPORT="employee:export";
  public static final String COURSE_MANAGE="course:manage";
  public static final String ATTENDANCE_MANAGE="attendance:manage";
  public static final String TASK_MANAGE="task:manage";
  public static final String TASK_REVIEW="task:review";
  public static final String EVALUATION_VIEW="evaluation:view";
  public static final String EVALUATION_SUBMIT="evaluation:submit";
  public static final String EVALUATION_MANAGE="evaluation:manage";
  public static final String EXAM_MANAGE="exam:manage";
  public static final String USER_EMPLOYEE_MANAGE="user:employee:manage";
  public static final String USER_OPS_ROLE_MANAGE="user:ops-role:manage";
  public static final String USER_ADMIN_MANAGE="user:admin:manage";
  public static final String MASTER_MANAGE="master:manage";
  public static final String AUDIT_READ="audit:read";
}
