ALTER TABLE exam_plan
  ADD COLUMN violation_limit INT NOT NULL DEFAULT 4 AFTER max_attempts,
  ADD COLUMN violation_grace_seconds INT NOT NULL DEFAULT 15 AFTER violation_limit;

ALTER TABLE exam_attempt
  ADD COLUMN active_violation_key VARCHAR(64) NULL AFTER client_context,
  ADD COLUMN violation_deadline_at DATETIME(3) NULL AFTER active_violation_key;

CREATE INDEX idx_exam_attempt_violation_deadline
  ON exam_attempt(status, violation_deadline_at);
