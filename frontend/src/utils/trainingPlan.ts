export type TrainingPlan = {
  id: number
  name: string
  description?: string | null
  enabled: boolean | number
  task_count: number
  dispatched_task_count: number
  creator_name?: string
  created_at?: string
  updated_at?: string
}

export type TrainingPlanTask = {
  id: number
  plan_id: number
  title: string
  description: string
  requirements?: string | null
  sort_order: number
  dispatched_count: number
  attachments?: TaskAttachment[]
  created_at?: string
}

export type TaskAttachment = {
  id: number
  original_name: string
  content_type?: string | null
  size: number
  uploader_name?: string
  created_at?: string
}

export function isPlanEnabled(plan: Pick<TrainingPlan, 'enabled'>) {
  return plan.enabled === true || plan.enabled === 1
}

export function planStatus(plan: Pick<TrainingPlan, 'enabled' | 'task_count'>) {
  if (isPlanEnabled(plan)) {
    return Number(plan.task_count || 0) > 0
      ? {key: 'ACTIVE', label: '已启用', type: 'success' as const}
      : {key: 'INCOMPLETE', label: '待完善', type: 'warning' as const}
  }
  return Number(plan.task_count || 0) > 0
    ? {key: 'DISABLED', label: '已停用', type: 'info' as const}
    : {key: 'DRAFT', label: '草稿', type: 'info' as const}
}

export function formatPlanDate(value?: string | null) {
  if (!value) return '-'
  return value.substring(0, 16).replace('T', ' ')
}
