ALTER TABLE history_import_batch
  ADD COLUMN exam_name VARCHAR(255) NULL AFTER file_sha256,
  ADD COLUMN exam_date DATE NULL AFTER exam_name,
  ADD COLUMN score_month DATE NULL AFTER exam_date,
  ADD COLUMN summary_type VARCHAR(10) NULL AFTER score_month,
  ADD COLUMN period_key VARCHAR(10) NULL AFTER summary_type,
  ADD COLUMN remark VARCHAR(1000) NULL AFTER period_key,
  ADD COLUMN generated_exam_key VARCHAR(128) NULL AFTER remark,
  ADD COLUMN revision_of_batch_id BIGINT NULL AFTER generated_exam_key,
  ADD COLUMN superseded_by_batch_id BIGINT NULL AFTER revision_of_batch_id,
  ADD KEY idx_history_import_type_status(import_type,status),
  ADD KEY idx_history_import_exam_date(exam_date),
  ADD UNIQUE KEY uk_history_import_generated_exam_key(generated_exam_key);

UPDATE history_import_batch
SET generated_exam_key=CONCAT('LEGACY-',id)
WHERE generated_exam_key IS NULL;

ALTER TABLE history_import_batch
  ADD CONSTRAINT fk_history_batch_revision FOREIGN KEY(revision_of_batch_id) REFERENCES history_import_batch(id),
  ADD CONSTRAINT fk_history_batch_superseded FOREIGN KEY(superseded_by_batch_id) REFERENCES history_import_batch(id);
