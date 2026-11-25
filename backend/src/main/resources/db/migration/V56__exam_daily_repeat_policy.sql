-- V56: 允许 ALLOW_REPEAT 策略在同一计划内跨天复用已做过的题。
-- V55 的 uk_edsq_plan_employee_question 会阻止同一计划同一人再次写入旧题，
-- 与「题库不足时循环复用」策略冲突（生成时选中旧题后会被 insert ignore 全部跳过，产生 0 题 session）。
-- 调整后：
--   1. 同一套练习内的题目去重仍由主键 (session_id, question_id) 保证；
--   2. 跨天去重由应用层按该计划该员工的历史题集排除，job_run/session 唯一键继续保证每日只生成一套。
ALTER TABLE exam_daily_session_question
  DROP INDEX uk_edsq_plan_employee_question,
  ADD INDEX idx_edsq_plan_employee_question(plan_id, employee_id, question_id);
