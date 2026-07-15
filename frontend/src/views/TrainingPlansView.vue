<script setup lang="ts">
import {computed, onMounted, reactive, ref, watch} from 'vue'
import {ElMessage} from 'element-plus'
import {api, type Envelope} from '@/api'

const plans = ref<any[]>([])
const planTasks = ref<any[]>([])
const selectedPlanId = ref<number | null>(null)
const loading = ref(false)
const editingPlanId = ref<number | null>(null)
const editingTaskId = ref<number | null>(null)

const planForm = reactive({name: '', description: ''})
const taskForm = reactive({title: '', description: '', requirements: ''})

const selectedPlan = computed(() => plans.value.find(item => item.id === selectedPlanId.value))

async function loadPlans() {
  loading.value = true
  try {
    plans.value = (await api.get<any, Envelope<any[]>>('/training-plans')).data
    if (!selectedPlanId.value && plans.value.length) selectedPlanId.value = plans.value[0].id
  } finally {
    loading.value = false
  }
}

async function loadPlanTasks() {
  if (!selectedPlanId.value) {
    planTasks.value = []
    return
  }
  planTasks.value = (await api.get<any, Envelope<any[]>>(`/training-plans/${selectedPlanId.value}/tasks`)).data
}

async function savePlan() {
  if (editingPlanId.value) await api.put(`/training-plans/${editingPlanId.value}`, planForm)
  else await api.post('/training-plans', planForm)
  ElMessage.success(editingPlanId.value ? '培养计划已更新' : '培养计划已创建')
  resetPlanForm()
  await loadPlans()
}

function editPlan(row: any) {
  editingPlanId.value = row.id
  planForm.name = row.name
  planForm.description = row.description || ''
}

function resetPlanForm() {
  editingPlanId.value = null
  planForm.name = ''
  planForm.description = ''
}

async function togglePlan(row: any) {
  await api.put(`/training-plans/${row.id}/enabled`, {enabled: !(row.enabled === true || row.enabled === 1)})
  await loadPlans()
}

async function savePlanTask() {
  if (!selectedPlanId.value) return
  if (editingTaskId.value) {
    await api.put(`/training-plans/${selectedPlanId.value}/tasks/${editingTaskId.value}`, taskForm)
  } else {
    await api.post(`/training-plans/${selectedPlanId.value}/tasks`, taskForm)
  }
  ElMessage.success(editingTaskId.value ? '计划任务已更新' : '计划任务已创建')
  resetTaskForm()
  await Promise.all([loadPlans(), loadPlanTasks()])
}

function editPlanTask(row: any) {
  editingTaskId.value = row.id
  taskForm.title = row.title
  taskForm.description = row.description
  taskForm.requirements = row.requirements || ''
}

function resetTaskForm() {
  editingTaskId.value = null
  taskForm.title = ''
  taskForm.description = ''
  taskForm.requirements = ''
}

async function deletePlanTask(row: any) {
  if (!selectedPlanId.value) return
  await api.delete(`/training-plans/${selectedPlanId.value}/tasks/${row.id}`)
  ElMessage.success('计划任务已删除')
  await Promise.all([loadPlans(), loadPlanTasks()])
}

async function moveTask(index: number, delta: number) {
  const target = index + delta
  if (!selectedPlanId.value || target < 0 || target >= planTasks.value.length) return
  const current = planTasks.value[index]
  planTasks.value[index] = planTasks.value[target]
  planTasks.value[target] = current
  await api.put(`/training-plans/${selectedPlanId.value}/tasks/order`, {
    items: planTasks.value.map((item, sortOrder) => ({id: item.id, sortOrder: sortOrder + 1}))
  })
  await loadPlanTasks()
}

watch(selectedPlanId, async () => {
  resetTaskForm()
  await loadPlanTasks()
})

onMounted(async () => {
  await loadPlans()
  await loadPlanTasks()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head"><h2>培养计划</h2></div>

    <el-card class="section-card">
      <template #header>培养计划管理</template>
      <div class="form-grid plan-form">
        <el-input v-model="planForm.name" placeholder="计划名称" />
        <el-input v-model="planForm.description" placeholder="计划说明" />
      </div>
      <div class="actions">
        <el-button type="primary" :disabled="!planForm.name" @click="savePlan">{{ editingPlanId ? '保存计划' : '创建计划' }}</el-button>
        <el-button v-if="editingPlanId" @click="resetPlanForm">取消</el-button>
      </div>
      <el-table :data="plans" empty-text="暂无培养计划">
        <el-table-column prop="name" label="计划" min-width="180" />
        <el-table-column prop="description" label="计划说明" min-width="240" show-overflow-tooltip />
        <el-table-column prop="task_count" label="任务数" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="scope"><el-tag :type="(scope.row.enabled === true || scope.row.enabled === 1) ? 'success' : 'info'">{{ (scope.row.enabled === true || scope.row.enabled === 1) ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="scope">
            <el-button link @click="selectedPlanId = scope.row.id">编排</el-button>
            <el-button link @click="editPlan(scope.row)">编辑</el-button>
            <el-button link @click="togglePlan(scope.row)">{{ (scope.row.enabled === true || scope.row.enabled === 1) ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="section-card">
      <template #header>计划任务编排</template>
      <div class="toolbar">
        <el-select v-model="selectedPlanId" placeholder="选择培养计划" style="width: 260px">
          <el-option v-for="item in plans" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <span v-if="selectedPlan" class="muted">计划任务只定义任务内容和成果要求；截止规则在闯关任务中下达时确定。</span>
      </div>

      <div v-if="selectedPlan" class="task-editor">
        <el-input v-model="taskForm.title" placeholder="任务标题" />
        <el-input v-model="taskForm.description" type="textarea" :rows="2" placeholder="任务说明" />
        <el-input v-model="taskForm.requirements" type="textarea" :rows="2" placeholder="成果要求" />
        <div class="actions">
          <el-button type="primary" :disabled="!taskForm.title || !taskForm.description" @click="savePlanTask">{{ editingTaskId ? '保存任务' : '新建任务' }}</el-button>
          <el-button v-if="editingTaskId" @click="resetTaskForm">取消</el-button>
        </div>
      </div>

      <el-table :data="planTasks" empty-text="当前计划暂无任务">
        <el-table-column prop="sort_order" label="顺序" width="70" />
        <el-table-column prop="title" label="任务" min-width="150" />
        <el-table-column prop="description" label="任务说明" min-width="250" show-overflow-tooltip />
        <el-table-column prop="requirements" label="成果要求" min-width="250" show-overflow-tooltip />
        <el-table-column label="操作" width="210">
          <template #default="scope">
            <el-button link :disabled="scope.$index === 0" @click="moveTask(scope.$index, -1)">上移</el-button>
            <el-button link :disabled="scope.$index === planTasks.length - 1" @click="moveTask(scope.$index, 1)">下移</el-button>
            <el-button link @click="editPlanTask(scope.row)">编辑</el-button>
            <el-button link type="danger" @click="deletePlanTask(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.section-card { margin-bottom: 16px; }
.plan-form { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.task-editor { display: grid; gap: 10px; margin: 16px 0; }
.actions, .toolbar { display: flex; align-items: center; gap: 12px; margin: 12px 0; flex-wrap: wrap; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
@media (max-width: 760px) { .plan-form { grid-template-columns: 1fr; } }
</style>
