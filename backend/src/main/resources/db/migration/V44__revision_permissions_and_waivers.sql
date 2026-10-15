ALTER TABLE score_summary ADD revision_override_score DECIMAL(5,2) NULL;
CREATE TABLE evaluation_revision_permission (
  group_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  component_type VARCHAR(20) NOT NULL,
  scope_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  PRIMARY KEY(group_id,employee_id,period_month,component_type,scope_id,reviewer_id),
  FOREIGN KEY(group_id) REFERENCES evaluation_revision_group(id)
);

-- Keep content and snapshots intact; only the latest published version is current.
UPDATE score_summary old
JOIN score_summary newer ON newer.employee_id=old.employee_id
  AND newer.summary_type=old.summary_type AND newer.period_key=old.period_key
  AND newer.status='PUBLISHED' AND newer.version>old.version
SET old.status='SUPERSEDED' WHERE old.status='PUBLISHED';
ALTER TABLE score_summary
  ADD current_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_current_summary(employee_id,summary_type,period_key,current_slot);

ALTER TABLE exam_attempt ADD opportunity_id BIGINT NULL,
  ADD UNIQUE KEY uk_exam_opportunity(opportunity_id),
  ADD FOREIGN KEY(opportunity_id) REFERENCES submission_opportunity(id);
ALTER TABLE task_submission ADD opportunity_id BIGINT NULL,
  ADD UNIQUE KEY uk_task_opportunity(opportunity_id),
  ADD FOREIGN KEY(opportunity_id) REFERENCES submission_opportunity(id);
