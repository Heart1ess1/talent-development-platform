<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {useRouter} from 'vue-router'
import {
  Calendar,ChatDotRound,CircleCheck,Clock,Collection,DataAnalysis,DocumentChecked,
  EditPen,Histogram,List,Medal,Refresh,Right,School,TrendCharts,UserFilled,Warning
} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import '@/styles/dashboard.css'

type AnyRecord=Record<string,any>
type DashboardPayload=AnyRecord&{audience:'EMPLOYEE'|'MANAGER'}

const auth=useAuthStore(),router=useRouter()
const data=ref<DashboardPayload>(),loading=ref(false)
const isEmployee=computed(()=>data.value?.audience==='EMPLOYEE')
const metrics=computed(()=>data.value?.metrics||{})
const operations=computed(()=>data.value?.operations||{})
const greeting=computed(()=>{const hour=new Date().getHours();return hour<6?'夜深了':hour<11?'早上好':hour<14?'中午好':hour<18?'下午好':'晚上好'})
const employeeSummary=computed(()=>[
  {label:'待处理任务',value:metrics.value.pending_tasks||0,detail:`其中逾期 ${data.value?.action_items?.filter((x:AnyRecord)=>x.status==='OVERDUE').length||0} 项`,tone:'amber',icon:List,route:'/tasks'},
  {label:'已完成任务',value:metrics.value.completed_tasks||0,detail:`任务完成率 ${percent(metrics.value.task_completion_rate)}`,tone:'green',icon:CircleCheck,route:'/tasks'},
  {label:'近期课程',value:metrics.value.upcoming_courses||0,detail:'已安排且尚未结束',tone:'blue',icon:School,route:'/courses/my'},
  {label:'待参加考试',value:metrics.value.pending_exams||0,detail:'含即将开始和进行中',tone:'violet',icon:DocumentChecked,route:'/exams/my'}
])
const managerSummary=computed(()=>[
  {label:'管理范围员工',value:metrics.value.employee_count||0,detail:data.value?.scope_label||'当前数据范围',tone:'blue',icon:UserFilled,route:'/employee-directory'},
  {label:'职责待办',value:metrics.value.pending_actions||0,detail:'按权限聚合审核与发布事项',tone:'amber',icon:List,route:''},
  {label:'任务完成率',value:percent(metrics.value.task_completion_rate),detail:`已完成 ${operations.value.task?.approved||0} / ${operations.value.task?.assignment_total||0}`,tone:'green',icon:CircleCheck,route:'/training-plans/tracking'},
  {label:'本月评价覆盖',value:percent(metrics.value.evaluation_coverage),detail:`已发布 ${operations.value.evaluation?.published_total||0} / ${operations.value.evaluation?.employee_total||0}`,tone:'violet',icon:Histogram,route:'/evaluation/workbench'}
])
const operationCards=computed(()=>[
  {key:'task',title:'任务推进',description:'下发、提交、审核与逾期闭环',icon:List,tone:'blue',rate:Number(operations.value.task?.completion_rate||0),route:'/training-plans/tracking',stats:[['已完成',operations.value.task?.approved||0],['待审核',operations.value.task?.pending_review||0],['已逾期',operations.value.task?.overdue||0]]},
  {key:'course',title:'课程执行',description:'课程安排、参训与签到完成情况',icon:School,tone:'green',rate:Number(operations.value.course?.attendance_rate||0),route:auth.can('course:manage')?'/courses/sessions':'/courses/attendance',stats:[['场次',operations.value.course?.session_total||0],['即将开始',operations.value.course?.upcoming_sessions||0],['进行中',operations.value.course?.ongoing_sessions||0]]},
  {key:'exam',title:'考试进度',description:'客观题自动评分，考试结束统一下发',icon:DocumentChecked,tone:'amber',rate:Number(operations.value.exam?.completion_rate||0),route:auth.can('exam:manage')?'/exams/plans':'/exams/results',stats:[['考试计划',operations.value.exam?.plan_total||0],['待批阅',operations.value.exam?.pending_review||0],['缺考',operations.value.exam?.absent_total||0]]},
  {key:'evaluation',title:'综合评价',description:`${data.value?.period_key||''} 月度评分与结果发布`,icon:Histogram,tone:'violet',rate:Number(operations.value.evaluation?.coverage||0),route:'/evaluation/workbench',stats:[['已发布',operations.value.evaluation?.published_total||0],['草稿',operations.value.evaluation?.draft_total||0],['平均分',score(operations.value.evaluation?.average_score)]]}
])

async function load(){loading.value=true;try{data.value=(await api.get<any,Envelope<DashboardPayload>>('/dashboard')).data}finally{loading.value=false}}
function open(route?:string){if(route)router.push(route)}
function percent(value:any){return `${Number(value||0).toFixed(Number(value||0)%1?1:0)}%`}
function score(value:any){return value===null||value===undefined||value===''?'—':Number(value).toFixed(1)}
function dateTime(value:any){if(!value)return '时间待定';return new Intl.DateTimeFormat('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value))}
function shortDate(value:any){if(!value)return '—';return new Intl.DateTimeFormat('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(value))}
function statusLabel(value:string){return ({OVERDUE:'已逾期',RETURNED:'需修改',NOT_SUBMITTED:'待提交',PENDING_REVIEW:'待审核',IN_PROGRESS:'进行中',UPCOMING:'即将开始',READY:'可参加'} as AnyRecord)[value]||value}
function statusType(value:string){return ['OVERDUE','RETURNED'].includes(value)?'danger':['PENDING_REVIEW','UPCOMING'].includes(value)?'warning':'primary'}
function queueIcon(code:string){return code==='TASK_REVIEW'?List:code.startsWith('EXAM')?DocumentChecked:code==='ATTENDANCE'?School:EditPen}
function scheduleIcon(type:string){return type==='COURSE'?School:DocumentChecked}
function riskType(level:string){return level==='HIGH'?'danger':level==='MEDIUM'?'warning':'info'}
function riskLabel(level:string){return level==='HIGH'?'重点关注':level==='MEDIUM'?'持续跟进':'常规提醒'}
function generatedAt(value:any){if(!value)return '';return `数据更新于 ${new Intl.DateTimeFormat('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value))}`}
onMounted(load)
</script>

<template>
  <div class="dashboard-page" v-loading="loading">
    <header class="dashboard-head">
      <div>
        <span class="dashboard-eyebrow">{{isEmployee?'个人学习主页':`${data?.period_key||''} · 培养运营`}}</span>
        <h1 v-if="isEmployee">{{greeting}}，{{data?.profile?.name||auth.user?.displayName}}</h1>
        <h1 v-else>培养运营工作台</h1>
        <p v-if="isEmployee&&data?.profile">{{data.profile.batch_name||'未分配批次'}} · {{data.profile.station_name||'未分配服务站'}} · 导师 {{data.profile.mentor_name||'待分配'}}</p>
        <p v-else>{{data?.role_label}} · {{data?.scope_label}}。从这里掌握培养执行状态并处理职责内事项。</p>
      </div>
      <div class="dashboard-head-actions">
        <span>{{generatedAt(data?.generated_at)}}</span>
        <el-button :icon="Refresh" @click="load">刷新数据</el-button>
        <template v-if="!isEmployee">
          <el-button v-if="auth.can('course:manage')" @click="open('/courses/sessions')">安排课程</el-button>
          <el-button v-if="auth.can('exam:manage')" type="primary" @click="open('/exams/plans')">安排考试</el-button>
        </template>
      </div>
    </header>

    <el-alert v-if="isEmployee&&!data?.profile" title="当前账号尚未关联员工档案" description="请联系管理员完成账号与员工档案绑定。" type="warning" :closable="false" show-icon/>

    <template v-if="isEmployee&&data?.profile">
      <section class="dashboard-summary-grid">
        <button v-for="item in employeeSummary" :key="item.label" class="dashboard-metric-card" :class="item.tone" type="button" @click="open(item.route)">
          <span class="metric-icon"><el-icon><component :is="item.icon"/></el-icon></span>
          <span class="metric-copy"><small>{{item.label}}</small><strong>{{item.value}}</strong><span>{{item.detail}}</span></span>
          <el-icon class="metric-arrow"><Right/></el-icon>
        </button>
      </section>

      <section class="dashboard-employee-grid">
        <article class="dashboard-panel dashboard-actions-panel">
          <div class="dashboard-panel-head"><div><h2>我的待办</h2><p>按截止时间汇总需要你处理的任务与考试</p></div><el-button link type="primary" @click="open('/tasks')">查看全部</el-button></div>
          <div v-if="data.action_items?.length" class="dashboard-action-list">
            <button v-for="item in data.action_items" :key="`${item.type}-${item.title}-${item.due_at}`" type="button" class="dashboard-action-row" @click="open(item.route)">
              <span class="action-marker" :class="item.tone.toLowerCase()"><el-icon><component :is="item.type==='TASK'?List:DocumentChecked"/></el-icon></span>
              <span class="action-copy"><span><strong>{{item.title}}</strong><el-tag size="small" :type="statusType(item.status)" effect="plain">{{statusLabel(item.status)}}</el-tag></span><small>{{item.description}}</small></span>
              <span class="action-time"><b>{{dateTime(item.due_at)}}</b><small>截止时间</small></span>
              <el-icon><Right/></el-icon>
            </button>
          </div>
          <el-empty v-else :image-size="72" description="当前没有需要处理的事项"/>
        </article>

        <aside class="dashboard-panel dashboard-growth-panel">
          <div class="dashboard-panel-head"><div><h2>学习成长概览</h2><p>任务、课程与考试完成情况</p></div><span class="growth-score"><small>最新季度分</small><strong>{{score(metrics.latest_quarter_score)}}</strong></span></div>
          <div class="growth-progress"><div><span>任务完成率 <b>{{percent(metrics.task_completion_rate)}}</b></span><el-progress :percentage="Number(metrics.task_completion_rate||0)" :show-text="false"/></div><div><span>课程出勤率 <b>{{percent(metrics.course_attendance_rate)}}</b></span><el-progress :percentage="Number(metrics.course_attendance_rate||0)" :show-text="false" color="#41a273"/></div><div><span>考试完成率 <b>{{percent(metrics.exam_completion_rate)}}</b></span><el-progress :percentage="Number(metrics.exam_completion_rate||0)" :show-text="false" color="#7b63c7"/></div></div>
          <button class="dashboard-soft-link" type="button" @click="open('/evaluation/results')"><span><el-icon><TrendCharts/></el-icon>查看完整综合评价</span><el-icon><Right/></el-icon></button>
        </aside>
      </section>

      <section class="dashboard-panel">
        <div class="dashboard-panel-head"><div><h2>近期学习日程</h2><p>未来 30 天已安排的课程与考试</p></div><el-icon class="panel-head-icon"><Calendar/></el-icon></div>
        <div v-if="data.learning_schedule?.length" class="dashboard-timeline">
          <button v-for="item in data.learning_schedule" :key="`${item.type}-${item.id}`" type="button" @click="open(item.route)">
            <span class="timeline-date"><b>{{dateTime(item.starts_at).split(' ')[0]}}</b><small>{{dateTime(item.starts_at).split(' ')[1]}}</small></span>
            <span class="timeline-icon" :class="item.type.toLowerCase()"><el-icon><component :is="scheduleIcon(item.type)"/></el-icon></span>
            <span class="timeline-copy"><strong>{{item.title}}</strong><small>{{item.type==='COURSE'?`${item.course_name}${item.location?` · ${item.location}`:''}`:`考试 · ${item.exam_name}`}}</small></span>
            <el-tag size="small" :type="item.type==='COURSE'?'success':'warning'" effect="plain">{{item.type==='COURSE'?'课程':'考试'}}</el-tag>
          </button>
        </div>
        <el-empty v-else :image-size="66" description="未来 30 天暂无课程或考试安排"/>
      </section>

      <section class="dashboard-two-column">
        <article class="dashboard-panel">
          <div class="dashboard-panel-head"><div><h2>季度综合评分</h2><p>仅展示已经正式发布的结果</p></div><el-button link type="primary" @click="open('/evaluation/results')">查看详情</el-button></div>
          <div v-if="data.quarter_scores?.length" class="quarter-score-grid">
            <button v-for="item in data.quarter_scores" :key="item.period_key" type="button" @click="open('/evaluation/results')"><span><small>{{item.period_key}}</small><strong>{{score(item.final_score)}}</strong></span><el-progress type="circle" :percentage="Number(item.final_score||0)" :width="58" :stroke-width="6" :show-text="false"/></button>
          </div>
          <el-empty v-else :image-size="66" description="暂无已发布季度评分"/>
        </article>
        <article class="dashboard-panel">
          <div class="dashboard-panel-head"><div><h2>导师评价</h2><p>评价结果发布后在这里呈现导师反馈</p></div><el-icon class="panel-head-icon"><ChatDotRound/></el-icon></div>
          <div v-if="data.mentor_feedback?.length" class="feedback-list"><div v-for="item in data.mentor_feedback" :key="item.period_key"><span class="feedback-score">{{score(item.score)}}</span><span><strong>{{item.period_key}} · {{item.evaluator_name}}</strong><p>{{item.comment}}</p></span></div></div>
          <el-empty v-else :image-size="66" description="暂无已发布导师评价"/>
        </article>
      </section>

      <section class="dashboard-panel">
        <div class="dashboard-panel-head"><div><h2>最近完成的任务</h2><p>保留完成时间和审核得分，便于回顾阶段成果</p></div><el-icon class="panel-head-icon"><Medal/></el-icon></div>
        <el-table :data="data.completed_tasks||[]" empty-text="暂无已完成任务">
          <el-table-column prop="title" label="任务" min-width="220"/><el-table-column label="完成时间" width="150"><template #default="scope">{{shortDate(scope.row.completed_at)}}</template></el-table-column><el-table-column label="审核得分" width="110"><template #default="scope"><strong class="table-score">{{score(scope.row.final_score)}}</strong></template></el-table-column><el-table-column label="状态" width="100"><template #default><el-tag type="success" effect="plain">已完成</el-tag></template></el-table-column>
        </el-table>
      </section>
    </template>

    <template v-else-if="data">
      <section class="dashboard-summary-grid">
        <button v-for="item in managerSummary" :key="item.label" class="dashboard-metric-card" :class="item.tone" type="button" @click="open(item.route)">
          <span class="metric-icon"><el-icon><component :is="item.icon"/></el-icon></span><span class="metric-copy"><small>{{item.label}}</small><strong>{{item.value}}</strong><span>{{item.detail}}</span></span><el-icon v-if="item.route" class="metric-arrow"><Right/></el-icon>
        </button>
      </section>

      <section class="dashboard-panel">
        <div class="dashboard-panel-head"><div><h2>职责待办</h2><p>只呈现当前角色有权限处理的事项，已清零项目仍保留核对入口</p></div><span class="dashboard-count-badge">{{metrics.pending_actions||0}} 项待处理</span></div>
        <div v-if="data.work_queue?.length" class="manager-queue-grid">
          <button v-for="item in data.work_queue" :key="item.code" type="button" :class="item.tone.toLowerCase()" @click="open(item.route)">
            <span class="queue-icon"><el-icon><component :is="queueIcon(item.code)"/></el-icon></span>
            <span class="queue-copy"><strong>{{item.title}}</strong><small>{{item.description}}</small></span>
            <span class="queue-count" :class="{done:Number(item.count)===0}"><b>{{item.count}}</b><small>{{Number(item.count)===0?'已清零':'待处理'}}</small></span>
            <el-icon><Right/></el-icon>
          </button>
        </div>
        <el-empty v-else :image-size="66" description="当前角色暂无操作待办"/>
      </section>

      <section class="dashboard-operation-grid">
        <button v-for="item in operationCards" :key="item.key" type="button" class="operation-card" :class="item.tone" @click="open(item.route)">
          <span class="operation-head"><span class="operation-icon"><el-icon><component :is="item.icon"/></el-icon></span><span><strong>{{item.title}}</strong><small>{{item.description}}</small></span><el-icon class="operation-arrow"><Right/></el-icon></span>
          <span class="operation-progress"><span><b>{{percent(item.rate)}}</b><small>{{item.key==='evaluation'?'发布覆盖率':item.key==='course'?'出勤完成率':'完成率'}}</small></span><el-progress :percentage="item.rate" :show-text="false"/></span>
          <span class="operation-stats"><span v-for="stat in item.stats" :key="stat[0]"><small>{{stat[0]}}</small><b>{{stat[1]}}</b></span></span>
        </button>
      </section>

      <section class="dashboard-two-column dashboard-manager-lower">
        <article class="dashboard-panel">
          <div class="dashboard-panel-head"><div><h2>近期培养日程</h2><p>未来 30 天课程和考试安排</p></div><el-icon class="panel-head-icon"><Calendar/></el-icon></div>
          <div v-if="data.schedule?.length" class="manager-schedule-list"><button v-for="item in data.schedule" :key="`${item.type}-${item.id}`" type="button" @click="open(item.route)"><span class="schedule-day"><b>{{dateTime(item.starts_at).slice(0,5)}}</b><small>{{dateTime(item.starts_at).slice(6)}}</small></span><span class="timeline-icon" :class="item.type.toLowerCase()"><el-icon><component :is="scheduleIcon(item.type)"/></el-icon></span><span class="timeline-copy"><strong>{{item.title}}</strong><small>{{item.type==='COURSE'?`${item.course_name}${item.location?` · ${item.location}`:''}`:`考试 · ${item.exam_name}`}}</small></span><span class="schedule-target">{{item.target_count}} 人</span><el-icon><Right/></el-icon></button></div>
          <el-empty v-else :image-size="66" description="未来 30 天暂无培养安排"/>
        </article>

        <article class="dashboard-panel">
          <div class="dashboard-panel-head"><div><h2>需要关注的员工</h2><p>根据逾期、退回、缺考和本月评价状态聚合</p></div><el-icon class="panel-head-icon warning"><Warning/></el-icon></div>
          <div v-if="data.attention_employees?.length" class="attention-list"><button v-for="item in data.attention_employees" :key="item.id" type="button" @click="open(`/evaluation/monthly?employeeId=${item.id}`)"><span class="attention-avatar">{{String(item.name||'员').slice(0,1)}}</span><span class="attention-copy"><span><strong>{{item.name}}</strong><small>{{item.employee_no}} · {{item.station_name||item.batch_name||'未分组'}}</small></span><span class="attention-reasons"><em v-if="item.overdue_tasks">逾期任务 {{item.overdue_tasks}}</em><em v-if="item.returned_tasks">退回任务 {{item.returned_tasks}}</em><em v-if="item.absent_exams">缺考 {{item.absent_exams}}</em><em v-if="item.evaluation_missing">本月评价未发布</em></span></span><el-tag :type="riskType(item.risk_level)" effect="plain" size="small">{{riskLabel(item.risk_level)}}</el-tag><el-icon><Right/></el-icon></button></div>
          <el-empty v-else :image-size="66" description="当前没有需要特别关注的员工"/>
        </article>
      </section>
    </template>
  </div>
</template>
