ALTER TABLE sys_user ADD COLUMN security_version INT NOT NULL DEFAULT 0 AFTER version;

CREATE TABLE station_manager_scope (
  user_id BIGINT NOT NULL,
  station_id BIGINT NOT NULL,
  PRIMARY KEY(user_id, station_id),
  CONSTRAINT fk_sms_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_sms_station FOREIGN KEY(station_id) REFERENCES service_station(id)
);

CREATE TABLE course_enrollment (
  session_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  assigned_by BIGINT NOT NULL,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(session_id, employee_id),
  CONSTRAINT fk_ce_session FOREIGN KEY(session_id) REFERENCES course_session(id),
  CONSTRAINT fk_ce_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
);

ALTER TABLE operation_log
  ADD COLUMN request_id VARCHAR(64),
  ADD COLUMN before_value JSON,
  ADD COLUMN after_value JSON;

CREATE TABLE score_scheme (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  version INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  exam_weight DECIMAL(5,2) NOT NULL,
  task_weight DECIMAL(5,2) NOT NULL,
  mentor_weight DECIMAL(5,2) NOT NULL,
  station_weight DECIMAL(5,2) NOT NULL,
  training_weight DECIMAL(5,2) NOT NULL,
  bonus_cap DECIMAL(5,2) NOT NULL DEFAULT 10,
  deduction_cap DECIMAL(5,2) NOT NULL DEFAULT 10,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME,
  UNIQUE KEY uk_scheme_version(batch_id, version),
  CONSTRAINT fk_scheme_batch FOREIGN KEY(batch_id) REFERENCES talent_batch(id)
);

CREATE TABLE monthly_evaluation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  evaluator_type VARCHAR(20) NOT NULL,
  evaluator_user_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  comment VARCHAR(1000) NOT NULL,
  submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_monthly_evaluation(employee_id, period_month, evaluator_type),
  CONSTRAINT fk_me_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
);

CREATE TABLE score_adjustment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  adjustment_type VARCHAR(10) NOT NULL,
  points DECIMAL(5,2) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  evidence_file_id BIGINT,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_sa_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_sa_file FOREIGN KEY(evidence_file_id) REFERENCES stored_file(id)
);

CREATE TABLE score_summary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  summary_type VARCHAR(10) NOT NULL,
  period_key VARCHAR(10) NOT NULL,
  version INT NOT NULL,
  scheme_id BIGINT,
  exam_score DECIMAL(5,2), task_score DECIMAL(5,2), mentor_score DECIMAL(5,2),
  station_score DECIMAL(5,2), training_score DECIMAL(5,2), bonus DECIMAL(5,2), deduction DECIMAL(5,2),
  final_score DECIMAL(5,2), status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  missing_items VARCHAR(500), waiver_reason VARCHAR(1000), reopen_reason VARCHAR(1000),
  generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, published_at DATETIME,
  UNIQUE KEY uk_score_summary(employee_id, summary_type, period_key, version),
  CONSTRAINT fk_ss_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_ss_scheme FOREIGN KEY(scheme_id) REFERENCES score_scheme(id)
);

CREATE TABLE question_bank (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  question_type VARCHAR(20) NOT NULL,
  stem TEXT NOT NULL,
  options_json JSON,
  answer_json JSON,
  explanation TEXT,
  default_score DECIMAL(5,2) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exam_paper (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description TEXT,
  randomize_questions BOOLEAN NOT NULL DEFAULT TRUE,
  randomize_options BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exam_paper_question (
  paper_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY(paper_id, question_id),
  CONSTRAINT fk_epq_paper FOREIGN KEY(paper_id) REFERENCES exam_paper(id),
  CONSTRAINT fk_epq_question FOREIGN KEY(question_id) REFERENCES question_bank(id)
);

CREATE TABLE exam_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  paper_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  batch_id BIGINT,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  duration_minutes INT NOT NULL,
  max_attempts INT NOT NULL DEFAULT 1,
  score_month DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_plan_paper FOREIGN KEY(paper_id) REFERENCES exam_paper(id)
);

CREATE TABLE exam_assignment (
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  assigned_by BIGINT NOT NULL,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(plan_id, employee_id),
  CONSTRAINT fk_ea_plan FOREIGN KEY(plan_id) REFERENCES exam_plan(id),
  CONSTRAINT fk_ea_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
);

CREATE TABLE exam_attempt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deadline_at DATETIME NOT NULL,
  submitted_at DATETIME,
  objective_score DECIMAL(5,2), subjective_score DECIMAL(5,2), total_score DECIMAL(5,2),
  published BOOLEAN NOT NULL DEFAULT FALSE,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_attempt(plan_id, employee_id, attempt_no),
  CONSTRAINT fk_attempt_plan FOREIGN KEY(plan_id) REFERENCES exam_plan(id),
  CONSTRAINT fk_attempt_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
);

CREATE TABLE exam_answer (
  attempt_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  answer_json JSON,
  score DECIMAL(5,2),
  reviewer_comment VARCHAR(1000),
  reviewed_by BIGINT,
  saved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(attempt_id, question_id),
  CONSTRAINT fk_answer_attempt FOREIGN KEY(attempt_id) REFERENCES exam_attempt(id),
  CONSTRAINT fk_answer_question FOREIGN KEY(question_id) REFERENCES question_bank(id)
);

CREATE TABLE exam_proctor_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  attempt_id BIGINT NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  detail VARCHAR(500),
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_proctor_attempt FOREIGN KEY(attempt_id) REFERENCES exam_attempt(id)
);

