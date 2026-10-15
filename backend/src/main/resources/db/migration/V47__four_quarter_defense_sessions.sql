-- Preserve the interpretation of existing schemes and published snapshots.
ALTER TABLE evaluation_template ADD defense_scope VARCHAR(16) NOT NULL DEFAULT 'MONTH';
ALTER TABLE score_scheme ADD defense_scope VARCHAR(16) NOT NULL DEFAULT 'MONTH';
ALTER TABLE evaluation_template ALTER COLUMN defense_scope SET DEFAULT 'QUARTER';
ALTER TABLE score_scheme ALTER COLUMN defense_scope SET DEFAULT 'QUARTER';

CREATE TABLE defense_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  talent_batch_id BIGINT NOT NULL,
  stage_no TINYINT NOT NULL,
  period_key VARCHAR(7) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT ck_defense_stage CHECK (stage_no BETWEEN 1 AND 4),
  CONSTRAINT ck_defense_quarter CHECK (period_key REGEXP '^[0-9]{4}-Q[1-4]$'),
  UNIQUE KEY uk_defense_stage(talent_batch_id,stage_no),
  UNIQUE KEY uk_defense_quarter(talent_batch_id,period_key),
  FOREIGN KEY(talent_batch_id) REFERENCES talent_batch(id)
);
ALTER TABLE defense_result
  ADD session_id BIGINT NULL,
  ADD legacy_current_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN session_id IS NULL AND status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
  ADD FOREIGN KEY(session_id) REFERENCES defense_session(id);
ALTER TABLE defense_result ADD UNIQUE KEY uk_defense_legacy(employee_id,period_month,legacy_current_slot);
ALTER TABLE defense_result DROP INDEX uk_defense_current;
ALTER TABLE defense_result ADD UNIQUE KEY uk_defense_session_current(employee_id,session_id,current_slot);
ALTER TABLE defense_import_batch
  ADD session_id BIGINT NULL,
  ADD legacy_month DATE GENERATED ALWAYS AS (CASE WHEN session_id IS NULL THEN period_month ELSE NULL END) STORED,
  ADD FOREIGN KEY(session_id) REFERENCES defense_session(id);
ALTER TABLE defense_import_batch ADD UNIQUE KEY uk_defense_legacy_import(talent_batch_id,legacy_month,file_sha256);
ALTER TABLE defense_import_batch DROP INDEX uk_defense_import;
ALTER TABLE defense_import_batch ADD UNIQUE KEY uk_defense_session_import(session_id,file_sha256);
