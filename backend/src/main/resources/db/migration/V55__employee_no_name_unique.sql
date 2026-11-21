-- 人员台账以工号+姓名作为唯一识别项，支持同工号不同姓名的人员分别建档。
ALTER TABLE employee
  DROP INDEX employee_no,
  ADD UNIQUE KEY uk_employee_no_name (employee_no, name);
