export type TaskNavigationRow = {
  id?: unknown
  task_id?: unknown
}

function positiveId(value: unknown): number | null {
  const id = typeof value === 'number' ? value : Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

/** The manager task list uses challenge_task.id as its row id. */
export function taskIdForManagedTask(row: TaskNavigationRow): number | null {
  return positiveId(row.id)
}

/** The employee assignment list must use task_assignment.task_id for task APIs. */
export function taskIdForEmployeeAssignment(row: TaskNavigationRow): number | null {
  return positiveId(row.task_id)
}
