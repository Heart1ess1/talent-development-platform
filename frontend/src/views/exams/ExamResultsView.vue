<script setup lang="ts">
import {computed,nextTick,onMounted,reactive,ref} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {ArrowRight,Calendar,CircleCheck,Download,Histogram,Search,Timer} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {loadDictionaryValues,type DictionaryOption} from '@/utils/masterData'
import {dateTimeParts,resultStatusLabels,scoreMonth} from './examUi'
import '@/styles/exam-center.css'

const auth=useAuthStore(),route=useRoute(),canManage=computed(()=>auth.can('exam:manage'))
const plans=ref<any[]>([]),results=ref<any[]>([]),planResults=ref<any[]>([])
const reviewQueue=ref<any[]>([]),reviewVisible=ref(false),reviewLoading=ref(false),reviewAttempt=ref<any>(),grades=reactive<Record<number,{score:number;comment:string}>>({})
const keyword=ref(''),phase=ref(''),detailKeyword=ref(''),detailStatus=ref('')
const detailClassId=ref<number|null>(null),classOptions=ref<DictionaryOption[]>([])
const detailVisible=ref(false),detailLoading=ref(false),exporting=ref(false),selectedPlan=ref<any>(null)
const pendingReviews=computed(()=>reviewQueue.value.filter(x=>x.status==='PENDING_REVIEW'))

const filteredPlans=computed(()=>plans.value.filter(row=>{
  const matchesKeyword=!keyword.value||`${row.name} ${row.paper_name}`.toLowerCase().includes(keyword.value.trim().toLowerCase())
  return matchesKeyword&&(!phase.value||row.plan_phase===phase.value)
}))
const overview=computed(()=>({
  total:plans.value.length,
  upcoming:plans.value.filter(x=>x.plan_phase==='UPCOMING').length,
  open:plans.value.filter(x=>x.plan_phase==='OPEN').length,
  ended:plans.value.filter(x=>x.plan_phase==='ENDED').length
}))
const filteredPlanResults=computed(()=>planResults.value.filter(row=>{
  const matchesKeyword=!detailKeyword.value||`${row.employee_name} ${row.employee_no}`.toLowerCase().includes(detailKeyword.value.trim().toLowerCase())
  return matchesKeyword&&(!detailClassId.value||row.class_id===detailClassId.value)&&(!detailStatus.value||row.participation_status===detailStatus.value)
}))
const detailOverview=computed(()=>({
  assigned:planResults.value.length,
  completed:planResults.value.filter(x=>x.participation_status==='COMPLETED').length,
  incomplete:planResults.value.filter(x=>!['COMPLETED','ABSENT'].includes(x.participation_status)).length,
  absent:planResults.value.filter(x=>x.participation_status==='ABSENT').length
}))

async function load(){
  if(canManage.value){const [planResponse,reviewResponse]=await Promise.all([api.get<any,Envelope<any[]>>('/exams/results/manage/plans'),api.get<any,Envelope<any[]>>('/exams/review')]);plans.value=planResponse.data;reviewQueue.value=reviewResponse.data}
  else results.value=(await api.get<any,Envelope<any[]>>('/exams/results')).data
}
async function openReview(row:any){reviewVisible.value=true;reviewLoading.value=true;try{reviewAttempt.value=(await api.get<any,Envelope<any>>(`/exams/attempts/${row.id}`)).data;for(const question of reviewAttempt.value.questions.filter((x:any)=>x.question_type==='SHORT'))grades[question.id]={score:Number(question.answer_score??0),comment:question.reviewer_comment||''}}finally{reviewLoading.value=false}}
function answerText(value:any){if(value===null||value===undefined)return '未作答';if(typeof value==='string')return value;return Array.isArray(value)?value.join('、'):JSON.stringify(value)}
async function gradeQuestion(question:any){const grade=grades[question.id];await api.put(`/exams/attempts/${reviewAttempt.value.id}/questions/${question.id}/grade`,grade);ElMessage.success('本题评分已保存');const refreshed=(await api.get<any,Envelope<any>>(`/exams/attempts/${reviewAttempt.value.id}`)).data;reviewAttempt.value=refreshed;await load();if(refreshed.status==='GRADED')ElMessage.success('该答卷已完成阅卷，将在整场考试结束后自动下发')}
async function openPlan(row:any){
  selectedPlan.value=row;detailKeyword.value='';detailClassId.value=null;detailStatus.value='';detailVisible.value=true;detailLoading.value=true
  try{planResults.value=(await api.get<any,Envelope<any[]>>(`/exams/results/manage/plans/${row.id}`)).data}
  finally{detailLoading.value=false}
}
async function exportResults(planId?:number){
  exporting.value=true
  try{
    const blob=await api.get<any,Blob>('/exams/results/export',{params:planId?{planId}:{},responseType:'blob'})
    const url=URL.createObjectURL(blob),link=document.createElement('a')
    link.href=url
    link.download=planId&&selectedPlan.value?`${selectedPlan.value.name}-考试成绩.xlsx`:'考试成绩.xlsx'
    link.click()
    URL.revokeObjectURL(url)
  }finally{exporting.value=false}
}
function resultStatus(row:any){return resultStatusLabels[row.result_status]??{label:'--',type:'success'}}
function isPublished(row:any){return row.published===true||row.published===1}
function planPhase(row:any){
  return ({UPCOMING:{label:'待开始',type:'info'},OPEN:{label:'进行中',type:'success'},ENDED:{label:'已结束',type:'warning'}} as any)[row.plan_phase]??{label:'--',type:'info'}
}
function participation(row:any){
  return ({COMPLETED:{label:'已完成',type:'success'},ABSENT:{label:'缺考',type:'danger'},IN_PROGRESS:{label:'考试中',type:'primary'},NOT_STARTED:{label:'未开始',type:'info'},INCOMPLETE:{label:'未完成',type:'warning'}} as any)[row.participation_status]??{label:'--',type:'info'}
}
onMounted(async()=>{if(canManage.value)classOptions.value=await loadDictionaryValues('CLASS');await load();if(String(route.query.focus)==='review'){await nextTick();document.getElementById('exam-review-queue')?.scrollIntoView({behavior:'smooth',block:'start'})}})
</script>

<template>
  <div class="exam-module-page">
    <header class="exam-page-head">
      <div>
        <span class="eyebrow">考试中心 · {{canManage?'成绩管理':'我的成绩'}}</span>
        <h1>{{canManage?'成绩管理':'我的成绩'}}</h1>
        <p>{{canManage?'交卷后即时查看客观题得分；整场考试结束时由系统统一向员工发布。':'成绩将在整场考试结束后自动显示，避免考试期间互通答案。'}}</p>
      </div>
      <div class="exam-head-actions"><el-button v-if="canManage" :icon="Download" :loading="exporting" @click="exportResults()">导出已发布成绩</el-button></div>
    </header>

    <template v-if="canManage">
      <section class="exam-summary-grid">
        <article class="exam-summary-card blue"><span class="exam-summary-icon"><el-icon><Histogram/></el-icon></span><div><small>考试总数</small><strong>{{overview.total}}</strong><span>已发布考试</span></div></article>
        <article class="exam-summary-card violet"><span class="exam-summary-icon"><el-icon><Calendar/></el-icon></span><div><small>待开始</small><strong>{{overview.upcoming}}</strong><span>等待开放</span></div></article>
        <article class="exam-summary-card green"><span class="exam-summary-icon"><el-icon><Timer/></el-icon></span><div><small>进行中</small><strong>{{overview.open}}</strong><span>实时参考</span></div></article>
        <article class="exam-summary-card amber"><span class="exam-summary-icon"><el-icon><CircleCheck/></el-icon></span><div><small>已结束</small><strong>{{overview.ended}}</strong><span>可归档导出</span></div></article>
      </section>
      <section v-if="pendingReviews.length" id="exam-review-queue" class="exam-workspace review-workspace">
        <div class="exam-workspace-head"><div><span class="card-title">待阅卷答卷</span><span class="header-tip">主观题评分完成后，系统自动合成考试总分</span></div><el-tag type="warning" effect="plain">{{pendingReviews.length}} 份待处理</el-tag></div>
        <el-table :data="pendingReviews" empty-text="暂无待阅卷答卷">
          <el-table-column prop="exam_name" label="考试" min-width="160"/><el-table-column prop="employee_name" label="员工" min-width="110"/><el-table-column prop="objective_score" label="客观题得分" width="110"/><el-table-column prop="event_count" label="异常次数" width="90"/><el-table-column label="提交时间" width="150"><template #default="s">{{dateTimeParts(s.row.submitted_at).date}} {{dateTimeParts(s.row.submitted_at).time}}</template></el-table-column><el-table-column label="操作" width="90"><template #default="s"><el-button link type="primary" @click="openReview(s.row)">开始阅卷</el-button></template></el-table-column>
        </el-table>
      </section>
      <section class="exam-workspace">
          <div class="exam-workspace-head">
            <div><span class="card-title">考试完成情况</span><span class="header-tip">点击任意一行查看该考试的员工成绩单</span></div>
            <div class="filters">
              <el-input v-model="keyword" clearable placeholder="搜索考试或试卷" :prefix-icon="Search"/>
              <el-select v-model="phase" clearable placeholder="考试状态">
                <el-option label="待开始" value="UPCOMING"/><el-option label="进行中" value="OPEN"/><el-option label="已结束" value="ENDED"/>
              </el-select>
            </div>
          </div>
        <el-table :data="filteredPlans" v-loading="false" empty-text="暂无考试计划" class="summary-table" @row-click="openPlan">
          <el-table-column prop="name" label="考试名称" min-width="145" show-overflow-tooltip>
            <template #default="s"><div class="exam-name">{{s.row.name}}</div><div class="sub-text">{{s.row.paper_name}}</div></template>
          </el-table-column>
          <el-table-column label="考试时间" width="176">
            <template #default="s">
              <div class="time-range"><span>开始 {{dateTimeParts(s.row.starts_at).date}} {{dateTimeParts(s.row.starts_at).time}}</span><span>结束 {{dateTimeParts(s.row.ends_at).date}} {{dateTimeParts(s.row.ends_at).time}}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="考试状态" width="90"><template #default="s"><el-tag :type="planPhase(s.row).type" effect="plain">{{planPhase(s.row).label}}</el-tag></template></el-table-column>
          <el-table-column prop="assigned_count" label="应考" width="64" align="center"/>
          <el-table-column prop="participant_count" label="参考" width="64" align="center"/>
          <el-table-column prop="completed_count" label="已完成" width="72" align="center"/>
          <el-table-column prop="incomplete_count" label="未完成" width="72" align="center"/>
          <el-table-column prop="absent_count" label="缺考" width="64" align="center"/>
          <el-table-column label="" width="42"><template #default><el-icon class="row-arrow"><ArrowRight/></el-icon></template></el-table-column>
        </el-table>
      </section>
    </template>

    <el-card v-else>
      <template #header>已发布成绩</template>
      <el-table :data="results" empty-text="暂无已发布成绩">
        <el-table-column prop="exam_name" label="考试"/><el-table-column prop="total_score" label="成绩"/>
        <el-table-column label="状态" width="110"><template #default="s"><el-tag :type="resultStatus(s.row).type" effect="plain">{{resultStatus(s.row).label}}</el-tag></template></el-table-column>
        <el-table-column label="计分月份"><template #default="s">{{scoreMonth(s.row.score_month)}}</template></el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" size="88%" class="result-drawer">
      <template #header>
        <div class="drawer-title">
          <div><h3>{{selectedPlan?.name}}</h3><p>{{selectedPlan?.paper_name}} · {{dateTimeParts(selectedPlan?.starts_at).date}} {{dateTimeParts(selectedPlan?.starts_at).time}} 至 {{dateTimeParts(selectedPlan?.ends_at).date}} {{dateTimeParts(selectedPlan?.ends_at).time}}</p></div>
          <div class="exam-head-actions"><el-tag type="info" effect="plain">考试结束后自动发布</el-tag><el-button :icon="Download" :loading="exporting" @click="exportResults(selectedPlan?.id)">导出本场成绩</el-button></div>
        </div>
      </template>
      <div class="detail-overview">
        <div><span>应考人数</span><strong>{{detailOverview.assigned}}</strong></div>
        <div><span>已完成</span><strong class="green">{{detailOverview.completed}}</strong></div>
        <div><span>未完成</span><strong class="orange">{{detailOverview.incomplete}}</strong></div>
        <div><span>缺考</span><strong class="red">{{detailOverview.absent}}</strong></div>
      </div>
      <div class="detail-toolbar">
        <div><strong>员工成绩单</strong><span class="header-tip">系统按客观题标准答案自动评分</span></div>
        <div class="filters">
          <el-input v-model="detailKeyword" clearable placeholder="搜索姓名或工号" :prefix-icon="Search"/>
          <el-select v-model="detailClassId" clearable filterable placeholder="全部班级"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
          <el-select v-model="detailStatus" clearable placeholder="完成状态">
            <el-option label="已完成" value="COMPLETED"/><el-option label="考试中" value="IN_PROGRESS"/><el-option label="未开始" value="NOT_STARTED"/><el-option label="未完成" value="INCOMPLETE"/><el-option label="缺考" value="ABSENT"/>
          </el-select>
        </div>
      </div>
      <el-table :data="filteredPlanResults" v-loading="detailLoading" empty-text="暂无应考员工">
        <el-table-column prop="employee_no" label="工号" width="110"/><el-table-column prop="employee_name" label="员工" min-width="100"/><el-table-column prop="class_name" label="班级" min-width="100"><template #default="s">{{s.row.class_name||'未设置'}}</template></el-table-column>
        <el-table-column label="完成状态" width="92"><template #default="s"><el-tag :type="participation(s.row).type" effect="plain">{{participation(s.row).label}}</el-tag></template></el-table-column>
        <el-table-column prop="attempt_no" label="考试次数" width="82" align="center"><template #default="s">{{s.row.attempt_no??'--'}}</template></el-table-column>
        <el-table-column prop="total_score" label="成绩" width="74" align="center"><template #default="s"><strong v-if="s.row.total_score!==null&&s.row.total_score!==undefined">{{s.row.total_score}}</strong><span v-else>--</span></template></el-table-column>
        <el-table-column prop="event_count" label="异常次数" width="82" align="center"><template #default="s">{{s.row.event_count??0}}</template></el-table-column>
        <el-table-column label="开始时间" width="118"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.started_at).date}}</span><span>{{dateTimeParts(s.row.started_at).time}}</span></span></template></el-table-column>
        <el-table-column label="提交时间" width="118"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.submitted_at).date}}</span><span>{{dateTimeParts(s.row.submitted_at).time}}</span></span></template></el-table-column>
        <el-table-column label="员工可见" width="108"><template #default="s"><el-tag v-if="s.row.participation_status==='COMPLETED'" :type="isPublished(s.row)?'success':'info'" effect="plain">{{isPublished(s.row)?'已下发':'考试结束后'}}</el-tag><span v-else>--</span></template></el-table-column>
      </el-table>
    </el-drawer>

    <el-drawer v-model="reviewVisible" size="min(760px, 94vw)" class="review-drawer">
      <template #header><div class="drawer-title"><div><h3>主观题阅卷</h3><p>{{reviewAttempt?.exam_name}} · 答卷 #{{reviewAttempt?.id}}</p></div><el-tag v-if="reviewAttempt" :type="reviewAttempt.status==='GRADED'?'success':'warning'" effect="plain">{{reviewAttempt.status==='GRADED'?'已完成阅卷':'待阅卷'}}</el-tag></div></template>
      <div v-loading="reviewLoading">
        <el-alert title="逐题保存评分；最后一道主观题保存后，系统自动计算总分。" type="info" :closable="false" show-icon/>
        <div v-for="(question,index) in reviewAttempt?.questions?.filter((x:any)=>x.question_type==='SHORT')||[]" :key="question.id" class="subjective-question">
          <div class="question-title"><span>{{index+1}}</span><div><strong>{{question.stem}}</strong><small>满分 {{question.score}} 分</small></div></div>
          <div class="answer-box"><span>员工答案</span><p>{{answerText(question.saved_answer)}}</p></div>
          <div class="grade-form"><el-input-number v-model="grades[question.id]!.score" :min="0" :max="Number(question.score)" :precision="2" controls-position="right"/><el-input v-model="grades[question.id]!.comment" placeholder="阅卷意见（可选）" maxlength="500"/><el-button type="primary" @click="gradeQuestion(question)">保存本题</el-button></div>
        </div>
        <el-empty v-if="reviewAttempt&&!reviewAttempt.questions?.some((x:any)=>x.question_type==='SHORT')" description="该答卷没有需要人工评分的主观题"/>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.overview-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:16px}.overview-item{padding:18px 20px;background:#fff;border:1px solid #e5e7eb;border-radius:8px;box-shadow:0 2px 8px rgba(31,41,55,.04)}.overview-item span{display:block;color:#7b8799;font-size:13px;margin-bottom:7px}.overview-item strong{font-size:25px;color:#253044}.overview-item.success{border-left:3px solid #67c23a}.overview-item.warning{border-left:3px solid #e6a23c}.card-head,.detail-toolbar,.drawer-title{display:flex;align-items:center;justify-content:space-between;gap:16px}.card-title{font-weight:600}.header-tip{color:#8a96a8;font-size:12px;margin-left:12px}.filters{display:flex;gap:10px}.filters .el-input{width:190px}.filters .el-select{width:130px}.summary-table :deep(.el-table__row){cursor:pointer}.summary-table :deep(.el-table__row:hover) .row-arrow{color:#409eff;transform:translateX(2px)}.exam-name{font-weight:600;color:#303846}.sub-text{color:#8a96a8;font-size:12px;margin-top:4px}.time-range{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#596579;white-space:nowrap}.row-arrow{color:#a9b1bd;transition:.2s}.drawer-title{width:100%}.drawer-title h3{font-size:20px;margin:0;color:#253044}.drawer-title p{font-size:13px;color:#7b8799;margin:7px 0 0}.detail-overview{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:22px}.detail-overview>div{background:#f7f9fc;border-radius:6px;padding:14px 18px}.detail-overview span{display:block;color:#7b8799;font-size:12px}.detail-overview strong{display:block;font-size:22px;margin-top:5px;color:#253044}.detail-overview .green{color:#4d9f2f}.detail-overview .orange{color:#d88a1d}.detail-overview .red{color:#e34d59}.detail-toolbar{margin-bottom:14px}.datetime-cell{display:inline-flex;flex-direction:column;line-height:1.35;white-space:nowrap}.datetime-cell span:last-child{color:#606266}@media(max-width:900px){.overview-grid{grid-template-columns:repeat(2,1fr)}.card-head,.detail-toolbar,.drawer-title{align-items:flex-start;flex-direction:column}.detail-overview{grid-template-columns:repeat(2,1fr)}.filters{width:100%}.filters .el-input{flex:1}}
.review-workspace{border-left:3px solid #e6a23c}.publish-workspace{border-left:3px solid #67c23a}.subjective-question{margin-top:16px;padding:18px;border:1px solid #e5eaf1;border-radius:9px;background:#fbfcfe}.question-title{display:flex;align-items:flex-start;gap:10px}.question-title>span{display:flex;width:25px;height:25px;flex:none;align-items:center;justify-content:center;border-radius:50%;color:#fff;background:#3977c6;font-size:12px}.question-title strong,.question-title small{display:block}.question-title small{margin-top:5px;color:#8a96a8}.answer-box{margin:14px 0;padding:13px;border-radius:7px;background:#f1f4f8}.answer-box span{color:#8a96a8;font-size:11px}.answer-box p{margin:6px 0 0;line-height:1.65;white-space:pre-wrap}.grade-form{display:grid;grid-template-columns:130px 1fr auto;gap:10px}.grade-form .el-input-number{width:100%}@media(max-width:700px){.grade-form{grid-template-columns:1fr}}
</style>
