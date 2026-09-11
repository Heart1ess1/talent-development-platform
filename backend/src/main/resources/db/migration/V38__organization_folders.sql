CREATE TABLE training_plan_folder (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL UNIQUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE question_bank_folder (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL UNIQUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE training_plan ADD COLUMN folder_id BIGINT NULL,
  ADD CONSTRAINT fk_training_plan_folder FOREIGN KEY (folder_id) REFERENCES training_plan_folder(id) ON DELETE SET NULL;
ALTER TABLE exam_question_bank ADD COLUMN folder_id BIGINT NULL,
  ADD CONSTRAINT fk_question_bank_folder FOREIGN KEY (folder_id) REFERENCES question_bank_folder(id) ON DELETE SET NULL;
