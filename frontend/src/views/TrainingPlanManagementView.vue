<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {useRouter} from 'vue-router'
import {CircleCheck,Collection,Connection,CopyDocument,Delete,Document,EditPen,Plus,Refresh,Search,VideoPause,VideoPlay} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {formatPlanDate,isPlanEnabled,planStatus,type TrainingPlan} from '@/utils/trainingPlan'

type Summary={
  totalPlans:number
  enabledPlans:number
  readyPlans:number
  totalTasks:number
  dispatchedTasks:number
}

const router=useRouter()
const plans=ref<TrainingPlan[]>([])
const loading=ref(false)
const saving=ref(false)
const keyword=ref('')
const statusFilter=ref('ALL')
const editorOpen=ref(false)
const copyOpen=ref(false)
const editing=ref<TrainingPlan|null>(null)
const copying=ref<TrainingPlan|null>(null)
const form=reactive({name:'',description:''})
const copyName=ref('')
const summary=reactive<Summary>({
  totalPlans:0,
  enabledPlans:0,
  readyPlans:0,
  totalTasks:0,
  dispatchedTasks:0
})

const statusTabs=computed(()=>[
  {label:'全部计划',value:'ALL',count:plans.value.length},
  {label:'已启用',value:'ACTIVE',count:plans.value.filter(item=>planStatus(item).key==='ACTIVE').length},
  {label:'待编排',value:'DRAFT',count:plans.value.filter(item=>['DRAFT','INCOMPLETE'].includes(planStatus(item).key)).length},
  {label:'已停用',value:'DISABLED',count:plans.value.filter(item=>planStatus(item).key==='DISABLED').length}
])

const filteredPlans=computed(()=>{
  const term=keyword.value.trim().toLowerCase()
  return plans.value.filter(plan=>{
    const state=planStatus(plan).key
    const matchesStatus=statusFilter.value==='ALL'
      ||(statusFilter.value==='DRAFT'&&['DRAFT','INCOMPLETE'].includes(state))
      ||state===statusFilter.value
    const matchesKeyword=!term||`${plan.name} ${plan.description||''} ${plan.creator_name||''}`.toLowerCase().includes(term)
    return matchesStatus&&matchesKeyword
  })
})

function numberValue(value:unknown){
  return Number(value||0)
}

function updateSummaryFromPlans(){
  summary.totalPlans=plans.value.length
  summary.enabledPlans=plans.value.filter(isPlanEnabled).length
  summary.readyPlans=plans.value.filter(item=>numberValue(item.task_count)>0).length
  summary.totalTasks=plans.value.reduce((total,item)=>total+numberValue(item.task_count),0)
  summary.dispatchedTasks=plans.value.reduce((total,item)=>total+numberValue(item.dispatched_task_count),0)
}

async function load(){
  loading.value=true
  try{
    const response=await api.get<any,Envelope<TrainingPlan[]>>('/training-plans')
    plans.value=response.data.map(item=>({
      ...item,
      task_count:numberValue(item.task_count),
      dispatched_task_count:numberValue(item.dispatched_task_count)
    }))
    updateSummaryFromPlans()
    try{
      const summaryResponse=await api.get<any,Envelope<Summary>>('/training-plans/summary',{silentError:true} as any)
      Object.assign(summary,summaryResponse.data)
    }catch{
      // 兼容尚未重启的旧版后端，列表数据仍可支撑页面统计。
    }
  }finally{
    loading.value=false
  }
}

function openCreate(){
  editing.value=null
  Object.assign(form,{name:'',description:''})
  editorOpen.value=true
}

function openEdit(plan:TrainingPlan){
  editing.value=plan
  Object.assign(form,{name:plan.name,description:plan.description||''})
  editorOpen.value=true
}

async function save(){
  if(!form.name.trim())return ElMessage.warning('请输入计划名称')
  saving.value=true
  try{
    const payload={name:form.name.trim(),description:form.description.trim()}
    if(editing.value)await api.put(`/training-plans/${editing.value.id}`,payload)
    else await api.post('/training-plans',payload)
    editorOpen.value=false
    ElMessage.success(editing.value?'培养计划已更新':'培养计划已创建，请继续编排任务')
    await load()
  }finally{
    saving.value=false
  }
}

function openOrchestration(plan?:TrainingPlan){
  router.push({
    path:'/training-plans/tasks',
    query:plan?{planId:String(plan.id)}:undefined
  })
}

async function togglePlan(plan:TrainingPlan){
  const enabling=!isPlanEnabled(plan)
  if(enabling&&!numberValue(plan.task_count)){
    ElMessage.warning('该计划尚未编排任务，请先完成任务编排')
    return openOrchestration(plan)
  }
  await ElMessageBox.confirm(
    enabling
      ?`启用后，“${plan.name}”可在任务下发中直接选择。`
      :`停用后将不能继续下发“${plan.name}”，已下发任务不受影响。`,
    enabling?'确认启用计划':'确认停用计划',
    {confirmButtonText:enabling?'启用':'停用',cancelButtonText:'取消',type:enabling?'success':'warning'}
  )
  await api.put(`/training-plans/${plan.id}/enabled`,{enabled:enabling})
  ElMessage.success(enabling?'计划已启用':'计划已停用')
  await load()
}

function openCopy(plan:TrainingPlan){
  copying.value=plan
  copyName.value=`${plan.name} - 副本`
  copyOpen.value=true
}

async function copyPlan(){
  if(!copying.value||!copyName.value.trim())return ElMessage.warning('请输入新计划名称')
  saving.value=true
  try{
    const response=await api.post<any,Envelope<number>>(`/training-plans/${copying.value.id}/copy`,{name:copyName.value.trim()})
    copyOpen.value=false
    ElMessage.success('计划及任务已复制，新计划默认为草稿')
    await load()
    openOrchestration(plans.value.find(item=>item.id===response.data))
  }finally{
    saving.value=false
  }
}

async function deletePlan(plan:TrainingPlan){
  if(numberValue(plan.dispatched_task_count)>0){
    return ElMessage.warning('该计划已有下发记录，为保证历史可追溯，请停用而不是删除')
  }
  await ElMessageBox.confirm(
    `将永久删除“${plan.name}”及其 ${numberValue(plan.task_count)} 项编排任务，此操作不可撤销。`,
    '删除培养计划',
    {confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'}
  )
  await api.delete(`/training-plans/${plan.id}`)
  ElMessage.success('培养计划已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="plan-module-page">
    <section class="plan-hero">
      <div>
        <span class="eyebrow">培养计划 · 计划管理</span>
        <h1>计划管理</h1>
        <p>统一维护培养方案的基本信息和启停状态；任务内容请进入“任务编排”集中设计。</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="Connection" @click="openOrchestration()">进入任务编排</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建计划</el-button>
      </div>
    </section>

    <section class="plan-summary-grid" v-loading="loading">
      <article class="plan-summary-card blue">
        <span class="summary-icon"><el-icon><Collection/></el-icon></span>
        <div><small>全部计划</small><strong>{{summary.totalPlans}}</strong><span>培养方案总数</span></div>
      </article>
      <article class="plan-summary-card green">
        <span class="summary-icon"><el-icon><CircleCheck/></el-icon></span>
        <div><small>已启用</small><strong>{{summary.enabledPlans}}</strong><span>可用于任务下发</span></div>
      </article>
      <article class="plan-summary-card violet">
        <span class="summary-icon"><el-icon><Document/></el-icon></span>
        <div><small>编排任务</small><strong>{{summary.totalTasks}}</strong><span>{{summary.readyPlans}} 个计划已有内容</span></div>
      </article>
      <article class="plan-summary-card amber">
        <span class="summary-icon"><el-icon><Connection/></el-icon></span>
        <div><small>已下发任务</small><strong>{{summary.dispatchedTasks}}</strong><span>保留完整执行记录</span></div>
      </article>
    </section>

    <section class="plan-workspace">
      <div class="workspace-head">
        <div>
          <h2>培养计划库</h2>
          <p>先完成计划信息、任务编排与附件配置，再启用并前往任务下发。</p>
        </div>
        <span class="result-count">共 {{filteredPlans.length}} 个计划</span>
      </div>

      <div class="plan-tabs">
        <button
          v-for="tab in statusTabs"
          :key="tab.value"
          type="button"
          :class="{active:statusFilter===tab.value}"
          @click="statusFilter=tab.value"
        >
          {{tab.label}}<span>{{tab.count}}</span>
        </button>
      </div>

      <div class="plan-filter-bar">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索计划名称、说明或创建人"/>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <div class="plan-desktop-table">
        <el-table :data="filteredPlans" v-loading="loading" row-key="id">
          <el-table-column label="计划名称" min-width="250">
            <template #default="{row}">
              <div class="plan-name-cell">
                <span class="plan-mark"><el-icon><Collection/></el-icon></span>
                <div>
                  <strong>{{row.name}}</strong>
                  <span>{{row.description||'尚未填写计划说明'}}</span>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="任务完整度" min-width="170">
            <template #default="{row}">
              <div class="task-count-cell">
                <strong>{{row.task_count}} 项任务</strong>
                <span>{{row.task_count?'已具备下发内容':'需要先完成任务编排'}}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="使用情况" min-width="145">
            <template #default="{row}">
              <div class="task-count-cell">
                <strong>{{row.dispatched_task_count}} 次下发</strong>
                <span>{{row.dispatched_task_count?'已产生执行记录':'尚未投入使用'}}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{row}">
              <el-tag :type="planStatus(row).type" effect="light">{{planStatus(row).label}}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后更新" min-width="155">
            <template #default="{row}">
              <div class="task-count-cell"><strong>{{formatPlanDate(row.updated_at)}}</strong><span>{{row.creator_name||'管理员'}}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="330" fixed="right">
            <template #default="{row}">
              <div class="table-actions">
                <el-button type="primary" size="small" plain @click="openOrchestration(row)">任务编排</el-button>
                <el-button link :icon="EditPen" @click="openEdit(row)">编辑</el-button>
                <el-button link :icon="isPlanEnabled(row)?VideoPause:VideoPlay" @click="togglePlan(row)">
                  {{isPlanEnabled(row)?'停用':'启用'}}
                </el-button>
                <el-dropdown trigger="click">
                  <el-button link>更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item :icon="CopyDocument" @click="openCopy(row)">复制计划</el-dropdown-item>
                      <el-dropdown-item :icon="Delete" divided :disabled="row.dispatched_task_count>0" @click="deletePlan(row)">删除计划</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="plan-empty">
              <el-icon><Collection/></el-icon>
              <strong>{{keyword?'没有找到匹配的培养计划':'还没有培养计划'}}</strong>
              <span>{{keyword?'请调整搜索词后重试':'新建计划后，再进入任务编排设计培养路径'}}</span>
              <el-button v-if="!keyword" type="primary" :icon="Plus" @click="openCreate">新建计划</el-button>
            </div>
          </template>
        </el-table>
      </div>

      <div class="plan-mobile-list" v-loading="loading">
        <article v-for="plan in filteredPlans" :key="plan.id" class="mobile-plan-card">
          <div class="mobile-plan-head">
            <span class="plan-mark"><el-icon><Collection/></el-icon></span>
            <div><strong>{{plan.name}}</strong><span>{{plan.description||'尚未填写计划说明'}}</span></div>
            <el-tag :type="planStatus(plan).type" size="small">{{planStatus(plan).label}}</el-tag>
          </div>
          <div class="mobile-plan-metrics">
            <span><strong>{{plan.task_count}}</strong>项任务</span>
            <span><strong>{{plan.dispatched_task_count}}</strong>次下发</span>
            <span>{{formatPlanDate(plan.updated_at)}}</span>
          </div>
          <div class="mobile-plan-actions">
            <el-button type="primary" plain @click="openOrchestration(plan)">任务编排</el-button>
            <el-button @click="openEdit(plan)">编辑</el-button>
            <el-button @click="togglePlan(plan)">{{isPlanEnabled(plan)?'停用':'启用'}}</el-button>
          </div>
        </article>
        <div v-if="!loading&&!filteredPlans.length" class="plan-empty">
          <el-icon><Collection/></el-icon><strong>暂无匹配的培养计划</strong><span>请调整筛选条件或新建计划</span>
        </div>
      </div>
    </section>

    <el-dialog v-model="editorOpen" :title="editing?'编辑培养计划':'新建培养计划'" width="min(560px, 92vw)" destroy-on-close>
      <div class="dialog-intro">
        <span class="dialog-icon"><el-icon><Collection/></el-icon></span>
        <div><strong>{{editing?'完善计划定位和说明':'先建立计划框架'}}</strong><p>任务内容将在独立的任务编排页面维护，新建计划默认为草稿。</p></div>
      </div>
      <el-form label-position="top">
        <el-form-item label="计划名称" required>
          <el-input v-model="form.name" maxlength="128" show-word-limit placeholder="例如：2026 届售后新人基础培养计划"/>
        </el-form-item>
        <el-form-item label="计划说明">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="说明适用对象、培养目标和使用场景"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorOpen=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{editing?'保存修改':'创建计划'}}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="copyOpen" title="复制培养计划" width="min(500px, 92vw)" destroy-on-close>
      <div class="dialog-intro">
        <span class="dialog-icon"><el-icon><CopyDocument/></el-icon></span>
        <div><strong>复用计划结构、全部任务与附件</strong><p>复制结果默认为草稿，不会复制历史下发和员工执行记录。</p></div>
      </div>
      <el-form label-position="top">
        <el-form-item label="新计划名称" required>
          <el-input v-model="copyName" maxlength="128" show-word-limit/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyOpen=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="copyPlan">复制并编排</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style src="@/styles/training-plans.css"></style>
