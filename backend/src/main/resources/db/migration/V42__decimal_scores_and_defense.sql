ALTER TABLE task_submission_review MODIFY score DECIMAL(7,2) NULL;
ALTER TABLE task_submission MODIFY score DECIMAL(7,2) NULL;
ALTER TABLE task_assignment MODIFY final_score DECIMAL(7,2) NULL;
ALTER TABLE evaluation_template
  ADD defense_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD defense_weight DECIMAL(5,2) NOT NULL DEFAULT 0,
  ADD defense_max_score DECIMAL(7,2) NOT NULL DEFAULT 100;
ALTER TABLE score_scheme
  ADD defense_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD defense_weight DECIMAL(5,2) NOT NULL DEFAULT 0,
  ADD defense_max_score DECIMAL(7,2) NOT NULL DEFAULT 100;
ALTER TABLE score_summary ADD defense_score DECIMAL(7,2) NULL;

CREATE TABLE defense_import_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  talent_batch_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_defense_import(talent_batch_id,period_month,file_sha256),
  FOREIGN KEY(talent_batch_id) REFERENCES talent_batch(id)
);
CREATE TABLE defense_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NULL,
  employee_no VARCHAR(64) NOT NULL,
  employee_name VARCHAR(128),
  period_month DATE NOT NULL,
  score DECIMAL(7,2),
  full_score DECIMAL(7,2) NOT NULL DEFAULT 100,
  comment VARCHAR(1000),
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 1,
  revision_of BIGINT NULL,
  import_batch_id BIGINT NULL,
  row_no INT NULL,
  raw_score VARCHAR(64),
  error_message VARCHAR(1000),
  created_by BIGINT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  published_at DATETIME NULL,
  current_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_defense_current(employee_id,period_month,current_slot),
  KEY idx_defense_month(period_month,status),
  FOREIGN KEY(employee_id) REFERENCES employee(id),
  FOREIGN KEY(import_batch_id) REFERENCES defense_import_batch(id)
);
