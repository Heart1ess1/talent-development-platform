ALTER TABLE challenge_task
  ADD starts_at DATETIME NULL,
  ADD schedule_mode VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE',
  ADD schedule_version INT NOT NULL DEFAULT 0,
  ADD dispatch_key VARCHAR(64) NULL;
UPDATE challenge_task t SET starts_at=COALESCE((SELECT MIN(a.assigned_at) FROM task_assignment a WHERE a.task_id=t.id),t.created_at);
ALTER TABLE challenge_task ADD UNIQUE KEY uk_task_dispatch(training_plan_task_id,dispatch_key);
ALTER TABLE challenge_task DROP INDEX uk_challenge_task_source;
ALTER TABLE task_assignment
  ADD window_starts_at DATETIME NULL,
  ADD window_ends_at DATETIME NULL,
  ADD score_month DATE NULL;
UPDATE task_assignment a JOIN challenge_task t ON t.id=a.task_id SET a.score_month=DATE_FORMAT(t.deadline,'%Y-%m-01');
ALTER TABLE exam_plan
  ADD schedule_mode VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
  ADD schedule_version INT NOT NULL DEFAULT 0,
  ADD base_date DATE NULL,
  ADD offset_days INT NULL;
ALTER TABLE exam_assignment
  ADD window_starts_at DATETIME NULL,
  ADD window_ends_at DATETIME NULL,
  ADD score_month DATE NULL;
UPDATE exam_assignment a JOIN exam_plan p ON p.id=a.plan_id SET a.score_month=p.score_month;
ALTER TABLE evaluation_rating_task
  ADD starts_at DATETIME NULL,
  ADD schedule_mode VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE',
  ADD schedule_version INT NOT NULL DEFAULT 0;
UPDATE evaluation_rating_task SET starts_at=created_at;
CREATE TABLE submission_opportunity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resource_type VARCHAR(20) NOT NULL,
  resource_id BIGINT NOT NULL,
  participant_id BIGINT NOT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  reason VARCHAR(1000) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  consumed_at DATETIME NULL,
  active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_active_opportunity(resource_type,resource_id,participant_id,active_slot),
  KEY idx_opportunity(resource_type,resource_id,participant_id,status)
);
CREATE TABLE evaluation_revision_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  reason VARCHAR(1000) NOT NULL,
  generation INT NOT NULL DEFAULT 1,
  reviewed_generation INT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME NULL
);
CREATE TABLE evaluation_revision_member (
  group_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  period_key VARCHAR(10) NOT NULL,
  summary_type VARCHAR(20) NOT NULL,
  summary_id BIGINT NULL,
  error_message VARCHAR(1000),
  PRIMARY KEY(group_id,employee_id,summary_type,period_key),
  FOREIGN KEY(group_id) REFERENCES evaluation_revision_group(id)
);
ALTER TABLE score_summary ADD revision_group_id BIGINT NULL;
CREATE TABLE monthly_evaluation_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  evaluation_id BIGINT NOT NULL,
  score DECIMAL(7,2),
  comment VARCHAR(1000),
  submitted_at DATETIME,
  archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
