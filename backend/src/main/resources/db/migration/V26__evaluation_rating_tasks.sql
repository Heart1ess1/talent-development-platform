CREATE TABLE evaluation_rating_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  component_type VARCHAR(20) NOT NULL,
  scope_id BIGINT NOT NULL DEFAULT 0,
  due_at DATETIME,
  note VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rating_task(employee_id, period_month, component_type, scope_id),
  KEY idx_rating_task_month(period_month, component_type, status),
  CONSTRAINT fk_ert_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_ert_created_by FOREIGN KEY(created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_ert_updated_by FOREIGN KEY(updated_by) REFERENCES sys_user(id)
);

CREATE TABLE evaluation_rating_reviewer (
  task_id BIGINT NOT NULL,
  reviewer_user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  assigned_by BIGINT NOT NULL,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  removed_at DATETIME,
  PRIMARY KEY(task_id, reviewer_user_id),
  KEY idx_rating_reviewer_user(reviewer_user_id, status),
  CONSTRAINT fk_err_task FOREIGN KEY(task_id) REFERENCES evaluation_rating_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_err_reviewer FOREIGN KEY(reviewer_user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_err_assigned_by FOREIGN KEY(assigned_by) REFERENCES sys_user(id)
);

INSERT INTO evaluation_rating_task(
  employee_id, period_month, component_type, scope_id, status, created_by, updated_by
)
SELECT m.employee_id, m.period_month, m.evaluator_type, m.scope_id,
  CASE WHEN EXISTS(
    SELECT 1 FROM score_summary s
    WHERE s.employee_id=m.employee_id
      AND s.summary_type='MONTH'
      AND s.period_key=DATE_FORMAT(m.period_month,'%Y-%m')
      AND s.status='PUBLISHED'
  ) THEN 'CLOSED' ELSE 'ACTIVE' END,
  MIN(m.evaluator_user_id), MIN(m.evaluator_user_id)
FROM monthly_evaluation m
WHERE m.evaluator_type IN ('MENTOR','STATION','TRAINING')
GROUP BY m.employee_id,m.period_month,m.evaluator_type,m.scope_id;

INSERT INTO evaluation_rating_reviewer(task_id, reviewer_user_id, status, assigned_by, assigned_at)
SELECT t.id,m.evaluator_user_id,'ACTIVE',m.evaluator_user_id,MIN(m.submitted_at)
FROM evaluation_rating_task t
JOIN monthly_evaluation m
  ON m.employee_id=t.employee_id
 AND m.period_month=t.period_month
 AND m.evaluator_type=t.component_type
 AND m.scope_id=t.scope_id
GROUP BY t.id,m.evaluator_user_id;
