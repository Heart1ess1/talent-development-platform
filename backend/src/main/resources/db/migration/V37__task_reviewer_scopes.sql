CREATE TABLE task_reviewer_scope (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  batch_id BIGINT NULL,
  batch_name VARCHAR(64) NULL,
  business_unit_id BIGINT NULL,
  business_unit_name VARCHAR(128) NULL,
  class_id BIGINT NULL,
  class_name VARCHAR(64) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_task_reviewer_scope_task(task_id, status),
  CONSTRAINT fk_task_reviewer_scope_task FOREIGN KEY(task_id) REFERENCES challenge_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_task_reviewer_scope_created_by FOREIGN KEY(created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_task_reviewer_scope_updated_by FOREIGN KEY(updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_reviewer_scope_member (
  scope_id BIGINT NOT NULL,
  reviewer_user_id BIGINT NOT NULL,
  assigned_by BIGINT NOT NULL,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(scope_id, reviewer_user_id),
  KEY idx_task_reviewer_scope_member_user(reviewer_user_id, scope_id),
  CONSTRAINT fk_task_reviewer_scope_member_scope FOREIGN KEY(scope_id) REFERENCES task_reviewer_scope(id) ON DELETE CASCADE,
  CONSTRAINT fk_task_reviewer_scope_member_user FOREIGN KEY(reviewer_user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_task_reviewer_scope_member_assigned_by FOREIGN KEY(assigned_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE task_assignment
  ADD COLUMN batch_id_snapshot BIGINT NULL AFTER employee_id,
  ADD COLUMN batch_name_snapshot VARCHAR(64) NULL AFTER batch_id_snapshot,
  ADD COLUMN business_unit_id_snapshot BIGINT NULL AFTER batch_name_snapshot,
  ADD COLUMN business_unit_name_snapshot VARCHAR(128) NULL AFTER business_unit_id_snapshot,
  ADD COLUMN class_id_snapshot BIGINT NULL AFTER business_unit_name_snapshot,
  ADD COLUMN class_name_snapshot VARCHAR(64) NULL AFTER class_id_snapshot,
  ADD COLUMN scoring_scope_id BIGINT NULL AFTER class_name_snapshot,
  ADD KEY idx_task_assignment_scoring_scope(scoring_scope_id);

ALTER TABLE task_submission_review
  ADD COLUMN scoring_scope_id BIGINT NULL AFTER submission_id,
  ADD KEY idx_task_submission_review_scope(scoring_scope_id, status);

UPDATE task_assignment a
JOIN employee e ON e.id=a.employee_id
LEFT JOIN talent_batch b ON b.id=e.batch_id
LEFT JOIN business_unit bu ON bu.id=e.business_unit_id
LEFT JOIN dictionary_item cls ON cls.id=e.class_id AND cls.type_code='CLASS'
SET a.batch_id_snapshot=e.batch_id,
    a.batch_name_snapshot=b.name,
    a.business_unit_id_snapshot=e.business_unit_id,
    a.business_unit_name_snapshot=bu.name,
    a.class_id_snapshot=e.class_id,
    a.class_name_snapshot=cls.label;

INSERT INTO task_reviewer_scope(task_id,status,created_by,updated_by)
SELECT tr.task_id,'ACTIVE',MIN(tr.assigned_by),MIN(tr.assigned_by)
FROM task_reviewer tr
GROUP BY tr.task_id;

INSERT INTO task_reviewer_scope_member(scope_id,reviewer_user_id,assigned_by,assigned_at)
SELECT rs.id,tr.reviewer_user_id,tr.assigned_by,tr.assigned_at
FROM task_reviewer tr
JOIN task_reviewer_scope rs ON rs.task_id=tr.task_id
  AND rs.batch_id IS NULL AND rs.business_unit_id IS NULL AND rs.class_id IS NULL
  AND rs.status='ACTIVE';

UPDATE task_assignment a
JOIN task_reviewer_scope rs ON rs.task_id=a.task_id AND rs.status='ACTIVE'
SET a.scoring_scope_id=rs.id
WHERE rs.batch_id IS NULL AND rs.business_unit_id IS NULL AND rs.class_id IS NULL;

UPDATE task_submission_review r
JOIN task_submission s ON s.id=r.submission_id
JOIN task_assignment a ON a.id=s.assignment_id
SET r.scoring_scope_id=a.scoring_scope_id;

ALTER TABLE task_assignment
  ADD CONSTRAINT fk_task_assignment_scoring_scope FOREIGN KEY(scoring_scope_id) REFERENCES task_reviewer_scope(id);

ALTER TABLE task_submission_review
  ADD CONSTRAINT fk_task_submission_review_scope FOREIGN KEY(scoring_scope_id) REFERENCES task_reviewer_scope(id);
