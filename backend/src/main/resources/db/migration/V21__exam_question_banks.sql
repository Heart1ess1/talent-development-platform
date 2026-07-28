CREATE TABLE exam_question_bank (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(500),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_exam_question_bank_name UNIQUE(name)
);

INSERT INTO exam_question_bank(name,description,enabled,created_by)
SELECT '默认题库','由系统升级自动归集的历史题目',TRUE,COALESCE(MIN(created_by),1)
FROM question_bank;

ALTER TABLE question_bank ADD COLUMN bank_id BIGINT NULL AFTER id;
UPDATE question_bank
SET bank_id=(SELECT id FROM exam_question_bank WHERE name='默认题库' LIMIT 1)
WHERE bank_id IS NULL;
ALTER TABLE question_bank MODIFY bank_id BIGINT NOT NULL;
ALTER TABLE question_bank
  ADD CONSTRAINT fk_question_bank_group FOREIGN KEY(bank_id) REFERENCES exam_question_bank(id);

ALTER TABLE exam_paper_random_rule ADD COLUMN bank_ids JSON NULL AFTER tags;
