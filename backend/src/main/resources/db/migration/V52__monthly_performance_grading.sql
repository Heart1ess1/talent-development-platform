CREATE TABLE monthly_performance_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  talent_batch_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  version INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  ratio_a DECIMAL(5,2) NOT NULL,
  ratio_b DECIMAL(5,2) NOT NULL,
  ratio_c DECIMAL(5,2) NOT NULL,
  generation INT NOT NULL DEFAULT 1,
  participant_count INT NOT NULL DEFAULT 0,
  distribution_snapshot JSON,
  reopen_reason VARCHAR(1000),
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME,
  current_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
  draft_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='DRAFT' THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_performance_version(talent_batch_id,period_month,version),
  UNIQUE KEY uk_performance_current(talent_batch_id,period_month,current_slot),
  UNIQUE KEY uk_performance_draft(talent_batch_id,period_month,draft_slot),
  FOREIGN KEY(talent_batch_id) REFERENCES talent_batch(id),
  FOREIGN KEY(created_by) REFERENCES sys_user(id)
);

-- An employee remains in their frozen grading cohort even if their current batch changes.
CREATE TABLE monthly_performance_scope (
  employee_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  talent_batch_id BIGINT NOT NULL,
  PRIMARY KEY(employee_id,period_month),
  FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE CASCADE,
  FOREIGN KEY(talent_batch_id) REFERENCES talent_batch(id)
);

CREATE TABLE monthly_performance_member (
  group_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  employee_no VARCHAR(64) NOT NULL,
  employee_name VARCHAR(128) NOT NULL,
  participating BOOLEAN NOT NULL DEFAULT TRUE,
  exclusion_reason VARCHAR(1000),
  summary_id BIGINT,
  summary_version INT,
  final_score DECIMAL(7,2),
  rank_no INT,
  grade CHAR(1),
  PRIMARY KEY(group_id,employee_id),
  KEY idx_performance_employee(employee_id,summary_id),
  FOREIGN KEY(group_id) REFERENCES monthly_performance_group(id),
  FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE CASCADE,
  FOREIGN KEY(summary_id) REFERENCES score_summary(id) ON DELETE SET NULL
);
