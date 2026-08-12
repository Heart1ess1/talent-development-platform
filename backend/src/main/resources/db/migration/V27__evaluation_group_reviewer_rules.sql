CREATE TABLE evaluation_reviewer_scope_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  period_month DATE NOT NULL,
  component_type VARCHAR(20) NOT NULL,
  target_type VARCHAR(20) NOT NULL,
  target_id BIGINT NOT NULL DEFAULT 0,
  due_at DATETIME,
  note VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_evaluation_scope_rule(period_month, component_type, target_type, target_id),
  KEY idx_evaluation_scope_rule_month(period_month, component_type, status),
  CONSTRAINT fk_esr_created_by FOREIGN KEY(created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_esr_updated_by FOREIGN KEY(updated_by) REFERENCES sys_user(id)
);

CREATE TABLE evaluation_reviewer_scope_member (
  rule_id BIGINT NOT NULL,
  reviewer_user_id BIGINT NOT NULL,
  PRIMARY KEY(rule_id, reviewer_user_id),
  CONSTRAINT fk_esm_rule FOREIGN KEY(rule_id) REFERENCES evaluation_reviewer_scope_rule(id) ON DELETE CASCADE,
  CONSTRAINT fk_esm_reviewer FOREIGN KEY(reviewer_user_id) REFERENCES sys_user(id)
);

ALTER TABLE evaluation_rating_reviewer
  ADD COLUMN assignment_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' AFTER assigned_by,
  ADD COLUMN scope_rule_id BIGINT NULL AFTER assignment_source,
  ADD KEY idx_rating_reviewer_rule(scope_rule_id),
  ADD CONSTRAINT fk_err_scope_rule FOREIGN KEY(scope_rule_id) REFERENCES evaluation_reviewer_scope_rule(id) ON DELETE SET NULL;
