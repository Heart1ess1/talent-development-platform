<script setup lang="ts">
import {computed,nextTick,onMounted,reactive,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Calendar,Clock,Document,Plus,Search,UserFilled} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {loadDictionaryValues,loadEnabledBusinessUnits,type DictionaryOption} from '@/utils/masterData'
import {dateTimeParts,planPhaseLabels,scoreMonth} from './examUi'
import '@/styles/exam-center.css'

const plans=ref<any[]>([]),papers=ref<any[]>([]),batches=ref<any[]>([]),businessUnits=ref<any[]>([]),classOptions=ref<DictionaryOption[]>([])
const candidates=ref<any[]>([]),selectedCandidates=ref<any[]>([]),candidateTable=ref<any>()
const drawerVisible=ref(false),candidateLoading=ref(false),saving=ref(false)
const keyword=ref(''),statusFilter=ref('')
const scope=reactive<any>({batchIds:[] as number[],businessUnitIds:[] as number[],classId:null,keyword:''})
const emptyPlan=()=>({paperId:null,name:'',startsAt:'',endsAt:'',durationMinutes:60,maxAttempts:1,violationLimit:4,violationGraceSeconds:15,employeeIds:[] as number[]})
const plan=reactive<any>(emptyPlan())

const filteredPlans=computed(()=>plans.value.filter(row=>{
  const matches=!keyword.value||`${row.name} ${row.paper_name}`.toLowerCase().includes(keyword.value.trim().toLowerCase())
  return matches&&(!statusFilter.value||row.plan_phase===statusFilter.value)
}))
const overview=computed(()=>({
  total:plans.value.length,
  draft:plans.value.filter(x=>x.status==='DRAFT').length,
  open:plans.value.filter(x=>x.plan_phase==='OPEN').length,
  upcoming:plans.value.filter(x=>x.plan_phase==='UPCOMING').length
}))
const selectedPaper=computed(()=>papers.value.find(x=>x.id===plan.paperId))
const selectedBatchNames=computed(()=>scope.batchIds.length?batches.value.filter(x=>scope.batchIds.includes(x.id)).map(x=>x.name).join('、'):'全部')
const selectedBusinessUnitNames=computed(()=>scope.businessUnitIds.length?businessUnits.value.filter(x=>scope.businessUnitIds.includes(x.id)).map(x=>x.name).join('、'):'全部')
const scoreMonthLabel=computed(()=>plan.startsAt?plan.startsAt.slice(0,7):'选择开始时间后自动生成')
const windowMinutes=computed(()=>{
  if(!plan.startsAt||!plan.endsAt)return 0
  return Math.max(0,Math.floor((new Date(plan.endsAt).getTime()-new Date(plan.startsAt).getTime())/60000))
})

async function load(){
  const [planRes,paperRes,batchRes,businessUnitOptions,classValues]=await Promise.all([
    api.get<any,Envelope<any[]>>('/exams/plans'),
    api.get<any,Envelope<any[]>>('/exams/papers'),
    api.get<any,Envelope<any[]>>('/batches'),
    loadEnabledBusinessUnits(),
    loadDictionaryValues('CLASS')
  ])
  plans.value=planRes.data;papers.value=paperRes.data;batches.value=batchRes.data.filter(x=>x.enabled);businessUnits.value=businessUnitOptions;classOptions.value=classValues
}
function openCreate(){
  Object.assign(plan,emptyPlan());Object.assign(scope,{batchIds:[],businessUnitIds:[],classId:null,keyword:''})
  candidates.value=[];selectedCandidates.value=[];drawerVisible.value=true
}
function clearCandidates(){candidates.value=[];selectedCandidates.value=[]}
async function matchCandidates(){
  candidateLoading.value=true
  try{
    const params={batchIds:scope.batchIds.join(','),businessUnitIds:scope.businessUnitIds.join(','),classId:scope.classId,keyword:scope.keyword}
    const res=await api.get<any,Envelope<any[]>>('/exams/plans/candidates',{params})
    candidates.value=res.data;selectedCandidates.value=[]
    await nextTick()
    if(candidates.value.length)candidateTable.value?.toggleAllSelection()
    if(!candidates.value.length)ElMessage.warning('当前条件未匹配到可参考的在职员工')
  }finally{candidateLoading.value=false}
}
function onSelectionChange(rows:any[]){selectedCandidates.value=rows}
function onStartChange(value:string){
  if(!value)return
  if(!plan.endsAt||new Date(plan.endsAt)<=new Date(value)){
    const end=new Date(new Date(value).getTime()+Math.max(60,plan.durationMinutes)*60000)
    const pad=(n:number)=>String(n).padStart(2,'0')
    plan.endsAt=`${end.getFullYear()}-${pad(end.getMonth()+1)}-${pad(end.getDate())}T${pad(end.getHours())}:${pad(end.getMinutes())}:00`
  }
}
function validatePlan(){
  if(!plan.name.trim())return '请输入考试名称'
  if(!plan.paperId)return '请选择试卷'
  if(!plan.startsAt||!plan.endsAt)return '请设置考试开始和结束时间'
  if(new Date(plan.endsAt)<=new Date(plan.startsAt))return '结束时间必须晚于开始时间'
  if(windowMinutes.value<plan.durationMinutes)return '考试开放时段不能短于考试时长'
  if(!Number.isInteger(plan.violationLimit)||plan.violationLimit<1||plan.violationLimit>20)return '异常行为自动交卷次数应为 1 至 20 次'
  if(!Number.isInteger(plan.violationGraceSeconds)||plan.violationGraceSeconds<5||plan.violationGraceSeconds>300)return '异常离场限时应为 5 至 300 秒'
  if(!selectedCandidates.value.length)return '请匹配并选择至少一名参考人员'
  return ''
}
async function createPlan(publish:boolean){
  const message=validatePlan();if(message)return ElMessage.warning(message)
  saving.value=true
  try{
    const payload={...plan,name:plan.name.trim(),batchId:scope.batchIds.length===1?scope.batchIds[0]:null,batchIds:scope.batchIds,businessUnitIds:scope.businessUnitIds,employeeIds:selectedCandidates.value.map(x=>x.id)}
    const res=await api.post<any,Envelope<number>>('/exams/plans',payload)
    if(publish)await api.post(`/exams/plans/${res.data}/publish`)
    ElMessage.success(publish?'考试计划已创建并发布':'考试计划已保存为草稿')
    drawerVisible.value=false;await load()
  }finally{saving.value=false}
}
async function publishPlan(row:any){
  await ElMessageBox.confirm(`发布后，${row.assigned_count||0} 名员工将可以看到“${row.name}”，确认发布吗？`,'发布考试计划',{type:'warning',confirmButtonText:'确认发布'})
  await api.post(`/exams/plans/${row.id}/publish`);ElMessage.success('考试计划已发布');await load()
}
async function deletePlan(row:any){
  await ElMessageBox.confirm(`确认删除草稿“${row.name}”吗？已配置的参考人员范围也会一并移除。`,'删除考试计划',{type:'warning',confirmButtonText:'确认删除'})
  await api.delete(`/exams/plans/${row.id}`);ElMessage.success('考试计划草稿已删除');await load()
}
function planPhase(row:any){return planPhaseLabels[row.plan_phase]??{label:row.status,type:'info'}}
function planBatchNames(row:any){if(row.target_batch_names)return row.target_batch_names;return batches.value.find(x=>x.id===row.batch_id)?.name||'全部'}
function disabledPast(date:Date){return date.getTime()<new Date().setHours(0,0,0,0)}
onMounted(load)
</script>

<template>
  <div class="exam-module-page">
    <header class="exam-page-head">
      <div><span class="eyebrow">考试中心 · 考试计划</span><h1>考试计划</h1><p>统一设置考试试卷、开放时段、作答规则与参考人员范围。</p></div>
      <div class="exam-head-actions"><el-button type="primary" :icon="Plus" @click="openCreate">新建考试计划</el-button></div>
    </header>

    <section class="exam-summary-grid">
      <article class="exam-summary-card blue"><span class="exam-summary-icon"><el-icon><Calendar/></el-icon></span><div><small>计划总数</small><strong>{{overview.total}}</strong><span>全部考试安排</span></div></article>
      <article class="exam-summary-card amber"><span class="exam-summary-icon"><el-icon><Document/></el-icon></span><div><small>待发布草稿</small><strong>{{overview.draft}}</strong><span>需要确认发布</span></div></article>
      <article class="exam-summary-card violet"><span class="exam-summary-icon"><el-icon><Clock/></el-icon></span><div><small>待开始</small><strong>{{overview.upcoming}}</strong><span>已发布未开放</span></div></article>
      <article class="exam-summary-card green"><span class="exam-summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>进行中</small><strong>{{overview.open}}</strong><span>员工可参加</span></div></article>
    </section>

    <section class="exam-workspace">
        <div class="exam-workspace-head">
          <div><span class="card-title">计划列表</span><span class="header-tip">计分月份根据考试开始时间自动归属</span></div>
          <div class="filters">
            <el-input v-model="keyword" clearable placeholder="搜索考试或试卷" :prefix-icon="Search"/>
            <el-select v-model="statusFilter" clearable placeholder="计划状态">
              <el-option label="草稿" value="DRAFT"/><el-option label="待开始" value="UPCOMING"/><el-option label="进行中" value="OPEN"/><el-option label="已结束" value="ENDED"/>
            </el-select>
          </div>
        </div>
      <el-table :data="filteredPlans" empty-text="暂无考试计划" class="plan-table">
        <el-table-column label="考试信息" min-width="150">
          <template #default="s"><div class="exam-name">{{s.row.name}}</div><div class="sub-text">试卷：{{s.row.paper_name}}</div></template>
        </el-table-column>
        <el-table-column label="开放时间" width="180">
          <template #default="s"><div class="time-range"><span>开始 {{dateTimeParts(s.row.starts_at).date}} {{dateTimeParts(s.row.starts_at).time}}</span><span>结束 {{dateTimeParts(s.row.ends_at).date}} {{dateTimeParts(s.row.ends_at).time}}</span></div></template>
        </el-table-column>
        <el-table-column label="考试规则" width="125">
          <template #default="s"><div class="rule-cell"><span>{{s.row.duration_minutes}} 分钟 / 最多 {{s.row.max_attempts}} 次</span><span>异常达 {{s.row.violation_limit??4}} 次自动交卷</span><span>离场限时 {{s.row.violation_grace_seconds??15}} 秒</span></div></template>
        </el-table-column>
        <el-table-column label="计分月份" width="100"><template #default="s">{{scoreMonth(s.row.score_month)}}</template></el-table-column>
        <el-table-column label="批次" width="112" class-name="batch-column" label-class-name="batch-column" show-overflow-tooltip><template #default="s">{{planBatchNames(s.row)}}</template></el-table-column>
        <el-table-column label="板块" width="100" show-overflow-tooltip><template #default="s">{{s.row.target_business_unit_names||'全部'}}</template></el-table-column>
        <el-table-column label="参考人数" width="100" align="center" header-align="center"><template #default="s"><strong>{{s.row.assigned_count??0}}</strong></template></el-table-column>
        <el-table-column label="计划状态" width="92"><template #default="s"><el-tag :type="planPhase(s.row).type" effect="plain">{{planPhase(s.row).label}}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="126" fixed="right"><template #default="s"><div v-if="s.row.status==='DRAFT'" class="exam-table-actions"><el-button link type="primary" @click="publishPlan(s.row)">发布</el-button><el-button link type="danger" @click="deletePlan(s.row)">删除</el-button></div><span v-else class="muted">已发布</span></template></el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="drawerVisible" size="760px" class="plan-drawer" :close-on-click-modal="false">
      <template #header><div class="drawer-title"><h3>新建考试计划</h3><p>设置考试内容、开放时间与参考人员范围</p></div></template>
      <el-form label-position="top">
        <section class="form-section">
          <div class="section-title"><span>1</span><div><strong>基本信息</strong><small>明确考试名称并选择已完成组卷的试卷</small></div></div>
          <div class="form-grid">
            <el-form-item label="考试名称" required><el-input v-model="plan.name" maxlength="128" show-word-limit placeholder="例如：新员工安全规范月度考试"/></el-form-item>
            <el-form-item label="考试试卷" required>
              <el-select v-model="plan.paperId" filterable placeholder="请选择试卷">
                <el-option v-for="x in papers" :key="x.id" :value="x.id" :label="`${x.name}（${x.question_count}题 / ${x.total_score}分）`"/>
              </el-select>
            </el-form-item>
          </div>
          <div v-if="selectedPaper" class="paper-summary">已选择「{{selectedPaper.name}}」，共 {{selectedPaper.question_count}} 题，满分 {{selectedPaper.total_score}} 分</div>
        </section>

        <section class="form-section">
          <div class="section-title"><span>2</span><div><strong>考试安排</strong><small>计分月份将自动取考试开始时间所在月份</small></div></div>
          <div class="form-grid">
            <el-form-item label="开始时间" required><el-date-picker v-model="plan.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择开始时间" :disabled-date="disabledPast" @change="onStartChange"/></el-form-item>
            <el-form-item label="结束时间" required><el-date-picker v-model="plan.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择结束时间" :disabled-date="disabledPast"/></el-form-item>
            <el-form-item label="考试时长（分钟）" required><el-input-number v-model="plan.durationMinutes" :min="1" :max="600" controls-position="right"/></el-form-item>
            <el-form-item label="最大考试次数" required><el-input-number v-model="plan.maxAttempts" :min="1" :max="10" controls-position="right"/></el-form-item>
            <el-form-item label="异常行为自动交卷次数" required><el-input-number v-model="plan.violationLimit" :min="1" :max="20" controls-position="right"/><div class="field-help">累计异常行为达到该次数时立即自动交卷。</div></el-form-item>
            <el-form-item label="异常离场限时（秒）" required><el-input-number v-model="plan.violationGraceSeconds" :min="5" :max="300" controls-position="right"/><div class="field-help">切屏、退出全屏后须在此时间内返回并确认，否则自动交卷。</div></el-form-item>
          </div>
          <div class="schedule-summary">
            <div><span>自动计分月份</span><strong>{{scoreMonthLabel}}</strong></div>
            <div><span>开放时长</span><strong :class="{danger:windowMinutes&&windowMinutes<plan.durationMinutes}">{{windowMinutes?`${windowMinutes} 分钟`:'待设置'}}</strong></div>
          </div>
        </section>

        <section class="form-section">
          <div class="section-title"><span>3</span><div><strong>参考人员范围</strong><small>批次与板块均支持多选；某项不选择表示该维度全部，可在匹配结果中排除个别人员</small></div></div>
          <div class="scope-grid">
            <el-select v-model="scope.batchIds" multiple collapse-tags collapse-tags-tooltip clearable placeholder="全部批次" @change="clearCandidates"><el-option v-for="x in batches" :key="x.id" :label="x.name" :value="x.id"/></el-select>
            <el-select v-model="scope.businessUnitIds" multiple clearable placeholder="全部板块" @change="clearCandidates"><el-option v-for="x in businessUnits" :key="x.id" :label="x.name" :value="x.id"/></el-select>
            <el-select v-model="scope.classId" clearable filterable placeholder="全部班级" @change="clearCandidates"><el-option v-for="x in classOptions" :key="x.id" :label="x.label" :value="x.id"/></el-select>
            <el-input v-model="scope.keyword" clearable placeholder="姓名或工号（可选）" :prefix-icon="Search" @input="clearCandidates" @keyup.enter="matchCandidates"/>
            <el-button type="primary" plain :loading="candidateLoading" @click="matchCandidates">匹配人员</el-button>
          </div>
          <div class="scope-summary"><span>参考批次：<strong>{{selectedBatchNames}}</strong></span><span>参考板块：<strong>{{selectedBusinessUnitNames}}</strong></span></div>
          <div class="candidate-head">
            <span><el-icon><UserFilled/></el-icon> 匹配 {{candidates.length}} 人，已选择 <strong>{{selectedCandidates.length}}</strong> 人</span>
            <span class="muted">默认全选，可取消不需要参加的员工</span>
          </div>
          <el-table ref="candidateTable" :data="candidates" v-loading="candidateLoading" height="230" empty-text="请先设置筛选条件并点击“匹配人员”" @selection-change="onSelectionChange">
            <el-table-column type="selection" width="44"/><el-table-column prop="employee_no" label="工号" width="112"/><el-table-column prop="name" label="姓名" min-width="100"/><el-table-column prop="batch_name" label="批次" min-width="100"><template #default="s">{{s.row.batch_name||'未设置'}}</template></el-table-column><el-table-column prop="class_name" label="班级" min-width="100"><template #default="s">{{s.row.class_name||'未设置'}}</template></el-table-column><el-table-column prop="business_unit_name" label="所属板块" min-width="110"><template #default="s">{{s.row.business_unit_name||'未设置'}}</template></el-table-column>
          </el-table>
        </section>
      </el-form>
      <template #footer>
        <div class="drawer-footer"><span>将安排 <strong>{{selectedCandidates.length}}</strong> 人参考</span><div><el-button @click="drawerVisible=false">取消</el-button><el-button :loading="saving" @click="createPlan(false)">保存草稿</el-button><el-button type="primary" :loading="saving" @click="createPlan(true)">保存并发布</el-button></div></div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.overview-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:16px}.overview-item{padding:18px 20px;background:#fff;border:1px solid #e5e7eb;border-radius:8px;box-shadow:0 2px 8px rgba(31,41,55,.04)}.overview-item span{display:block;color:#7b8799;font-size:13px;margin-bottom:7px}.overview-item strong{font-size:25px;color:#253044}.overview-item.warning{border-left:3px solid #e6a23c}.overview-item.success{border-left:3px solid #67c23a}.card-head{display:flex;align-items:center;justify-content:space-between;gap:16px}.card-title{font-weight:600}.header-tip{color:#8a96a8;font-size:12px;margin-left:12px}.filters{display:flex;gap:10px}.filters .el-input{width:190px}.filters .el-select{width:130px}.plan-table :deep(.el-table__cell){padding:11px 0}.plan-table :deep(th.el-table__cell .cell){white-space:nowrap}.plan-table :deep(.batch-column .cell){padding-left:18px}.exam-name{font-weight:600;color:#303846}.sub-text{color:#8a96a8;font-size:12px;margin-top:4px}.time-range,.rule-cell{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#596579;white-space:nowrap}.drawer-title h3{font-size:20px;margin:0;color:#253044}.drawer-title p{font-size:13px;color:#7b8799;margin:7px 0 0}.form-section{padding:2px 0 24px;margin-bottom:22px;border-bottom:1px solid #edf0f4}.form-section:last-child{border-bottom:0;margin-bottom:0}.section-title{display:flex;align-items:flex-start;gap:10px;margin-bottom:17px}.section-title>span{display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:#ecf5ff;color:#409eff;font-weight:700}.section-title strong,.section-title small{display:block}.section-title strong{color:#253044}.section-title small{font-size:12px;color:#8a96a8;margin-top:4px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:2px 16px}.form-grid :deep(.el-input-number),.form-grid :deep(.el-date-editor),.form-grid :deep(.el-select){width:100%}.paper-summary{background:#f5f9ff;color:#4e6685;border-radius:6px;padding:10px 13px;font-size:13px}.schedule-summary{display:grid;grid-template-columns:1fr 1fr;gap:12px}.schedule-summary>div{background:#f7f9fc;border-radius:6px;padding:11px 14px}.schedule-summary span{display:block;color:#8a96a8;font-size:12px}.schedule-summary strong{display:block;color:#303846;margin-top:4px}.schedule-summary .danger{color:#f56c6c}.scope-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:10px}.scope-summary{display:flex;gap:20px;padding:9px 12px;margin-bottom:13px;border-radius:6px;background:#f7f9fc;color:#7b8799;font-size:12px}.scope-summary strong{color:#4e6685}.candidate-head{display:flex;align-items:center;justify-content:space-between;font-size:13px;margin-bottom:10px;color:#596579}.candidate-head .el-icon{vertical-align:-2px}.drawer-footer{display:flex;align-items:center;justify-content:space-between;width:100%}.drawer-footer>span{color:#7b8799;font-size:13px}.drawer-footer strong,.candidate-head strong{color:#409eff}@media(max-width:900px){.overview-grid{grid-template-columns:repeat(2,1fr)}.card-head{align-items:flex-start;flex-direction:column}.form-grid,.scope-grid{grid-template-columns:1fr}.candidate-head{align-items:flex-start;flex-direction:column;gap:5px}}
.field-help{margin-top:6px;color:#8a96a8;font-size:12px;line-height:1.5}
</style>
