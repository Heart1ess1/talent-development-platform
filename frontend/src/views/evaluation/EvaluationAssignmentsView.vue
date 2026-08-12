<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {Calendar,EditPen,Plus,Refresh,Search,UserFilled} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {componentLabels,scoreText,type ComponentCode} from '@/evaluation/model'
import '@/styles/evaluation-center.css'

type TaskStatus='UNASSIGNED'|'PENDING'|'IN_PROGRESS'|'OVERDUE'|'COMPLETED'|'CLOSED'
interface Reviewer{reviewerId:number;reviewerName:string;role:string;score:number|null;comment?:string;submittedAt?:string}
interface RatingTask{id:number;employee_id:number;employee_no:string;employee_name:string;batch_name?:string;station_name?:string;scope_name?:string;period_month:string;component_type:ComponentCode;due_at?:string;note?:string;status:TaskStatus;reviewers:Reviewer[];reviewerCount:number;submittedCount:number;averageScore:number|null;finalAverageScore:number|null;locked:boolean}

const router=useRouter(),loading=ref(false),rows=ref<RatingTask[]>([]),selectedRows=ref<RatingTask[]>([])
const month=ref(new Date().toISOString().slice(0,7)),keyword=ref(''),component=ref(''),status=ref('')
const assignOpen=ref(false),saving=ref(false),reviewerOptions=ref<any[]>([])
const assignForm=reactive<{taskIds:number[];component:ComponentCode|'',reviewerIds:number[];mode:'REPLACE'|'ADD';dueAt:string|null;note:string|null}>({taskIds:[],component:'',reviewerIds:[],mode:'REPLACE',dueAt:null,note:null})
const statusLabels:Record<TaskStatus,string>={UNASSIGNED:'未分配',PENDING:'待评分',IN_PROGRESS:'评分中',OVERDUE:'已逾期',COMPLETED:'已完成',CLOSED:'已锁定'}
const statusTypes:Record<TaskStatus,''|'success'|'warning'|'info'|'danger'>={UNASSIGNED:'danger',PENDING:'warning',IN_PROGRESS:'info',OVERDUE:'danger',COMPLETED:'success',CLOSED:''}
const manualComponents=[{value:'MENTOR',label:'导师评价'},{value:'STATION',label:'站点评价'},{value:'TRAINING',label:'培训评价'}]
const metrics=computed(()=>({total:rows.value.length,unassigned:rows.value.filter(x=>x.status==='UNASSIGNED').length,pending:rows.value.filter(x=>['PENDING','IN_PROGRESS','OVERDUE'].includes(x.status)).length,completed:rows.value.filter(x=>['COMPLETED','CLOSED'].includes(x.status)).length}))
const selectedComponent=computed(()=>new Set(selectedRows.value.map(x=>x.component_type)))

async function load(){loading.value=true;try{rows.value=(await api.get<any,Envelope<RatingTask[]>>('/evaluation/assignments',{params:{month:month.value,keyword:keyword.value||undefined,component:component.value||undefined,status:status.value||undefined}})).data}finally{loading.value=false}}
async function generate(){const dueAt=`${month.value}-${String(new Date(Number(month.value.slice(0,4)),Number(month.value.slice(5,7)),0).getDate()).padStart(2,'0')}T23:59:59`;await ElMessageBox.confirm('系统将根据当前已发布方案生成导师、站点和培训人工评分任务；已有任务不会重复创建。','生成月度评分任务',{type:'info'});const count=(await api.post<any,Envelope<number>>('/evaluation/assignments/generate',{month:month.value,dueAt})).data;ElMessage.success(`已新增 ${count} 个评分任务`);await load()}
function onSelectionChange(value:RatingTask[]){selectedRows.value=value}
async function openAssign(row?:RatingTask){const targets=row?[row]:selectedRows.value;if(!targets.length)return ElMessage.warning('请先选择评分任务');const components=new Set(targets.map(x=>x.component_type));if(components.size!==1)return ElMessage.warning('批量分配时请选择同一种评分项');if(targets.some(x=>x.locked))return ElMessage.warning('已发布结果的任务不能调整');const first=targets[0]!;const code=first.component_type;reviewerOptions.value=(await api.get<any,Envelope<any[]>>('/evaluation/assignments/reviewers',{params:{component:code}})).data;assignForm.taskIds=targets.map(x=>x.id);assignForm.component=code;assignForm.reviewerIds=row?row.reviewers.map(x=>x.reviewerId):[];assignForm.mode='REPLACE';const dueValues=new Set(targets.map(x=>x.due_at?String(x.due_at).replace(' ','T').slice(0,19):null));const noteValues=new Set(targets.map(x=>x.note||null));assignForm.dueAt=dueValues.size===1?[...dueValues][0]!:null;assignForm.note=noteValues.size===1?[...noteValues][0]!:null;assignOpen.value=true}
async function saveAssignment(){saving.value=true;try{await api.put('/evaluation/assignments/reviewers',assignForm);ElMessage.success(assignForm.taskIds.length>1?`已更新 ${assignForm.taskIds.length} 个评分任务`:'评分人已更新');assignOpen.value=false;await load()}finally{saving.value=false}}
function detail(row:RatingTask){router.push(`/evaluation/assignments/${row.id}`)}
function reviewerNames(row:RatingTask){return row.reviewers.length?row.reviewers.map(x=>x.reviewerName).join('、'):'尚未分配'}
function dueText(value?:string){return value?String(value).replace('T',' ').slice(0,16):'未设置'}
function componentLabel(value:ComponentCode|string){return componentLabels[value as ComponentCode]||value}
function statusLabel(value:TaskStatus|string){return statusLabels[value as TaskStatus]||value}
function statusType(value:TaskStatus|string){return statusTypes[value as TaskStatus]||'info'}

let searchTimer:number|undefined
watch([month,component,status],load)
watch(keyword,()=>{window.clearTimeout(searchTimer);searchTimer=window.setTimeout(load,250)})
onMounted(load)
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · 评分任务</span><h1>评分任务与评分人</h1><p>先生成当月人工评分任务，再明确每项由谁负责。一个任务可以分配多人，全部评分人提交后自动取平均分。</p></div>
      <div class="evaluation-head-actions"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false"/><el-button :icon="Refresh" @click="load">刷新</el-button><el-button type="primary" :icon="Plus" @click="generate">生成本月任务</el-button></div>
    </header>

    <section class="evaluation-summary-grid">
      <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Calendar/></el-icon></span><div><small>任务总数</small><strong>{{metrics.total}}</strong><span>{{month}} 人工评分项</span></div></article>
      <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>未分配评分人</small><strong>{{metrics.unassigned}}</strong><span>需要管理员优先处理</span></div></article>
      <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>待完成</small><strong>{{metrics.pending}}</strong><span>包含待评分、评分中和逾期</span></div></article>
      <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>已完成 / 锁定</small><strong>{{metrics.completed}}</strong><span>评分人已全部提交</span></div></article>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>任务清单</h2><p>任务粒度为“员工 + 月份 + 人工评分项”；跨站员工会按当月实际站点分别生成站点评价任务。</p></div><el-button :disabled="!selectedRows.length||selectedComponent.size!==1" @click="openAssign()">批量分配评分人</el-button></div>
      <div class="assignment-filters">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索员工、工号、批次或站点"/>
        <el-select v-model="component" clearable placeholder="全部评分项"><el-option v-for="item in manualComponents" :key="item.value" :label="item.label" :value="item.value"/></el-select>
        <el-select v-model="status" clearable placeholder="全部状态"><el-option v-for="(label,key) in statusLabels" :key="key" :label="label" :value="key"/></el-select>
      </div>
      <el-table :data="rows" row-key="id" empty-text="当前月份尚未生成评分任务" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="46" :selectable="(row:RatingTask)=>!row.locked"/>
        <el-table-column label="评价对象" min-width="190"><template #default="s"><div class="assignment-person"><strong>{{s.row.employee_name}} <small>{{s.row.employee_no}}</small></strong><span>{{s.row.batch_name||'未分配批次'}} · {{s.row.station_name||'未分配站点'}}</span></div></template></el-table-column>
        <el-table-column label="评分项" min-width="135"><template #default="s"><strong>{{componentLabel(s.row.component_type)}}</strong><small v-if="s.row.scope_name" class="cell-subtitle">{{s.row.scope_name}}</small></template></el-table-column>
        <el-table-column label="评分人" min-width="220"><template #default="s"><span :class="{'evaluation-warning':!s.row.reviewerCount}">{{reviewerNames(s.row)}}</span><small class="cell-subtitle">已提交 {{s.row.submittedCount}} / {{s.row.reviewerCount}}</small></template></el-table-column>
        <el-table-column label="当前平均" width="105"><template #default="s"><strong>{{scoreText(s.row.averageScore)}}</strong><small v-if="s.row.averageScore!==null&&s.row.finalAverageScore===null" class="cell-subtitle">暂存平均</small></template></el-table-column>
        <el-table-column label="截止时间" width="145"><template #default="s">{{dueText(s.row.due_at)}}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="s"><el-tag :type="statusType(s.row.status)" effect="plain">{{statusLabel(s.row.status)}}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right"><template #default="s"><el-button link type="primary" @click="detail(s.row)">查看</el-button><el-button v-if="!s.row.locked" link @click="openAssign(s.row)">分配</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="assignOpen" :title="assignForm.taskIds.length>1?'批量分配评分人':'分配评分人'" width="min(620px,94vw)" :close-on-click-modal="false">
      <el-alert :title="`${componentLabel(assignForm.component)} · ${assignForm.taskIds.length} 个任务`" description="替换会更新当前有效评分人，但不会删除已经提交的历史评分记录。只有当前有效评分人的评分参与平均。" type="info" :closable="false" show-icon/>
      <el-form label-position="top" style="margin-top:18px">
        <el-form-item label="分配方式"><el-radio-group v-model="assignForm.mode"><el-radio-button value="REPLACE">替换当前评分人</el-radio-button><el-radio-button value="ADD">追加评分人</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="评分人"><el-select v-model="assignForm.reviewerIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="可选择多个评分人" style="width:100%"><el-option v-for="item in reviewerOptions" :key="item.id" :value="item.id" :label="`${item.display_name}（${item.username}）`"/></el-select></el-form-item>
        <el-form-item label="截止时间"><el-date-picker v-model="assignForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="批量时留空则保持原截止时间" style="width:100%"/></el-form-item>
        <el-form-item label="任务说明"><el-input v-model="assignForm.note" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="批量时留空则保持原任务说明"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="assignOpen=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveAssignment">保存分配</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.assignment-filters{display:grid;grid-template-columns:minmax(260px,1fr) 180px 160px;gap:10px;margin-bottom:14px}.assignment-person strong,.assignment-person span,.cell-subtitle{display:block}.assignment-person strong{color:#2d394c}.assignment-person small{color:#8a96a8;font-weight:400}.assignment-person span,.cell-subtitle{margin-top:4px;color:#8995a7;font-size:11px}
@media(max-width:760px){.assignment-filters{grid-template-columns:1fr}.evaluation-workspace{overflow-x:auto}}
</style>
