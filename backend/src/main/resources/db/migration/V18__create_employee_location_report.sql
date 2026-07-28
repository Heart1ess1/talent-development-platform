CREATE TABLE employee_location_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  from_location VARCHAR(128) NOT NULL,
  to_location VARCHAR(128) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  occurred_at DATETIME NOT NULL,
  expected_return_at DATETIME,
  report_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
  reported_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_elr_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_elr_reporter FOREIGN KEY(reported_by) REFERENCES sys_user(id),
  INDEX idx_elr_employee_time(employee_id,occurred_at,id),
  INDEX idx_elr_occurred_at(occurred_at),
  INDEX idx_elr_to_location(to_location)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
