CREATE TABLE course_material (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  course_id BIGINT NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(128),
  size BIGINT NOT NULL,
  storage_key VARCHAR(512) NOT NULL,
  uploaded_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_course_material_course FOREIGN KEY(course_id) REFERENCES course(id),
  CONSTRAINT fk_course_material_uploader FOREIGN KEY(uploaded_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_course_material_course ON course_material(course_id, created_at);
