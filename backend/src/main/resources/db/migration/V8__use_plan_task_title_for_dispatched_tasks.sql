UPDATE challenge_task ct
JOIN training_plan_task tpt ON tpt.id = ct.training_plan_task_id
SET ct.title = tpt.title
WHERE ct.training_plan_task_id IS NOT NULL
  AND ct.title <> tpt.title;
