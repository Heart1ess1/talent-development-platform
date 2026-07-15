CREATE TABLE training_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description TEXT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_training_plan_creator FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  description TEXT NOT NULL,
  requirements TEXT,
  deadline_offset_days INT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_task_template_creator FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE training_plan_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  sort_order INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_training_plan_template(plan_id, template_id),
  UNIQUE KEY uk_training_plan_sort(plan_id, sort_order),
  CONSTRAINT fk_tpt_plan FOREIGN KEY(plan_id) REFERENCES training_plan(id),
  CONSTRAINT fk_tpt_template FOREIGN KEY(template_id) REFERENCES task_template(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE challenge_task
  ADD COLUMN training_plan_id BIGINT NULL AFTER created_by,
  ADD COLUMN task_template_id BIGINT NULL AFTER training_plan_id,
  ADD COLUMN source_base_date DATE NULL AFTER task_template_id,
  ADD CONSTRAINT fk_challenge_task_training_plan FOREIGN KEY(training_plan_id) REFERENCES training_plan(id),
  ADD CONSTRAINT fk_challenge_task_template FOREIGN KEY(task_template_id) REFERENCES task_template(id),
  ADD UNIQUE KEY uk_challenge_task_source(training_plan_id, task_template_id, source_base_date);

CREATE INDEX idx_training_plan_enabled ON training_plan(enabled);
CREATE INDEX idx_task_template_enabled ON task_template(enabled);
