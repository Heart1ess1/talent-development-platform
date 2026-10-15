-- The original source date is an immutable dispatch snapshot, unlike deadline.
UPDATE challenge_task
SET dispatch_key=CONCAT('legacy:',training_plan_id,':',DATE_FORMAT(source_base_date,'%Y-%m-%d'))
WHERE dispatch_key IS NULL AND training_plan_task_id IS NOT NULL
  AND training_plan_id IS NOT NULL AND source_base_date IS NOT NULL;
