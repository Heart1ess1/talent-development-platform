CREATE TABLE task_attachment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  training_plan_task_id BIGINT NULL,
  challenge_task_id BIGINT NULL,
  source_attachment_id BIGINT NULL,
  original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(128),
  size BIGINT NOT NULL,
  storage_key VARCHAR(512) NOT NULL,
  uploaded_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_task_attachment_plan_task
    FOREIGN KEY(training_plan_task_id) REFERENCES training_plan_task(id),
  CONSTRAINT fk_task_attachment_challenge_task
    FOREIGN KEY(challenge_task_id) REFERENCES challenge_task(id),
  CONSTRAINT fk_task_attachment_uploader
    FOREIGN KEY(uploaded_by) REFERENCES sys_user(id),
  CONSTRAINT chk_task_attachment_owner CHECK (
    (training_plan_task_id IS NOT NULL AND challenge_task_id IS NULL)
    OR
    (training_plan_task_id IS NULL AND challenge_task_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_attachment_plan_task
  ON task_attachment(training_plan_task_id, created_at);
CREATE INDEX idx_task_attachment_challenge_task
  ON task_attachment(challenge_task_id, created_at);
CREATE INDEX idx_task_attachment_storage_key
  ON task_attachment(storage_key);
CREATE UNIQUE INDEX uk_task_attachment_snapshot
  ON task_attachment(challenge_task_id, source_attachment_id);
