ALTER TABLE evaluation_template
  ADD COLUMN station_aggregation_mode VARCHAR(24) NOT NULL DEFAULT 'AUTO_BY_DAYS' AFTER station_max_score;

ALTER TABLE score_scheme
  ADD COLUMN station_aggregation_mode VARCHAR(24) NOT NULL DEFAULT 'AUTO_BY_DAYS' AFTER station_max_score;

CREATE TABLE evaluation_template_source_weight (
  template_id BIGINT NOT NULL,
  component_type VARCHAR(20) NOT NULL,
  source_id BIGINT NOT NULL,
  weight DECIMAL(5,2) NOT NULL,
  PRIMARY KEY(template_id, component_type, source_id),
  CONSTRAINT fk_etsw_template FOREIGN KEY(template_id) REFERENCES evaluation_template(id) ON DELETE CASCADE
);

CREATE TABLE score_scheme_source_weight (
  scheme_id BIGINT NOT NULL,
  component_type VARCHAR(20) NOT NULL,
  source_id BIGINT NOT NULL,
  weight DECIMAL(5,2) NOT NULL,
  PRIMARY KEY(scheme_id, component_type, source_id),
  CONSTRAINT fk_sssw_scheme FOREIGN KEY(scheme_id) REFERENCES score_scheme(id) ON DELETE CASCADE
);

ALTER TABLE monthly_evaluation
  ADD COLUMN scope_id BIGINT NOT NULL DEFAULT 0 AFTER evaluator_user_id,
  ADD INDEX idx_monthly_employee(employee_id);

UPDATE monthly_evaluation m
JOIN employee e ON e.id=m.employee_id
SET m.scope_id=COALESCE(e.station_id,0)
WHERE m.evaluator_type='STATION';

ALTER TABLE monthly_evaluation
  DROP INDEX uk_monthly_evaluation,
  ADD UNIQUE KEY uk_monthly_evaluator(employee_id,period_month,evaluator_type,evaluator_user_id,scope_id),
  ADD INDEX idx_monthly_component(employee_id,period_month,evaluator_type,scope_id);

CREATE TABLE monthly_station_weight (
  employee_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  station_id BIGINT NOT NULL,
  weight DECIMAL(5,2) NOT NULL,
  updated_by BIGINT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(employee_id,period_month,station_id),
  CONSTRAINT fk_msw_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_msw_station FOREIGN KEY(station_id) REFERENCES service_station(id),
  CONSTRAINT fk_msw_user FOREIGN KEY(updated_by) REFERENCES sys_user(id)
);
