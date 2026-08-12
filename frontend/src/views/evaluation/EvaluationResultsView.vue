<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {Calendar,Document,Histogram,Lock,RefreshLeft} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {componentLabels,scoreText,type ComponentCode} from '@/evaluation/model'
import '@/styles/evaluation-center.css'

const auth=useAuthStore()
const canManage=computed(()=>auth.can('evaluation:manage')),isAdmin=computed(()=>['ADMIN','SUPER_ADMIN'].includes(auth.user?.role||'')),isEmployee=computed(()=>auth.user?.role==='EMPLOYEE')
const employees=ref<any[]>([]),selected=ref<number>(),summaries=ref<any[]>([]),loading=ref(false)
const monthRows=computed(()=>summaries.value.filter(x=>x.summary_type==='MONTH'))
const quarterRows=computed(()=>summaries.value.filter(x=>x.summary_type==='QUARTER'))
const latestPublished=computed(()=>monthRows.value.find(x=>x.status==='PUBLISHED'))
const metrics=computed(()=>({total:summaries.value.length,published:summaries.value.filter(x=>x.status==='PUBLISHED').length,drafts:summaries.value.filter(x=>x.status==='DRAFT').length,missing:summaries.value.filter(x=>x.missing_items).length}))
function snapshot(row:any){return row.summary_type==='MONTH'?row.component_snapshot?.components:row.quarter_snapshot?.months}
async function loadEmployees(){if(isEmployee.value){const profile=(await api.get<any,Envelope<any>>('/profile/employee')).data;employees.value=[profile]}else employees.value=(await api.get<any,Envelope<any>>('/employees',{params:{size:200}})).data.records;if(!selected.value&&employees.value.length)selected.value=employees.value[0].id}
async function load(){if(!selected.value)return;loading.value=true;try{summaries.value=(await api.get<any,Envelope<any[]>>('/evaluation/summaries',{params:{employeeId:selected.value}})).data}finally{loading.value=false}}
async function generateQuarter(){const year=Number((await ElMessageBox.prompt('请输入年份','生成季度汇总',{inputValue:String(new Date().getFullYear()),inputPattern:/^20\d{2}$/,inputErrorMessage:'请输入四位年份'})).value);const quarter=Number((await ElMessageBox.prompt('请输入季度 1-4','生成季度汇总',{inputPattern:/^[1-4]$/,inputErrorMessage:'请输入1至4'})).value);const count=(await api.post<any,Envelope<number>>('/evaluation/summaries/generate-quarter',null,{params:{year,quarter}})).data;ElMessage.success(`已生成或刷新 ${count} 份季度汇总草稿`);await load()}
async function publish(row:any){let waiverReason:string|undefined,overrideScore:number|undefined;if(row.missing_items){if(!isAdmin.value)return ElMessage.warning('存在缺失项，仅管理员可以核定发布');waiverReason=(await ElMessageBox.prompt('说明为何允许缺项发布','缺项豁免原因')).value;overrideScore=Number((await ElMessageBox.prompt('请输入最终核定综合分（0-100）','人工核定总分',{inputPattern:/^(100(?:\.0{1,2})?|\d{1,2}(?:\.\d{1,2})?)$/,inputErrorMessage:'请输入0至100'})).value)}await api.post(`/evaluation/summaries/${row.id}/publish`,{waiverReason,overrideScore});ElMessage.success('评价结果已发布并锁定');await load()}
async function reopen(row:any){const reason=(await ElMessageBox.prompt('请说明重开原因；系统将保留旧版本','重开月度结果')).value;await api.post(`/evaluation/summaries/${row.id}/reopen`,{reason});ElMessage.success('已创建新的月度草稿版本');await load()}
watch(selected,load);onMounted(async()=>{await loadEmployees();await load()})
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · {{isEmployee?'我的评价':'结果中心'}}</span><h1>{{isEmployee?'我的综合评价':'评价结果中心'}}</h1><p>{{isEmployee?'查看已经正式发布的月度与季度综合评价结果。':'集中核对分项快照、缺失项和版本状态，并完成月度、季度结果发布。'}}</p></div>
      <div class="evaluation-head-actions"><el-select v-if="!isEmployee" v-model="selected" filterable placeholder="选择员工" style="width:240px"><el-option v-for="employee in employees" :key="employee.id" :value="employee.id" :label="`${employee.name}（${employee.employee_no}）`"/></el-select><el-button v-if="canManage" type="primary" :icon="Calendar" @click="generateQuarter">生成季度汇总</el-button></div>
    </header>

    <section class="evaluation-summary-grid">
      <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Histogram/></el-icon></span><div><small>最新月度综合分</small><strong>{{scoreText(latestPublished?.final_score)}}</strong><span>{{latestPublished?.period_key||'暂无已发布结果'}}</span></div></article>
      <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><Lock/></el-icon></span><div><small>已发布结果</small><strong>{{metrics.published}}</strong><span>已锁定可追溯</span></div></article>
      <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><Document/></el-icon></span><div><small>汇总草稿</small><strong>{{metrics.drafts}}</strong><span>等待核对或发布</span></div></article>
      <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><RefreshLeft/></el-icon></span><div><small>存在缺失项</small><strong>{{metrics.missing}}</strong><span>发布前需要处理</span></div></article>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>月度评价</h2><p>展开记录可查看当时使用的评分项、满分、权重、有效得分和综合贡献。</p></div></div>
      <el-table :data="monthRows" empty-text="暂无月度评价结果">
        <el-table-column type="expand"><template #default="s"><div v-if="snapshot(s.row)" class="evaluation-snapshot"><div v-for="item in snapshot(s.row)" :key="item.code"><b>{{componentLabels[item.code as ComponentCode]||item.code}}</b><span>得分 {{scoreText(item.effectiveScore)}} / {{scoreText(item.fullScore||100)}}</span><span>权重 {{item.weight}}%</span><span>贡献 {{scoreText(item.weightedScore)}}</span></div></div><div v-else class="evaluation-empty">历史记录没有分项快照</div></template></el-table-column>
        <el-table-column prop="period_key" label="月份" width="100"/><el-table-column prop="version" label="版本" width="75"><template #default="s">V{{s.row.version}}</template></el-table-column><el-table-column label="综合分" width="100"><template #default="s"><strong>{{scoreText(s.row.final_score)}}</strong></template></el-table-column><el-table-column label="状态" width="100"><template #default="s"><el-tag :type="s.row.status==='PUBLISHED'?'success':'warning'" effect="plain">{{s.row.status==='PUBLISHED'?'已发布':'草稿'}}</el-tag></template></el-table-column><el-table-column prop="missing_items" label="缺失项"><template #default="s"><span v-if="s.row.missing_items" class="evaluation-warning">{{String(s.row.missing_items).split(',').map((x:string)=>componentLabels[x as ComponentCode]||x).join('、')}}</span><span v-else class="evaluation-good">无</span></template></el-table-column><el-table-column v-if="canManage" label="操作" width="130"><template #default="s"><el-button v-if="s.row.status==='DRAFT'" link type="primary" @click="publish(s.row)">发布</el-button><el-button v-if="isAdmin&&s.row.status==='PUBLISHED'" link @click="reopen(s.row)">重开</el-button></template></el-table-column>
      </el-table>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>季度评价</h2><p>季度分由三个月已发布月评按方案权重计算，不直接重复录入评分。</p></div></div>
      <el-table :data="quarterRows" empty-text="暂无季度评价结果">
        <el-table-column type="expand"><template #default="s"><div v-if="snapshot(s.row)" class="evaluation-snapshot"><div v-for="item in snapshot(s.row)" :key="item.month"><b>{{item.month}}</b><span>月度分 {{scoreText(item.score)}}</span><span>权重 {{item.weight}}%</span><span>贡献 {{scoreText(item.contribution)}}</span></div></div></template></el-table-column><el-table-column prop="period_key" label="季度" width="100"/><el-table-column prop="version" label="版本" width="75"><template #default="s">V{{s.row.version}}</template></el-table-column><el-table-column label="综合分" width="100"><template #default="s"><strong>{{scoreText(s.row.final_score)}}</strong></template></el-table-column><el-table-column label="状态" width="100"><template #default="s"><el-tag :type="s.row.status==='PUBLISHED'?'success':'warning'" effect="plain">{{s.row.status==='PUBLISHED'?'已发布':'草稿'}}</el-tag></template></el-table-column><el-table-column prop="missing_items" label="缺失月份"/><el-table-column v-if="canManage" label="操作" width="90"><template #default="s"><el-button v-if="s.row.status==='DRAFT'" link type="primary" @click="publish(s.row)">发布</el-button></template></el-table-column>
      </el-table>
    </section>
  </div>
</template>
