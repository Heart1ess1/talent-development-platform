ALTER TABLE employee
  ADD COLUMN class_id BIGINT NULL AFTER batch_id,
  ADD COLUMN notes TEXT NULL AFTER id_card,
  ADD CONSTRAINT fk_employee_class
    FOREIGN KEY (class_id) REFERENCES dictionary_item(id),
  ADD INDEX idx_employee_class (class_id);
