ALTER TABLE employee
  ADD COLUMN gender VARCHAR(8) NULL AFTER name,
  ADD COLUMN class_position_id BIGINT NULL AFTER class_id,
  ADD CONSTRAINT fk_employee_class_position
    FOREIGN KEY (class_position_id) REFERENCES dictionary_item(id),
  ADD INDEX idx_employee_class_position (class_position_id);
