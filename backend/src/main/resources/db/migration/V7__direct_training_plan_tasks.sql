ALTER TABLE training_plan_task
  ADD COLUMN title VARCHAR(128) NULL AFTER plan_id,
  ADD COLUMN description TEXT NULL AFTER title,
  ADD COLUMN requirements TEXT NULL AFTER description;

UPDATE training_plan_task tpt
JOIN task_template tt ON tt.id=tpt.template_id
SET tpt.title=tt.title,tpt.description=tt.description,tpt.requirements=tt.requirements;

ALTER TABLE challenge_task
  ADD COLUMN training_plan_task_id BIGINT NULL AFTER training_plan_id;

UPDATE challenge_task ct
JOIN training_plan_task tpt ON tpt.plan_id=ct.training_plan_id AND tpt.template_id=ct.task_template_id
SET ct.training_plan_task_id=tpt.id
WHERE ct.task_template_id IS NOT NULL;

ALTER TABLE training_plan_task
  MODIFY COLUMN title VARCHAR(128) NOT NULL,
  MODIFY COLUMN description TEXT NOT NULL,
  DROP FOREIGN KEY fk_tpt_template,
  DROP INDEX uk_training_plan_template,
  DROP COLUMN template_id;

ALTER TABLE challenge_task DROP FOREIGN KEY fk_challenge_task_template;
ALTER TABLE challenge_task ADD KEY idx_challenge_task_training_plan(training_plan_id);
ALTER TABLE challenge_task DROP INDEX uk_challenge_task_source;
ALTER TABLE challenge_task DROP COLUMN task_template_id;
ALTER TABLE challenge_task ADD CONSTRAINT fk_challenge_task_training_plan_task FOREIGN KEY(training_plan_task_id) REFERENCES training_plan_task(id);
ALTER TABLE challenge_task ADD UNIQUE KEY uk_challenge_task_source(training_plan_task_id, source_base_date);
