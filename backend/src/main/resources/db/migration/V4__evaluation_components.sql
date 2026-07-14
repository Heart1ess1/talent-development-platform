ALTER TABLE score_scheme
  ADD COLUMN effective_month DATE NOT NULL DEFAULT '2000-01-01' AFTER status,
  ADD COLUMN exam_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER effective_month,
  ADD COLUMN task_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER exam_weight,
  ADD COLUMN mentor_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER task_weight,
  ADD COLUMN station_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER mentor_weight,
  ADD COLUMN training_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER station_weight,
  ADD COLUMN quarter_month1_weight DECIMAL(5,2) NOT NULL DEFAULT 33.33 AFTER training_weight,
  ADD COLUMN quarter_month2_weight DECIMAL(5,2) NOT NULL DEFAULT 33.33 AFTER quarter_month1_weight,
  ADD COLUMN quarter_month3_weight DECIMAL(5,2) NOT NULL DEFAULT 33.34 AFTER quarter_month2_weight;

UPDATE score_scheme SET
  exam_enabled = exam_weight > 0,
  task_enabled = task_weight > 0,
  mentor_enabled = mentor_weight > 0,
  station_enabled = station_weight > 0,
  training_enabled = training_weight > 0;

CREATE TABLE score_component_override (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  component_type VARCHAR(20) NOT NULL,
  original_score DECIMAL(5,2),
  override_score DECIMAL(5,2) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_component_override(employee_id, period_month, component_type),
  CONSTRAINT fk_sco_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_sco_user FOREIGN KEY(created_by) REFERENCES sys_user(id)
);

ALTER TABLE score_summary
  ADD COLUMN component_snapshot JSON AFTER deduction,
  ADD COLUMN quarter_snapshot JSON AFTER component_snapshot;

UPDATE score_summary s
LEFT JOIN score_scheme sc ON sc.id = s.scheme_id
SET s.component_snapshot = JSON_OBJECT(
  'legacy', TRUE,
  'components', JSON_ARRAY(
    JSON_OBJECT('code','EXAM','enabled',COALESCE(sc.exam_enabled,TRUE),'weight',sc.exam_weight,'effectiveScore',s.exam_score),
    JSON_OBJECT('code','TASK','enabled',COALESCE(sc.task_enabled,TRUE),'weight',sc.task_weight,'effectiveScore',s.task_score),
    JSON_OBJECT('code','MENTOR','enabled',COALESCE(sc.mentor_enabled,TRUE),'weight',sc.mentor_weight,'effectiveScore',s.mentor_score),
    JSON_OBJECT('code','STATION','enabled',COALESCE(sc.station_enabled,TRUE),'weight',sc.station_weight,'effectiveScore',s.station_score),
    JSON_OBJECT('code','TRAINING','enabled',COALESCE(sc.training_enabled,TRUE),'weight',sc.training_weight,'effectiveScore',s.training_score)
  )
)
WHERE s.summary_type = 'MONTH' AND s.component_snapshot IS NULL;

UPDATE score_summary
SET quarter_snapshot = JSON_OBJECT(
  'legacy', TRUE,
  'weights', JSON_ARRAY(33.33,33.33,33.34)
)
WHERE summary_type = 'QUARTER' AND quarter_snapshot IS NULL;
