UPDATE legacy_exam SET external_exam_key=CONCAT('LEGACY-',id);

ALTER TABLE history_import_batch
  DROP INDEX uk_history_import_file,
  DROP COLUMN source_system;

ALTER TABLE legacy_exam
  DROP INDEX uk_legacy_exam_version,
  DROP INDEX idx_legacy_exam_active,
  DROP COLUMN source_system,
  ADD UNIQUE KEY uk_legacy_exam_version(external_exam_key,version),
  ADD KEY idx_legacy_exam_active(external_exam_key,active);

ALTER TABLE legacy_evaluation
  DROP COLUMN source_system;
