CREATE TABLE evaluation_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  exam_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  exam_weight DECIMAL(5,2) NOT NULL DEFAULT 20,
  exam_max_score DECIMAL(7,2) NOT NULL DEFAULT 100,
  task_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  task_weight DECIMAL(5,2) NOT NULL DEFAULT 30,
  task_max_score DECIMAL(7,2) NOT NULL DEFAULT 100,
  mentor_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  mentor_weight DECIMAL(5,2) NOT NULL DEFAULT 15,
  mentor_max_score DECIMAL(7,2) NOT NULL DEFAULT 100,
  station_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  station_weight DECIMAL(5,2) NOT NULL DEFAULT 15,
  station_max_score DECIMAL(7,2) NOT NULL DEFAULT 100,
  training_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  training_weight DECIMAL(5,2) NOT NULL DEFAULT 20,
  training_max_score DECIMAL(7,2) NOT NULL DEFAULT 100,
  quarter_month1_weight DECIMAL(5,2) NOT NULL DEFAULT 33.33,
  quarter_month2_weight DECIMAL(5,2) NOT NULL DEFAULT 33.33,
  quarter_month3_weight DECIMAL(5,2) NOT NULL DEFAULT 33.34,
  bonus_cap DECIMAL(5,2) NOT NULL DEFAULT 10,
  deduction_cap DECIMAL(5,2) NOT NULL DEFAULT 10,
  created_by BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_evaluation_template_creator FOREIGN KEY(created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_evaluation_template_updater FOREIGN KEY(updated_by) REFERENCES sys_user(id)
);

ALTER TABLE score_scheme
  ADD COLUMN template_id BIGINT AFTER batch_id,
  ADD COLUMN exam_max_score DECIMAL(7,2) NOT NULL DEFAULT 100 AFTER exam_weight,
  ADD COLUMN task_max_score DECIMAL(7,2) NOT NULL DEFAULT 100 AFTER task_weight,
  ADD COLUMN mentor_max_score DECIMAL(7,2) NOT NULL DEFAULT 100 AFTER mentor_weight,
  ADD COLUMN station_max_score DECIMAL(7,2) NOT NULL DEFAULT 100 AFTER station_weight,
  ADD COLUMN training_max_score DECIMAL(7,2) NOT NULL DEFAULT 100 AFTER training_weight,
  ADD CONSTRAINT fk_score_scheme_template FOREIGN KEY(template_id) REFERENCES evaluation_template(id);

CREATE INDEX idx_evaluation_template_status ON evaluation_template(status, updated_at);
CREATE INDEX idx_score_scheme_template ON score_scheme(template_id);
