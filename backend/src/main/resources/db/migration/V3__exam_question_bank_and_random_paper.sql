ALTER TABLE exam_paper
  ADD COLUMN assembly_mode VARCHAR(10) NOT NULL DEFAULT 'MANUAL' AFTER description;

CREATE INDEX idx_question_bank_type_enabled ON question_bank(question_type, enabled);
