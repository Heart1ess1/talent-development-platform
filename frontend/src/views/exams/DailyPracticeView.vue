<template>
  <div class="exam-module-page">
    <header class="exam-page-head">
      <div>
        <span class="eyebrow">DAILY PRACTICE</span>
        <h1>每日练习</h1>
        <p>每天到点推送一套练习题，在截止时间前完成即可；提交后立即出分并查看逐题解析。</p>
      </div>
      <div class="exam-head-actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <div v-loading="loading">
      <el-empty v-if="payload&&!isEmployee" description="当前身份没有员工档案，暂不能参加每日练习"/>
      <template v-else-if="payload">
        <section class="daily-today">
          <div class="daily-section-head">
            <h2>今日任务</h2>
            <span>{{todayText}}</span>
          </div>
          <el-empty v-if="!todayTasks.length" description="今天暂无练习任务，请留意推送时间" :image-size="90"/>
          <div v-else class="daily-task-grid">
            <article v-for="row in todayTasks" :key="row.id" class="daily-task-card">
              <header>
                <div>
                  <h3>{{row.plan_name}}</h3>
                  <p>{{row.question_count}} 题 · 每题 {{row.score_per_question}} 分<template v-if="row.pass_score!=null"> · 及格 {{row.pass_score}} 分</template></p>
                </div>
                <el-tag :type="statusOf(row.status).type">{{statusOf(row.status).label}}</el-tag>
              </header>
              <div class="daily-task-meta">
                <span>截止：{{deadlineText(row.deadline_at)}}</span>
                <span v-if="row.status==='PENDING'||row.status==='IN_PROGRESS'"
                  class="daily-task-clock" :class="{danger:isUrgent(row)}">剩余 {{remainingFor(row)}}</span>
                <span v-else-if="row.status==='SUBMITTED'">得分 {{row.total_score??'-'}}</span>
              </div>
              <footer>
                <el-button v-if="row.status==='PENDING'" type="primary" @click="openSession(row)">开始练习</el-button>
                <el-button v-else-if="row.status==='IN_PROGRESS'" type="primary" @click="openSession(row)">继续作答</el-button>
                <el-button v-else-if="row.status==='SUBMITTED'" @click="openResult(row)">查看结果</el-button>
                <span v-else class="muted">已过期，无法作答</span>
              </footer>
            </article>
          </div>
        </section>

        <section class="daily-history">
          <div class="daily-section-head">
            <h2>练习历史</h2>
            <span>最近 60 条</span>
          </div>
          <el-table :data="history" class="exam-table" empty-text="暂无历史记录">
            <el-table-column label="日期" width="120">
              <template #default="{row}">{{row.practice_date}}</template>
            </el-table-column>
            <el-table-column prop="plan_name" label="练习计划" min-width="180" show-overflow-tooltip/>
            <el-table-column label="状态" width="100">
              <template #default="{row}"><el-tag size="small" :type="statusOf(row.status).type">{{statusOf(row.status).label}}</el-tag></template>
            </el-table-column>
            <el-table-column prop="question_count" label="题数" width="80"/>
            <el-table-column label="正确 / 得分" width="130">
              <template #default="{row}">{{row.correct_count??'-'}} 题 / {{row.total_score??'-'}} 分</template>
            </el-table-column>
            <el-table-column label="提交时间" width="170">
              <template #default="{row}">{{deadlineText(row.submitted_at)||'-'}}</template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{row}">
                <el-button v-if="row.status==='SUBMITTED'" link type="primary" @click="openResult(row)">查看结果</el-button>
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </template>
    </div>

    <el-dialog v-model="dialogVisible" :title="result?'练习结果':(session?.plan_name||'每日练习')"
      width="820px" append-to-body :close-on-click-modal="false" @closed="resetDialog">
      <div v-if="session" class="daily-session">
        <div class="daily-session-head">
          <div>
            <h3>{{session.plan_name}}</h3>
            <p>共 {{session.question_count}} 题 · 每题 {{session.score_per_question}} 分<template v-if="session.pass_score!=null"> · 及格 {{session.pass_score}} 分</template></p>
          </div>
          <div class="daily-session-clock" :class="{danger:!result&&sessionRemaining<=300}">
            <span>{{result?'已结束':'剩余时间'}}</span>
            <strong v-if="!result">{{formatExamCountdown(sessionRemaining)}}</strong>
          </div>
        </div>

        <el-alert v-if="result" type="success" :closable="false" show-icon class="daily-result-summary"
          :title="`得分 ${result.total_score} 分，答对 ${result.correct_count}/${result.question_count} 题`"
          :description="result.pass_score==null?'已提交，可查看逐题解析':(result.passed?'已达到及格线':'未达到及格线')"/>

        <div class="daily-question-list">
          <section v-for="(q,index) in session.questions" :key="q.id" class="daily-question">
            <div class="daily-question-head">
              <strong>
                {{index+1}}.
                <el-tag size="small" effect="plain">{{typeLabels[q.question_type]||q.question_type}}</el-tag>
                <el-tag v-if="q.repeated" size="small" type="warning" effect="plain">复习题</el-tag>
              </strong>
              <span>{{q.score}} 分</span>
            </div>
            <p class="daily-stem">{{q.stem}}</p>

            <template v-if="!result">
              <el-checkbox-group v-if="q.question_type==='MULTIPLE'" v-model="answers[q.id]" @change="saveAnswer(q)">
                <el-checkbox v-for="(option,oi) in q.options" :key="oi" :value="option">{{optionLabel(option)}}</el-checkbox>
              </el-checkbox-group>
              <el-radio-group v-else v-model="answers[q.id]" @change="saveAnswer(q)">
                <el-radio v-for="(option,oi) in q.options" :key="oi" :value="option">{{optionLabel(option)}}</el-radio>
              </el-radio-group>
            </template>
            <template v-else>
              <p class="daily-answer-line">你的答案：<b :class="q.is_correct?'ok':'bad'">{{answerText(q.saved_answer)}}</b></p>
              <p class="daily-answer-line">正确答案：<b class="ok">{{answerText(q.correct_answer)}}</b></p>
              <p v-if="q.explanation" class="daily-explanation">解析：{{q.explanation}}</p>
            </template>
          </section>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible=false">关闭</el-button>
        <el-button v-if="session&&!result&&session.status!=='SUBMITTED'" type="primary" :loading="submitting" @click="submit()">提交并判分</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {typeLabels} from './examUi'
import {formatExamCountdown,remainingExamSeconds,serverClockOffsetMillis} from './examProctor'
import '@/styles/exam-center.css'

type TagType='primary'|'success'|'warning'|'info'|'danger'
interface DailyTask{
  id:number;plan_id:number;plan_name:string;status:string;question_count:number;
  correct_count:number|null;total_score:number|null;pass_score:number|null;score_per_question:number;
  deadline_at:string|null;deadline_epoch_ms:number|null;started_at:string|null;submitted_at:string|null;
  practice_date?:string;
}
interface Question{
  id:number;question_type:string;stem:string;options:any[];score:number;sort_order:number;repeated:boolean;
  saved_answer:any;correct_answer?:any;is_correct?:boolean;earned_score?:number;explanation?:string|null;
}
interface SessionPayload{
  id:number;plan_id:number;plan_name:string;status:string;question_count:number;score_per_question:number;
  pass_score:number|null;total_score:number|null;correct_count:number|null;
  deadline_at:string|null;deadline_epoch_ms?:number|null;started_at:string|null;submitted_at:string|null;
  server_now_epoch_ms?:number;passed?:boolean;questions:Question[];
}
interface Overview{
  is_employee:boolean;server_now_epoch_ms:number;today:DailyTask[];history:DailyTask[];
  employee?:{id:number;employee_no:string;name:string};
}

const loading=ref(false)
const payload=ref<Overview|null>(null)
const dialogVisible=ref(false)
const session=ref<SessionPayload|null>(null)
const result=ref<SessionPayload|null>(null)
const answers=reactive<Record<number,any>>({})
const submitting=ref(false)
const nowTick=ref(Date.now())
const clockOffset=ref(0)
let ticker:number|undefined

const isEmployee=computed(()=>Boolean(payload.value?.is_employee))
const todayTasks=computed<DailyTask[]>(()=>payload.value?.today??[])
const history=computed<DailyTask[]>(()=>payload.value?.history??[])
const todayText=computed(()=>new Date(nowTick.value).toLocaleDateString('zh-CN'))

const statusLabels:Record<string,{label:string;type:TagType}>={
  PENDING:{label:'待开始',type:'warning'},
  IN_PROGRESS:{label:'作答中',type:'primary'},
  SUBMITTED:{label:'已提交',type:'success'},
  EXPIRED:{label:'已过期',type:'danger'}
}
function statusOf(status:string){return statusLabels[status]??{label:status||'-',type:'info' as TagType}}

function deadlineText(value:string|null|undefined){
  if(!value)return ''
  return value.replace('T',' ').slice(0,16)
}

function remainingSecondsFor(row:DailyTask){
  const deadline=Number(row?.deadline_epoch_ms)
  if(!row?.deadline_epoch_ms||!Number.isFinite(deadline))return 0
  return remainingExamSeconds(deadline,nowTick.value,clockOffset.value)
}
function remainingFor(row:DailyTask){return formatExamCountdown(remainingSecondsFor(row))}
function isUrgent(row:DailyTask){return remainingSecondsFor(row)<=300}

const sessionRemaining=computed(()=>{
  const deadline=Number(session.value?.deadline_epoch_ms)
  if(!session.value?.deadline_epoch_ms||!Number.isFinite(deadline))return 0
  return remainingExamSeconds(deadline,nowTick.value,clockOffset.value)
})

watch(sessionRemaining,(seconds)=>{
  if(seconds===0&&dialogVisible.value&&!result.value&&session.value?.status==='IN_PROGRESS'&&!submitting.value)
    void submit(true)
})

async function load(){
  loading.value=true
  try{
    const res=await api.get<any,Envelope<Overview>>('/exam-daily/my')
    payload.value=res.data
    clockOffset.value=serverClockOffsetMillis(res.data?.server_now_epoch_ms)
  }finally{
    loading.value=false
  }
}

function optionLabel(value:any){
  if(value===true)return '正确'
  if(value===false)return '错误'
  return String(value)
}
function answerText(value:any){
  if(value===null||value===undefined||value==='')return '未作答'
  if(Array.isArray(value))return value.length?value.map(optionLabel).join('、'):'未作答'
  return optionLabel(value)
}

async function openSession(row:DailyTask){
  try{
    const res=await api.post<any,Envelope<SessionPayload>>(`/exam-daily/sessions/${row.id}/start`)
    session.value=res.data
    result.value=null
    fillAnswers(res.data)
    dialogVisible.value=true
  }catch{}
}

async function openResult(row:DailyTask){
  try{
    const res=await api.get<any,Envelope<SessionPayload>>(`/exam-daily/sessions/${row.id}/result`)
    session.value=res.data
    result.value=res.data
    fillAnswers(res.data)
    dialogVisible.value=true
  }catch{}
}

function fillAnswers(data:SessionPayload){
  for(const key of Object.keys(answers))delete answers[Number(key)]
  for(const question of data.questions??[])
    answers[question.id]=question.saved_answer??(question.question_type==='MULTIPLE'?[]:null)
}

async function saveAnswer(question:Question){
  if(!session.value||session.value.status!=='IN_PROGRESS')return
  try{
    await api.put(`/exam-daily/sessions/${session.value.id}/answers`,
      {questionId:question.id,answer:answers[question.id]},{silentError:true} as any)
  }catch{}
}

async function persistAll(){
  if(!session.value)return
  await Promise.all((session.value.questions??[]).map(question=>
    api.put(`/exam-daily/sessions/${session.value!.id}/answers`,
      {questionId:question.id,answer:answers[question.id]},{silentError:true} as any).catch(()=>null)
  ))
}

async function submit(force=false){
  if(!session.value||submitting.value)return
  if(!force){
    try{
      await ElMessageBox.confirm('提交后不可修改，确定提交本次练习吗？','提交确认',{type:'warning'})
    }catch{return}
  }
  submitting.value=true
  try{
    await persistAll()
    const res=await api.post<any,Envelope<SessionPayload>>(`/exam-daily/sessions/${session.value.id}/submit`)
    result.value=res.data
    session.value=res.data
    fillAnswers(res.data)
    ElMessage.success('已提交并完成判分')
    await load()
  }catch{
    await load()
    if(session.value?.id){
      try{
        const current=await api.get<any,Envelope<SessionPayload>>(`/exam-daily/sessions/${session.value.id}`)
        session.value=current.data
      }catch{}
    }
  }finally{
    submitting.value=false
  }
}

function resetDialog(){
  dialogVisible.value=false
  session.value=null
  result.value=null
  for(const key of Object.keys(answers))delete answers[Number(key)]
}

onMounted(()=>{
  void load()
  ticker=window.setInterval(()=>{nowTick.value=Date.now()},1000)
})
onBeforeUnmount(()=>{
  if(ticker)window.clearInterval(ticker)
  ticker=undefined
})
</script>

<style scoped>
.daily-today{margin-top:4px}
.daily-section-head{display:flex;align-items:baseline;gap:10px;margin:0 0 12px}
.daily-section-head h2{margin:0;font-size:18px}
.daily-section-head span{color:#9a9a9a;font-size:13px}
.daily-task-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px}
.daily-task-card{display:flex;flex-direction:column;gap:12px;padding:16px;border:1px solid #e9e9e9;border-radius:8px;background:#fff}
.daily-task-card header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}
.daily-task-card h3{margin:0;font-size:16px}
.daily-task-card p{margin:5px 0 0;color:#8a8a8a;font-size:13px}
.daily-task-meta{display:flex;flex-wrap:wrap;gap:6px 16px;color:#5f5f5f;font-size:13px}
.daily-task-clock{color:#176b58;font-variant-numeric:tabular-nums}
.daily-task-clock.danger{color:#dc2626;font-weight:700}
.daily-task-card footer{display:flex;align-items:center;gap:8px;margin-top:auto}
.daily-history{margin-top:24px}
.daily-session-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:14px}
.daily-session-head h3{margin:0;font-size:18px}
.daily-session-head p{margin:6px 0 0;color:#888;font-size:13px}
.daily-session-clock{display:flex;flex-direction:column;align-items:flex-end;gap:4px;color:#6b6b6b;font-size:12px}
.daily-session-clock strong{color:#176b58;font-size:24px;font-variant-numeric:tabular-nums}
.daily-session-clock.danger strong{color:#dc2626}
.daily-question{padding:14px 0;border-bottom:1px solid #f0f0f0}
.daily-question-head{display:flex;align-items:center;justify-content:space-between;gap:10px}
.daily-question-head strong{display:flex;align-items:center;gap:6px;font-size:15px}
.daily-question-head>span{color:#999;font-size:13px;white-space:nowrap}
.daily-stem{margin:8px 0 12px;color:#303030;line-height:1.7;white-space:pre-wrap}
.daily-question :deep(.el-radio-group),.daily-question :deep(.el-checkbox-group){display:flex;flex-direction:column;align-items:flex-start;gap:6px}
.daily-question :deep(.el-radio),.daily-question :deep(.el-checkbox){height:auto;margin-right:0;white-space:normal}
.daily-answer-line{margin:6px 0;color:#5f5f5f}
.daily-answer-line b.ok{color:#15803d}
.daily-answer-line b.bad{color:#dc2626}
.daily-explanation{margin:8px 0 0;padding:8px 10px;border-radius:6px;background:var(--app-page-bg);color:#5f5f5f;line-height:1.6}
.daily-result-summary{margin-bottom:8px}
</style>
