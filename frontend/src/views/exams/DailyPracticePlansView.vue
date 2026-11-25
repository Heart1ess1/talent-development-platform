<script setup lang="ts">
import {computed,nextTick,onMounted,reactive,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Calendar,Clock,Download,Plus,Search,Tickets,UserFilled} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {loadEnabledBusinessUnits} from '@/utils/masterData'
import '@/styles/exam-center.css'

const plans=ref<any[]>([]),banks=ref<any[]>([]),batches=ref<any[]>([]),businessUnits=ref<any[]>([])
const candidates=ref<any[]>([]),selectedCandidates=ref<any[]>([]),candidateTable=ref<any>()
const drawerVisible=ref(false),candidateLoading=ref(false),saving=ref(false)
const editingId=ref<number|null>(null),editingStatus=ref('DRAFT'),originalEndDate=ref('')
const keyword=ref(''),statusFilter=ref('')
const scope=reactive<any>({batchIds:[] as number[],businessUnitIds:[] as number[],keyword:''})

const participantsDialog=ref(false),participants=ref<any[]>([]),participantPlan=ref<any>(null),participantLoading=ref(false)
const exclusionDialog=ref(false),exclusions=ref<any[]>([]),exclusionPlan=ref<any>(null)
const exclusionForm=reactive({date:'',reason:''})
const runsDialog=ref(false),runs=ref<any[]>([]),runsPlan=ref<any>(null)
const resultsDialog=ref(false),resultsPlan=ref<any>(null),resultsRows=ref<any[]>([]),resultsLoading=ref(false),exporting=ref(false)
const resultsFilters=reactive({dates:[] as string[],keyword:'',status:''})
const resultsPage=reactive({page:1,size:20,total:0})

const todayYmd=()=>{
  const d=new Date();const pad=(n:number)=>String(n).padStart(2,'0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`
}
const emptyPlan=()=>({
  name:'',description:'',bankIds:[] as number[],questionTypes:['SINGLE','MULTIPLE','TRUE_FALSE'] as string[],tags:[] as string[],
  dailyCount:10,scorePerQuestion:10,passScore:60,randomizeOptions:true,repeatPolicy:'ALLOW_REPEAT',includeWeekends:true,
  startDate:todayYmd(),endDate:'',pushTime:'00:00:00',deadlineTime:'23:59:59',version:null as number|null
})
const plan=reactive<any>(emptyPlan())

const locked=computed(()=>editingId.value!==null&&editingStatus.value!=='DRAFT')
const totalScore=computed(()=>Number(plan.dailyCount||0)*Number(plan.scorePerQuestion||0))
const filteredPlans=computed(()=>plans.value.filter(row=>{
  const text=`${row.name} ${row.bank_names||''}`.toLowerCase()
  return (!keyword.value||text.includes(keyword.value.trim().toLowerCase()))&&(!statusFilter.value||row.status===statusFilter.value)
}))
const overview=computed(()=>({
  total:plans.value.length,
  running:plans.value.filter(x=>x.status==='PUBLISHED').length,
  todaySuccess:plans.value.filter(x=>x.today_status==='SUCCESS').length,
  completion:(()=>{
    const total=plans.value.reduce((sum,x)=>sum+Number(x.today_session_count||0),0)
    const done=plans.value.reduce((sum,x)=>sum+Number(x.today_submitted_count||0),0)
    return total?Math.round(done/total*100):0
  })()
}))
const statusLabels:Record<string,{label:string;type:string}>={DRAFT:{label:'草稿',type:'info'},PUBLISHED:{label:'已发布',type:'success'},PAUSED:{label:'已暂停',type:'warning'},ENDED:{label:'已结束',type:'info'}}
const todayLabels:Record<string,string>={DRAFT:'未发布',PAUSED:'已暂停',ENDED:'已结束',OUT_OF_RANGE:'不在有效期',WEEKEND:'周末不推送',EXCLUDED:'排除日期',PENDING:'待生成',SUCCESS:'已生成',FAILED:'生成失败',SKIPPED:'已跳过',RUNNING:'生成中'}

async function load(){
  const [planRes,bankRes,batchRes,businessUnitOptions]=await Promise.all([
    api.get<any,Envelope<any[]>>('/exam-daily/plans'),
    api.get<any,Envelope<any[]>>('/exams/question-banks'),
    api.get<any,Envelope<any[]>>('/batches'),
    loadEnabledBusinessUnits()
  ])
  plans.value=planRes.data
  banks.value=bankRes.data.filter((x:any)=>x.enabled===true||x.enabled===1)
  batches.value=batchRes.data.filter((x:any)=>x.enabled===true||x.enabled===1)
  businessUnits.value=businessUnitOptions
}
function resetForm(){
  Object.assign(plan,emptyPlan())
  Object.assign(scope,{batchIds:[],businessUnitIds:[],keyword:''})
  candidates.value=[];selectedCandidates.value=[]
  editingId.value=null;editingStatus.value='DRAFT';originalEndDate.value=''
}
function openCreate(){resetForm();drawerVisible.value=true}
async function openEdit(row:any){
  const res=await api.get<any,Envelope<any>>(`/exam-daily/plans/${row.id}`)
  const d=res.data
  Object.assign(plan,{
    name:d.name,description:d.description||'',bankIds:d.bank_ids||[],questionTypes:d.question_types||[],tags:d.tags||[],
    dailyCount:d.daily_count,scorePerQuestion:Number(d.score_per_question),
    passScore:d.pass_score===null||d.pass_score===undefined?null:Number(d.pass_score),
    randomizeOptions:Boolean(d.randomize_options),repeatPolicy:d.repeat_policy,includeWeekends:Boolean(d.include_weekends),
    startDate:d.start_date,endDate:d.end_date,pushTime:d.push_time,deadlineTime:d.deadline_time,version:d.version
  })
  Object.assign(scope,{batchIds:d.batch_ids||[],businessUnitIds:d.business_unit_ids||[],keyword:''})
  editingId.value=d.id;editingStatus.value=d.status;originalEndDate.value=d.end_date
  candidates.value=(d.participants||[]).map((p:any)=>({id:p.employee_id,employee_no:p.employee_no,name:p.name,batch_name:p.batch_name,class_name:p.class_name,class_position_name:p.class_position_name,business_unit_name:p.business_unit_name,active:p.active}))
  selectedCandidates.value=candidates.value.filter((x:any)=>x.active)
  drawerVisible.value=true
  await nextTick()
  if(selectedCandidates.value.length)selectRows()
}
function selectRows(){
  const table=candidateTable.value
  if(!table)return
  table.clearSelection()
  for(const row of candidates.value)
    if(selectedCandidates.value.some((x:any)=>x.id===row.id))table.toggleRowSelection(row,true)
}
function onSelectionChange(rows:any[]){selectedCandidates.value=rows}
function clearCandidates(){candidates.value=[];selectedCandidates.value=[]}
async function matchCandidates(){
  candidateLoading.value=true
  try{
    const params={batchIds:scope.batchIds.join(','),businessUnitIds:scope.businessUnitIds.join(','),keyword:scope.keyword}
    const res=await api.get<any,Envelope<any[]>>('/exam-daily/candidates',{params})
    candidates.value=res.data;selectedCandidates.value=[]
    await nextTick()
    if(candidates.value.length)candidateTable.value?.toggleAllSelection()
    if(!candidates.value.length)ElMessage.warning('当前条件未匹配到在职且已开通账号的员工')
  }finally{candidateLoading.value=false}
}
function ymd(date:Date){const pad=(n:number)=>String(n).padStart(2,'0');return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}`}
function disabledStart(date:Date){return ymd(date)<todayYmd()}
function disabledEnd(date:Date){
  const value=ymd(date)
  if(plan.startDate&&value<plan.startDate)return true
  if(locked.value&&originalEndDate.value&&value<originalEndDate.value)return true
  return false
}
function validatePlan(){
  if(!plan.name.trim())return '请输入计划名称'
  if(!plan.questionTypes.length)return '请至少选择一种题型'
  if(!plan.dailyCount||plan.dailyCount<1)return '每日题数至少为 1 题'
  if(!plan.scorePerQuestion||plan.scorePerQuestion<=0)return '每题分值必须大于 0'
  if(plan.passScore!==null&&plan.passScore!==undefined&&plan.passScore!==''){
    if(Number(plan.passScore)<0||Number(plan.passScore)>totalScore.value)return `及格分必须在 0 至满分 ${totalScore.value} 之间`
  }
  if(!plan.startDate||!plan.endDate)return '请设置计划起止日期'
  if(plan.endDate<plan.startDate)return '结束日期不能早于开始日期'
  if(!plan.pushTime||!plan.deadlineTime)return '请设置每日推送与截止时刻'
  if(plan.pushTime>=plan.deadlineTime)return '每日推送时刻必须早于截止时刻'
  if(!selectedCandidates.value.length)return '请匹配并选择至少一名参与人员'
  return ''
}
async function savePlan(publish:boolean){
  const message=validatePlan();if(message)return ElMessage.warning(message)
  saving.value=true
  try{
    const payload={
      name:plan.name.trim(),description:plan.description?.trim()||null,bankIds:plan.bankIds,questionTypes:plan.questionTypes,tags:plan.tags,
      dailyCount:plan.dailyCount,scorePerQuestion:plan.scorePerQuestion,
      passScore:plan.passScore===null||plan.passScore===''?null:plan.passScore,
      startDate:plan.startDate,endDate:plan.endDate,pushTime:plan.pushTime,deadlineTime:plan.deadlineTime,
      randomizeOptions:plan.randomizeOptions,repeatPolicy:plan.repeatPolicy,includeWeekends:plan.includeWeekends,
      batchIds:scope.batchIds,businessUnitIds:scope.businessUnitIds,
      employeeIds:selectedCandidates.value.map(x=>x.id),version:plan.version
    }
    let planId=editingId.value
    if(planId){
      await api.put(`/exam-daily/plans/${planId}`,payload)
      if(publish)await api.post(`/exam-daily/plans/${planId}/publish`)
      ElMessage.success(publish?'每日练习计划已发布':'每日练习计划已保存')
    }else{
      const res=await api.post<any,Envelope<number>>('/exam-daily/plans',payload)
      planId=res.data
      if(publish)await api.post(`/exam-daily/plans/${planId}/publish`)
      ElMessage.success(publish?'每日练习计划已创建并发布':'每日练习计划已保存为草稿')
    }
    drawerVisible.value=false
    await load()
  }finally{saving.value=false}
}
async function publishRow(row:any){
  await ElMessageBox.confirm(`发布后将固化当前 ${row.participant_count||0} 名参与人，并从下一个推送时刻开始生成每日练习，确认发布“${row.name}”吗？`,'发布每日练习计划',{type:'warning',confirmButtonText:'确认发布'})
  await api.post(`/exam-daily/plans/${row.id}/publish`)
  ElMessage.success('每日练习计划已发布');await load()
}
async function pauseRow(row:any){
  await ElMessageBox.confirm(`暂停后“${row.name}”不再生成新的每日练习，已完成记录保留。确认暂停吗？`,'暂停计划',{type:'warning',confirmButtonText:'确认暂停'})
  await api.post(`/exam-daily/plans/${row.id}/pause`)
  ElMessage.success('计划已暂停');await load()
}
async function resumeRow(row:any){
  await api.post(`/exam-daily/plans/${row.id}/resume`)
  ElMessage.success('计划已恢复');await load()
}
async function deleteRow(row:any){
  await ElMessageBox.confirm(`确认删除草稿“${row.name}”吗？参与人、排除日期等配置会一并删除。`,'删除每日练习计划',{type:'warning',confirmButtonText:'确认删除'})
  await api.delete(`/exam-daily/plans/${row.id}`)
  ElMessage.success('计划草稿已删除');await load()
}
async function pushToday(row:any){
  await ElMessageBox.confirm(`将按计划规则校验并触发“${row.name}”今天的生成入口，确认继续吗？`,'立即推送今天',{type:'warning',confirmButtonText:'确认推送'})
  const res=await api.post<any,Envelope<any>>(`/exam-daily/plans/${row.id}/push`)
  ElMessage.success(res.data?.message||'今日推送校验通过')
  await load()
}
async function openParticipants(row:any){
  participantLoading.value=true
  try{
    const res=await api.get<any,Envelope<any>>(`/exam-daily/plans/${row.id}`)
    participantPlan.value=res.data;participants.value=res.data.participants||[]
    participantsDialog.value=true
  }finally{participantLoading.value=false}
}
async function reloadParticipants(){
  if(!participantPlan.value)return
  const res=await api.get<any,Envelope<any>>(`/exam-daily/plans/${participantPlan.value.id}`)
  participantPlan.value=res.data;participants.value=res.data.participants||[]
}
async function refreshParticipants(){
  participantLoading.value=true
  try{
    const res=await api.post<any,Envelope<number>>(`/exam-daily/plans/${participantPlan.value.id}/participants/refresh`)
    ElMessage.success(`已按当前范围新增 ${res.data} 名参与人`)
    await reloadParticipants();await load()
  }finally{participantLoading.value=false}
}
async function toggleParticipant(row:any,value:boolean){
  await api.put(`/exam-daily/plans/${participantPlan.value.id}/participants/${row.employee_id}`,{active:value})
  row.active=value
  ElMessage.success(value?'已恢复该人员参与':'已停止该人员参与')
  await load()
}
async function openExclusions(row:any){
  const res=await api.get<any,Envelope<any>>(`/exam-daily/plans/${row.id}`)
  exclusionPlan.value=res.data;exclusions.value=res.data.exclusions||[]
  exclusionForm.date='';exclusionForm.reason=''
  exclusionDialog.value=true
}
async function reloadExclusions(){
  const res=await api.get<any,Envelope<any>>(`/exam-daily/plans/${exclusionPlan.value.id}`)
  exclusionPlan.value=res.data;exclusions.value=res.data.exclusions||[]
}
async function addExclusion(){
  if(!exclusionForm.date)return ElMessage.warning('请选择要排除的日期')
  await api.post(`/exam-daily/plans/${exclusionPlan.value.id}/exclusions`,{date:exclusionForm.date,reason:exclusionForm.reason||null})
  ElMessage.success('排除日期已添加')
  exclusionForm.date='';exclusionForm.reason=''
  await reloadExclusions();await load()
}
async function removeExclusion(row:any){
  await api.delete(`/exam-daily/plans/${exclusionPlan.value.id}/exclusions/${row.exclude_date}`)
  ElMessage.success('排除日期已删除');await reloadExclusions();await load()
}
async function openRuns(row:any){
  const res=await api.get<any,Envelope<any[]>>(`/exam-daily/plans/${row.id}/runs`)
  runs.value=res.data;runsPlan.value=row;runsDialog.value=true
}
function openResults(row:any){
  resultsPlan.value=row
  resultsFilters.dates=[];resultsFilters.keyword='';resultsFilters.status=''
  resultsPage.page=1
  resultsDialog.value=true
  void loadResults()
}
function resultParams(){
  return {
    dateFrom:resultsFilters.dates?.[0]||undefined,dateTo:resultsFilters.dates?.[1]||undefined,
    keyword:resultsFilters.keyword.trim()||undefined,status:resultsFilters.status||undefined
  }
}
async function loadResults(){
  if(!resultsPlan.value)return
  resultsLoading.value=true
  try{
    const res=await api.get<any,Envelope<{records:any[];total:number}>>(`/exam-daily/plans/${resultsPlan.value.id}/results`,
      {params:{...resultParams(),page:resultsPage.page,size:resultsPage.size}})
    resultsRows.value=res.data.records||[]
    resultsPage.total=Number(res.data.total||0)
  }finally{resultsLoading.value=false}
}
function searchResults(){resultsPage.page=1;void loadResults()}
function resetResults(){resultsFilters.dates=[];resultsFilters.keyword='';resultsFilters.status='';searchResults()}
function changeResultsPage(page:number){resultsPage.page=page;void loadResults()}
function changeResultsSize(size:number){resultsPage.size=size;resultsPage.page=1;void loadResults()}
async function exportResults(){
  if(!resultsPlan.value)return
  if(!resultsPage.total)return ElMessage.warning('当前筛选条件下没有可导出的成绩')
  exporting.value=true
  try{
    const blob=await api.get<any,Blob>(`/exam-daily/plans/${resultsPlan.value.id}/results/export`,{params:resultParams(),responseType:'blob'})
    const url=URL.createObjectURL(blob),a=document.createElement('a')
    a.href=url;a.download=`每日练习成绩-${resultsPlan.value.name}.xlsx`;a.click();URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${resultsPage.total} 条成绩`)
  }finally{exporting.value=false}
}
const resultStatusLabels:Record<string,{label:string;type:string}>={PENDING:{label:'待开始',type:'info'},IN_PROGRESS:{label:'作答中',type:'warning'},SUBMITTED:{label:'已提交',type:'success'},EXPIRED:{label:'已过期',type:'danger'}}
function resultStatus(row:any){return resultStatusLabels[row.status]||{label:row.status||'--',type:'info'}}
function resultTimeText(value:any){return value?String(value).replace('T',' ').slice(0,19):'—'}
function onMore(command:string,row:any){
  if(command==='participants')openParticipants(row)
  else if(command==='exclusions')openExclusions(row)
  else if(command==='runs')openRuns(row)
  else if(command==='results')openResults(row)
}
function toggleFor(row:any){return (value:any)=>toggleParticipant(row,Boolean(value))}
function statusTag(row:any){return statusLabels[row.status]||{label:row.status,type:'info'}}
function todayLabel(row:any){return todayLabels[row.today_status]||row.today_status||'待生成'}
function todayTagType(row:any){
  if(row.today_status==='SUCCESS')return 'success'
  if(row.today_status==='FAILED')return 'danger'
  if(row.today_status==='SKIPPED'||row.today_status==='EXCLUDED'||row.today_status==='WEEKEND')return 'info'
  if(row.today_status==='PENDING'||row.today_status==='RUNNING')return 'warning'
  return 'info'
}
function ruleText(row:any){
  const pass=row.pass_score===null||row.pass_score===undefined?'':' / 及格 '+Number(row.pass_score)
  return `每日 ${row.daily_count} 题 / 每题 ${Number(row.score_per_question)} 分${pass}`
}
onMounted(load)
</script>

<template>
  <div class="exam-module-page">
    <header class="exam-page-head">
      <div>
        <span class="eyebrow">考试中心</span>
        <h1>每日练习计划</h1>
        <p>按计划每天为参与人随机抽取题目，支持推送时段、每日完成时限、节假日排除与发布后参与人快照。所有时间均为北京时间。</p>
      </div>
      <div class="exam-head-actions"><el-button type="primary" :icon="Plus" @click="openCreate">新建练习计划</el-button></div>
    </header>

    <section class="exam-summary-grid">
      <article class="exam-summary-card blue"><span class="exam-summary-icon"><el-icon><Calendar/></el-icon></span><div><small>计划总数</small><strong>{{overview.total}}</strong></div></article>
      <article class="exam-summary-card violet"><span class="exam-summary-icon"><el-icon><Clock/></el-icon></span><div><small>进行中</small><strong>{{overview.running}}</strong></div></article>
      <article class="exam-summary-card amber"><span class="exam-summary-icon"><el-icon><Tickets/></el-icon></span><div><small>今日生成成功</small><strong>{{overview.todaySuccess}}</strong></div></article>
      <article class="exam-summary-card green"><span class="exam-summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>今日完成率</small><strong>{{overview.completion}}%</strong></div></article>
    </section>

    <section class="exam-workspace">
      <div class="exam-workspace-head">
        <div><span class="card-title">计划列表</span></div>
        <div class="filters">
          <el-input v-model="keyword" clearable placeholder="搜索计划或题库" :prefix-icon="Search"/>
          <el-select v-model="statusFilter" clearable filterable placeholder="计划状态">
            <el-option label="草稿" value="DRAFT"/><el-option label="已发布" value="PUBLISHED"/><el-option label="已暂停" value="PAUSED"/><el-option label="已结束" value="ENDED"/>
          </el-select>
        </div>
      </div>
      <el-table :data="filteredPlans" empty-text="暂无每日练习计划" class="plan-table">
        <el-table-column label="计划信息" min-width="180">
          <template #default="s"><div class="exam-name">{{s.row.name}}</div><div class="sub-text">{{s.row.description||'按计划每天随机抽题'}}</div></template>
        </el-table-column>
        <el-table-column label="题库" width="160" show-overflow-tooltip><template #default="s">{{s.row.bank_names||'全部启用题库'}}</template></el-table-column>
        <el-table-column label="练习规则" width="200"><template #default="s"><div class="rule-cell">{{ruleText(s.row)}}<span>可用题 {{s.row.available_questions}} 题</span></div></template></el-table-column>
        <el-table-column label="有效期" width="170"><template #default="s"><div class="rule-cell"><span>{{s.row.start_date}} 起</span><span>{{s.row.end_date}} 止</span></div></template></el-table-column>
        <el-table-column label="推送 / 截止" width="130"><template #default="s"><div class="rule-cell"><span>{{s.row.push_time}}</span><span>{{s.row.deadline_time}} 截止</span></div></template></el-table-column>
        <el-table-column label="参与人" width="90" align="center" header-align="center">
          <template #default="s"><strong>{{s.row.participant_count||0}}</strong><div v-if="s.row.stopped_count" class="sub-text">停用 {{s.row.stopped_count}}</div></template>
        </el-table-column>
        <el-table-column label="今日生成" width="120">
          <template #default="s">
            <el-tag :type="todayTagType(s.row)" effect="plain" class="today-tag">{{todayLabel(s.row)}}</el-tag>
            <div v-if="s.row.today_session_count" class="sub-text">{{s.row.today_submitted_count||0}} / {{s.row.today_session_count}} 已提交</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90"><template #default="s"><el-tag :type="statusTag(s.row).type" effect="plain">{{statusTag(s.row).label}}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="s">
            <div v-if="s.row.status==='DRAFT'" class="exam-table-actions">
              <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>
              <el-button link type="success" @click="publishRow(s.row)">发布</el-button>
              <el-button link type="danger" @click="deleteRow(s.row)">删除</el-button>
            </div>
            <div v-else class="exam-table-actions">
              <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>
              <el-button v-if="s.row.status==='PUBLISHED'" link type="warning" @click="pauseRow(s.row)">暂停</el-button>
              <el-button v-else-if="s.row.status==='PAUSED'" link type="success" @click="resumeRow(s.row)">恢复</el-button>
              <el-button link type="primary" @click="pushToday(s.row)">推送今天</el-button>
              <el-dropdown trigger="click" @command="(command:string)=>onMore(command,s.row)">
                <el-button link type="primary">更多</el-button>
                <template #dropdown><el-dropdown-menu>
                  <el-dropdown-item command="results">成绩查询</el-dropdown-item>
                  <el-dropdown-item command="participants">参与人管理</el-dropdown-item>
                  <el-dropdown-item command="exclusions">排除日期</el-dropdown-item>
                  <el-dropdown-item command="runs">生成记录</el-dropdown-item>
                </el-dropdown-menu></template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="drawerVisible" size="780px" :close-on-click-modal="false">
      <template #header>
        <div class="drawer-title"><h3>{{editingId?'编辑每日练习计划':'新建每日练习计划'}}</h3><p v-if="locked">计划已发布：题库、题型、标签、题数、分值等规则不可修改，可延长结束日期、调整未来日期的推送/截止时刻或追加参与人。</p></div>
      </template>
      <el-form label-position="top">
        <section class="form-section">
          <div class="section-title"><span>1</span><div><strong>基本信息</strong><small>限定抽题范围；题库、题型、标签都不选表示全部启用题库中的客观题</small></div></div>
          <div class="form-grid">
            <el-form-item label="计划名称" required><el-input v-model="plan.name" maxlength="128" show-word-limit placeholder="例如：2026 年 12 月每日安全练习"/></el-form-item>
            <el-form-item label="计划说明"><el-input v-model="plan.description" maxlength="500" placeholder="选填，说明练习目的或范围"/></el-form-item>
          </div>
          <div class="form-grid">
            <el-form-item label="限定题库"><el-select v-model="plan.bankIds" multiple filterable clearable collapse-tags collapse-tags-tooltip placeholder="全部启用题库" :disabled="locked">
              <el-option v-for="b in banks" :key="b.id" :label="`${b.name}（可用 ${b.enabled_count} 题）`" :value="b.id"/>
            </el-select></el-form-item>
            <el-form-item label="题型"><el-checkbox-group v-model="plan.questionTypes" :disabled="locked">
              <el-checkbox value="SINGLE">单选题</el-checkbox><el-checkbox value="MULTIPLE">多选题</el-checkbox><el-checkbox value="TRUE_FALSE">判断题</el-checkbox>
            </el-checkbox-group></el-form-item>
          </div>
          <el-form-item label="专业标签（可选）"><el-select v-model="plan.tags" multiple filterable allow-create default-first-option :reserve-keyword="false" placeholder="输入标签后回车；命中标签的题与无标签公共题都会参与" :disabled="locked"/></el-form-item>
        </section>

        <section class="form-section">
          <div class="section-title"><span>2</span><div><strong>练习规则</strong><small>题目只取客观题，提交后自动判分；练习成绩独立留档，不计入月度评价</small></div></div>
          <div class="form-grid">
            <el-form-item label="每日题数" required><el-input-number v-model="plan.dailyCount" :min="1" :max="200" controls-position="right" :disabled="locked"/></el-form-item>
            <el-form-item label="每题分值" required><el-input-number v-model="plan.scorePerQuestion" :min="0.01" :max="100" :precision="2" :step="1" controls-position="right" :disabled="locked"/></el-form-item>
            <el-form-item label="及格分（可选）"><el-input-number v-model="plan.passScore" :min="0" :max="totalScore" :precision="2" :step="1" controls-position="right" :disabled="locked"/></el-form-item>
            <el-form-item label="题库不足时的策略">
              <el-select v-model="plan.repeatPolicy" filterable :disabled="locked">
                <el-option label="循环复用已做过的题" value="ALLOW_REPEAT"/><el-option label="能抽多少抽多少" value="REDUCE_COUNT"/><el-option label="当天不生成" value="FAIL"/>
              </el-select>
            </el-form-item>
          </div>
          <div class="switch-row">
            <div><span>选项乱序</span><small>同一份练习刷新后选项顺序保持不变</small></div><el-switch v-model="plan.randomizeOptions" :disabled="locked"/>
            <div><span>含周末</span><small>关闭后周六、周日不推送</small></div><el-switch v-model="plan.includeWeekends" :disabled="locked"/>
          </div>
          <div class="rule-summary"><span>每日满分</span><strong>{{totalScore}} 分</strong><span>每日题数 × 每题分值</span></div>
        </section>

        <section class="form-section">
          <div class="section-title"><span>3</span><div><strong>时间与范围</strong><small>每天在推送时刻生成，截止时刻前可作答；发布后固化参与人快照</small></div></div>
          <div class="form-grid">
            <el-form-item label="开始日期" required><el-date-picker v-model="plan.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择开始日期" :disabled="locked" :disabled-date="disabledStart"/></el-form-item>
            <el-form-item label="结束日期" required><el-date-picker v-model="plan.endDate" type="date" value-format="YYYY-MM-DD" placeholder="选择结束日期" :disabled-date="disabledEnd"/></el-form-item>
            <el-form-item label="每日推送时刻" required><el-time-picker v-model="plan.pushTime" value-format="HH:mm:ss" format="HH:mm:ss" arrow-control placeholder="00:00:00"/></el-form-item>
            <el-form-item label="每日截止时刻" required><el-time-picker v-model="plan.deadlineTime" value-format="HH:mm:ss" format="HH:mm:ss" arrow-control placeholder="23:59:59"/></el-form-item>
          </div>
          <div class="scope-grid">
            <el-select v-model="scope.batchIds" multiple filterable clearable placeholder="全部批次" @change="clearCandidates"><el-option v-for="x in batches" :key="x.id" :label="x.name" :value="x.id"/></el-select>
            <el-select v-model="scope.businessUnitIds" multiple clearable filterable placeholder="全部板块" @change="clearCandidates"><el-option v-for="x in businessUnits" :key="x.id" :label="x.name" :value="x.id"/></el-select>
            <el-input v-model="scope.keyword" clearable placeholder="姓名或工号（可选）" :prefix-icon="Search" @input="clearCandidates" @keyup.enter="matchCandidates"/>
            <el-button type="primary" plain :loading="candidateLoading" @click="matchCandidates">匹配人员</el-button>
          </div>
          <div class="candidate-head">
            <span><el-icon><UserFilled/></el-icon> 匹配 {{candidates.length}} 人，已选择 <strong>{{selectedCandidates.length}}</strong> 人</span>
            <span class="muted">默认全选，可取消不需要参加的员工</span>
          </div>
          <el-table ref="candidateTable" :data="candidates" v-loading="candidateLoading" height="220" empty-text="请先设置筛选条件并点击“匹配人员”" @selection-change="onSelectionChange">
            <el-table-column type="selection" width="44"/>
            <el-table-column prop="employee_no" label="工号" width="112"/>
            <el-table-column prop="name" label="姓名" min-width="90"/>
            <el-table-column label="批次" min-width="96"><template #default="s">{{s.row.batch_name||'未设置'}}</template></el-table-column>
            <el-table-column label="班级" min-width="96"><template #default="s">{{s.row.class_name||'未设置'}}</template></el-table-column>
            <el-table-column label="班级职务" min-width="100"><template #default="s">{{s.row.class_position_name||'未设置'}}</template></el-table-column>
            <el-table-column label="所属板块" min-width="104"><template #default="s">{{s.row.business_unit_name||'未设置'}}</template></el-table-column>
          </el-table>
        </section>
      </el-form>
      <template #footer>
        <div class="drawer-footer">
          <span>将安排 <strong>{{selectedCandidates.length}}</strong> 人参与</span>
          <div>
            <el-button @click="drawerVisible=false">取消</el-button>
            <el-button :loading="saving" @click="savePlan(false)">{{editingId?'保存修改':'保存草稿'}}</el-button>
            <el-button v-if="!editingId||editingStatus==='DRAFT'" type="primary" :loading="saving" @click="savePlan(true)">保存并发布</el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="participantsDialog" title="参与人管理" width="820px">
      <div class="dialog-toolbar">
        <span>共 {{participants.length}} 人，参与中 {{participants.filter(x=>x.active).length}} 人（发布时固化，刷新只追加不删除历史）</span>
        <el-button size="small" :loading="participantLoading" @click="refreshParticipants">按范围刷新参与人</el-button>
      </div>
      <el-table :data="participants" height="430" empty-text="暂无参与人">
        <el-table-column prop="employee_no" label="工号" width="112"/>
        <el-table-column prop="name" label="姓名" width="100"/>
        <el-table-column label="批次" min-width="100"><template #default="s">{{s.row.batch_name||'未设置'}}</template></el-table-column>
        <el-table-column label="班级" min-width="100"><template #default="s">{{s.row.class_name||'未设置'}}</template></el-table-column>
        <el-table-column label="班级职务" min-width="110"><template #default="s">{{s.row.class_position_name||'未设置'}}</template></el-table-column>
        <el-table-column label="所属板块" min-width="110"><template #default="s">{{s.row.business_unit_name||'未设置'}}</template></el-table-column>
        <el-table-column label="参与状态" width="100" align="center">
          <template #default="s"><el-switch :model-value="s.row.active" @change="toggleFor(s.row)"/></template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="exclusionDialog" title="排除日期" width="580px">
      <div class="exclusion-form">
        <el-date-picker v-model="exclusionForm.date" type="date" value-format="YYYY-MM-DD" placeholder="选择排除日期"/>
        <el-input v-model="exclusionForm.reason" maxlength="200" placeholder="原因（可选，如节假日）"/>
        <el-button type="primary" @click="addExclusion">添加</el-button>
      </div>
      <el-table :data="exclusions" height="320" empty-text="暂无排除日期">
        <el-table-column prop="exclude_date" label="日期" width="140"/>
        <el-table-column prop="reason" label="原因" min-width="160"><template #default="s">{{s.row.reason||'—'}}</template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="s"><el-button link type="danger" @click="removeExclusion(s.row)">删除</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="runsDialog" :title="`生成记录${runsPlan?' · '+runsPlan.name:''}`" width="680px">
      <el-table :data="runs" height="380" empty-text="暂无生成记录（每日生成逻辑将在阶段三上线）">
        <el-table-column prop="practice_date" label="练习日期" width="120"/>
        <el-table-column prop="status" label="状态" width="100"><template #default="s">{{todayLabels[s.row.status]||s.row.status}}</template></el-table-column>
        <el-table-column prop="generated_count" label="生成人数" width="100" align="center"/>
        <el-table-column prop="failed_count" label="失败人数" width="100" align="center"/>
        <el-table-column prop="error_message" label="错误摘要" min-width="180" show-overflow-tooltip/>
      </el-table>
    </el-dialog>

    <el-drawer v-model="resultsDialog" :title="`成绩查询${resultsPlan?' · '+resultsPlan.name:''}`" size="900px">
      <div class="filters results-filters">
        <el-date-picker v-model="resultsFilters.dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" unlink-panels/>
        <el-input v-model="resultsFilters.keyword" clearable placeholder="工号或姓名" :prefix-icon="Search" @keyup.enter="searchResults"/>
        <el-select v-model="resultsFilters.status" clearable filterable placeholder="全部状态">
          <el-option label="待开始" value="PENDING"/><el-option label="作答中" value="IN_PROGRESS"/>
          <el-option label="已提交" value="SUBMITTED"/><el-option label="已过期" value="EXPIRED"/>
        </el-select>
        <el-button type="primary" plain @click="searchResults">查询</el-button>
        <el-button @click="resetResults">重置</el-button>
        <el-button type="primary" :icon="Download" :loading="exporting" @click="exportResults">导出</el-button>
      </div>
      <el-table :data="resultsRows" v-loading="resultsLoading" height="520" empty-text="当前筛选条件下暂无成绩">
        <el-table-column prop="employee_no" label="工号" width="120"/>
        <el-table-column prop="employee_name" label="姓名" width="100"/>
        <el-table-column label="批次" min-width="110"><template #default="s">{{s.row.batch_name||'—'}}</template></el-table-column>
        <el-table-column prop="practice_date" label="日期" width="110"/>
        <el-table-column prop="question_count" label="题数" width="70" align="center"/>
        <el-table-column prop="correct_count" label="正确数" width="80" align="center"/>
        <el-table-column label="得分" width="90" align="center"><template #default="s"><strong>{{s.row.total_score==null?'—':Number(s.row.total_score)}}</strong></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="s"><el-tag :type="resultStatus(s.row).type" effect="plain">{{resultStatus(s.row).label}}</el-tag></template></el-table-column>
        <el-table-column label="提交时间" min-width="160"><template #default="s">{{resultTimeText(s.row.submitted_at)}}</template></el-table-column>
      </el-table>
      <div class="results-pager">
        <el-pagination background layout="total, sizes, prev, pager, next" :total="resultsPage.total"
                       :current-page="resultsPage.page" :page-size="resultsPage.size" :page-sizes="[20,50,100]"
                       @current-change="changeResultsPage" @size-change="changeResultsSize"/>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.plan-table :deep(.el-table__cell){padding:11px 0}
.plan-table :deep(th.el-table__cell .cell){white-space:nowrap}
.filters{display:flex;gap:10px}.filters .el-input{width:190px}.filters .el-select{width:130px}
.results-filters{flex-wrap:wrap;align-items:center;margin-bottom:12px}.results-filters .el-input{width:170px}
.results-filters .el-date-editor{width:260px}.results-pager{display:flex;justify-content:flex-end;padding-top:12px}
.exam-name{font-weight:600;color:#373737}.sub-text{color:#959595;font-size:14px;margin-top:4px}
.rule-cell{display:flex;flex-direction:column;gap:4px;font-size:14px;color:#646464;white-space:nowrap}
.today-tag{margin-bottom:2px}
.drawer-title h3{font-size:22px;margin:0;color:#2f2f2f}.drawer-title p{font-size:14px;color:#868686;margin:7px 0 0}
.form-section{padding:2px 0 24px;margin-bottom:22px;border-bottom:1px solid #f0f0f0}.form-section:last-child{border-bottom:0;margin-bottom:0}
.section-title{display:flex;align-items:flex-start;gap:10px;margin-bottom:17px}
.section-title>span{display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:var(--app-primary-soft);color:var(--app-primary);font-weight:700}
.section-title strong,.section-title small{display:block}.section-title strong{color:#2f2f2f}.section-title small{font-size:14px;color:#959595;margin-top:4px}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:2px 16px}
.form-grid :deep(.el-input-number),.form-grid :deep(.el-date-editor),.form-grid :deep(.el-select){width:100%}
.switch-row{display:grid;grid-template-columns:1fr auto 1fr auto;align-items:center;gap:8px 18px;padding:10px 12px;margin-bottom:12px;border-radius:6px;background:var(--app-primary-soft)}
.switch-row span{display:block;font-size:14px;color:#4a4a4a}.switch-row small{display:block;margin-top:3px;font-size:13px;color:#959595}
.rule-summary{display:grid;grid-template-columns:auto auto 1fr;align-items:baseline;gap:8px;padding:10px 13px;border-radius:6px;background:var(--app-primary-soft)}
.rule-summary span{color:#959595;font-size:14px}.rule-summary strong{color:#373737;font-size:18px}
.scope-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:10px 0;align-items:start}
/* 批次、板块多选不折叠标签：标签自然换行，控件随内容增高，不影响同段其它控件顶部对齐 */
.scope-grid>.el-select{width:100%;min-width:0}
.scope-grid :deep(.el-select__wrapper){height:auto}
.scope-grid :deep(.el-select__selection){flex-wrap:wrap;row-gap:4px}
.candidate-head{display:flex;align-items:center;justify-content:space-between;font-size:14px;margin-bottom:10px;color:#646464}
.candidate-head .el-icon{vertical-align:-2px}.candidate-head strong{color:var(--app-primary)}
.drawer-footer{display:flex;align-items:center;justify-content:space-between;width:100%}.drawer-footer>span{color:#868686;font-size:14px}.drawer-footer strong{color:var(--app-primary)}
.dialog-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px;color:#868686;font-size:14px}
.exclusion-form{display:grid;grid-template-columns:180px 1fr auto;gap:10px;margin-bottom:14px}
@media(max-width:900px){.form-grid,.scope-grid{grid-template-columns:1fr}.switch-row{grid-template-columns:1fr auto}.filters{flex-direction:column}.filters .el-input,.filters .el-select{width:100%}.exclusion-form{grid-template-columns:1fr}}
</style>
