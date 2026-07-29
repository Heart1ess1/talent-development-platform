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
import {useAuthStore} from '@/stores/auth'
import {avatarUrl,nameInitial} from '@/utils/avatar'

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
const filters=reactive({
  keyword:'',
  location:'',
  currentOnly:true,
  dateRange:[] as string[]
})
const historyOpen=ref(false)
const historyLoading=ref(false)
const historyEmployee=ref<LocationRecord|null>(null)
const history=ref<LocationRecord[]>([])

const latestReport=computed(()=>mine.value.records[0])
const currentUpdatedAt=computed(()=>latestReport.value?.occurred_at||'')
const expectedReturn=computed(()=>latestReport.value?.expected_return_at||'')
const hasManagerFilters=computed(()=>Boolean(
  filters.keyword||filters.location||filters.dateRange.length||!filters.currentOnly
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
  const response=await api.get<any,Envelope<MineData>>('/location-reports/mine')
  mine.value=response.data
}
function requestParams(){
  return {
    page:page.value,
    size:pageSize.value,
    keyword:filters.keyword||undefined,
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
  Object.assign(filters,{keyword:'',location:'',currentOnly:true,dateRange:[]})
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
    const response=await api.get<any,Envelope<LocationRecord[]>>(`/location-reports/employee/${row.employee_id}`)
    history.value=response.data
  }finally{
    historyLoading.value=false
  }
}
function isCurrent(row:LocationRecord){
  return row.is_current===true||row.is_current===1
}

onMounted(load)
</script>

<template>
  <div class="movement-page">
    <template v-if="isEmployee">
      <section class="movement-hero employee-hero">
        <div>
          <div class="eyebrow">个人行程</div>
          <h1>位置报备</h1>
          <p>位置发生变化时及时报备，替代群消息，让带教老师和管理人员准确掌握行程。</p>
        </div>
        <el-button type="primary" :icon="Plus" @click="openReport">提交位置报备</el-button>
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
          <span>外出、出差或临时前往其他地点时，请及时提交位置报备。</span>
          <el-button type="primary" plain :icon="Plus" @click="openReport">提交第一条报备</el-button>
        </div>
      </section>
    </template>

    <template v-else>
      <section class="movement-hero">
        <div>
          <div class="eyebrow">人员管理 · 人员流动</div>
          <h1>人员流动</h1>
          <p>集中查看权限范围内员工的位置变化和行程原因，减少群消息遗漏。</p>
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
            <div class="empty-state compact"><el-icon><Search/></el-icon><strong>暂无匹配的流动记录</strong><span>调整筛选条件后重试</span></div>
          </template>
        </el-table>
        <div class="pagination-bar">
          <span>每条报备均保留为独立轨迹，不覆盖历史记录</span>
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
.movement-page{min-height:100%;padding:22px 22px 42px;background:#f5f7fb;color:#172033}
.movement-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;max-width:none;margin:0 auto 22px}
.eyebrow{margin-bottom:7px;color:#356bd8;font-size:12px;font-weight:700;letter-spacing:.12em}
.movement-hero h1{margin:0;font-size:28px;line-height:1.25;letter-spacing:-.02em}
.movement-hero p{margin:8px 0 0;color:#64748b;font-size:14px}
.movement-hero .el-button{height:38px;border-radius:9px}
.current-location-card{display:flex;align-items:center;gap:18px;max-width:1100px;margin:0 auto 18px;padding:22px 24px;border:1px solid #dfe7f3;border-radius:14px;background:linear-gradient(135deg,#fff 0%,#f7faff 100%);box-shadow:0 5px 20px rgba(34,68,124,.05)}
.location-visual{display:grid;place-items:center;width:58px;height:58px;flex:0 0 auto;border-radius:14px;color:#fff;background:linear-gradient(145deg,#4f8ce9,#2e67ca)}
.location-visual .el-icon{font-size:27px}
.current-copy{display:flex;flex:1;flex-direction:column;min-width:0}
.current-copy>span{color:#7b8799;font-size:12px}
.current-copy>strong{margin-top:4px;color:#1f3d6d;font-size:23px}
.current-copy p{display:flex;align-items:center;gap:6px;margin:6px 0 0;color:#748196;font-size:12px}
.current-meta{display:grid;grid-template-columns:1fr 1fr;gap:28px;margin:0;padding-left:24px;border-left:1px solid #e4e9f1}
.current-meta dt{margin-bottom:5px;color:#8a96a8;font-size:11px}
.current-meta dd{margin:0;color:#344054;font-size:13px;font-weight:600}
.history-card,.flow-card{max-width:none;margin:0 auto;border:1px solid #e4e9f1;border-radius:14px;background:#fff;box-shadow:0 5px 18px rgba(32,51,82,.04);overflow:hidden}
.employee-hero,.history-card{max-width:1100px}
.section-heading,.flow-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:19px 20px;border-bottom:1px solid #edf0f4}
.section-heading>div,.flow-heading>div:first-child{display:flex;align-items:baseline;gap:9px}
.section-heading h2,.flow-heading h2{margin:0;font-size:17px}
.section-heading span,.flow-heading span{color:#8a96a8;font-size:12px}
.movement-timeline{padding:22px 24px}
.timeline-row{display:grid;grid-template-columns:24px minmax(0,1fr);gap:13px;min-height:120px}
.timeline-rail{position:relative;display:flex;justify-content:center}
.timeline-rail:after{position:absolute;top:18px;bottom:-2px;width:2px;background:#dfe5ee;content:''}
.timeline-row:last-child .timeline-rail:after{display:none}
.timeline-dot{position:relative;z-index:1;width:12px;height:12px;margin-top:4px;border:3px solid #99a5b5;border-radius:50%;background:#fff;box-shadow:0 0 0 3px #f0f2f5}
.timeline-dot.is-current{border-color:#26a779;box-shadow:0 0 0 3px #e7f7f1}
.timeline-content{padding:0 0 24px;min-width:0}
.timeline-topline{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}
.route,.table-route{display:flex;align-items:center;gap:7px;min-width:0;color:#7b8797;font-size:13px}
.route strong,.table-route strong{color:#273348;font-size:15px}
.route .el-icon,.table-route .el-icon{color:#a5afbd}
.timeline-topline time,.timeline-content>time{display:block;color:#8793a4;font-size:12px;white-space:nowrap}
.timeline-content>time{margin-top:5px}
.reason{margin:9px 0 0;color:#4e5c70;font-size:13px;line-height:1.65}
.timeline-extra{display:flex;gap:18px;margin-top:9px;color:#929dae;font-size:11px}
.timeline-extra span{display:flex;align-items:center;gap:4px}
.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;max-width:none;margin:0 auto 18px}
.summary-card{display:flex;align-items:center;gap:13px;padding:17px 18px;border:1px solid #e7ebf2;border-radius:12px;background:#fff}
.summary-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:11px}
.summary-icon :deep(svg){width:20px}
.summary-icon.blue{color:#3468d4;background:#eef4ff}.summary-icon.green{color:#14815e;background:#eaf8f2}.summary-icon.amber{color:#b46912;background:#fff5e6}.summary-icon.violet{color:#7656c8;background:#f2effd}
.summary-card>div:last-child{display:flex;flex-direction:column;gap:3px}
.summary-card span{color:#7a879b;font-size:12px}.summary-card strong{font-size:22px}
.flow-heading{align-items:flex-start;flex-direction:column}
.manager-filters{display:flex!important;align-items:center!important;gap:9px!important;width:100%}
.manager-filters .el-input{width:220px}.manager-filters .el-date-editor{width:260px}.manager-filters .el-checkbox{margin:0 4px}
.flow-table{border-top:1px solid #edf0f4}
.flow-table :deep(th.el-table__cell){background:#f8fafc;color:#64748b}
.person-cell{display:flex;align-items:center;gap:10px;border:0;background:transparent;text-align:left;cursor:pointer}
.person-cell .el-avatar{flex:0 0 auto;color:#356bd8;background:#eaf2ff;font-size:12px;font-weight:700}
.person-cell>span,.org-cell{display:flex;flex-direction:column;gap:2px;min-width:0}
.person-cell strong{color:#273348;font-size:13px}.person-cell small,.org-cell small{color:#8d98a8;font-size:11px}
.person-cell:hover strong{color:#356bd8}
.table-route span,.table-route strong{max-width:115px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.table-route strong{font-size:13px}.org-cell span{color:#475467;font-size:12px}
.muted-value{color:#a0a9b6}
.pagination-bar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:15px 18px;border-top:1px solid #edf0f4}
.pagination-bar>span{color:#8d98a8;font-size:11px}
.empty-state{display:flex;flex-direction:column;align-items:center;gap:8px;padding:48px 20px;color:#8f9bad}
.empty-state .el-icon{font-size:34px;color:#aeb8c6}.empty-state strong{color:#526075;font-size:14px}.empty-state span{font-size:12px;text-align:center}
.empty-state.compact{padding:30px 16px}.empty-state.compact .el-icon{font-size:28px}
.report-notice{display:flex;gap:11px;margin:-4px 0 20px;padding:13px 14px;border-radius:10px;background:#f4f7fc}
.report-notice>.el-icon{margin-top:2px;color:#356bd8;font-size:20px}
.report-notice>div{display:flex;flex-direction:column;gap:3px}.report-notice strong{font-size:13px}.report-notice span{color:#7e8a9d;font-size:11px}
.form-row{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-row :deep(.el-date-editor){width:100%}
.drawer-person{display:flex;align-items:center;gap:11px}
.drawer-person>div{display:flex;flex-direction:column;gap:3px}.drawer-person strong{color:#273348;font-size:16px}.drawer-person span{color:#8b96a7;font-size:11px}
.drawer-history{padding:6px 4px}.drawer-history .timeline-row{min-height:142px}
@media(max-width:1100px){
  .summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .manager-filters{flex-wrap:wrap}
}
@media(max-width:700px){
  .movement-page{padding:18px 12px 80px}
  .movement-hero{align-items:flex-start;flex-direction:column}
  .movement-hero h1{font-size:24px}
  .movement-hero .el-button{width:100%}
  .current-location-card{align-items:flex-start;flex-wrap:wrap;padding:18px}
  .current-copy{min-width:calc(100% - 80px)}
  .current-meta{grid-template-columns:1fr 1fr;width:100%;padding:14px 0 0;border-top:1px solid #e4e9f1;border-left:0}
  .summary-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}
  .summary-card{padding:13px 11px}.summary-icon{width:36px;height:36px}.summary-card strong{font-size:19px}
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
