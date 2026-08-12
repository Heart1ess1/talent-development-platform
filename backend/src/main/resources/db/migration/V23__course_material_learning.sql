CREATE TABLE course_material_view_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  material_id BIGINT NOT NULL,
  employee_id BIGINT,
  user_id BIGINT NOT NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at DATETIME,
  duration_seconds INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_cmvs_material FOREIGN KEY(material_id) REFERENCES course_material(id) ON DELETE CASCADE,
  CONSTRAINT fk_cmvs_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
  CONSTRAINT fk_cmvs_user FOREIGN KEY(user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_cmvs_material_employee ON course_material_view_session(material_id, employee_id);
CREATE INDEX idx_cmvs_user_active ON course_material_view_session(user_id, ended_at, last_seen_at);
