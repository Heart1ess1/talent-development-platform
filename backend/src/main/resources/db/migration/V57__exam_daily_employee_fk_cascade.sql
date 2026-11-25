-- 每日练习参与人/会话与员工外键改为级联删除：
-- 员工做过每日练习后，删除账号时由数据库自动清理对应参与人行、会话及其题目快照和答案，避免 ERROR 1451。
ALTER TABLE exam_daily_participant DROP FOREIGN KEY fk_edpa_employee;
ALTER TABLE exam_daily_participant
  ADD CONSTRAINT fk_edpa_employee FOREIGN KEY (employee_id) REFERENCES employee (id) ON DELETE CASCADE;

ALTER TABLE exam_daily_session DROP FOREIGN KEY fk_eds_employee;
ALTER TABLE exam_daily_session
  ADD CONSTRAINT fk_eds_employee FOREIGN KEY (employee_id) REFERENCES employee (id) ON DELETE CASCADE;
