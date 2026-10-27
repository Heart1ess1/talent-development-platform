CREATE TABLE area_manager_scope (
  user_id BIGINT NOT NULL,
  station_id BIGINT NOT NULL,
  PRIMARY KEY(user_id, station_id),
  CONSTRAINT fk_ams_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_ams_station FOREIGN KEY(station_id) REFERENCES service_station(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  current_version INT NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_lr_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  INDEX idx_lr_employee_status(employee_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_draft (
  employee_id BIGINT PRIMARY KEY,
  content JSON NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ld_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_submission_key (
  user_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  request_id BIGINT,
  PRIMARY KEY(user_id,request_key),
  CONSTRAINT fk_lsk_user FOREIGN KEY(user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_request_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  scenario VARCHAR(32) NOT NULL,
  leave_type VARCHAR(16) NOT NULL,
  start_at DATETIME NOT NULL,
  end_at DATETIME NOT NULL,
  destination VARCHAR(128) NOT NULL,
  return_location VARCHAR(128) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  supplemental_reason VARCHAR(500),
  is_supplemental BOOLEAN NOT NULL DEFAULT FALSE,
  day_units INT NOT NULL,
  approver_level VARCHAR(24) NOT NULL,
  approver_user_id BIGINT NOT NULL,
  station_id BIGINT,
  status VARCHAR(24) NOT NULL,
  notice_version VARCHAR(16) NOT NULL,
  rule_version VARCHAR(16) NOT NULL DEFAULT 'HALFDAY_V1',
  notice_acknowledged_at DATETIME NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at DATETIME,
  CONSTRAINT fk_lrv_request FOREIGN KEY(request_id) REFERENCES leave_request(id),
  CONSTRAINT fk_lrv_approver FOREIGN KEY(approver_user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_lrv_creator FOREIGN KEY(created_by) REFERENCES sys_user(id),
  UNIQUE KEY uk_lrv_version(request_id,version_no),
  INDEX idx_lrv_approver_status(approver_user_id,status),
  INDEX idx_lrv_end_status(end_at,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_action (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id BIGINT NOT NULL,
  version_no INT,
  actor_user_id BIGINT NOT NULL,
  action VARCHAR(24) NOT NULL,
  comment VARCHAR(1000),
  actual_return_at DATETIME,
  actual_return_location VARCHAR(128),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_la_request FOREIGN KEY(request_id) REFERENCES leave_request(id),
  CONSTRAINT fk_la_actor FOREIGN KEY(actor_user_id) REFERENCES sys_user(id),
  INDEX idx_la_request(request_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_reminder (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  recipient_user_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME,
  CONSTRAINT fk_lrem_request FOREIGN KEY(request_id) REFERENCES leave_request(id),
  CONSTRAINT fk_lrem_user FOREIGN KEY(recipient_user_id) REFERENCES sys_user(id),
  UNIQUE KEY uk_lrem_version_recipient(request_id,version_no,recipient_user_id),
  INDEX idx_lrem_recipient_status(recipient_user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE employee_location_report
  ADD COLUMN leave_request_id BIGINT NULL,
  ADD COLUMN leave_version_no INT NULL,
  ADD COLUMN movement_kind VARCHAR(16) NOT NULL DEFAULT 'ACTUAL';
