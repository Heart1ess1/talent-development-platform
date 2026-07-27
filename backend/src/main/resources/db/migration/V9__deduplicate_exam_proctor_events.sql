ALTER TABLE exam_proctor_event
  ADD COLUMN violation_key VARCHAR(64) AFTER detail;

CREATE UNIQUE INDEX uk_proctor_event_attempt_violation
  ON exam_proctor_event(attempt_id, violation_key);
