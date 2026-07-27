CREATE TABLE business_unit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE employee
  ADD COLUMN business_unit_id BIGINT NULL AFTER batch_id,
  ADD COLUMN skill_mentor_user_id BIGINT NULL AFTER mentor_user_id,
  ADD CONSTRAINT fk_employee_business_unit
    FOREIGN KEY (business_unit_id) REFERENCES business_unit(id),
  ADD CONSTRAINT fk_employee_skill_mentor
    FOREIGN KEY (skill_mentor_user_id) REFERENCES sys_user(id),
  ADD INDEX idx_employee_business_unit (business_unit_id),
  ADD INDEX idx_employee_skill_mentor (skill_mentor_user_id);

ALTER TABLE station_change_request
  MODIFY COLUMN requested_station_id BIGINT NULL;
