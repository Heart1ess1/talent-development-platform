<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {ArrowRight,Search} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {dateTimeParts,resultStatusLabels,scoreMonth} from './examUi'

const auth=useAuthStore(),canManage=computed(()=>auth.can('exam:manage'))
const plans=ref<any[]>([]),results=ref<any[]>([]),planResults=ref<any[]>([])
const keyword=ref(''),phase=ref(''),detailKeyword=ref(''),detailStatus=ref('')
const detailVisible=ref(false),detailLoading=ref(false),selectedPlan=ref<any>(null)

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
  return matchesKeyword&&(!detailStatus.value||row.participation_status===detailStatus.value)
}))
const detailOverview=computed(()=>({
  assigned:planResults.value.length,
  completed:planResults.value.filter(x=>x.participation_status==='COMPLETED').length,
  incomplete:planResults.value.filter(x=>!['COMPLETED','ABSENT'].includes(x.participation_status)).length,
  absent:planResults.value.filter(x=>x.participation_status==='ABSENT').length
}))

async function load(){
  if(canManage.value)plans.value=(await api.get<any,Envelope<any[]>>('/exams/results/manage/plans')).data
  else results.value=(await api.get<any,Envelope<any[]>>('/exams/results')).data
}
async function openPlan(row:any){
  selectedPlan.value=row;detailKeyword.value='';detailStatus.value='';detailVisible.value=true;detailLoading.value=true
  try{planResults.value=(await api.get<any,Envelope<any[]>>(`/exams/results/manage/plans/${row.id}`)).data}
  finally{detailLoading.value=false}
}
async function publishResult(row:any){
  await api.post(`/exams/attempts/${row.id}/publish`)
  row.published=true
  ElMessage.success('成绩已发布')
  await load()
}
function resultStatus(row:any){return resultStatusLabels[row.result_status]??{label:'--',type:'success'}}
function isPublished(row:any){return row.published===true||row.published===1}
function planPhase(row:any){
  return ({UPCOMING:{label:'待开始',type:'info'},OPEN:{label:'进行中',type:'success'},ENDED:{label:'已结束',type:'warning'}} as any)[row.plan_phase]??{label:'--',type:'info'}
}
function participation(row:any){
  return ({COMPLETED:{label:'已完成',type:'success'},ABSENT:{label:'缺考',type:'danger'},IN_PROGRESS:{label:'考试中',type:'primary'},NOT_STARTED:{label:'未开始',type:'info'},INCOMPLETE:{label:'未完成',type:'warning'}} as any)[row.participation_status]??{label:'--',type:'info'}
}
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>{{canManage?'成绩管理':'我的成绩'}}</h2>
        <p class="muted">{{canManage?'按考试查看完成情况，点击考试可进入员工成绩单':'查看已发布的考试成绩和缺考记录'}}</p>
      </div>
    </div>

    <template v-if="canManage">
      <div class="overview-grid">
        <div class="overview-item"><span>考试总数</span><strong>{{overview.total}}</strong></div>
        <div class="overview-item"><span>待开始</span><strong>{{overview.upcoming}}</strong></div>
        <div class="overview-item success"><span>进行中</span><strong>{{overview.open}}</strong></div>
        <div class="overview-item"><span>已结束</span><strong>{{overview.ended}}</strong></div>
      </div>
      <el-card>
        <template #header>
          <div class="card-head">
            <div><span class="card-title">考试完成情况</span><span class="header-tip">点击任意一行查看该考试的员工成绩单</span></div>
            <div class="filters">
              <el-input v-model="keyword" clearable placeholder="搜索考试或试卷" :prefix-icon="Search"/>
              <el-select v-model="phase" clearable placeholder="考试状态">
                <el-option label="待开始" value="UPCOMING"/><el-option label="进行中" value="OPEN"/><el-option label="已结束" value="ENDED"/>
              </el-select>
            </div>
          </div>
        </template>
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
      </el-card>
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
          <el-select v-model="detailStatus" clearable placeholder="完成状态">
            <el-option label="已完成" value="COMPLETED"/><el-option label="考试中" value="IN_PROGRESS"/><el-option label="未开始" value="NOT_STARTED"/><el-option label="未完成" value="INCOMPLETE"/><el-option label="缺考" value="ABSENT"/>
          </el-select>
        </div>
      </div>
      <el-table :data="filteredPlanResults" v-loading="detailLoading" empty-text="暂无应考员工">
        <el-table-column prop="employee_no" label="工号" width="110"/><el-table-column prop="employee_name" label="员工" min-width="100"/>
        <el-table-column label="完成状态" width="92"><template #default="s"><el-tag :type="participation(s.row).type" effect="plain">{{participation(s.row).label}}</el-tag></template></el-table-column>
        <el-table-column prop="attempt_no" label="考试次数" width="82" align="center"><template #default="s">{{s.row.attempt_no??'--'}}</template></el-table-column>
        <el-table-column prop="total_score" label="成绩" width="74" align="center"><template #default="s"><strong v-if="s.row.total_score!==null&&s.row.total_score!==undefined">{{s.row.total_score}}</strong><span v-else>--</span></template></el-table-column>
        <el-table-column prop="event_count" label="异常次数" width="82" align="center"><template #default="s">{{s.row.event_count??0}}</template></el-table-column>
        <el-table-column label="开始时间" width="118"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.started_at).date}}</span><span>{{dateTimeParts(s.row.started_at).time}}</span></span></template></el-table-column>
        <el-table-column label="提交时间" width="118"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.submitted_at).date}}</span><span>{{dateTimeParts(s.row.submitted_at).time}}</span></span></template></el-table-column>
        <el-table-column label="发布状态" width="92"><template #default="s"><el-tag v-if="s.row.participation_status==='COMPLETED'" :type="isPublished(s.row)?'success':'warning'" effect="plain">{{isPublished(s.row)?'已发布':'待发布'}}</el-tag><span v-else>--</span></template></el-table-column>
        <el-table-column label="操作" width="88" fixed="right"><template #default="s"><el-button v-if="s.row.participation_status==='COMPLETED'&&!isPublished(s.row)" link type="primary" @click="publishResult(s.row)">发布成绩</el-button><span v-else class="muted">--</span></template></el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.overview-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:16px}.overview-item{padding:18px 20px;background:#fff;border:1px solid #e5e7eb;border-radius:8px;box-shadow:0 2px 8px rgba(31,41,55,.04)}.overview-item span{display:block;color:#7b8799;font-size:13px;margin-bottom:7px}.overview-item strong{font-size:25px;color:#253044}.overview-item.success{border-left:3px solid #67c23a}.overview-item.warning{border-left:3px solid #e6a23c}.card-head,.detail-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px}.card-title{font-weight:600}.header-tip{color:#8a96a8;font-size:12px;margin-left:12px}.filters{display:flex;gap:10px}.filters .el-input{width:190px}.filters .el-select{width:130px}.summary-table :deep(.el-table__row){cursor:pointer}.summary-table :deep(.el-table__row:hover) .row-arrow{color:#409eff;transform:translateX(2px)}.exam-name{font-weight:600;color:#303846}.sub-text{color:#8a96a8;font-size:12px;margin-top:4px}.time-range{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#596579;white-space:nowrap}.row-arrow{color:#a9b1bd;transition:.2s}.drawer-title h3{font-size:20px;margin:0;color:#253044}.drawer-title p{font-size:13px;color:#7b8799;margin:7px 0 0}.detail-overview{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:22px}.detail-overview>div{background:#f7f9fc;border-radius:6px;padding:14px 18px}.detail-overview span{display:block;color:#7b8799;font-size:12px}.detail-overview strong{display:block;font-size:22px;margin-top:5px;color:#253044}.detail-overview .green{color:#4d9f2f}.detail-overview .orange{color:#d88a1d}.detail-overview .red{color:#e34d59}.detail-toolbar{margin-bottom:14px}.datetime-cell{display:inline-flex;flex-direction:column;line-height:1.35;white-space:nowrap}.datetime-cell span:last-child{color:#606266}@media(max-width:900px){.overview-grid{grid-template-columns:repeat(2,1fr)}.card-head,.detail-toolbar{align-items:flex-start;flex-direction:column}.detail-overview{grid-template-columns:repeat(2,1fr)}.filters{width:100%}.filters .el-input{flex:1}}
</style>
