CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(64) NOT NULL, role VARCHAR(32) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE talent_batch (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE service_station (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(128) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE employee (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT UNIQUE, employee_no VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(64) NOT NULL,
  batch_id BIGINT, station_id BIGINT, mentor_user_id BIGINT, school VARCHAR(128), major VARCHAR(128), education VARCHAR(32),
  birth_date DATE, native_place VARCHAR(128), residence VARCHAR(128), phone VARCHAR(32), onboard_date DATE,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_employee_user FOREIGN KEY(user_id) REFERENCES sys_user(id), CONSTRAINT fk_employee_batch FOREIGN KEY(batch_id) REFERENCES talent_batch(id),
  CONSTRAINT fk_employee_station FOREIGN KEY(station_id) REFERENCES service_station(id), CONSTRAINT fk_employee_mentor FOREIGN KEY(mentor_user_id) REFERENCES sys_user(id)
);
CREATE TABLE course (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(128) NOT NULL, description TEXT, enabled BOOLEAN NOT NULL DEFAULT TRUE, created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE course_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, course_id BIGINT NOT NULL, title VARCHAR(128) NOT NULL, location VARCHAR(128), hours DECIMAL(5,1),
  starts_at DATETIME NOT NULL, ends_at DATETIME NOT NULL, checkin_starts_at DATETIME NOT NULL, checkin_ends_at DATETIME NOT NULL,
  checkin_code VARCHAR(12) NOT NULL UNIQUE, created_by BIGINT NOT NULL, CONSTRAINT fk_session_course FOREIGN KEY(course_id) REFERENCES course(id)
);
CREATE TABLE attendance (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, employee_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
  source VARCHAR(20) NOT NULL, checked_at DATETIME NOT NULL, operator_user_id BIGINT NOT NULL, remark VARCHAR(255),
  UNIQUE KEY uk_attendance(session_id, employee_id), CONSTRAINT fk_att_session FOREIGN KEY(session_id) REFERENCES course_session(id),
  CONSTRAINT fk_att_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
);
CREATE TABLE challenge_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(128) NOT NULL, description TEXT NOT NULL, requirements TEXT, deadline DATETIME NOT NULL,
  created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE task_assignment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL, employee_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'NOT_SUBMITTED',
  final_score INT, assigned_by BIGINT NOT NULL, assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_task_employee(task_id, employee_id), CONSTRAINT fk_assignment_task FOREIGN KEY(task_id) REFERENCES challenge_task(id),
  CONSTRAINT fk_assignment_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
);
CREATE TABLE task_submission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, assignment_id BIGINT NOT NULL, submission_version INT NOT NULL, content TEXT,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW', submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_by BIGINT, reviewed_at DATETIME, review_comment VARCHAR(1000), score INT,
  UNIQUE KEY uk_submission_version(assignment_id, submission_version), CONSTRAINT fk_submission_assignment FOREIGN KEY(assignment_id) REFERENCES task_assignment(id)
);
CREATE TABLE stored_file (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, submission_id BIGINT, original_name VARCHAR(255) NOT NULL, content_type VARCHAR(128), size BIGINT NOT NULL,
  storage_key VARCHAR(512) NOT NULL UNIQUE, uploader_user_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_file_submission FOREIGN KEY(submission_id) REFERENCES task_submission(id)
);
CREATE TABLE operation_log (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT, action VARCHAR(64) NOT NULL, target_type VARCHAR(64), target_id BIGINT, detail VARCHAR(1000), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO talent_batch(name) VALUES ('2026届');
