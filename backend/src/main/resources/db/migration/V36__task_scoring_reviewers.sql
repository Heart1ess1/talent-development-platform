ALTER TABLE task_assignment
  MODIFY COLUMN final_score DECIMAL(5,1) NULL;

ALTER TABLE task_submission
  MODIFY COLUMN score DECIMAL(5,1) NULL;

CREATE TABLE task_reviewer (
  task_id BIGINT NOT NULL,
  reviewer_user_id BIGINT NOT NULL,
  assigned_by BIGINT NOT NULL,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(task_id, reviewer_user_id),
  KEY idx_task_reviewer_user(reviewer_user_id, task_id),
  CONSTRAINT fk_task_reviewer_task FOREIGN KEY(task_id) REFERENCES challenge_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_task_reviewer_user FOREIGN KEY(reviewer_user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_task_reviewer_assigned_by FOREIGN KEY(assigned_by) REFERENCES sys_user(id)
);

CREATE TABLE task_submission_review (
  submission_id BIGINT NOT NULL,
  reviewer_user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  decision VARCHAR(20),
  score INT,
  comment VARCHAR(1000),
  submitted_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(submission_id, reviewer_user_id),
  KEY idx_task_submission_review_user(reviewer_user_id, status),
  CONSTRAINT fk_task_submission_review_submission FOREIGN KEY(submission_id) REFERENCES task_submission(id) ON DELETE CASCADE,
  CONSTRAINT fk_task_submission_review_user FOREIGN KEY(reviewer_user_id) REFERENCES sys_user(id)
);
