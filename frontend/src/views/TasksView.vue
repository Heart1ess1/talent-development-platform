<script setup lang="ts">
import {computed, onMounted, reactive, ref, watch} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {RefreshLeft} from '@element-plus/icons-vue'
import {api, type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'

const auth = useAuthStore()
const employee = computed(() => auth.user?.role === 'EMPLOYEE')
const canManage = computed(() => auth.can('task:manage'))
const canReview = computed(() => auth.can('task:review'))

const tasks = ref<any[]>([])
const assignments = ref<any[]>([])
const pendingReviews = ref<any[]>([])
const employees = ref<any[]>([])
const batches = ref<any[]>([])
const stations = ref<any[]>([])
const plans = ref<any[]>([])
const planTasks = ref<any[]>([])
const selectedPlanId = ref<number | null>(null)
const dialog = ref(false)
const submissionMode = ref<'SUBMIT' | 'RESUBMIT' | 'VIEW' | 'REVIEW'>('VIEW')
const selected = ref<any>()
const files = ref<any[]>([])
const history = ref<any[]>([])
const previewDialog = ref(false)
const previewFile = ref<any>()
const previewType = ref<'PDF' | 'IMAGE' | 'TEXT' | 'HTML'>('TEXT')
const previewUrl = ref('')
const previewContent = ref('')
const previewLoading = ref(false)
const progressDialog = ref(false)
const detailDialog = ref(false)
const selectedTask = ref<any>()
const detailTaskId = ref<number | null>(null)
const taskProgress = ref<any[]>([])
const progressFilters = reactive({keyword: '', status: ''})

const manualDispatch = reactive<any>({title: '', description: '', requirements: '', deadline: '', employeeIds: [], batchId: null, stationId: null})
const dispatch = reactive<any>({planTaskIds: [], taskTitle: '', deadlineMode: 'OFFSET', baseDate: new Date().toISOString().slice(0, 10), offsetDays: 7, deadlineDate: '', employeeIds: [], batchId: null, stationId: null})
const submit = reactive({content: ''})
const review = reactive<any>({decision: 'APPROVE', comment: '', score: null})
const taskDetail = reactive({title: '', description: '', requirements: '', deadline: ''})

const selectedPlan = computed(() => plans.value.find(item => item.id === selectedPlanId.value))
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

async function load() {
  tasks.value = (await api.get<any, Envelope<any[]>>('/tasks')).data
  assignments.value = (await api.get<any, Envelope<any[]>>('/assignments')).data
  pendingReviews.value = canReview.value
    ? (await api.get<any, Envelope<any[]>>('/assignments/pending-review')).data
    : []
  if (!canManage.value) return
  const [employeeResponse, batchResponse, stationResponse, planResponse] = await Promise.all([
    api.get<any, Envelope<any>>('/employees', {params: {size: 100}}),
    api.get<any, Envelope<any[]>>('/batches'),
    api.get<any, Envelope<any[]>>('/stations'),
    api.get<any, Envelope<any[]>>('/training-plans')
  ])
  employees.value = employeeResponse.data.records
  batches.value = batchResponse.data
  stations.value = stationResponse.data
  plans.value = planResponse.data.filter(item => item.enabled === true || item.enabled === 1)
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
  const response = await api.post<any, Envelope<any>>('/tasks/dispatch-manual', manualDispatch)
  ElMessage.success(`任务已下达给 ${response.data.assignedEmployees} 人`)
  Object.assign(manualDispatch, {title: '', description: '', requirements: '', deadline: '', employeeIds: [], batchId: null, stationId: null})
  await load()
}

async function dispatchPlanTasks() {
  if (!selectedPlanId.value) return
  const response = await api.post<any, Envelope<any>>('/tasks/dispatch-plan', {...dispatch, planId: selectedPlanId.value})
  ElMessage.success(`已向 ${response.data.targetEmployees} 人下达，新增 ${response.data.createdAssignments} 项任务分配`)
  dispatch.planTaskIds = []
  dispatch.taskTitle = ''
  await load()
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
}

function statusLabel(status: string) {
  return ({NOT_SUBMITTED: '未提交', PENDING_REVIEW: '待审核', APPROVED: '已通过', RETURNED: '已退回', OVERDUE: '已逾期'} as Record<string, string>)[status] || status
}

function statusTagType(status: string) {
  return ({APPROVED: 'success', RETURNED: 'danger', PENDING_REVIEW: 'warning', OVERDUE: 'danger'} as Record<string, string>)[status] || 'info'
}

function resetProgressFilters() {
  progressFilters.keyword = ''
  progressFilters.status = ''
}

function formatDate(value?: string) {
  return value ? String(value).replace('T', ' ') : '--'
}

async function showTaskProgress(task: any) {
  selectedTask.value = task
  resetProgressFilters()
  taskProgress.value = (await api.get<any, Envelope<any[]>>(`/tasks/${task.id}/progress`)).data
  progressDialog.value = true
}

async function deleteTask(task: any) {
  await ElMessageBox.confirm(`确定删除任务“${task.title}”吗？未提交成果的分配会一并删除。`, '删除任务', {type: 'warning'})
  await api.delete(`/tasks/${task.id}`)
  ElMessage.success('任务已删除')
  await load()
}

async function reviewAssignment(row: any) {
  progressDialog.value = false
  await open(row, 'REVIEW')
}

async function viewSubmission(row: any) {
  progressDialog.value = false
  await open(row, 'VIEW')
}

async function downloadSubmissionFile(file: any) {
  const blob = await api.get<any, Blob>(`/files/${file.id}`, {responseType: 'blob'})
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = file.original_name
  link.click()
  URL.revokeObjectURL(url)
}

function clearPreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewContent.value = ''
  previewFile.value = undefined
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
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>闯关任务</h2></div>

    <el-card v-if="canManage" class="section-card">
      <template #header>手动下达任务</template>
      <div class="form-grid manual-dispatch-grid">
        <el-input v-model="manualDispatch.title" placeholder="任务标题" />
        <el-date-picker v-model="manualDispatch.deadline" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="截止时间" />
        <el-select v-model="manualDispatch.employeeIds" multiple filterable clearable placeholder="员工">
          <el-option v-for="item in employees" :key="item.id" :label="`${item.name} ${item.employee_no || ''}`" :value="item.id" />
        </el-select>
        <el-select v-model="manualDispatch.batchId" clearable placeholder="批次"><el-option v-for="item in batches" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-select v-model="manualDispatch.stationId" clearable placeholder="服务站"><el-option v-for="item in stations" :key="item.id" :label="item.name" :value="item.id" /></el-select>
      </div>
      <div class="form-stack manual-description-fields">
        <el-input v-model="manualDispatch.description" type="textarea" :rows="2" placeholder="任务说明" />
        <el-input v-model="manualDispatch.requirements" type="textarea" :rows="2" placeholder="成果要求" />
      </div>
      <div class="manual-actions">
        <el-button type="primary" :disabled="!manualDispatch.title || !manualDispatch.description || !manualDispatch.deadline || (!manualDispatch.employeeIds.length && !manualDispatch.batchId && !manualDispatch.stationId)" @click="dispatchManualTask">下达任务</el-button>
      </div>
    </el-card>

    <el-card v-if="canManage" class="section-card">
      <template #header>从培养计划下达任务</template>
      <div class="form-grid dispatch-grid">
        <el-select v-model="selectedPlanId" placeholder="选择培养计划">
          <el-option v-for="item in plans" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="dispatch.planTaskIds" multiple collapse-tags collapse-tags-tooltip placeholder="选择计划任务" :disabled="!selectedPlan">
          <el-option v-for="item in planTasks" :key="item.id" :label="`${item.sort_order}. ${item.title}`" :value="item.id" />
        </el-select>
        <el-input v-model="dispatch.taskTitle" clearable placeholder="任务命名（留空使用计划任务名称）" :disabled="!dispatch.planTaskIds.length" />
        <el-select v-model="dispatch.deadlineMode" placeholder="截止方式">
          <el-option label="日期偏移" value="OFFSET" />
          <el-option label="绝对日期" value="ABSOLUTE" />
        </el-select>
        <template v-if="dispatch.deadlineMode === 'OFFSET'">
          <el-date-picker v-model="dispatch.baseDate" type="date" value-format="YYYY-MM-DD" placeholder="基准日期" />
          <el-input-number v-model="dispatch.offsetDays" :min="0" placeholder="偏移天数" />
        </template>
        <template v-else-if="dispatch.deadlineMode === 'ABSOLUTE'">
          <el-date-picker v-model="dispatch.deadlineDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日期" />
        </template>
        <el-select v-model="dispatch.employeeIds" multiple filterable clearable placeholder="员工">
          <el-option v-for="item in employees" :key="item.id" :label="`${item.name} ${item.employee_no || ''}`" :value="item.id" />
        </el-select>
        <el-select v-model="dispatch.batchId" clearable placeholder="批次"><el-option v-for="item in batches" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-select v-model="dispatch.stationId" clearable placeholder="服务站"><el-option v-for="item in stations" :key="item.id" :label="item.name" :value="item.id" /></el-select>
      </div>
      <div class="dispatch-note">
        <span v-if="dispatch.deadlineMode === 'OFFSET'">截止日期按“基准日期 + 偏移天数”计算。</span>
        <span v-else-if="dispatch.deadlineMode === 'ABSOLUTE'">所有选中的计划任务使用指定的固定截止日期。</span>
      </div>
      <el-button type="primary" :disabled="!selectedPlanId || !dispatch.planTaskIds.length" @click="dispatchPlanTasks">下达计划任务</el-button>
    </el-card>

    <el-card v-if="canReview" class="section-card">
      <template #header>待审核任务</template>
      <el-table :data="pendingReviews" empty-text="暂无待审核任务">
        <el-table-column prop="title" label="任务" min-width="180" show-overflow-tooltip />
        <el-table-column prop="employee_name" label="员工" min-width="110" />
        <el-table-column prop="employee_no" label="工号" min-width="120" />
        <el-table-column prop="submitted_at" label="提交日期" min-width="170"><template #default="scope">{{ formatDate(scope.row.submitted_at) }}</template></el-table-column>
        <el-table-column prop="deadline" label="截止时间" min-width="170"><template #default="scope">{{ formatDate(scope.row.deadline) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" @click="reviewAssignment(scope.row)">审核</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>{{ employee ? '我的任务' : '任务完成情况' }}</template>
      <el-table v-if="employee" :data="assignments" empty-text="暂无任务">
        <el-table-column prop="title" label="任务" min-width="180" />
        <el-table-column prop="deadline" label="截止时间" min-width="170"><template #default="scope">{{ formatDate(scope.row.deadline) }}</template></el-table-column>
        <el-table-column prop="status" label="状态"><template #default="scope"><el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="final_score" label="培训方评分" />
        <el-table-column label="操作" width="100"><template #default="scope"><el-button v-if="['NOT_SUBMITTED', 'RETURNED'].includes(scope.row.status)" link @click="open(scope.row, 'SUBMIT')">提交</el-button><el-button v-else-if="scope.row.status === 'PENDING_REVIEW'" link @click="open(scope.row, 'RESUBMIT')">重新提交</el-button></template></el-table-column>
      </el-table>
      <el-table v-else :data="tasks" empty-text="暂无任务">
        <el-table-column prop="title" label="任务" min-width="180" show-overflow-tooltip />
        <el-table-column prop="deadline" label="截止时间" width="210"><template #default="scope">{{ formatDate(scope.row.deadline) }}</template></el-table-column>
        <el-table-column prop="assigned_count" label="分配" width="70" />
        <el-table-column prop="submitted_count" label="提交" width="70" />
        <el-table-column prop="approved_count" label="完成" width="70" />
        <el-table-column label="操作" width="300" fixed="right"><template #default="scope"><div class="task-actions"><el-button link @click="openTaskDetail(scope.row)">查看详情</el-button><el-button link @click="showTaskProgress(scope.row)">完成情况</el-button><el-button v-if="canManage" link type="danger" @click="deleteTask(scope.row)">删除任务</el-button></div></template></el-table-column>
      </el-table>
    </el-card>

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

    <el-dialog v-model="previewDialog" :title="`预览：${previewFile?.original_name || ''}`" width="900px" @closed="clearPreview">
      <iframe v-if="previewType === 'PDF'" :src="previewUrl" class="file-preview pdf-preview" title="PDF 预览" />
      <img v-else-if="previewType === 'IMAGE'" :src="previewUrl" class="file-preview image-preview" :alt="previewFile?.original_name || '附件预览'" />
      <div v-else-if="previewType === 'HTML'" class="docx-preview" v-html="previewContent" />
      <pre v-else class="text-preview">{{ previewContent }}</pre>
      <template #footer><el-button @click="previewDialog = false">关闭</el-button><el-button type="primary" @click="downloadSubmissionFile(previewFile)">下载</el-button></template>
    </el-dialog>

    <el-dialog v-model="progressDialog" :title="`${selectedTask?.title || ''} - 员工完成情况`" width="1080px">
      <div class="progress-filters">
        <el-input v-model="progressFilters.keyword" placeholder="搜索员工姓名或工号" />
        <el-select v-model="progressFilters.status" placeholder="全部状态">
          <el-option label="未提交" value="NOT_SUBMITTED" />
          <el-option label="待审核" value="PENDING_REVIEW" />
          <el-option label="已退回" value="RETURNED" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已逾期" value="OVERDUE" />
        </el-select>
        <el-tooltip content="清除筛选"><el-button class="progress-reset" :icon="RefreshLeft" circle :disabled="!hasProgressFilters" @click="resetProgressFilters" /></el-tooltip>
        <span class="progress-count">显示 {{ filteredTaskProgress.length }} / {{ taskProgress.length }} 人</span>
      </div>
      <el-table :data="filteredTaskProgress" empty-text="未找到符合条件的员工">
        <el-table-column prop="employee_name" label="员工" min-width="120" />
        <el-table-column prop="employee_no" label="工号" min-width="100" />
        <el-table-column prop="assigned_at" label="下达日期" min-width="160"><template #default="scope">{{ formatDate(scope.row.assigned_at) }}</template></el-table-column>
        <el-table-column prop="submitted_at" label="提交日期" min-width="160"><template #default="scope">{{ formatDate(scope.row.submitted_at) }}</template></el-table-column>
        <el-table-column prop="status" label="完成状态" min-width="110"><template #default="scope"><el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="final_score" label="评分" width="80"><template #default="scope">{{ scope.row.final_score ?? '--' }}</template></el-table-column>
        <el-table-column label="操作" width="150"><template #default="scope"><el-button v-if="!canReview && scope.row.submission_version" link @click="viewSubmission(scope.row)">查看提交</el-button><el-button v-if="canReview && scope.row.status === 'PENDING_REVIEW'" link @click="reviewAssignment(scope.row)">审核</el-button></template></el-table-column>
      </el-table>
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
      <template #footer><el-button @click="detailDialog = false">{{ canManage ? '取消' : '关闭' }}</el-button><el-button v-if="canManage" type="primary" :disabled="!taskDetail.title || !taskDetail.description || !taskDetail.deadline" @click="saveTaskDetail">保存修改</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.section-card { margin-bottom: 16px; }
.form-stack { display: grid; gap: 14px; }
.manual-dispatch-grid, .dispatch-grid { grid-template-columns: repeat(3, minmax(180px, 1fr)); row-gap: 16px; column-gap: 12px; }
.manual-description-fields { margin: 16px 0; }
.manual-actions { padding-top: 2px; }
.dispatch-note { color: var(--el-text-color-secondary); font-size: 13px; margin: 16px 0; }
.task-actions { display: flex; align-items: center; gap: 10px; white-space: nowrap; }
.progress-filters { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.progress-filters .el-input { width: 260px; }
.progress-filters .el-select { width: 160px; }
.progress-count { color: var(--el-text-color-secondary); font-size: 13px; }
.task-detail-form { padding-top: 4px; }
.pre-wrap { white-space: pre-wrap; }
.submission-heading { color: var(--el-text-color-regular); font-weight: 600; margin-bottom: 8px; }
.submission-content { white-space: pre-wrap; margin: 0 0 16px; }
.review-panel { display: grid; gap: 16px; margin-top: 20px; }
.review-field { display: flex; align-items: center; gap: 14px; min-height: 32px; }
.review-label { width: 64px; color: var(--el-text-color-regular); }
.review-score { width: 160px; }
.review-unit { color: var(--el-text-color-secondary); }
.file-preview { width: 100%; border: 0; }
.pdf-preview { height: 68vh; }
.image-preview { display: block; max-height: 68vh; object-fit: contain; }
.text-preview { max-height: 68vh; margin: 0; overflow: auto; white-space: pre-wrap; font-family: inherit; line-height: 1.7; }
.docx-preview { max-height: 68vh; overflow: auto; padding: 28px 40px; background: #fff; color: var(--el-text-color-primary); line-height: 1.7; }
.docx-preview :deep(p) { margin: 0 0 14px; word-break: break-word; }
.docx-preview :deep(img) { max-width: 100%; max-height: 52vh; width: auto !important; height: auto !important; margin: 4px 0; object-fit: contain; vertical-align: middle; }
.docx-preview :deep(p:has(> img:only-child)) { margin: 18px 0; text-align: center; }
.docx-preview :deep(table) { width: 100%; table-layout: fixed; border-collapse: collapse; margin: 14px 0; }
.docx-preview :deep(td), .docx-preview :deep(th) { border: 1px solid var(--el-border-color); padding: 6px 8px; overflow-wrap: anywhere; vertical-align: top; }
@media (max-width: 700px) { .docx-preview { padding: 18px; } }
@media (max-width: 900px) { .manual-dispatch-grid, .dispatch-grid { grid-template-columns: 1fr; } .progress-filters { align-items: stretch; flex-direction: column; } .progress-filters .el-input, .progress-filters .el-select { width: 100%; } }
</style>
