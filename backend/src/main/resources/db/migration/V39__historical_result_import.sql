CREATE TABLE history_import_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  import_type VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  source_system VARCHAR(128) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  row_count INT NOT NULL DEFAULT 0,
  valid_count INT NOT NULL DEFAULT 0,
  error_count INT NOT NULL DEFAULT 0,
  warning_count INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  reviewed_by BIGINT,
  reviewed_at DATETIME,
  published_at DATETIME,
  revoked_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_history_import_file(import_type, source_system, file_sha256),
  CONSTRAINT fk_history_batch_creator FOREIGN KEY(created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_history_batch_reviewer FOREIGN KEY(reviewed_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE history_import_row (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  sheet_name VARCHAR(64) NOT NULL,
  row_no INT NOT NULL,
  row_type VARCHAR(32) NOT NULL,
  raw_json JSON NOT NULL,
  normalized_json JSON,
  employee_no VARCHAR(64),
  employee_id BIGINT,
  state VARCHAR(20) NOT NULL DEFAULT 'ERROR',
  error_message VARCHAR(1000),
  warning_message VARCHAR(1000),
  row_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_history_row(batch_id,sheet_name,row_no),
  KEY idx_history_row_state(batch_id,state),
  CONSTRAINT fk_history_row_batch FOREIGN KEY(batch_id) REFERENCES history_import_batch(id) ON DELETE CASCADE,
  CONSTRAINT fk_history_row_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE legacy_exam (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  source_system VARCHAR(128) NOT NULL,
  external_exam_key VARCHAR(128) NOT NULL,
  name VARCHAR(255) NOT NULL,
  exam_date DATE NOT NULL,
  score_month DATE NOT NULL,
  max_score DECIMAL(7,2) NOT NULL,
  remark VARCHAR(1000),
  version INT NOT NULL DEFAULT 1,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_legacy_exam_version(source_system,external_exam_key,version),
  KEY idx_legacy_exam_active(source_system,external_exam_key,active),
  CONSTRAINT fk_legacy_exam_batch FOREIGN KEY(batch_id) REFERENCES history_import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE legacy_exam_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  exam_id BIGINT NOT NULL,
  batch_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  employee_no_snapshot VARCHAR(64) NOT NULL,
  employee_name_snapshot VARCHAR(64) NOT NULL,
  result_status VARCHAR(20) NOT NULL,
  score DECIMAL(7,2),
  objective_score DECIMAL(7,2),
  subjective_score DECIMAL(7,2),
  attempt_no INT NOT NULL DEFAULT 1,
  taken_at DATETIME,
  remark VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_legacy_exam_result_version(exam_id,employee_id,attempt_no,batch_id),
  KEY idx_legacy_exam_result_active(employee_id,active),
  CONSTRAINT fk_legacy_result_exam FOREIGN KEY(exam_id) REFERENCES legacy_exam(id),
  CONSTRAINT fk_legacy_result_batch FOREIGN KEY(batch_id) REFERENCES history_import_batch(id),
  CONSTRAINT fk_legacy_result_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE legacy_evaluation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  employee_no_snapshot VARCHAR(64) NOT NULL,
  summary_type VARCHAR(10) NOT NULL,
  period_key VARCHAR(10) NOT NULL,
  original_final_score DECIMAL(7,2) NOT NULL,
  source_system VARCHAR(128) NOT NULL,
  remark VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_legacy_evaluation_version(employee_id,summary_type,period_key,batch_id),
  KEY idx_legacy_evaluation_active(employee_id,summary_type,period_key,active),
  CONSTRAINT fk_legacy_evaluation_batch FOREIGN KEY(batch_id) REFERENCES history_import_batch(id),
  CONSTRAINT fk_legacy_evaluation_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE legacy_evaluation_component (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  evaluation_id BIGINT NOT NULL,
  component_code VARCHAR(20) NOT NULL,
  source_key VARCHAR(128),
  source_name VARCHAR(255),
  raw_score DECIMAL(7,2),
  max_score DECIMAL(7,2),
  weight DECIMAL(7,2),
  weighted_score DECIMAL(7,2),
  evaluator_name VARCHAR(64),
  comment VARCHAR(1000),
  snapshot_json JSON,
  CONSTRAINT fk_legacy_component_evaluation FOREIGN KEY(evaluation_id) REFERENCES legacy_evaluation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE score_summary
  ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'ONLINE' AFTER scheme_id,
  ADD COLUMN source_batch_id BIGINT AFTER source_type,
  ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT FALSE AFTER source_batch_id,
  ADD KEY idx_score_summary_source_batch(source_type,source_batch_id);
