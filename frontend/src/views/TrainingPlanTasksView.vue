<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {Back,Bottom,CircleCheck,Collection,Delete,Document,EditPen,Plus,Rank,Right,Search,Top,VideoPause,VideoPlay} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import TaskAttachmentsPanel from '@/components/TaskAttachmentsPanel.vue'
import {isPlanEnabled,planStatus,type TrainingPlan,type TrainingPlanTask} from '@/utils/trainingPlan'

const route=useRoute()
const router=useRouter()
const plans=ref<TrainingPlan[]>([])
const tasks=ref<TrainingPlanTask[]>([])
const selectedPlanId=ref<number|null>(null)
const loadingPlans=ref(false)
const loadingTasks=ref(false)
const saving=ref(false)
const searchTerm=ref('')
const editorOpen=ref(false)
const editing=ref<TrainingPlanTask|null>(null)
const draggingId=ref<number|null>(null)
const form=reactive({title:'',description:'',requirements:''})

const selectedPlan=computed(()=>plans.value.find(item=>item.id===selectedPlanId.value)||null)
const filteredPlans=computed(()=>{
  const term=searchTerm.value.trim().toLowerCase()
  return plans.value.filter(plan=>!term||`${plan.name} ${plan.description||''}`.toLowerCase().includes(term))
})
const requirementsCount=computed(()=>tasks.value.filter(task=>task.requirements?.trim()).length)
const usedTaskCount=computed(()=>tasks.value.filter(task=>Number(task.dispatched_count||0)>0).length)
const readinessText=computed(()=>{
  if(!selectedPlan.value)return '未选择计划'
  if(!tasks.value.length)return '待编排'
  return isPlanEnabled(selectedPlan.value)?'可下发':'待启用'
})

function normalizePlan(plan:TrainingPlan):TrainingPlan{
  return {...plan,task_count:Number(plan.task_count||0),dispatched_task_count:Number(plan.dispatched_task_count||0)}
}

function normalizeTask(task:TrainingPlanTask):TrainingPlanTask{
  return {...task,sort_order:Number(task.sort_order||0),dispatched_count:Number(task.dispatched_count||0)}
}

async function loadPlans(preferredId?:number|null){
  loadingPlans.value=true
  try{
    const response=await api.get<any,Envelope<TrainingPlan[]>>('/training-plans')
    plans.value=response.data.map(normalizePlan)
    const requested=preferredId||selectedPlanId.value
    if(requested&&plans.value.some(item=>item.id===requested))selectedPlanId.value=requested
    else selectedPlanId.value=plans.value[0]?.id||null
  }finally{
    loadingPlans.value=false
  }
}

async function loadTasks(){
  if(!selectedPlanId.value){
    tasks.value=[]
    return
  }
  loadingTasks.value=true
  try{
    const response=await api.get<any,Envelope<TrainingPlanTask[]>>(`/training-plans/${selectedPlanId.value}/tasks`)
    tasks.value=response.data.map(normalizeTask)
    const plan=plans.value.find(item=>item.id===selectedPlanId.value)
    if(plan)plan.task_count=tasks.value.length
  }finally{
    loadingTasks.value=false
  }
}

function selectPlan(id:number){
  selectedPlanId.value=id
  router.replace({path:'/training-plans/tasks',query:{planId:String(id)}})
}

function openCreate(){
  if(!selectedPlan.value)return ElMessage.warning('请先选择培养计划')
  editing.value=null
  Object.assign(form,{title:'',description:'',requirements:''})
  editorOpen.value=true
}

function openEdit(task:TrainingPlanTask){
  editing.value=task
  Object.assign(form,{
    title:task.title,
    description:task.description,
    requirements:task.requirements||''
  })
  editorOpen.value=true
}

async function saveTask(){
  if(!selectedPlanId.value)return
  if(!form.title.trim())return ElMessage.warning('请输入任务名称')
  if(!form.description.trim())return ElMessage.warning('请填写任务说明')
  saving.value=true
  try{
    const payload={
      title:form.title.trim(),
      description:form.description.trim(),
      requirements:form.requirements.trim()
    }
    if(editing.value){
      await api.put(`/training-plans/${selectedPlanId.value}/tasks/${editing.value.id}`,payload)
      editorOpen.value=false
      ElMessage.success('计划任务已更新')
      await Promise.all([loadTasks(),loadPlans(selectedPlanId.value)])
    }else{
      const response=await api.post<any,Envelope<number>>(`/training-plans/${selectedPlanId.value}/tasks`,payload)
      await Promise.all([loadTasks(),loadPlans(selectedPlanId.value)])
      editing.value=tasks.value.find(task=>task.id===Number(response.data))||null
      ElMessage.success('任务已创建，可继续上传附件')
    }
  }finally{
    saving.value=false
  }
}

async function saveOrder(next:TrainingPlanTask[]){
  if(!selectedPlanId.value)return
  tasks.value=next.map((task,index)=>({...task,sort_order:index+1}))
  try{
    await api.put(`/training-plans/${selectedPlanId.value}/tasks/order`,{
      items:tasks.value.map(task=>({id:task.id,sortOrder:task.sort_order}))
    })
    ElMessage.success('任务顺序已更新')
  }catch(error){
    await loadTasks()
    throw error
  }
}

async function moveTask(index:number,delta:number){
  const target=index+delta
  if(target<0||target>=tasks.value.length)return
  const next=[...tasks.value]
  const current=next[index]!
  next[index]=next[target]!
  next[target]=current
  await saveOrder(next)
}

function startDrag(task:TrainingPlanTask){
  draggingId.value=task.id
}

async function dropTask(target:TrainingPlanTask){
  const sourceId=draggingId.value
  draggingId.value=null
  if(!sourceId||sourceId===target.id)return
  const next=[...tasks.value]
  const sourceIndex=next.findIndex(item=>item.id===sourceId)
  const targetIndex=next.findIndex(item=>item.id===target.id)
  if(sourceIndex<0||targetIndex<0)return
  const source=next.splice(sourceIndex,1)[0]!
  next.splice(targetIndex,0,source)
  await saveOrder(next)
}

async function deleteTask(task:TrainingPlanTask){
  if(Number(task.dispatched_count)>0){
    return ElMessage.warning('该任务已产生下发记录，为保证执行历史完整，不能删除')
  }
  await ElMessageBox.confirm(
    `确认从“${selectedPlan.value?.name}”中删除任务“${task.title}”？`,
    '删除计划任务',
    {confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'}
  )
  await api.delete(`/training-plans/${selectedPlanId.value}/tasks/${task.id}`)
  ElMessage.success('计划任务已删除')
  await Promise.all([loadTasks(),loadPlans(selectedPlanId.value)])
}

async function togglePlan(){
  const plan=selectedPlan.value
  if(!plan)return
  const enabling=!isPlanEnabled(plan)
  if(enabling&&!tasks.value.length)return ElMessage.warning('请至少添加一项任务后再启用计划')
  await ElMessageBox.confirm(
    enabling?'启用后，该计划可在任务下发中直接选择。':'停用后不能继续下发，已下发任务仍正常执行。',
    enabling?'确认启用计划':'确认停用计划',
    {confirmButtonText:enabling?'启用':'停用',cancelButtonText:'取消',type:enabling?'success':'warning'}
  )
  await api.put(`/training-plans/${plan.id}/enabled`,{enabled:enabling})
  ElMessage.success(enabling?'计划已启用':'计划已停用')
  await loadPlans(plan.id)
}

function dispatchTasks(){
  if(!selectedPlan.value||!isPlanEnabled(selectedPlan.value)){
    return ElMessage.warning('请先启用当前培养计划')
  }
  router.push({path:'/tasks',query:{planId:String(selectedPlan.value.id)}})
}

watch(selectedPlanId,async(id,oldId)=>{
  if(id===oldId)return
  editing.value=null
  await loadTasks()
})

onMounted(async()=>{
  const queryId=Number(route.query.planId)
  await loadPlans(Number.isFinite(queryId)&&queryId>0?queryId:null)
  await loadTasks()
})
</script>

<template>
  <div class="plan-module-page orchestration-page">
    <section class="plan-hero">
      <div>
        <span class="eyebrow">培养计划 · 任务编排</span>
        <h1>任务编排</h1>
        <p>按照实际培养路径设计任务内容、附件与先后顺序；下发时再确定目标人员和截止时间。</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="Back" @click="router.push('/training-plans/manage')">返回计划管理</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!selectedPlan" @click="openCreate">新增任务</el-button>
      </div>
    </section>

    <section v-if="selectedPlan" class="plan-summary-grid orchestration-summary">
      <article class="plan-summary-card blue">
        <span class="summary-icon"><el-icon><Document/></el-icon></span>
        <div><small>编排任务</small><strong>{{tasks.length}}</strong><span>按顺序组成培养路径</span></div>
      </article>
      <article class="plan-summary-card violet">
        <span class="summary-icon"><el-icon><CircleCheck/></el-icon></span>
        <div><small>成果要求</small><strong>{{requirementsCount}}</strong><span>{{tasks.length-requirementsCount}} 项尚未补充</span></div>
      </article>
      <article class="plan-summary-card amber">
        <span class="summary-icon"><el-icon><Rank/></el-icon></span>
        <div><small>已投入使用</small><strong>{{usedTaskCount}}</strong><span>已有员工执行记录</span></div>
      </article>
      <article class="plan-summary-card green">
        <span class="summary-icon"><el-icon><VideoPlay/></el-icon></span>
        <div><small>计划就绪状态</small><strong class="status-strong">{{readinessText}}</strong><span>{{isPlanEnabled(selectedPlan)?'当前计划已启用':'完成编排后可启用'}}</span></div>
      </article>
    </section>

    <section class="orchestration-workspace">
      <aside class="plan-selector" v-loading="loadingPlans">
        <div class="selector-head">
          <div><h2>选择计划</h2><span>{{plans.length}} 个计划</span></div>
          <el-input v-model="searchTerm" :prefix-icon="Search" clearable placeholder="搜索计划"/>
        </div>
        <div class="selector-list">
          <button
            v-for="plan in filteredPlans"
            :key="plan.id"
            type="button"
            :class="{active:plan.id===selectedPlanId}"
            @click="selectPlan(plan.id)"
          >
            <span class="selector-mark"><el-icon><Collection/></el-icon></span>
            <span class="selector-copy">
              <strong>{{plan.name}}</strong>
              <small>{{plan.task_count}} 项任务 · {{planStatus(plan).label}}</small>
            </span>
            <el-icon class="selector-arrow"><Right/></el-icon>
          </button>
          <div v-if="!filteredPlans.length" class="selector-empty">暂无匹配计划</div>
        </div>
      </aside>

      <main class="task-canvas">
        <template v-if="selectedPlan">
          <div class="canvas-head">
            <div>
              <div class="canvas-title-line">
                <h2>{{selectedPlan.name}}</h2>
                <el-tag :type="planStatus(selectedPlan).type">{{planStatus(selectedPlan).label}}</el-tag>
              </div>
              <p>{{selectedPlan.description||'尚未填写计划说明，可返回计划管理补充。'}}</p>
            </div>
            <div class="canvas-actions">
              <el-button :icon="isPlanEnabled(selectedPlan)?VideoPause:VideoPlay" @click="togglePlan">
                {{isPlanEnabled(selectedPlan)?'停用计划':'启用计划'}}
              </el-button>
              <el-button type="primary" plain :disabled="!tasks.length||!isPlanEnabled(selectedPlan)" @click="dispatchTasks">前往任务下发</el-button>
            </div>
          </div>

          <div class="workflow-hint">
            <span><strong>1</strong>设计任务内容</span><i></i>
            <span><strong>2</strong>调整执行顺序</span><i></i>
            <span><strong>3</strong>启用计划</span><i></i>
            <span><strong>4</strong>选择人员并下发</span>
          </div>

          <div class="task-list" v-loading="loadingTasks">
            <article
              v-for="(task,index) in tasks"
              :key="task.id"
              class="orchestration-task"
              :class="{dragging:draggingId===task.id}"
              draggable="true"
              @dragstart="startDrag(task)"
              @dragend="draggingId=null"
              @dragover.prevent
              @drop.prevent="dropTask(task)"
            >
              <button class="drag-handle" type="button" title="拖动调整顺序" aria-label="拖动调整顺序">
                <el-icon><Rank/></el-icon>
              </button>
              <span class="task-sequence">{{String(index+1).padStart(2,'0')}}</span>
              <div class="task-body">
                <div class="task-title-line">
                  <strong>{{task.title}}</strong>
                  <el-tag v-if="task.dispatched_count>0" type="success" size="small" effect="plain">已下发 {{task.dispatched_count}} 次</el-tag>
                </div>
                <p>{{task.description}}</p>
                <div class="requirement-line">
                  <span>成果要求</span>
                  <strong>{{task.requirements||'暂未设置，建议补充可验收的交付标准'}}</strong>
                </div>
                <TaskAttachmentsPanel
                  v-if="task.attachments?.length"
                  compact
                  :items="task.attachments"
                />
              </div>
              <div class="task-actions">
                <el-button circle :icon="Top" :disabled="index===0" title="上移" @click="moveTask(index,-1)"/>
                <el-button circle :icon="Bottom" :disabled="index===tasks.length-1" title="下移" @click="moveTask(index,1)"/>
                <el-button circle :icon="EditPen" title="编辑" @click="openEdit(task)"/>
                <el-button circle :icon="Delete" :disabled="task.dispatched_count>0" title="删除" @click="deleteTask(task)"/>
              </div>
            </article>

            <div v-if="!loadingTasks&&!tasks.length" class="task-empty">
              <span class="empty-illustration"><el-icon><Document/></el-icon></span>
              <strong>这个计划还没有培养任务</strong>
              <p>建议从基础认知到独立实操逐项添加，并为每项任务写清成果要求。</p>
              <el-button type="primary" :icon="Plus" @click="openCreate">添加第一项任务</el-button>
            </div>
          </div>
        </template>

        <div v-else class="task-empty full-empty">
          <span class="empty-illustration"><el-icon><Collection/></el-icon></span>
          <strong>暂无可编排的培养计划</strong>
          <p>请先创建计划框架，再返回这里设计任务路径。</p>
          <el-button type="primary" @click="router.push('/training-plans/manage')">前往新建计划</el-button>
        </div>
      </main>
    </section>

    <el-dialog v-model="editorOpen" :title="editing?'编辑计划任务':'新增计划任务'" width="min(620px, 94vw)" destroy-on-close>
      <div class="dialog-intro">
        <span class="dialog-icon"><el-icon><Document/></el-icon></span>
        <div><strong>{{selectedPlan?.name}}</strong><p>任务中定义稳定的培养内容与配套资料；人员范围和截止时间在下发时配置。</p></div>
      </div>
      <el-form label-position="top">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="例如：完成售后服务流程学习"/>
        </el-form-item>
        <el-form-item label="任务说明" required>
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="4000" show-word-limit placeholder="说明员工需要完成的学习或实践内容"/>
        </el-form-item>
        <el-form-item label="成果要求">
          <el-input v-model="form.requirements" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="例如：提交一份流程图，并由导师审核通过"/>
          <span class="form-tip">使用可观察、可提交、可审核的标准，后续验收会更清晰。</span>
        </el-form-item>
      </el-form>
      <TaskAttachmentsPanel
        v-if="editing&&selectedPlanId"
        :list-url="`/training-plans/${selectedPlanId}/tasks/${editing.id}/attachments`"
        :upload-url="`/training-plans/${selectedPlanId}/tasks/${editing.id}/attachments`"
        :delete-url-prefix="`/training-plans/${selectedPlanId}/tasks/${editing.id}/attachments`"
        can-manage
        title="任务资料"
        description="附件将在任务下发时生成独立快照；点击文件名可直接预览"
        @changed="loadTasks"
      />
      <div v-else class="attachment-create-tip">保存任务基本信息后，即可继续上传任务附件。</div>
      <template #footer>
        <el-button @click="editorOpen=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTask">{{editing?'保存修改':'创建并添加附件'}}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style src="@/styles/training-plans.css"></style>
