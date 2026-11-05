CREATE TABLE defense_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  talent_batch_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  full_score DECIMAL(7,2) NOT NULL DEFAULT 100,
  defense_date DATE NULL,
  note VARCHAR(1000),
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_defense_task_month(period_month,status),
  FOREIGN KEY(talent_batch_id) REFERENCES talent_batch(id),
  FOREIGN KEY(created_by) REFERENCES sys_user(id)
);

CREATE TABLE defense_task_participant (
  task_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  period_month DATE NULL,
  active_flag TINYINT NOT NULL DEFAULT 1,
  active_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN active_flag=1 THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY(task_id,employee_id),
  FOREIGN KEY(task_id) REFERENCES defense_task(id),
  FOREIGN KEY(employee_id) REFERENCES employee(id)
);

ALTER TABLE defense_task_participant ADD UNIQUE KEY uk_defense_employee_month(employee_id,period_month,active_slot);

ALTER TABLE defense_result
  ADD COLUMN task_id BIGINT NULL,
  ADD COLUMN legacy_month_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN task_id IS NULL AND session_id IS NULL AND status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
  ADD FOREIGN KEY(task_id) REFERENCES defense_task(id);
ALTER TABLE defense_result DROP INDEX uk_defense_legacy;
ALTER TABLE defense_result ADD UNIQUE KEY uk_defense_legacy_month(employee_id,period_month,legacy_month_slot);
ALTER TABLE defense_result ADD UNIQUE KEY uk_defense_task_current(employee_id,task_id,current_slot);

ALTER TABLE defense_import_batch ADD COLUMN task_id BIGINT NULL,
  ADD COLUMN revision_mode BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN revision_reason VARCHAR(1000);
ALTER TABLE defense_import_batch ADD KEY idx_defense_import_talent_batch(talent_batch_id);
ALTER TABLE defense_import_batch DROP INDEX uk_defense_legacy_import;
ALTER TABLE defense_import_batch ADD COLUMN legacy_import_month DATE GENERATED ALWAYS AS
  (CASE WHEN task_id IS NULL AND session_id IS NULL THEN period_month ELSE NULL END) STORED;
ALTER TABLE defense_import_batch ADD UNIQUE KEY uk_defense_legacy_only_import(talent_batch_id,legacy_import_month,file_sha256);
ALTER TABLE defense_result ADD COLUMN revision_reason VARCHAR(1000);
ALTER TABLE score_scheme ADD COLUMN monthly_rule_version VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE score_scheme ALTER COLUMN monthly_rule_version SET DEFAULT 'TASK_MONTH_V1';
ALTER TABLE defense_import_batch ADD FOREIGN KEY(task_id) REFERENCES defense_task(id);
ALTER TABLE defense_import_batch ADD UNIQUE KEY uk_defense_task_import(task_id,file_sha256);

-- Newly edited templates and plans use a single monthly weight pool. Historical rows stay intact.
UPDATE evaluation_template SET defense_enabled=FALSE,defense_weight=0,defense_scope='MONTH';
UPDATE score_scheme SET defense_enabled=FALSE,defense_weight=0,defense_scope='MONTH',monthly_rule_version='TASK_MONTH_V1'
  WHERE status='DRAFT';
ALTER TABLE evaluation_template ALTER COLUMN defense_scope SET DEFAULT 'MONTH';
ALTER TABLE score_scheme ALTER COLUMN defense_scope SET DEFAULT 'MONTH';
