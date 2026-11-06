<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {
  Calendar,
  Clock,
  Location,
  MapLocation,
  Plus,
  Position,
  Refresh,
  Right,
  Search,
  UserFilled
} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useRouter} from 'vue-router'
import {useAuthStore} from '@/stores/auth'
import {avatarUrl,nameInitial} from '@/utils/avatar'
import {loadDictionaryValues,type DictionaryOption} from '@/utils/masterData'

type LocationRecord=Record<string,any>
type MineData={
  employeeId:number
  employeeName:string
  stationName:string
  currentLocation:string
  records:LocationRecord[]
}
type Summary={totalReports:number;trackedEmployees:number;todayReports:number;weekReports:number}

const auth=useAuthStore()
const router=useRouter()
function duringPlan(plan:LocationRecord){const at=Date.now();return plan.request_status==='APPROVED'&&new Date(String(plan.start_at).replace(' ','T')).getTime()<=at&&new Date(String(plan.end_at).replace(' ','T')).getTime()>at}
const isEmployee=computed(()=>auth.user?.role==='EMPLOYEE')
const loading=ref(false)
const submitting=ref(false)
const dialogOpen=ref(false)

const mine=ref<MineData>({
  employeeId:0,
  employeeName:'',
  stationName:'',
  currentLocation:'尚未报备',
  records:[]
})
const form=reactive({
  location:'',
  reason:'',
  occurredAt:'',
  expectedReturnAt:''
})

const rows=ref<LocationRecord[]>([])
const total=ref(0)
const page=ref(1)
const pageSize=ref(20)
const summary=reactive<Summary>({totalReports:0,trackedEmployees:0,todayReports:0,weekReports:0})
const classOptions=ref<DictionaryOption[]>([])
const classPositionOptions=ref<DictionaryOption[]>([])
const filters=reactive({
  keyword:'',
  classId:null as number|null,
  classPositionId:null as number|null,
  location:'',
  currentOnly:true,
  dateRange:[] as string[]
})
const historyOpen=ref(false)
const historyLoading=ref(false)
const historyEmployee=ref<LocationRecord|null>(null)
const history=ref<LocationRecord[]>([])
const leavePlans=ref<LocationRecord[]>([])
const historyPlans=ref<LocationRecord[]>([])

const latestReport=computed(()=>mine.value.records[0])
const currentUpdatedAt=computed(()=>latestReport.value?.occurred_at||'')
const expectedReturn=computed(()=>latestReport.value?.expected_return_at||'')
const hasManagerFilters=computed(()=>Boolean(
  filters.keyword||filters.classId||filters.classPositionId||filters.location||filters.dateRange.length||!filters.currentOnly
))

function pad(value:number){return String(value).padStart(2,'0')}
function nowValue(){
  const value=new Date()
  return `${value.getFullYear()}-${pad(value.getMonth()+1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:00`
}
function formatDateTime(value:any){
  if(!value)return '未设置'
  return String(value).replace('T',' ').substring(0,16)
}
function openReport(){
  Object.assign(form,{location:'',reason:'',occurredAt:nowValue(),expectedReturnAt:''})
  dialogOpen.value=true
}
async function loadMine(){
  const [response,plans]=await Promise.all([
    api.get<any,Envelope<MineData>>('/location-reports/mine'),
    api.get<any,Envelope<LocationRecord[]>>('/leave-requests/plans')
  ])
  mine.value=response.data
  leavePlans.value=plans.data
}
function requestParams(){
  return {
    page:page.value,
    size:pageSize.value,
    keyword:filters.keyword||undefined,
    classId:filters.classId||undefined,
    classPositionId:filters.classPositionId||undefined,
    location:filters.location||undefined,
    currentOnly:filters.currentOnly,
    dateFrom:filters.dateRange[0]||undefined,
    dateTo:filters.dateRange[1]||undefined
  }
}
async function loadManager(){
  const [listResponse,summaryResponse]=await Promise.all([
    api.get<any,Envelope<any>>('/location-reports',{params:requestParams()}),
    api.get<any,Envelope<Summary>>('/location-reports/summary')
  ])
  rows.value=listResponse.data.records
  total.value=listResponse.data.total
  Object.assign(summary,summaryResponse.data)
}
async function load(){
  loading.value=true
  try{
    if(isEmployee.value)await loadMine()
    else await loadManager()
  }finally{
    loading.value=false
  }
}
async function submitReport(){
  if(!form.location.trim())return ElMessage.warning('请输入新的所在位置')
  if(!form.occurredAt)return ElMessage.warning('请选择变动时间')
  if(!form.reason.trim())return ElMessage.warning('请填写位置变动原因')
  if(form.expectedReturnAt&&form.expectedReturnAt<=form.occurredAt){
    return ElMessage.warning('预计返回时间必须晚于变动时间')
  }
  submitting.value=true
  try{
    await api.post('/location-reports',{
      location:form.location.trim(),
      reason:form.reason.trim(),
      occurredAt:form.occurredAt.replace(' ','T'),
      expectedReturnAt:form.expectedReturnAt?form.expectedReturnAt.replace(' ','T'):null
    })
    dialogOpen.value=false
    ElMessage.success('位置报备已提交')
    await loadMine()
  }finally{
    submitting.value=false
  }
}
function search(){
  page.value=1
  load()
}
function resetFilters(){
  Object.assign(filters,{keyword:'',classId:null,classPositionId:null,location:'',currentOnly:true,dateRange:[]})
  search()
}
function changePageSize(){
  page.value=1
  load()
}
async function openHistory(row:LocationRecord){
  historyEmployee.value=row
  historyOpen.value=true
  historyLoading.value=true
  try{
    const [response,plans]=await Promise.all([
      api.get<any,Envelope<LocationRecord[]>>(`/location-reports/employee/${row.employee_id}`),
      api.get<any,Envelope<LocationRecord[]>>('/leave-requests/plans',{params:{employeeId:row.employee_id}})
    ])
    history.value=response.data
    historyPlans.value=plans.data
  }finally{
    historyLoading.value=false
  }
}
function isCurrent(row:LocationRecord){
  return row.is_current===true||row.is_current===1
}

onMounted(async()=>{if(!isEmployee.value)[classOptions.value,classPositionOptions.value]=await Promise.all([loadDictionaryValues('CLASS'),loadDictionaryValues('CLASS_POSITION')]);await load()})
</script>

<template>
  <div class="movement-page">
    <template v-if="isEmployee">
      <section class="movement-hero employee-hero">
        <div>

          <h1>位置报备</h1>

        </div>
        <el-button type="primary" :icon="Plus" @click="openReport">提交位置报备</el-button>
      </section>

      <section v-if="leavePlans.length" class="history-card leave-plan-section">
        <div class="section-heading"><div><h2>已批准的计划行程</h2><span>计划地点不代表实际位置</span></div></div>
        <div class="leave-plan-list"><article v-for="plan in leavePlans" :key="plan.request_id" class="leave-plan-row"><div><strong>{{plan.destination}}</strong><el-tag size="small" type="info">{{plan.request_status==='CANCELLED'?'已取消':plan.request_status==='RETURNED'?'已销假':'计划'}}</el-tag></div><span>{{formatDateTime(plan.start_at)}} 至 {{formatDateTime(plan.end_at)}} · 预计回 {{plan.return_location}}</span><small>审批人：{{plan.approver_name}}</small><small v-if="duringPlan(plan)">按批准计划处于请假时段</small><el-button link type="primary" @click="router.push({path:'/leave-requests',query:{requestId:plan.request_id}})">查看请假单</el-button></article></div>
      </section>

      <section v-loading="loading" class="current-location-card">
        <div class="location-visual"><el-icon><Position/></el-icon></div>
        <div class="current-copy">
          <span>当前报备位置</span>
          <strong>{{mine.currentLocation}}</strong>
          <p v-if="latestReport">{{latestReport.from_location}} <el-icon><Right/></el-icon> {{latestReport.to_location}}</p>
          <p v-else>以所属服务站“{{mine.stationName||'未分配'}}”作为初始位置</p>
        </div>
        <dl class="current-meta">
          <div>
            <dt>最近更新</dt>
            <dd>{{currentUpdatedAt?formatDateTime(currentUpdatedAt):'尚未提交报备'}}</dd>
          </div>
          <div>
            <dt>预计返回</dt>
            <dd>{{expectedReturn?formatDateTime(expectedReturn):'未设置'}}</dd>
          </div>
        </dl>
      </section>

      <section class="history-card">
        <div class="section-heading">
          <div><h2>我的位置轨迹</h2><span>共 {{mine.records.length}} 条报备记录</span></div>
          <el-button :icon="Refresh" :loading="loading" text @click="load">刷新</el-button>
        </div>
        <div v-if="mine.records.length" class="movement-timeline">
          <article v-for="(record,index) in mine.records" :key="record.id" class="timeline-row">
            <div class="timeline-rail">
              <span :class="['timeline-dot',{'is-current':index===0}]"></span>
            </div>
            <div class="timeline-content">
              <div class="timeline-topline">
                <div class="route">
                  <span>{{record.from_location}}</span>
                  <el-icon><Right/></el-icon>
                  <strong>{{record.to_location}}</strong>
                  <el-tag v-if="index===0" size="small" type="success" effect="light">当前位置</el-tag>
                </div>
                <time>{{formatDateTime(record.occurred_at)}}</time>
              </div>
              <p class="reason">{{record.reason}}</p>
              <div class="timeline-extra">
                <span><el-icon><Clock/></el-icon>预计返回：{{formatDateTime(record.expected_return_at)}}</span>
                <span>提交于 {{formatDateTime(record.created_at)}}</span>
              </div>
            </div>
          </article>
        </div>
        <div v-else class="empty-state">
          <el-icon><MapLocation/></el-icon>
          <strong>暂无位置变动记录</strong>

          <el-button type="primary" plain :icon="Plus" @click="openReport">提交第一条报备</el-button>
        </div>
      </section>
    </template>

    <template v-else>
      <section class="movement-hero">
        <div>

          <h1>人员流动</h1>

        </div>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
      </section>

      <section class="summary-grid">
        <div class="summary-card"><div class="summary-icon blue"><MapLocation/></div><div><span>累计报备</span><strong>{{summary.totalReports}}</strong></div></div>
        <div class="summary-card"><div class="summary-icon green"><UserFilled/></div><div><span>已报备人员</span><strong>{{summary.trackedEmployees}}</strong></div></div>
        <div class="summary-card"><div class="summary-icon amber"><Clock/></div><div><span>今日变动</span><strong>{{summary.todayReports}}</strong></div></div>
        <div class="summary-card"><div class="summary-icon violet"><Calendar/></div><div><span>近 7 日报备</span><strong>{{summary.weekReports}}</strong></div></div>
      </section>

      <section class="flow-card">
        <div class="flow-heading">
          <div><h2>流动记录</h2><span>共 {{total}} 条记录</span></div>
          <div class="manager-filters">
            <el-input v-model="filters.keyword" :prefix-icon="Search" clearable placeholder="搜索姓名、工号或原因" @keyup.enter="search"/>
            <el-select v-model="filters.classId" clearable filterable placeholder="全部班级"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
            <el-select v-model="filters.classPositionId" clearable filterable placeholder="全部班级职务"><el-option v-for="item in classPositionOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
            <el-input v-model="filters.location" :prefix-icon="Location" clearable placeholder="搜索地点" @keyup.enter="search"/>
            <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" range-separator="至"/>
            <el-checkbox v-model="filters.currentOnly" label="仅看当前位置" @change="search"/>
            <el-button type="primary" @click="search">查询</el-button>
            <el-button v-if="hasManagerFilters" @click="resetFilters">重置</el-button>
          </div>
        </div>

        <el-table v-loading="loading" :data="rows" row-key="id" class="flow-table">
          <el-table-column label="人员" min-width="180" fixed="left">
            <template #default="{row}">
              <button class="person-cell" type="button" @click="openHistory(row)">
                <el-avatar :size="36" :src="avatarUrl(row.avatar_token)">{{nameInitial(row.employee_name)}}</el-avatar>
                <span><strong>{{row.employee_name}}</strong><small>{{row.employee_no}} · {{row.batch_name||'未分批次'}}</small></span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="位置变化" min-width="250">
            <template #default="{row}">
              <div class="table-route">
                <span>{{row.from_location}}</span><el-icon><Right/></el-icon><strong>{{row.to_location}}</strong>
                <el-tag v-if="isCurrent(row)" size="small" type="success" effect="light">当前</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="变动时间" width="152"><template #default="{row}">{{formatDateTime(row.occurred_at)}}</template></el-table-column>
          <el-table-column label="预计返回" width="152"><template #default="{row}"><span :class="{'muted-value':!row.expected_return_at}">{{formatDateTime(row.expected_return_at)}}</span></template></el-table-column>
          <el-table-column label="变动原因" min-width="220" show-overflow-tooltip><template #default="{row}">{{row.reason}}</template></el-table-column>
          <el-table-column label="所属信息" min-width="180"><template #default="{row}"><div class="org-cell"><span>{{row.business_unit_name||'未分板块'}}</span><small>{{row.station_name||'未分配服务站'}}</small></div></template></el-table-column>
          <el-table-column label="操作" width="92" fixed="right" align="right"><template #default="{row}"><el-button link type="primary" @click="openHistory(row)">查看轨迹</el-button></template></el-table-column>
          <template #empty>
            <div class="empty-state compact"><el-icon><Search/></el-icon><strong>暂无匹配的流动记录</strong></div>
          </template>
        </el-table>
        <div class="pagination-bar">

          <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[20,50,100]" :total="total" layout="total,sizes,prev,pager,next" background @current-change="loadManager" @size-change="changePageSize"/>
        </div>
      </section>
    </template>

    <el-dialog v-model="dialogOpen" title="提交位置报备" width="560px" class="report-dialog" destroy-on-close>
      <div class="report-notice"><el-icon><Position/></el-icon><div><strong>记录本次位置变化</strong><span>提交后会形成不可覆盖的人员流动轨迹，请确保信息真实准确。</span></div></div>
      <el-form label-position="top">
        <el-form-item label="新的所在位置" required>
          <el-input v-model="form.location" maxlength="128" show-word-limit placeholder="请输入城市、区县或具体驻点，如：上海市浦东新区"/>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="变动时间" required>
            <el-date-picker v-model="form.occurredAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="选择变动时间"/>
          </el-form-item>
          <el-form-item label="预计返回时间（选填）">
            <el-date-picker v-model="form.expectedReturnAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="暂不确定可不填"/>
          </el-form-item>
        </div>
        <el-form-item label="位置变动原因" required>
          <el-input v-model="form.reason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="请说明外出、出差或临时变动的具体原因"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen=false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReport">确认提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="historyOpen" size="560px" class="history-drawer">
      <template #header>
        <div v-if="historyEmployee" class="drawer-person">
          <el-avatar :size="42" :src="avatarUrl(historyEmployee.avatar_token)">{{nameInitial(historyEmployee.employee_name)}}</el-avatar>
          <div><strong>{{historyEmployee.employee_name}} 的位置轨迹</strong><span>{{historyEmployee.employee_no}} · {{history.length}} 条记录</span></div>
        </div>
      </template>
      <div v-loading="historyLoading" class="drawer-history">
        <h3 v-if="historyPlans.length">批准的计划行程</h3>
        <article v-for="plan in historyPlans" :key="`leave-${plan.request_id}`" class="leave-plan-row"><strong>{{plan.destination}} · {{plan.request_status==='CANCELLED'?'已取消':plan.request_status==='RETURNED'?'已销假':'计划'}}</strong><span>{{formatDateTime(plan.start_at)}} 至 {{formatDateTime(plan.end_at)}}</span><small>预计回 {{plan.return_location}} · {{plan.approver_name}} 审批</small><small v-if="duringPlan(plan)">按批准计划处于请假时段</small><el-button link type="primary" @click="router.push({path:'/leave-requests',query:{requestId:plan.request_id}})">查看请假单</el-button></article>
        <h3 v-if="historyPlans.length">实际位置记录</h3>
        <article v-for="(record,index) in history" :key="record.id" class="timeline-row">
          <div class="timeline-rail"><span :class="['timeline-dot',{'is-current':index===0}]"></span></div>
          <div class="timeline-content">
            <div class="route"><span>{{record.from_location}}</span><el-icon><Right/></el-icon><strong>{{record.to_location}}</strong><el-tag v-if="index===0" size="small" type="success">当前</el-tag></div>
            <time>{{formatDateTime(record.occurred_at)}}</time>
            <p class="reason">{{record.reason}}</p>
            <div class="timeline-extra"><span>预计返回：{{formatDateTime(record.expected_return_at)}}</span></div>
          </div>
        </article>
        <div v-if="!historyLoading&&!history.length" class="empty-state compact"><el-icon><MapLocation/></el-icon><strong>暂无位置轨迹</strong></div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.movement-page{min-height:100%;padding:22px 22px 42px;background:var(--app-page-bg);color:#1f1f1f}
.leave-plan-section{margin-bottom:18px}.leave-plan-list{padding:4px 20px}.leave-plan-row{display:flex;flex-direction:column;gap:5px;padding:12px 0;border-bottom:1px solid #f0f0f0}.leave-plan-row>div{display:flex;align-items:center;gap:8px}.leave-plan-row span,.leave-plan-row small{color:#727272}
.movement-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;max-width:none;margin:0 auto 22px}
.eyebrow{margin-bottom:7px;color:var(--app-primary);font-size:14px;font-weight:700;letter-spacing:.12em}
.movement-hero h1{margin:0;font-size:34px;line-height:1.25;letter-spacing:-.02em}
.movement-hero p{margin:8px 0 0;color:#727272;font-size:16px}
.movement-hero .el-button{height:38px;border-radius:9px}
.current-location-card{display:flex;align-items:center;gap:18px;max-width:1100px;margin:0 auto 18px;padding:22px 24px;border:1px solid var(--app-primary-border);border-radius:14px;background:linear-gradient(135deg,#fff 0%,var(--app-primary-soft) 100%);box-shadow:0 5px 20px rgba(0,0,0,.05)}
.location-visual{display:grid;place-items:center;width:58px;height:58px;flex:0 0 auto;border-radius:14px;color:#fff;background:linear-gradient(145deg,var(--app-primary),var(--app-primary))}
.location-visual .el-icon{font-size:34px}
.current-copy{display:flex;flex:1;flex-direction:column;min-width:0}
.current-copy>span{color:#868686;font-size:14px}
.current-copy>strong{margin-top:4px;color:#3a3a3a;font-size:30px}
.current-copy p{display:flex;align-items:center;gap:6px;margin:6px 0 0;color:#808080;font-size:14px}
.current-meta{display:grid;grid-template-columns:1fr 1fr;gap:28px;margin:0;padding-left:24px;border-left:1px solid #e9e9e9}
.current-meta dt{margin-bottom:5px;color:#959595;font-size:14px}
.current-meta dd{margin:0;color:#3f3f3f;font-size:14px;font-weight:600}
.history-card,.flow-card{max-width:none;margin:0 auto;border:1px solid #e9e9e9;border-radius:14px;background:#fff;box-shadow:0 5px 18px rgba(0,0,0,.04);overflow:hidden}
.employee-hero,.history-card{max-width:1100px}
.section-heading,.flow-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:19px 20px;border-bottom:1px solid #f0f0f0}
.section-heading>div,.flow-heading>div:first-child{display:flex;align-items:baseline;gap:9px}
.section-heading h2,.flow-heading h2{margin:0;font-size:20px}
.section-heading span,.flow-heading span{color:#959595;font-size:14px}
.movement-timeline{padding:22px 24px}
.timeline-row{display:grid;grid-template-columns:24px minmax(0,1fr);gap:13px;min-height:120px}
.timeline-rail{position:relative;display:flex;justify-content:center}
.timeline-rail:after{position:absolute;top:18px;bottom:-2px;width:2px;background:#e4e4e4;content:''}
.timeline-row:last-child .timeline-rail:after{display:none}
.timeline-dot{position:relative;z-index:1;width:12px;height:12px;margin-top:4px;border:3px solid #a4a4a4;border-radius:50%;background:#fff;box-shadow:0 0 0 3px #f2f2f2}
.timeline-dot.is-current{border-color:#26a779;box-shadow:0 0 0 3px #e7f7f1}
.timeline-content{padding:0 0 24px;min-width:0}
.timeline-topline{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}
.route,.table-route{display:flex;align-items:center;gap:7px;min-width:0;color:#868686;font-size:14px}
.route strong,.table-route strong{color:#323232;font-size:16px}
.route .el-icon,.table-route .el-icon{color:#aeaeae}
.timeline-topline time,.timeline-content>time{display:block;color:#929292;font-size:14px;white-space:nowrap}
.timeline-content>time{margin-top:5px}
.reason{margin:9px 0 0;color:#5a5a5a;font-size:14px;line-height:1.65}
.timeline-extra{display:flex;gap:18px;margin-top:9px;color:#9c9c9c;font-size:14px}
.timeline-extra span{display:flex;align-items:center;gap:4px}
.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;max-width:none;margin:0 auto 18px}
.summary-card{display:flex;align-items:center;gap:13px;padding:17px 18px;border:1px solid #ebebeb;border-radius:12px;background:#fff}
.summary-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:11px}
.summary-icon :deep(svg){width:20px}
.summary-icon.blue{color:var(--app-primary);background:var(--app-page-bg)}.summary-icon.green{color:#14815e;background:#eaf8f2}.summary-icon.amber{color:#b46912;background:#fff5e6}.summary-icon.violet{color:var(--app-primary);background:var(--app-page-bg)}
.summary-card>div:last-child{display:flex;flex-direction:column;gap:3px}
.summary-card span{color:#868686;font-size:14px}.summary-card strong{font-size:22px}
.flow-heading{align-items:flex-start;flex-direction:column}
.manager-filters{display:flex!important;align-items:center!important;gap:9px!important;width:100%}
.manager-filters .el-input{width:220px}.manager-filters .el-date-editor{width:260px}.manager-filters .el-checkbox{margin:0 4px}
.flow-table{border-top:1px solid #f0f0f0}
.flow-table :deep(th.el-table__cell){background:var(--app-page-bg);color:#727272}
.person-cell{display:flex;align-items:center;gap:10px;border:0;background:transparent;text-align:left;cursor:pointer}
.person-cell .el-avatar{flex:0 0 auto;color:var(--app-primary);background:var(--app-page-bg);font-size:14px;font-weight:700}
.person-cell>span,.org-cell{display:flex;flex-direction:column;gap:2px;min-width:0}
.person-cell strong{color:#323232;font-size:14px}.person-cell small,.org-cell small{color:#979797;font-size:14px}
.person-cell:hover strong{color:var(--app-primary)}
.table-route span,.table-route strong{max-width:115px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.table-route strong{font-size:14px}.org-cell span{color:#535353;font-size:14px}
.muted-value{color:#a8a8a8}
.pagination-bar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:15px 18px;border-top:1px solid #f0f0f0}
.pagination-bar>span{color:#979797;font-size:14px}
.empty-state{display:flex;flex-direction:column;align-items:center;gap:8px;padding:48px 20px;color:#9a9a9a}
.empty-state .el-icon{font-size:34px;color:#b7b7b7}.empty-state strong{color:#5f5f5f;font-size:16px}.empty-state span{font-size:14px;text-align:center}
.empty-state.compact{padding:30px 16px}.empty-state.compact .el-icon{font-size:34px}
.report-notice{display:flex;gap:11px;margin:-4px 0 20px;padding:13px 14px;border-radius:10px;background:var(--app-page-bg)}
.report-notice>.el-icon{margin-top:2px;color:var(--app-primary);font-size:22px}
.report-notice>div{display:flex;flex-direction:column;gap:3px}.report-notice strong{font-size:14px}.report-notice span{color:#898989;font-size:14px}
.form-row{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-row :deep(.el-date-editor){width:100%}
.drawer-person{display:flex;align-items:center;gap:11px}
.drawer-person>div{display:flex;flex-direction:column;gap:3px}.drawer-person strong{color:#323232;font-size:16px}.drawer-person span{color:#959595;font-size:14px}
.drawer-history{padding:6px 4px}.drawer-history .timeline-row{min-height:142px}
@media(max-width:1100px){
  .summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .manager-filters{flex-wrap:wrap}
}
@media(max-width:700px){
  .movement-page{padding:18px 12px 80px}
  .movement-hero{align-items:flex-start;flex-direction:column}
  .movement-hero h1{font-size:30px}
  .movement-hero .el-button{width:100%}
  .current-location-card{align-items:flex-start;flex-wrap:wrap;padding:18px}
  .current-copy{min-width:calc(100% - 80px)}
  .current-meta{grid-template-columns:1fr 1fr;width:100%;padding:14px 0 0;border-top:1px solid #e9e9e9;border-left:0}
  .summary-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}
  .summary-card{padding:13px 11px}.summary-icon{width:36px;height:36px}.summary-card strong{font-size:20px}
  .manager-filters{display:grid!important;grid-template-columns:1fr 1fr}
  .manager-filters .el-input,.manager-filters .el-date-editor{width:100%}
  .manager-filters .el-date-editor{grid-column:1/-1}
  .timeline-topline{flex-direction:column;gap:5px}
  .timeline-extra{align-items:flex-start;flex-direction:column;gap:4px}
  .pagination-bar{align-items:flex-start;flex-direction:column}
  .pagination-bar :deep(.el-pagination__sizes){display:none}
  .form-row{grid-template-columns:1fr;gap:0}
  :deep(.report-dialog){width:calc(100% - 24px)!important}
  :deep(.history-drawer){width:100%!important}
}
</style>
