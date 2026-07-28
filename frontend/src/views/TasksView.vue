<script setup lang="ts">
import {computed, onMounted, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  Calendar,
  CircleCheck,
  Collection,
  Connection,
  Document,
  Download,
  FolderOpened,
  Plus,
  Refresh,
  RefreshLeft,
  Search,
  UserFilled,
  View
} from '@element-plus/icons-vue'
import {api, type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import TaskAttachmentsPanel from '@/components/TaskAttachmentsPanel.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const employee = computed(() => auth.user?.role === 'EMPLOYEE')
const canManage = computed(() => auth.can('task:manage'))
const canReview = computed(() => auth.can('task:review'))
const trackingMode = computed(() => !employee.value && (route.path === '/training-plans/tracking' || !canManage.value))

const tasks = ref<any[]>([])
const assignments = ref<any[]>([])
const pendingReviews = ref<any[]>([])
const batches = ref<any[]>([])
const businessUnits = ref<any[]>([])
const stations = ref<any[]>([])
const plans = ref<any[]>([])
const planTasks = ref<any[]>([])
const selectedPlanId = ref<number | null>(null)
const dialog = ref(false)
const submissionMode = ref<'SUBMIT' | 'RESUBMIT' | 'VIEW' | 'REVIEW'>('VIEW')
const selected = ref<any>()
const files = ref<any[]>([])
const manualFiles = ref<any[]>([])
const history = ref<any[]>([])
const previewDialog = ref(false)
const previewFile = ref<any>()
const previewType = ref<'PDF' | 'IMAGE' | 'TEXT' | 'HTML'>('TEXT')
const previewUrl = ref('')
const previewContent = ref('')
const previewLoading = ref(false)
const previewCandidates = ref<any[]>([])
const progressDialog = ref(false)
const exportingProgress = ref(false)
const exportingFiles = ref(false)
const downloadingAssignmentId = ref<number | null>(null)
const detailDialog = ref(false)
const selectedTask = ref<any>()
const detailTaskId = ref<number | null>(null)
const taskProgress = ref<any[]>([])
const progressFilters = reactive({keyword: '', status: ''})
const dispatchMode = ref<'PLAN' | 'MANUAL'>('PLAN')
const dispatching = ref(false)
const previewOpen = ref(false)
const dispatchPreviewLoading = ref(false)
const taskFilters = reactive({keyword: '', state: 'ALL'})
const dispatchPreview = reactive<any>({
  targetEmployees: 0,
  selectedTasks: 0,
  reusedTasks: 0,
  deadline: '',
  taskTitles: []
})

const manualDispatch = reactive<any>({title: '', description: '', requirements: '', deadline: '', batchId: null, businessUnitId: null, stationId: null})
const dispatch = reactive<any>({planTaskIds: [], taskTitle: '', deadlineMode: 'OFFSET', baseDate: new Date().toISOString().slice(0, 10), offsetDays: 7, deadlineDate: '', batchId: null, businessUnitId: null, stationId: null})
const submit = reactive({content: ''})
const review = reactive<any>({decision: 'APPROVE', comment: '', score: null})
const taskDetail = reactive<any>({title: '', description: '', requirements: '', deadline: '', attachments: []})

const selectedPlan = computed(() => plans.value.find(item => item.id === selectedPlanId.value))
const selectedPlanTasks = computed(() => planTasks.value.filter(item => dispatch.planTaskIds.includes(item.id)))
const hasDispatchTarget = computed(() => Boolean(dispatch.batchId || dispatch.businessUnitId || dispatch.stationId))
const hasManualTarget = computed(() => Boolean(manualDispatch.batchId || manualDispatch.businessUnitId || manualDispatch.stationId))
const planDispatchReady = computed(() => Boolean(
  selectedPlanId.value
  && dispatch.planTaskIds.length
  && hasDispatchTarget.value
  && (
    (dispatch.deadlineMode === 'OFFSET' && dispatch.baseDate && dispatch.offsetDays !== null)
    || (dispatch.deadlineMode === 'ABSOLUTE' && dispatch.deadlineDate)
  )
))
const manualDispatchReady = computed(() => Boolean(
  manualDispatch.title?.trim()
  && manualDispatch.description?.trim()
  && manualDispatch.deadline
  && hasManualTarget.value
))
const taskMetrics = computed(() => {
  const assigned = tasks.value.reduce((total, item) => total + Number(item.assigned_count || 0), 0)
  const submitted = tasks.value.reduce((total, item) => total + Number(item.submitted_count || 0), 0)
  const approved = tasks.value.reduce((total, item) => total + Number(item.approved_count || 0), 0)
  return {
    total: tasks.value.length,
    assigned,
    submitted,
    approved,
    completionRate: assigned ? Math.round(approved / assigned * 100) : 0
  }
})
const assignmentMetrics = computed(() => ({
  total: assignments.value.length,
  pending: assignments.value.filter(item => item.status === 'PENDING_REVIEW').length,
  returned: assignments.value.filter(item => item.status === 'RETURNED').length,
  completed: assignments.value.filter(item => item.status === 'APPROVED').length
}))
const taskStateTabs = computed(() => [
  {label: '全部任务', value: 'ALL', count: tasks.value.length},
  {label: '进行中', value: 'IN_PROGRESS', count: tasks.value.filter(item => managerTaskState(item) === 'IN_PROGRESS').length},
  {label: '已完成', value: 'COMPLETED', count: tasks.value.filter(item => managerTaskState(item) === 'COMPLETED').length},
  {label: '未分配', value: 'UNASSIGNED', count: tasks.value.filter(item => managerTaskState(item) === 'UNASSIGNED').length}
])
const filteredTasks = computed(() => {
  const keyword = taskFilters.keyword.trim().toLowerCase()
  return tasks.value.filter(item => {
    const text = `${item.title || ''} ${item.creator_name || ''}`.toLowerCase()
    return (!keyword || text.includes(keyword))
      && (taskFilters.state === 'ALL' || managerTaskState(item) === taskFilters.state)
  })
})
const targetDescription = computed(() => {
  const parts: string[] = []
  const batch = batches.value.find(item => item.id === dispatch.batchId)
  if (batch) parts.push(`批次：${batch.name}`)
  const businessUnit = businessUnits.value.find(item => item.id === dispatch.businessUnitId)
  if (businessUnit) parts.push(`板块：${businessUnitLabel(businessUnit.name)}`)
  const station = stations.value.find(item => item.id === dispatch.stationId)
  if (station) parts.push(`站点：${station.name}`)
  return parts.join('；') || '尚未选择下发对象'
})
const filteredTaskProgress = computed(() => {
  const keyword = progressFilters.keyword.trim().toLowerCase()
  return taskProgress.value.filter(row => {
    const matchesKeyword = !keyword
      || String(row.employee_name || '').toLowerCase().includes(keyword)
      || String(row.employee_no || '').toLowerCase().includes(keyword)
    return matchesKeyword && (!progressFilters.status || row.status === progressFilters.status)
  })
})
const hasProgressFilters = computed(() => Boolean(progressFilters.keyword.trim() || progressFilters.status))
const progressMetrics = computed(() => ({
  total: taskProgress.value.length,
  submitted: taskProgress.value.filter(row => row.submission_id).length,
  pending: taskProgress.value.filter(row => row.status === 'PENDING_REVIEW').length,
  approved: taskProgress.value.filter(row => row.status === 'APPROVED').length,
  files: taskProgress.value.reduce((total, row) => total + Number(row.file_count || 0), 0)
}))

async function load() {
  tasks.value = (await api.get<any, Envelope<any[]>>('/tasks')).data
  assignments.value = (await api.get<any, Envelope<any[]>>('/assignments')).data
  pendingReviews.value = canReview.value
    ? (await api.get<any, Envelope<any[]>>('/assignments/pending-review')).data
    : []
  if (!canManage.value) return
  const [batchResponse, businessUnitResponse, stationResponse, planResponse] = await Promise.all([
    api.get<any, Envelope<any[]>>('/batches'),
    api.get<any, Envelope<any[]>>('/business-units'),
    api.get<any, Envelope<any[]>>('/stations'),
    api.get<any, Envelope<any[]>>('/training-plans')
  ])
  batches.value = batchResponse.data
  businessUnits.value = businessUnitResponse.data.filter(item => item.enabled === true || item.enabled === 1)
  stations.value = stationResponse.data
  plans.value = planResponse.data.filter(item => item.enabled === true || item.enabled === 1)
  if (selectedPlanId.value && !plans.value.some(item => item.id === selectedPlanId.value)) {
    selectedPlanId.value = null
  }
}

async function loadPlanTasks() {
  dispatch.planTaskIds = []
  dispatch.taskTitle = ''
  if (!selectedPlanId.value) {
    planTasks.value = []
    return
  }
  planTasks.value = (await api.get<any, Envelope<any[]>>(`/training-plans/${selectedPlanId.value}/tasks`)).data
}

async function dispatchManualTask() {
  if (!manualDispatchReady.value) return ElMessage.warning('请完整填写任务、截止时间和下发对象')
  await ElMessageBox.confirm(
    `确认下发临时任务“${manualDispatch.title}”？系统将按所选批次、板块和服务站组合筛选在职员工。`,
    '确认下发任务',
    {confirmButtonText: '确认下发', cancelButtonText: '返回检查', type: 'warning'}
  )
  dispatching.value = true
  try {
    const response = await api.post<any, Envelope<any>>('/tasks/dispatch-manual', manualDispatch)
    for(const item of manualFiles.value){
      const form=new FormData()
      form.append('file',item.raw)
      await api.post(`/tasks/${response.data.taskId}/attachments`,form)
    }
    ElMessage.success(`任务已下发给 ${response.data.assignedEmployees} 人${manualFiles.value.length?`，并上传 ${manualFiles.value.length} 个附件`:''}`)
    Object.assign(manualDispatch, {title: '', description: '', requirements: '', deadline: '', batchId: null, businessUnitId: null, stationId: null})
    manualFiles.value=[]
    await load()
    router.push('/training-plans/tracking')
  } finally {
    dispatching.value = false
  }
}

function planDispatchPayload() {
  return {...dispatch, planId: selectedPlanId.value}
}

async function previewPlanDispatch() {
  if (!planDispatchReady.value) return ElMessage.warning('请完成计划任务、截止规则和下发对象设置')
  dispatchPreviewLoading.value = true
  try {
    const response = await api.post<any, Envelope<any>>('/tasks/dispatch-plan/preview', planDispatchPayload())
    Object.assign(dispatchPreview, response.data)
    previewOpen.value = true
  } finally {
    dispatchPreviewLoading.value = false
  }
}

async function dispatchPlanTasks() {
  if (!selectedPlanId.value) return
  dispatching.value = true
  try {
    const response = await api.post<any, Envelope<any>>('/tasks/dispatch-plan', planDispatchPayload())
    previewOpen.value = false
    ElMessage.success(`已向 ${response.data.targetEmployees} 人下发，新增 ${response.data.createdAssignments} 项任务分配`)
    dispatch.planTaskIds = []
    dispatch.taskTitle = ''
    await load()
    router.push('/training-plans/tracking')
  } finally {
    dispatching.value = false
  }
}

async function open(row: any, mode: 'SUBMIT' | 'RESUBMIT' | 'VIEW' | 'REVIEW') {
  selected.value = row
  submissionMode.value = mode
  if (mode === 'SUBMIT' || mode === 'RESUBMIT') {
    submit.content = ''
    files.value = []
  }
  if (mode === 'REVIEW') Object.assign(review, {decision: 'APPROVE', comment: '', score: null})
  history.value = (await api.get<any, Envelope<any[]>>(`/assignments/${row.id}/submissions`)).data
  dialog.value = true
}

async function doSubmit() {
  const form = new FormData()
  form.append('content', submit.content)
  files.value.forEach(item => form.append('files', item.raw))
  await api.post(`/assignments/${selected.value.id}/submissions`, form)
  dialog.value = false
  await load()
}

async function doReview() {
  if (submissionMode.value !== 'REVIEW') return
  if (review.decision === 'APPROVE' && (!Number.isInteger(review.score) || review.score < 0 || review.score > 100)) {
    ElMessage.error('请填写 0 到 100 的整数评分')
    return
  }
  await api.post(`/submissions/${history.value[0].id}/review`, review)
  dialog.value = false
  await load()
  if (progressDialog.value) await refreshTaskProgress()
}

function statusLabel(status: string) {
  return ({NOT_SUBMITTED: '未提交', PENDING_REVIEW: '待审核', APPROVED: '已通过', RETURNED: '已退回', OVERDUE: '已逾期'} as Record<string, string>)[status] || status
}

function statusTagType(status: string) {
  return ({APPROVED: 'success', RETURNED: 'danger', PENDING_REVIEW: 'warning', OVERDUE: 'danger'} as Record<string, string>)[status] || 'info'
}

function managerTaskState(task: any) {
  const assigned = Number(task.assigned_count || 0)
  const approved = Number(task.approved_count || 0)
  if (!assigned) return 'UNASSIGNED'
  if (approved >= assigned) return 'COMPLETED'
  return 'IN_PROGRESS'
}

function managerTaskStateLabel(task: any) {
  return ({UNASSIGNED: '未分配', COMPLETED: '已完成', IN_PROGRESS: '进行中'} as Record<string, string>)[managerTaskState(task)]
}

function managerTaskStateType(task: any) {
  return ({UNASSIGNED: 'info', COMPLETED: 'success', IN_PROGRESS: 'warning'} as Record<string, string>)[managerTaskState(task)] || 'info'
}

function resetProgressFilters() {
  progressFilters.keyword = ''
  progressFilters.status = ''
}

function formatDate(value?: string) {
  return value ? String(value).replace('T', ' ') : '--'
}

function businessUnitLabel(name: unknown) {
  const value = String(name || '')
  return value.endsWith('板块') ? value : `${value}板块`
}

async function showTaskProgress(task: any) {
  selectedTask.value = task
  resetProgressFilters()
  await refreshTaskProgress()
  progressDialog.value = true
}

async function refreshTaskProgress() {
  if (!selectedTask.value?.id) return
  taskProgress.value = (await api.get<any, Envelope<any[]>>(`/tasks/${selectedTask.value.id}/progress`)).data
}

async function deleteTask(task: any) {
  await ElMessageBox.confirm(`确定删除任务“${task.title}”吗？未提交成果的分配会一并删除。`, '删除任务', {type: 'warning'})
  await api.delete(`/tasks/${task.id}`)
  ElMessage.success('任务已删除')
  await load()
}

async function reviewAssignment(row: any) {
  await open(row, 'REVIEW')
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

async function downloadSubmissionFile(file: any) {
  const blob = await api.get<any, Blob>(`/files/${file.id}`, {responseType: 'blob'})
  saveBlob(blob, file.original_name)
}

async function latestSubmission(row: any) {
  const submissions = (await api.get<any, Envelope<any[]>>(`/assignments/${row.id}/submissions`)).data
  return submissions.find(item => item.id === row.submission_id) || submissions[0]
}

async function previewEmployeeSubmission(row: any) {
  const submission = await latestSubmission(row)
  const submissionFiles = submission?.files || []
  if (!submissionFiles.length) {
    ElMessage.info('该员工本次提交没有附件')
    return
  }
  previewCandidates.value = submissionFiles
  await previewSubmissionFile(submissionFiles[0])
}

async function downloadEmployeeSubmissionFiles(row: any) {
  downloadingAssignmentId.value = row.id
  try {
    const submission = await latestSubmission(row)
    const submissionFiles = submission?.files || []
    if (!submissionFiles.length) {
      ElMessage.info('该员工本次提交没有可下载的附件')
      return
    }
    for (const file of submissionFiles) {
      await downloadSubmissionFile(file)
    }
  } finally {
    downloadingAssignmentId.value = null
  }
}

async function exportTaskFiles() {
  if (!selectedTask.value?.id) return
  exportingFiles.value = true
  try {
    const blob = await api.get<any, Blob>(`/tasks/${selectedTask.value.id}/submissions/archive`, {responseType: 'blob'})
    saveBlob(blob, `${selectedTask.value.title || '任务'}-全部提交文件.zip`)
  } finally {
    exportingFiles.value = false
  }
}

async function exportTaskProgress() {
  if (!selectedTask.value?.id) return
  exportingProgress.value = true
  try {
    const blob = await api.get<any, Blob>(`/tasks/${selectedTask.value.id}/progress/export`, {responseType: 'blob'})
    saveBlob(blob, `${selectedTask.value.title || '任务'}-提交情况.xlsx`)
  } finally {
    exportingProgress.value = false
  }
}

function clearPreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewContent.value = ''
  previewFile.value = undefined
}

function finishPreview() {
  clearPreview()
  previewCandidates.value = []
}

function sanitizeDocxHtml(html: string) {
  const container = document.createElement('div')
  container.innerHTML = html
  container.querySelectorAll('script,style,iframe,object,embed,link,form').forEach(element => element.remove())
  container.querySelectorAll('*').forEach(element => {
    Array.from(element.attributes).forEach(attribute => {
      const name = attribute.name.toLowerCase()
      const value = attribute.value.trim().toLowerCase()
      if (name.startsWith('on') || name === 'style' || (name === 'href' && !value.startsWith('#')) || (name === 'src' && !value.startsWith('data:image/'))) {
        element.removeAttribute(attribute.name)
      }
    })
  })
  return container.innerHTML
}

async function previewSubmissionFile(file: any) {
  clearPreview()
  previewLoading.value = true
  try {
    const blob = await api.get<any, Blob>(`/files/${file.id}`, {responseType: 'blob'})
    const fileName = String(file.original_name || '').toLowerCase()
    const contentType = String(file.content_type || blob.type || '').toLowerCase()
    previewFile.value = file

    if (contentType === 'application/pdf' || fileName.endsWith('.pdf')) {
      previewType.value = 'PDF'
      previewUrl.value = URL.createObjectURL(blob)
    } else if (contentType.startsWith('image/') || /\.(png|jpe?g|gif|webp|bmp)$/i.test(fileName)) {
      previewType.value = 'IMAGE'
      previewUrl.value = URL.createObjectURL(blob)
    } else if (contentType.startsWith('text/') || /\.(txt|md|csv|json|log)$/i.test(fileName)) {
      previewType.value = 'TEXT'
      previewContent.value = await blob.text()
    } else if (fileName.endsWith('.docx') || contentType.includes('wordprocessingml.document')) {
      previewType.value = 'HTML'
      const mammoth = (await import('mammoth')).default
      const result = await mammoth.convertToHtml({arrayBuffer: await blob.arrayBuffer()}, {externalFileAccess: false})
      previewContent.value = sanitizeDocxHtml(result.value) || '<p>文档不含可预览的内容。</p>'
    } else {
      ElMessage.info('该附件暂不支持在线预览，请下载后查看')
      clearPreview()
      return
    }
    previewDialog.value = true
  } finally {
    previewLoading.value = false
  }
}

async function openTaskDetail(task: any) {
  const response = await api.get<any, Envelope<any>>(`/tasks/${task.id}`)
  detailTaskId.value = task.id
  Object.assign(taskDetail, response.data)
  detailDialog.value = true
}

async function saveTaskDetail() {
  if (!detailTaskId.value) return
  await api.put(`/tasks/${detailTaskId.value}`, taskDetail)
  ElMessage.success('任务详情已更新')
  detailDialog.value = false
  await load()
}

watch(selectedPlanId, loadPlanTasks)
watch(() => dispatch.planTaskIds, ids => {
  if (ids.length !== 1) dispatch.taskTitle = ''
}, {deep: true})
onMounted(async () => {
  await load()
  const planId = Number(route.query.planId)
  selectedPlanId.value = Number.isFinite(planId) && plans.value.some(item => item.id === planId)
    ? planId
    : plans.value[0]?.id || null
})
</script>

<template>
  <div class="task-module-page">
    <header class="task-page-head">
      <div>
        <span class="eyebrow">{{employee?'培养任务 · 我的任务':trackingMode?'培养计划 · 任务跟踪':'培养计划 · 任务下发'}}</span>
        <h1>{{employee?'我的任务':trackingMode?'任务跟踪':'任务下发'}}</h1>
        <p v-if="employee">查看任务说明与附件、提交成果，并跟踪审核结果。</p>
        <p v-else-if="trackingMode">集中查看任务覆盖、完成进度与待审核成果，及时发现培养阻塞。</p>
        <p v-else>从已启用计划批量下发标准任务，也可快速创建带附件的临时任务。</p>
      </div>
      <div class="task-head-actions">
        <el-button v-if="canManage&&trackingMode" :icon="Plus" type="primary" @click="router.push('/tasks')">任务下发</el-button>
        <el-button v-if="canManage&&!trackingMode" :icon="Connection" @click="router.push('/training-plans/tracking')">任务跟踪</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="task-summary-grid">
      <template v-if="employee">
        <article class="task-summary-card blue"><span class="summary-icon"><el-icon><Collection/></el-icon></span><div><small>全部任务</small><strong>{{assignmentMetrics.total}}</strong><span>当前分配记录</span></div></article>
        <article class="task-summary-card amber"><span class="summary-icon"><el-icon><Calendar/></el-icon></span><div><small>待审核</small><strong>{{assignmentMetrics.pending}}</strong><span>成果等待确认</span></div></article>
        <article class="task-summary-card red"><span class="summary-icon"><el-icon><RefreshLeft/></el-icon></span><div><small>已退回</small><strong>{{assignmentMetrics.returned}}</strong><span>需要补充提交</span></div></article>
        <article class="task-summary-card green"><span class="summary-icon"><el-icon><CircleCheck/></el-icon></span><div><small>已完成</small><strong>{{assignmentMetrics.completed}}</strong><span>审核通过任务</span></div></article>
      </template>
      <template v-else>
        <article class="task-summary-card blue"><span class="summary-icon"><el-icon><Collection/></el-icon></span><div><small>{{trackingMode?'已下发任务':'可用计划'}}</small><strong>{{trackingMode?taskMetrics.total:plans.length}}</strong><span>{{trackingMode?'任务批次总量':'当前已启用'}}</span></div></article>
        <article class="task-summary-card violet"><span class="summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>覆盖人次</small><strong>{{taskMetrics.assigned}}</strong><span>累计任务分配</span></div></article>
        <article class="task-summary-card amber"><span class="summary-icon"><el-icon><Document/></el-icon></span><div><small>待审核</small><strong>{{pendingReviews.length}}</strong><span>需要培训方处理</span></div></article>
        <article class="task-summary-card green"><span class="summary-icon"><el-icon><CircleCheck/></el-icon></span><div><small>完成率</small><strong>{{taskMetrics.completionRate}}%</strong><span>{{taskMetrics.approved}} 项已通过</span></div></article>
      </template>
    </section>

    <section v-if="canManage&&!trackingMode" class="task-workspace">
      <div class="task-workspace-head">
        <div><h2>配置下发任务</h2><p>按“内容—时限—对象”完成配置，预览确认后再正式下发。</p></div>
        <div class="dispatch-mode-tabs">
          <button type="button" :class="{active:dispatchMode==='PLAN'}" @click="dispatchMode='PLAN'">从培养计划下发</button>
          <button type="button" :class="{active:dispatchMode==='MANUAL'}" @click="dispatchMode='MANUAL'">临时任务</button>
        </div>
      </div>

      <div v-if="dispatchMode==='PLAN'" class="dispatch-layout">
        <section class="dispatch-main">
          <article class="dispatch-step">
            <div class="step-heading"><span>1</span><div><h3>选择计划与任务</h3><p>附件将随计划任务一并生成下发快照</p></div></div>
            <el-form label-position="top">
              <el-form-item label="培养计划" required>
                <el-select v-model="selectedPlanId" placeholder="选择已启用的培养计划" style="width:100%">
                  <el-option v-for="item in plans" :key="item.id" :label="item.name" :value="item.id"/>
                </el-select>
              </el-form-item>
            </el-form>
            <el-checkbox-group v-model="dispatch.planTaskIds" class="plan-task-options">
              <label v-for="item in planTasks" :key="item.id" class="plan-task-option" :class="{selected:dispatch.planTaskIds.includes(item.id)}">
                <el-checkbox :value="item.id"/>
                <span class="plan-task-index">{{String(item.sort_order).padStart(2,'0')}}</span>
                <span class="plan-task-copy">
                  <strong>{{item.title}}</strong>
                  <small>{{item.description}}</small>
                  <TaskAttachmentsPanel v-if="item.attachments?.length" compact :items="item.attachments"/>
                </span>
              </label>
              <div v-if="selectedPlanId&&!planTasks.length" class="dispatch-empty">当前计划尚未编排任务</div>
            </el-checkbox-group>
            <el-form label-position="top">
              <el-form-item v-if="dispatch.planTaskIds.length===1" label="下发后任务名称（可选）">
                <el-input v-model="dispatch.taskTitle" maxlength="128" clearable placeholder="留空则使用计划任务名称"/>
              </el-form-item>
            </el-form>
          </article>

          <article class="dispatch-step">
            <div class="step-heading"><span>2</span><div><h3>设置完成时限</h3><p>支持按入职或安排日期计算，也可统一指定截止日</p></div></div>
            <div class="deadline-grid">
              <el-select v-model="dispatch.deadlineMode">
                <el-option label="按基准日期偏移" value="OFFSET"/>
                <el-option label="统一截止日期" value="ABSOLUTE"/>
              </el-select>
              <template v-if="dispatch.deadlineMode==='OFFSET'">
                <el-date-picker v-model="dispatch.baseDate" type="date" value-format="YYYY-MM-DD" placeholder="基准日期" style="width:100%"/>
                <el-input-number v-model="dispatch.offsetDays" :min="0" :max="365" controls-position="right" style="width:100%"/>
              </template>
              <el-date-picker v-else v-model="dispatch.deadlineDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日期" style="width:100%"/>
            </div>
            <p class="field-note">{{dispatch.deadlineMode==='OFFSET'?`将在 ${dispatch.baseDate||'基准日期'} 后第 ${dispatch.offsetDays??'-'} 天 23:59 截止`:'所选任务使用同一个固定截止日期'}}</p>
          </article>

          <article class="dispatch-step">
            <div class="step-heading"><span>3</span><div><h3>选择下发对象</h3><p>按批次、所属板块和服务站组合筛选在职员工</p></div></div>
            <div class="target-grid">
              <el-select v-model="dispatch.batchId" clearable placeholder="按批次">
                <el-option v-for="item in batches" :key="item.id" :label="item.name" :value="item.id"/>
              </el-select>
              <el-select v-model="dispatch.businessUnitId" clearable placeholder="按板块">
                <el-option v-for="item in businessUnits" :key="item.id" :label="businessUnitLabel(item.name)" :value="item.id"/>
              </el-select>
              <el-select v-model="dispatch.stationId" clearable placeholder="按服务站">
                <el-option v-for="item in stations" :key="item.id" :label="item.name" :value="item.id"/>
              </el-select>
            </div>
          </article>
        </section>

        <aside class="dispatch-review">
          <span class="review-icon"><el-icon><View/></el-icon></span>
          <h3>下发检查</h3>
          <dl>
            <div><dt>培养计划</dt><dd>{{selectedPlan?.name||'未选择'}}</dd></div>
            <div><dt>任务数量</dt><dd>{{selectedPlanTasks.length}} 项</dd></div>
            <div><dt>下发对象</dt><dd>{{targetDescription}}</dd></div>
          </dl>
          <el-button type="primary" size="large" :loading="dispatchPreviewLoading" :disabled="!planDispatchReady" @click="previewPlanDispatch">预览并确认</el-button>
          <span class="safe-note">预览不会创建任务，可返回继续调整</span>
        </aside>
      </div>

      <div v-else class="manual-workspace">
        <div class="manual-form">
          <div class="step-heading"><span>1</span><div><h3>任务内容与资料</h3><p>适用于不属于标准培养计划的一次性任务</p></div></div>
          <div class="manual-title-grid">
            <el-input v-model="manualDispatch.title" maxlength="128" placeholder="任务名称"/>
            <el-date-picker v-model="manualDispatch.deadline" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="截止时间" style="width:100%"/>
          </div>
          <el-input v-model="manualDispatch.description" type="textarea" :rows="4" maxlength="4000" show-word-limit placeholder="任务说明"/>
          <el-input v-model="manualDispatch.requirements" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="成果要求（可选）"/>
          <div class="manual-upload">
            <div><strong>任务附件</strong><span>支持 PDF、Office、图片、文本和 ZIP，单个不超过 50MB</span></div>
            <el-upload multiple :auto-upload="false" :on-change="(_file:any,list:any[])=>manualFiles=list" :on-remove="(_file:any,list:any[])=>manualFiles=list">
              <el-button :icon="Plus">选择附件</el-button>
            </el-upload>
          </div>
        </div>
        <div class="manual-target">
          <div class="step-heading"><span>2</span><div><h3>下发对象</h3><p>按批次、所属板块和服务站组合筛选，至少选择一项</p></div></div>
          <el-select v-model="manualDispatch.batchId" clearable placeholder="按批次"><el-option v-for="item in batches" :key="item.id" :label="item.name" :value="item.id"/></el-select>
          <el-select v-model="manualDispatch.businessUnitId" clearable placeholder="按板块"><el-option v-for="item in businessUnits" :key="item.id" :label="businessUnitLabel(item.name)" :value="item.id"/></el-select>
          <el-select v-model="manualDispatch.stationId" clearable placeholder="按服务站"><el-option v-for="item in stations" :key="item.id" :label="item.name" :value="item.id"/></el-select>
          <el-button type="primary" size="large" :loading="dispatching" :disabled="!manualDispatchReady" @click="dispatchManualTask">确认下发临时任务</el-button>
        </div>
      </div>
    </section>

    <template v-else-if="!employee">
      <section v-if="canReview&&pendingReviews.length" class="task-workspace pending-section">
        <div class="task-workspace-head"><div><h2>待审核成果</h2><p>按提交时间排序，优先处理已等待较久的员工成果。</p></div><span class="result-count">{{pendingReviews.length}} 项待处理</span></div>
        <el-table :data="pendingReviews">
          <el-table-column prop="title" label="任务" min-width="210" show-overflow-tooltip/>
          <el-table-column prop="employee_name" label="员工" min-width="110"/>
          <el-table-column prop="employee_no" label="工号" min-width="120"/>
          <el-table-column label="提交时间" min-width="170"><template #default="{row}">{{formatDate(row.submitted_at)}}</template></el-table-column>
          <el-table-column label="截止时间" min-width="170"><template #default="{row}">{{formatDate(row.deadline)}}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="reviewAssignment(row)">审核</el-button></template></el-table-column>
        </el-table>
      </section>

      <section class="task-workspace">
        <div class="task-workspace-head"><div><h2>任务执行台账</h2><p>查看任务覆盖、成果提交、附件资料与员工完成情况。</p></div><span class="result-count">共 {{filteredTasks.length}} 项</span></div>
        <div class="task-tabs">
          <button v-for="tab in taskStateTabs" :key="tab.value" type="button" :class="{active:taskFilters.state===tab.value}" @click="taskFilters.state=tab.value">{{tab.label}}<span>{{tab.count}}</span></button>
        </div>
        <div class="task-filter-bar">
          <el-input v-model="taskFilters.keyword" :prefix-icon="Search" clearable placeholder="搜索任务名称或创建人"/>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>
        <el-table :data="filteredTasks" empty-text="暂无符合条件的任务">
          <el-table-column label="任务" min-width="260">
            <template #default="{row}"><div class="task-name-cell"><strong @click="openTaskDetail(row)">{{row.title}}</strong><span>截止 {{formatDate(row.deadline)}}</span><TaskAttachmentsPanel v-if="row.attachments?.length" compact :items="row.attachments"/></div></template>
          </el-table-column>
          <el-table-column label="执行进度" min-width="210"><template #default="{row}"><div class="progress-cell"><strong>{{row.approved_count}} / {{row.assigned_count}} 完成</strong><el-progress :percentage="Number(row.assigned_count)?Math.round(Number(row.approved_count)/Number(row.assigned_count)*100):0" :stroke-width="6" :show-text="false"/><span>{{row.submitted_count}} 项已提交</span></div></template></el-table-column>
          <el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="managerTaskStateType(row)">{{managerTaskStateLabel(row)}}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="270" fixed="right"><template #default="{row}"><div class="task-actions"><el-button size="small" text @click="openTaskDetail(row)">详情</el-button><el-button size="small" text type="primary" @click="showTaskProgress(row)">员工进度</el-button><el-button v-if="canManage" size="small" text type="danger" @click="deleteTask(row)">删除</el-button></div></template></el-table-column>
        </el-table>
      </section>
    </template>

    <section v-else class="task-workspace">
      <div class="task-workspace-head"><div><h2>任务清单</h2><p>点击任务名称查看说明和任务附件，完成后提交成果。</p></div><span class="result-count">共 {{assignments.length}} 项</span></div>
      <el-table :data="assignments" empty-text="暂无培养任务">
        <el-table-column label="任务" min-width="280"><template #default="{row}"><div class="task-name-cell"><strong @click="openTaskDetail(row)">{{row.title}}</strong><span>{{row.description}}</span><TaskAttachmentsPanel v-if="row.attachments?.length" compact :items="row.attachments"/></div></template></el-table-column>
        <el-table-column label="截止时间" min-width="170"><template #default="{row}">{{formatDate(row.deadline)}}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="statusTagType(row.status)">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
        <el-table-column prop="final_score" label="培训方评分" width="110"><template #default="{row}">{{row.final_score??'--'}}</template></el-table-column>
        <el-table-column label="操作" width="145" fixed="right"><template #default="{row}"><el-button link @click="openTaskDetail(row)">详情</el-button><el-button v-if="['NOT_SUBMITTED','RETURNED'].includes(row.status)" link type="primary" @click="open(row,'SUBMIT')">提交成果</el-button><el-button v-else-if="row.status==='PENDING_REVIEW'" link @click="open(row,'RESUBMIT')">重新提交</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="previewOpen" title="确认任务下发" width="min(640px, 92vw)">
      <div class="dispatch-preview-summary">
        <span><strong>{{dispatchPreview.targetEmployees}}</strong> 名员工</span>
        <span><strong>{{dispatchPreview.selectedTasks}}</strong> 项任务</span>
        <span><strong>{{dispatchPreview.reusedTasks}}</strong> 项复用已有批次</span>
      </div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="培养计划">{{selectedPlan?.name}}</el-descriptions-item>
        <el-descriptions-item label="截止时间">{{formatDate(dispatchPreview.deadline)}}</el-descriptions-item>
        <el-descriptions-item label="任务内容">{{dispatchPreview.taskTitles?.join('、')}}</el-descriptions-item>
        <el-descriptions-item label="附件策略">计划附件将生成任务快照，后续模板调整不影响本次下发</el-descriptions-item>
      </el-descriptions>
      <template #footer><el-button @click="previewOpen=false">返回调整</el-button><el-button type="primary" :loading="dispatching" @click="dispatchPlanTasks">确认下发</el-button></template>
    </el-dialog>

    <el-dialog v-model="dialog" :title="submissionMode === 'SUBMIT' ? '提交成果' : submissionMode === 'RESUBMIT' ? '重新提交成果' : submissionMode === 'REVIEW' ? '审核成果' : '提交详情'">
      <template v-if="submissionMode === 'SUBMIT' || submissionMode === 'RESUBMIT'">
        <el-input v-model="submit.content" type="textarea" :rows="4" placeholder="提交说明" />
        <el-upload :key="`${selected?.id || ''}-${submissionMode}`" multiple :auto-upload="false" :on-change="(_file: any, list: any[]) => files = list"><el-button>选择文件</el-button></el-upload>
      </template>
      <template v-else>
        <div class="submission-heading">提交说明</div>
        <p class="submission-content">{{ history[0]?.content || '未填写文字说明' }}</p>
        <el-table :data="history[0]?.files || []" empty-text="未提交附件">
          <el-table-column prop="original_name" label="附件" min-width="240" show-overflow-tooltip />
          <el-table-column prop="size" label="大小" width="100"><template #default="scope">{{ scope.row.size ? `${Math.ceil(scope.row.size / 1024)} KB` : '--' }}</template></el-table-column>
          <el-table-column label="操作" width="140"><template #default="scope"><el-button link type="primary" :loading="previewLoading" @click="previewSubmissionFile(scope.row)">预览</el-button><el-button link type="primary" @click="downloadSubmissionFile(scope.row)">下载</el-button></template></el-table-column>
        </el-table>
        <div v-if="submissionMode === 'REVIEW'" class="review-panel">
          <div class="review-field">
            <span class="review-label">审核结论</span>
            <el-radio-group v-model="review.decision"><el-radio value="APPROVE">通过</el-radio><el-radio value="RETURN">退回</el-radio></el-radio-group>
          </div>
          <div v-if="review.decision === 'APPROVE'" class="review-field">
            <span class="review-label">评分</span>
            <el-input-number v-model="review.score" :min="0" :max="100" :precision="0" :controls="false" placeholder="必填，0-100" class="review-score" />
            <span class="review-unit">分</span>
          </div>
          <el-input v-model="review.comment" type="textarea" :rows="3" placeholder="审核意见（退回时必填）" />
        </div>
      </template>
      <template #footer>
        <el-button v-if="submissionMode === 'SUBMIT' || submissionMode === 'RESUBMIT'" type="primary" @click="doSubmit">确认</el-button>
        <el-button v-else-if="submissionMode === 'REVIEW'" type="primary" @click="doReview">确认</el-button>
        <el-button v-else @click="dialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewDialog" :title="`预览：${previewFile?.original_name || ''}`" width="900px" @closed="finishPreview">
      <div v-if="previewCandidates.length > 1" class="preview-file-switcher">
        <span>本次提交文件</span>
        <el-button
          v-for="file in previewCandidates"
          :key="file.id"
          size="small"
          :type="previewFile?.id === file.id ? 'primary' : 'default'"
          :plain="previewFile?.id !== file.id"
          @click="previewSubmissionFile(file)"
        >{{file.original_name}}</el-button>
      </div>
      <iframe v-if="previewType === 'PDF'" :src="previewUrl" class="file-preview pdf-preview" title="PDF 预览" />
      <img v-else-if="previewType === 'IMAGE'" :src="previewUrl" class="file-preview image-preview" :alt="previewFile?.original_name || '附件预览'" />
      <div v-else-if="previewType === 'HTML'" class="docx-preview" v-html="previewContent" />
      <pre v-else class="text-preview">{{ previewContent }}</pre>
      <template #footer><el-button @click="previewDialog = false">关闭</el-button><el-button type="primary" @click="downloadSubmissionFile(previewFile)">下载</el-button></template>
    </el-dialog>

    <el-dialog v-model="progressDialog" width="min(1280px, 94vw)" top="5vh" class="progress-dialog" destroy-on-close>
      <template #header>
        <div class="progress-dialog-heading">
          <div><span>任务执行明细</span><h3>{{selectedTask?.title || '员工完成情况'}}</h3></div>
          <p>集中查看员工提交、附件和评分结果，并完成资料归档。</p>
        </div>
      </template>

      <section class="progress-overview">
        <article><small>下发人数</small><strong>{{progressMetrics.total}}</strong><span>全部任务对象</span></article>
        <article class="blue"><small>已提交</small><strong>{{progressMetrics.submitted}}</strong><span>包含待审核和已完成</span></article>
        <article class="amber"><small>待审核</small><strong>{{progressMetrics.pending}}</strong><span>需要及时处理</span></article>
        <article class="green"><small>已通过</small><strong>{{progressMetrics.approved}}</strong><span>已形成有效成绩</span></article>
      </section>

      <div class="progress-toolbar">
        <div class="progress-filters">
          <el-input v-model="progressFilters.keyword" :prefix-icon="Search" clearable placeholder="搜索员工姓名或工号" />
          <el-select v-model="progressFilters.status" clearable placeholder="全部状态">
            <el-option label="未提交" value="NOT_SUBMITTED" />
            <el-option label="待审核" value="PENDING_REVIEW" />
            <el-option label="已退回" value="RETURNED" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已逾期" value="OVERDUE" />
          </el-select>
          <el-tooltip content="清除筛选"><el-button class="progress-reset" :icon="RefreshLeft" circle :disabled="!hasProgressFilters" @click="resetProgressFilters" /></el-tooltip>
          <span class="progress-count">显示 {{filteredTaskProgress.length}} / {{taskProgress.length}} 人</span>
        </div>
        <div class="progress-export-actions">
          <el-button :icon="FolderOpened" :loading="exportingFiles" :disabled="!progressMetrics.files" @click="exportTaskFiles">打包下载文件</el-button>
          <el-button type="primary" plain :icon="Download" :loading="exportingProgress" @click="exportTaskProgress">导出提交情况</el-button>
        </div>
      </div>

      <div class="progress-table-shell">
        <el-table :data="filteredTaskProgress" max-height="520" empty-text="未找到符合条件的员工">
          <el-table-column prop="employee_name" label="员工" min-width="120" />
          <el-table-column prop="employee_no" label="工号" min-width="110" />
          <el-table-column prop="assigned_at" label="下发时间" min-width="160"><template #default="scope">{{formatDate(scope.row.assigned_at)}}</template></el-table-column>
          <el-table-column prop="submitted_at" label="最近提交" min-width="160"><template #default="scope">{{formatDate(scope.row.submitted_at)}}</template></el-table-column>
          <el-table-column prop="status" label="完成状态" width="110"><template #default="scope"><el-tag :type="statusTagType(scope.row.status)">{{statusLabel(scope.row.status)}}</el-tag></template></el-table-column>
          <el-table-column label="文件" width="90"><template #default="scope"><span class="file-count">{{scope.row.submission_id ? `${scope.row.file_count || 0} 个附件` : '--'}}</span></template></el-table-column>
          <el-table-column prop="final_score" label="评分" width="80" align="center"><template #default="scope"><strong class="score-value">{{scope.row.final_score ?? '--'}}</strong></template></el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="scope">
              <div class="progress-row-actions">
                <el-button v-if="scope.row.submission_id && Number(scope.row.file_count)" link type="primary" @click="previewEmployeeSubmission(scope.row)">预览</el-button>
                <el-button v-if="scope.row.submission_id && Number(scope.row.file_count)" link :loading="downloadingAssignmentId===scope.row.id" @click="downloadEmployeeSubmissionFiles(scope.row)">下载</el-button>
                <el-button v-if="canReview && scope.row.status==='PENDING_REVIEW'" link type="warning" @click="reviewAssignment(scope.row)">审核</el-button>
                <span v-if="!scope.row.submission_id" class="no-submission">尚未提交</span>
                <span v-else-if="!Number(scope.row.file_count)" class="no-submission">无附件</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="detailDialog" title="任务详情" width="720px">
      <div v-if="canManage" class="form-stack task-detail-form">
        <el-input v-model="taskDetail.title" placeholder="任务标题" />
        <el-date-picker v-model="taskDetail.deadline" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="截止时间" style="width: 100%" />
        <el-input v-model="taskDetail.description" type="textarea" :rows="5" placeholder="任务说明" />
        <el-input v-model="taskDetail.requirements" type="textarea" :rows="5" placeholder="成果要求" />
      </div>
      <el-descriptions v-else :column="1" border>
        <el-descriptions-item label="任务名称">{{ taskDetail.title }}</el-descriptions-item>
        <el-descriptions-item label="截止时间">{{ formatDate(taskDetail.deadline) }}</el-descriptions-item>
        <el-descriptions-item label="任务说明" class-name="pre-wrap">{{ taskDetail.description }}</el-descriptions-item>
        <el-descriptions-item label="成果要求" class-name="pre-wrap">{{ taskDetail.requirements || '--' }}</el-descriptions-item>
      </el-descriptions>
      <TaskAttachmentsPanel
        v-if="detailTaskId"
        :list-url="`/tasks/${detailTaskId}/attachments`"
        :upload-url="canManage?`/tasks/${detailTaskId}/attachments`:''"
        :delete-url-prefix="canManage?`/tasks/${detailTaskId}/attachments`:''"
        :can-manage="canManage"
        title="任务资料"
        :description="canManage?'可在此上传、删除、预览和下载附件；点击文件名即可预览':'点击附件文件名可预览，也可下载到本地'"
        @changed="load"
      />
      <template #footer><el-button @click="detailDialog = false">{{ canManage ? '取消' : '关闭' }}</el-button><el-button v-if="canManage" type="primary" :disabled="!taskDetail.title || !taskDetail.description || !taskDetail.deadline" @click="saveTaskDetail">保存修改</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.task-module-page{width:100%;min-height:100%;margin:0;padding:22px 22px 42px;color:#344054;background:#f5f7fb;box-sizing:border-box}
.task-page-head{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;margin-bottom:16px}.eyebrow{color:#3981cd;font-size:11px;font-weight:700}.task-page-head h1{margin:5px 0 5px;color:#1f2937;font-size:24px;line-height:1.3}.task-page-head p{max-width:760px;margin:0;color:#8691a2;font-size:13px}.task-head-actions{display:flex;align-items:center;gap:8px}
.task-summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:14px}.task-summary-card{display:flex;min-height:88px;align-items:center;gap:12px;padding:15px 16px;border:1px solid #e5e9f0;border-radius:8px;background:#fff;box-sizing:border-box}.task-summary-card .summary-icon{display:grid;flex:0 0 40px;height:40px;place-items:center;border-radius:9px;font-size:20px}.task-summary-card>div{display:grid;min-width:0;grid-template-columns:auto 1fr;align-items:baseline;column-gap:7px}.task-summary-card small{grid-column:1/-1;color:#7e899b;font-size:12px}.task-summary-card strong{color:#243044;font-size:23px;line-height:1.25}.task-summary-card div>span{overflow:hidden;color:#99a2b1;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.task-summary-card.blue .summary-icon{color:#2678c9;background:#eaf4ff}.task-summary-card.green .summary-icon{color:#15966b;background:#eafaf4}.task-summary-card.amber .summary-icon{color:#b76e00;background:#fff7e8}.task-summary-card.violet .summary-icon{color:#626ad1;background:#f0f1ff}.task-summary-card.red .summary-icon{color:#d75d63;background:#fff0f1}
.task-workspace{margin-bottom:16px;overflow:hidden;border:1px solid #e5e9f0;border-radius:8px;background:#fff}.task-workspace-head{display:flex;min-height:62px;align-items:center;justify-content:space-between;gap:18px;padding:14px 16px;border-bottom:1px solid #edf0f4}.task-workspace-head h2{margin:0;color:#344054;font-size:16px}.task-workspace-head p{margin:5px 0 0;color:#909aaa;font-size:11px}.result-count{color:#8994a5;font-size:12px}
.dispatch-mode-tabs,.task-tabs{display:flex;align-items:center;gap:4px;padding:3px;border-radius:8px;background:#f2f5f8}.dispatch-mode-tabs button,.task-tabs button{padding:7px 13px;border:0;border-radius:6px;color:#697487;background:transparent;font-size:12px;cursor:pointer}.dispatch-mode-tabs button.active,.task-tabs button.active{color:#2f79c4;background:#fff;box-shadow:0 1px 4px rgb(23 43 77 / 10%)}.task-tabs{padding:10px 14px;border-bottom:1px solid #edf0f4;border-radius:0;background:#fff}.task-tabs button span{margin-left:6px;padding:1px 5px;border-radius:8px;background:#eef2f6;font-size:10px}.task-tabs button.active span{color:#fff;background:#3b83ce}
.dispatch-layout{display:grid;grid-template-columns:minmax(0,1fr) 280px;align-items:start}.dispatch-main{padding:18px}.dispatch-step{padding:18px;margin-bottom:12px;border:1px solid #e8ecf2;border-radius:11px;background:#fff}.dispatch-step:last-child{margin-bottom:0}.step-heading{display:flex;align-items:flex-start;gap:10px;margin-bottom:15px}.step-heading>span{display:grid;flex:0 0 27px;height:27px;place-items:center;border-radius:8px;color:#fff;background:#3c82ca;font-size:12px;font-weight:700}.step-heading h3{margin:1px 0 0;color:#344054;font-size:14px}.step-heading p{margin:4px 0 0;color:#929cab;font-size:11px}
.plan-task-options{display:flex;flex-direction:column;gap:7px;margin-bottom:14px}.plan-task-option{display:flex;align-items:flex-start;gap:9px;padding:11px 12px;border:1px solid #e3e8ef;border-radius:9px;cursor:pointer;transition:.15s}.plan-task-option:hover,.plan-task-option.selected{border-color:#9dc6ed;background:#f7fbff}.plan-task-index{display:grid;flex:0 0 30px;height:30px;place-items:center;border-radius:8px;color:#397bbd;background:#eaf4ff;font-size:11px;font-weight:700}.plan-task-copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:4px}.plan-task-copy strong{color:#344054;font-size:13px}.plan-task-copy small{overflow:hidden;color:#8c96a7;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.dispatch-empty{padding:28px;text-align:center;color:#9aa4b2;font-size:12px}
.deadline-grid,.target-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.field-note{margin:10px 0 0;color:#8994a5;font-size:11px}
.dispatch-review{position:sticky;top:18px;display:flex;min-height:380px;align-items:stretch;flex-direction:column;padding:25px 20px;border-left:1px solid #e8ecf2;background:linear-gradient(180deg,#f8fbff,#fff)}.review-icon{display:grid;width:46px;height:46px;place-items:center;border-radius:12px;color:#347cc5;background:#e8f3ff;font-size:22px}.dispatch-review h3{margin:13px 0 18px;font-size:15px}.dispatch-review dl{display:flex;flex:1;flex-direction:column;gap:14px;margin:0}.dispatch-review dl div{display:flex;flex-direction:column;gap:4px}.dispatch-review dt{color:#909aaa;font-size:11px}.dispatch-review dd{margin:0;color:#455166;font-size:12px;line-height:1.55}.safe-note{margin-top:9px;text-align:center;color:#9aa3b0;font-size:10px}
.manual-workspace{display:grid;grid-template-columns:minmax(0,1.65fr) minmax(260px,.75fr);gap:0}.manual-form,.manual-target{display:flex;flex-direction:column;gap:12px;padding:22px}.manual-target{border-left:1px solid #e8ecf2;background:#fafcff}.manual-title-grid{display:grid;grid-template-columns:1fr 240px;gap:10px}.manual-upload{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding:13px;border:1px dashed #d4dde8;border-radius:9px;background:#fafcff}.manual-upload>div{display:flex;flex-direction:column;gap:4px}.manual-upload strong{font-size:12px}.manual-upload span{color:#929cab;font-size:10px}
.pending-section{margin-bottom:16px}.task-filter-bar{display:flex;align-items:center;gap:8px;padding:10px 14px;border-bottom:1px solid #edf0f4}.task-filter-bar .el-input{max-width:360px}.task-name-cell{display:flex;min-width:0;flex-direction:column;gap:5px;padding:3px 0}.task-name-cell>strong{overflow:hidden;color:#326fae;font-size:13px;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}.task-name-cell>strong:hover{text-decoration:underline}.task-name-cell>span{overflow:hidden;color:#939dac;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.progress-cell{display:flex;flex-direction:column;gap:5px}.progress-cell strong{font-size:12px}.progress-cell span{color:#939dac;font-size:10px}.task-actions{display:flex;min-height:32px;align-items:center;gap:6px;white-space:nowrap}.task-actions .el-button{min-width:50px;margin:0;padding:6px 9px}
.dispatch-preview-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;margin-bottom:16px}.dispatch-preview-summary span{display:flex;align-items:baseline;justify-content:center;gap:5px;padding:13px;border-radius:9px;color:#738093;background:#f5f8fb;font-size:11px}.dispatch-preview-summary strong{color:#347cc5;font-size:21px}
.form-stack{display:grid;gap:14px}.task-detail-form{padding:4px 0 18px}.pre-wrap{white-space:pre-wrap}.submission-heading{margin-bottom:8px;color:var(--el-text-color-regular);font-weight:600}.submission-content{margin:0 0 16px;white-space:pre-wrap}.review-panel{display:grid;gap:16px;margin-top:20px}.review-field{display:flex;min-height:32px;align-items:center;gap:14px}.review-label{width:64px;color:var(--el-text-color-regular)}.review-score{width:160px}.review-unit{color:var(--el-text-color-secondary)}
.progress-dialog-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;padding-right:28px}.progress-dialog-heading>div{display:flex;min-width:0;flex-direction:column;gap:4px}.progress-dialog-heading span{color:#3b82c4;font-size:11px;font-weight:700}.progress-dialog-heading h3{margin:0;overflow:hidden;color:#28364b;font-size:19px;text-overflow:ellipsis;white-space:nowrap}.progress-dialog-heading p{margin:0;color:#8c97a8;font-size:12px}.progress-overview{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:14px}.progress-overview article{display:grid;grid-template-columns:auto 1fr;align-items:baseline;min-height:72px;padding:12px 14px;border:1px solid #e7ebf1;border-radius:9px;background:#fafcff;column-gap:8px}.progress-overview small{grid-column:1/-1;color:#7d8899;font-size:11px}.progress-overview strong{color:#475569;font-size:22px;line-height:1.25}.progress-overview article>span{overflow:hidden;color:#9aa3b1;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.progress-overview .blue strong{color:#347fc4}.progress-overview .amber strong{color:#c47a12}.progress-overview .green strong{color:#168d70}.progress-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:14px;padding:12px;border:1px solid #e8ecf2;border-radius:9px;background:#f8fafc}.progress-filters,.progress-export-actions{display:flex;align-items:center;gap:8px}.progress-filters .el-input{width:250px}.progress-filters .el-select{width:145px}.progress-count{margin-left:2px;color:#8a95a5;font-size:12px;white-space:nowrap}.progress-export-actions .el-button{margin:0}.progress-table-shell{overflow:hidden;border:1px solid #e6eaf0;border-radius:9px}.progress-row-actions{display:flex;min-height:30px;align-items:center;gap:2px;white-space:nowrap}.progress-row-actions .el-button{margin:0;padding:4px 6px}.file-count{color:#778396;font-size:11px}.score-value{color:#354258}.no-submission{color:#a1a9b5;font-size:11px}
:deep(.progress-dialog){overflow:hidden;border-radius:12px}:deep(.progress-dialog .el-dialog__header){padding:20px 22px 14px;border-bottom:1px solid #edf0f4}:deep(.progress-dialog .el-dialog__body){padding:16px 22px 22px}
.preview-file-switcher{display:flex;align-items:center;gap:8px;margin-bottom:12px;padding:10px 12px;overflow-x:auto;border:1px solid #e7ebf1;border-radius:8px;background:#f8fafc;white-space:nowrap}.preview-file-switcher>span{flex:none;color:#7d8899;font-size:12px}.preview-file-switcher .el-button{flex:none;margin:0}.file-preview{width:100%;border:0}.pdf-preview{height:68vh}.image-preview{display:block;max-height:68vh;object-fit:contain}.text-preview{max-height:68vh;margin:0;overflow:auto;white-space:pre-wrap;font-family:inherit;line-height:1.7}.docx-preview{max-height:68vh;padding:28px 40px;overflow:auto;color:var(--el-text-color-primary);background:#fff;line-height:1.7}.docx-preview :deep(p){margin:0 0 14px;word-break:break-word}.docx-preview :deep(img){width:auto!important;max-width:100%;height:auto!important;max-height:52vh;margin:4px 0;object-fit:contain;vertical-align:middle}.docx-preview :deep(table){width:100%;margin:14px 0;border-collapse:collapse;table-layout:fixed}.docx-preview :deep(td),.docx-preview :deep(th){padding:6px 8px;border:1px solid var(--el-border-color);overflow-wrap:anywhere;vertical-align:top}
@media(max-width:1100px){.task-summary-grid{grid-template-columns:repeat(2,1fr)}.dispatch-layout{grid-template-columns:1fr}.dispatch-review{position:static;min-height:0;border-top:1px solid #e8ecf2;border-left:0}.manual-workspace{grid-template-columns:1fr}.manual-target{border-top:1px solid #e8ecf2;border-left:0}.progress-toolbar{align-items:stretch;flex-direction:column}.progress-export-actions{justify-content:flex-end}}
@media(max-width:760px){.task-module-page{padding:16px 12px 30px}.task-page-head{align-items:flex-start;flex-direction:column}.task-summary-grid{grid-template-columns:1fr}.task-summary-card div>span{display:none}.deadline-grid,.target-grid,.manual-title-grid{grid-template-columns:1fr}.task-workspace-head{align-items:flex-start;flex-direction:column}.dispatch-mode-tabs{width:100%}.dispatch-mode-tabs button{flex:1}.progress-dialog-heading{align-items:flex-start;flex-direction:column}.progress-dialog-heading p{display:none}.progress-overview{grid-template-columns:repeat(2,1fr)}.progress-filters{align-items:stretch;flex-direction:column}.progress-filters .el-input,.progress-filters .el-select{width:100%}.progress-count{margin:2px 0}.progress-export-actions{display:grid;grid-template-columns:1fr 1fr}.progress-export-actions .el-button{width:100%}.docx-preview{padding:18px}}
@media(max-width:460px){.task-summary-card{gap:9px}.task-summary-card .summary-icon{flex-basis:38px;height:38px;font-size:19px}.task-summary-card strong{font-size:22px}}
</style>
