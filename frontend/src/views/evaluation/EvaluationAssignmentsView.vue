<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {Calendar,EditPen,Plus,Refresh,Setting,UserFilled} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {componentLabels,type ComponentCode} from '@/evaluation/model'
import {loadDictionaryValues,loadEnabledBusinessUnits,type DictionaryOption} from '@/utils/masterData'
import '@/styles/evaluation-center.css'

type ManualComponent='MENTOR'|'STATION'|'TRAINING'
type TargetType='ALL'|'BATCH'|'BUSINESS_UNIT'
interface Summary{component:ManualComponent;taskCount:number;employeeCount:number;unassignedCount:number;pendingCount:number;completedCount:number;coveredTaskCount:number;ruleCount:number;reviewerCount:number}
interface Reviewer{id:number;username:string;display_name:string;role:string}
interface ScopeRule{id:number;period_month:string;component_type:ManualComponent;target_type:TargetType;target_id:number;targetName:string;due_at?:string;note?:string;reviewers:Reviewer[];matchedEmployeeCount:number;matchedTaskCount:number}
interface RatingTask{id:number;employee_no:string;employee_name:string;class_id?:number;class_name?:string;batch_name?:string;business_unit_name?:string;scope_name?:string;component_type:ManualComponent;status:string;reviewerCount:number;submittedCount:number;reviewers:Array<{reviewerName:string}>}

const router=useRouter(),loading=ref(false),month=ref(new Date().toISOString().slice(0,7))
const summaries=ref<Summary[]>([]),batches=ref<any[]>([]),businessUnits=ref<any[]>([])
const drawerOpen=ref(false),drawerLoading=ref(false),saving=ref(false),activeComponent=ref<ManualComponent>('MENTOR')
const rules=ref<ScopeRule[]>([]),reviewerOptions=ref<Reviewer[]>([]),employeeTasks=ref<RatingTask[]>([])
const editingId=ref<number|null>(null),detailKeyword=ref('')
const detailClassId=ref<number|null>(null),classOptions=ref<DictionaryOption[]>([])
const form=reactive<{targetType:TargetType;targetId:number|null;reviewerIds:number[];dueAt:string;note:string}>({targetType:'ALL',targetId:null,reviewerIds:[],dueAt:'',note:''})
const componentMeta:Record<ManualComponent,{title:string;description:string;color:string}>={
  MENTOR:{title:'导师评价任务',description:'按全员、批次或板块统一配置导师评分人',color:'blue'},
  STATION:{title:'站点评价任务',description:'为对应人员范围统一配置站点评分人',color:'violet'},
  TRAINING:{title:'培训评价任务',description:'为培训评价统一配置一名或多名评分人',color:'green'}
}
const targetLabels:Record<TargetType,string>={ALL:'全员默认',BATCH:'指定批次',BUSINESS_UNIT:'指定板块'}
const statusLabels:Record<string,string>={UNASSIGNED:'未分配',PENDING:'待评分',IN_PROGRESS:'评分中',OVERDUE:'已逾期',COMPLETED:'已完成',CLOSED:'已锁定'}
const totals=computed(()=>({tasks:summaries.value.reduce((n,x)=>n+x.taskCount,0),employees:Math.max(0,...summaries.value.map(x=>x.employeeCount)),unassigned:summaries.value.reduce((n,x)=>n+x.unassignedCount,0),rules:summaries.value.reduce((n,x)=>n+x.ruleCount,0)}))
const filteredTasks=computed(()=>{const q=detailKeyword.value.trim().toLowerCase();return employeeTasks.value.filter(x=>(!detailClassId.value||x.class_id===detailClassId.value)&&(!q||[x.employee_name,x.employee_no,x.class_name,x.batch_name,x.business_unit_name,x.scope_name].some(v=>String(v||'').toLowerCase().includes(q))))})

async function loadOverview(){loading.value=true;try{summaries.value=(await api.get<any,Envelope<Summary[]>>('/evaluation/assignments/overview',{params:{month:month.value}})).data}finally{loading.value=false}}
async function loadMasters(){const [batchResult,unitOptions,classValues]=await Promise.all([api.get<any,Envelope<any[]>>('/batches'),loadEnabledBusinessUnits(),loadDictionaryValues('CLASS')]);batches.value=batchResult.data.filter(x=>x.enabled);businessUnits.value=unitOptions;classOptions.value=classValues}
async function generate(){const dueAt=`${month.value}-${String(new Date(Number(month.value.slice(0,4)),Number(month.value.slice(5,7)),0).getDate()).padStart(2,'0')}T23:59:59`;await ElMessageBox.confirm('系统将依据本月已发布评分方案生成三类人工评分任务。已有任务不会重复创建，已配置的范围规则会自动应用。','生成本月评分任务',{type:'info'});const count=(await api.post<any,Envelope<number>>('/evaluation/assignments/generate',{month:month.value,dueAt})).data;ElMessage.success(`已新增 ${count} 个员工评分任务`);await loadOverview()}
async function loadComponent(){drawerLoading.value=true;try{const code=activeComponent.value;const [ruleResult,reviewerResult,taskResult]=await Promise.all([
  api.get<any,Envelope<ScopeRule[]>>('/evaluation/assignments/scope-rules',{params:{month:month.value,component:code}}),
  api.get<any,Envelope<Reviewer[]>>('/evaluation/assignments/reviewers',{params:{component:code}}),
  api.get<any,Envelope<RatingTask[]>>('/evaluation/assignments',{params:{month:month.value,component:code}})
]);rules.value=ruleResult.data;reviewerOptions.value=reviewerResult.data;employeeTasks.value=taskResult.data}finally{drawerLoading.value=false}}
async function openComponent(row:Summary){activeComponent.value=row.component;resetForm();drawerOpen.value=true;await loadComponent()}
function resetForm(){editingId.value=null;form.targetType='ALL';form.targetId=null;form.reviewerIds=[];form.dueAt='';form.note=''}
function editRule(row:ScopeRule){editingId.value=row.id;form.targetType=row.target_type;form.targetId=row.target_type==='ALL'?null:row.target_id;form.reviewerIds=row.reviewers.map(x=>x.id);form.dueAt=row.due_at?String(row.due_at).replace(' ','T').slice(0,19):'';form.note=row.note||''}
async function saveRule(){if(form.targetType!=='ALL'&&!form.targetId)return ElMessage.warning('请选择具体批次或板块');if(!form.reviewerIds.length)return ElMessage.warning('请至少选择一名评分人');saving.value=true;try{await api.put('/evaluation/assignments/scope-rules',{month:month.value,component:activeComponent.value,targetType:form.targetType,targetId:form.targetType==='ALL'?null:form.targetId,reviewerIds:form.reviewerIds,dueAt:form.dueAt||null,note:form.note||null});ElMessage.success(editingId.value?'范围配置已更新':'范围配置已添加');resetForm();await Promise.all([loadComponent(),loadOverview()])}finally{saving.value=false}}
async function removeRule(row:ScopeRule){await ElMessageBox.confirm(`删除“${targetLabels[row.target_type]} · ${row.targetName}”后，系统会重新计算本任务的评分人分配。`,'删除范围配置',{type:'warning'});await api.delete(`/evaluation/assignments/scope-rules/${row.id}`);ElMessage.success('范围配置已删除');if(editingId.value===row.id)resetForm();await Promise.all([loadComponent(),loadOverview()])}
function targetOptions(type:TargetType){return type==='BATCH'?batches.value:type==='BUSINESS_UNIT'?businessUnits.value:[]}
function targetLabel(type:unknown){return targetLabels[type as TargetType]||String(type||'-')}
function targetChanged(){form.targetId=null}
function reviewerNames(row:ScopeRule){return row.reviewers.map(x=>x.display_name).join('、')}
function taskReviewerNames(row:RatingTask){return row.reviewers?.length?row.reviewers.map(x=>x.reviewerName).join('、'):'未分配'}
function timeText(value?:string){return value?String(value).replace('T',' ').slice(0,16):'沿用任务截止时间'}

watch(month,loadOverview)
onMounted(async()=>{await Promise.all([loadOverview(),loadMasters()])})
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · 评分任务编排</span><h1>按任务和人员范围配置评分人</h1><p>先选择评价任务，再按全员、批次或板块统一设置评分人；系统自动展开到范围内的新员工，不再要求逐人分配。</p></div>
      <div class="evaluation-head-actions"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false"/><el-button :icon="Refresh" @click="loadOverview">刷新</el-button><el-button type="primary" :icon="Plus" @click="generate">生成本月任务</el-button></div>
    </header>

    <section class="evaluation-summary-grid">
      <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Calendar/></el-icon></span><div><small>员工任务数</small><strong>{{totals.tasks}}</strong><span>{{month}} 已生成任务</span></div></article>
      <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>覆盖员工</small><strong>{{totals.employees}}</strong><span>按员工去重统计</span></div></article>
      <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>未分配任务</small><strong>{{totals.unassigned}}</strong><span>需要补充范围规则</span></div></article>
      <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><Setting/></el-icon></span><div><small>范围配置</small><strong>{{totals.rules}}</strong><span>全员、批次和板块规则</span></div></article>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>评价任务列表</h2><p>日常只需维护下面三类任务。板块配置优先于批次配置，批次配置优先于全员默认。</p></div></div>
      <div class="task-type-grid">
        <article v-for="row in summaries" :key="row.component" class="task-type-card" :class="componentMeta[row.component].color">
          <div class="task-type-head"><span><el-icon><UserFilled/></el-icon></span><div><h3>{{componentMeta[row.component].title}}</h3><p>{{componentMeta[row.component].description}}</p></div></div>
          <div class="task-type-metrics"><div><small>员工 / 任务</small><strong>{{row.employeeCount}} / {{row.taskCount}}</strong></div><div><small>范围配置</small><strong>{{row.ruleCount}}</strong></div><div><small>评分人数</small><strong>{{row.reviewerCount}}</strong></div><div><small>未分配</small><strong :class="{danger:row.unassignedCount}">{{row.unassignedCount}}</strong></div></div>
          <div class="coverage-line"><span>评分人覆盖</span><strong>{{row.coveredTaskCount}} / {{row.taskCount}}</strong><el-progress :percentage="row.taskCount?Math.round(row.coveredTaskCount*100/row.taskCount):0" :show-text="false"/></div>
          <el-button type="primary" plain :icon="Setting" @click="openComponent(row)">配置评分范围与评分人</el-button>
        </article>
      </div>
      <el-empty v-if="!summaries.length" description="暂无评价任务数据"/>
    </section>

    <el-drawer v-model="drawerOpen" size="min(920px,96vw)" :with-header="false" destroy-on-close>
      <div class="scope-drawer" v-loading="drawerLoading">
        <header class="scope-drawer-head"><div><span>评分任务配置</span><h2>{{componentMeta[activeComponent].title}}</h2><p>{{month}} · 范围越具体优先级越高；同一员工只采用优先级最高的配置。</p></div><el-button @click="drawerOpen=false">关闭</el-button></header>

        <el-alert title="推荐做法" description="人员基本一致时先设置“全员默认”；某个批次或板块需要不同评分人时，再增加对应范围作为覆盖规则。" type="info" :closable="false" show-icon/>

        <section class="scope-editor">
          <div class="section-heading"><div><h3>{{editingId?'编辑范围配置':'新增范围配置'}}</h3><p>一次选择一个人员范围和该范围的全部评分人。</p></div><el-button v-if="editingId" link @click="resetForm">取消编辑</el-button></div>
          <el-form label-position="top">
            <div class="scope-form-grid">
              <el-form-item label="适用范围"><el-select v-model="form.targetType" :disabled="editingId!==null" style="width:100%" @change="targetChanged"><el-option label="全员默认" value="ALL"/><el-option label="指定批次" value="BATCH"/><el-option label="指定板块" value="BUSINESS_UNIT"/></el-select></el-form-item>
              <el-form-item v-if="form.targetType!=='ALL'" :label="form.targetType==='BATCH'?'选择批次':'选择板块'"><el-select v-model="form.targetId" :disabled="editingId!==null" filterable style="width:100%"><el-option v-for="item in targetOptions(form.targetType)" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item>
              <el-form-item :class="{'wide-field':form.targetType==='ALL'}" label="评分人"><el-select v-model="form.reviewerIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="可选择多名评分人" style="width:100%"><el-option v-for="item in reviewerOptions" :key="item.id" :label="`${item.display_name}（${item.username}）`" :value="item.id"/></el-select></el-form-item>
              <el-form-item label="截止时间"><el-date-picker v-model="form.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空则沿用任务截止时间" style="width:100%"/></el-form-item>
              <el-form-item class="wide-field" label="任务说明"><el-input v-model="form.note" maxlength="500" show-word-limit placeholder="可填写本范围的评分重点或注意事项"/></el-form-item>
            </div>
            <el-button type="primary" :loading="saving" @click="saveRule">{{editingId?'保存修改':'添加范围配置'}}</el-button>
          </el-form>
        </section>

        <section class="scope-list">
          <div class="section-heading"><div><h3>当前范围配置</h3><p>板块 ＞ 批次 ＞ 全员；更具体的规则会覆盖默认规则。</p></div><strong>{{rules.length}} 条</strong></div>
          <el-table :data="rules" empty-text="尚未配置评分范围">
            <el-table-column label="人员范围" min-width="170"><template #default="s"><strong>{{targetLabel(s.row.target_type)}}</strong><small class="cell-subtitle">{{s.row.targetName}}</small></template></el-table-column>
            <el-table-column label="评分人" min-width="210"><template #default="s">{{reviewerNames(s.row)}}</template></el-table-column>
            <el-table-column label="覆盖" width="120"><template #default="s">{{s.row.matchedEmployeeCount}} 人 / {{s.row.matchedTaskCount}} 项</template></el-table-column>
            <el-table-column label="截止时间" width="150"><template #default="s">{{timeText(s.row.due_at)}}</template></el-table-column>
            <el-table-column label="操作" width="120" fixed="right"><template #default="s"><el-button link type="primary" @click="editRule(s.row)">编辑</el-button><el-button link type="danger" @click="removeRule(s.row)">删除</el-button></template></el-table-column>
          </el-table>
        </section>

        <el-collapse class="employee-task-detail">
          <el-collapse-item name="tasks"><template #title><strong>查看系统自动展开的员工任务（{{employeeTasks.length}}）</strong></template>
            <div style="display:flex;gap:10px;margin-bottom:12px"><el-input v-model="detailKeyword" clearable placeholder="搜索姓名、工号、班级、批次或板块" style="max-width:360px"/><el-select v-model="detailClassId" clearable filterable placeholder="全部班级" style="width:180px"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select></div>
            <el-table :data="filteredTasks" max-height="360" empty-text="尚未生成员工任务">
              <el-table-column label="员工" min-width="150"><template #default="s"><strong>{{s.row.employee_name}}</strong><small class="cell-subtitle">{{s.row.employee_no}}</small></template></el-table-column>
              <el-table-column prop="class_name" label="班级" min-width="100"/><el-table-column prop="batch_name" label="批次" min-width="110"/><el-table-column prop="business_unit_name" label="板块" min-width="110"/>
              <el-table-column label="评分人" min-width="180"><template #default="s">{{taskReviewerNames(s.row)}}</template></el-table-column>
              <el-table-column label="进度" width="100"><template #default="s">{{s.row.submittedCount}} / {{s.row.reviewerCount}}</template></el-table-column>
              <el-table-column label="状态" width="90"><template #default="s">{{statusLabels[s.row.status]||s.row.status}}</template></el-table-column>
              <el-table-column label="操作" width="70"><template #default="s"><el-button link type="primary" @click="router.push(`/evaluation/assignments/${s.row.id}`)">查看</el-button></template></el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.task-type-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.task-type-card{padding:20px;border:1px solid #e6eaf0;border-radius:14px;background:#fff}.task-type-card.blue{border-top:3px solid #409eff}.task-type-card.violet{border-top:3px solid #8264d7}.task-type-card.green{border-top:3px solid #27a87d}.task-type-head{display:flex;gap:12px}.task-type-head>span{display:grid;flex:0 0 42px;height:42px;place-items:center;border-radius:11px;color:#3177c9;background:#eaf3ff}.task-type-head h3{margin:0;color:#26344a}.task-type-head p{min-height:36px;margin:5px 0 0;color:#8490a2;font-size:12px}.task-type-metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:8px;margin:18px 0}.task-type-metrics>div{padding:10px 12px;border-radius:9px;background:#f7f9fc}.task-type-metrics small,.task-type-metrics strong{display:block}.task-type-metrics small{color:#8b96a7}.task-type-metrics strong{margin-top:4px;color:#344258}.task-type-metrics strong.danger{color:#e25555}.coverage-line{display:grid;grid-template-columns:1fr auto;gap:5px 12px;margin-bottom:16px;color:#7f8b9d;font-size:12px}.coverage-line strong{color:#4a5a70}.coverage-line .el-progress{grid-column:1/-1}.task-type-card>.el-button{width:100%}.scope-drawer{padding:26px}.scope-drawer-head{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:18px}.scope-drawer-head span{color:#3b82d0;font-size:12px;font-weight:700}.scope-drawer-head h2{margin:5px 0;color:#263449}.scope-drawer-head p{margin:0;color:#8591a3}.scope-editor,.scope-list,.employee-task-detail{margin-top:18px;padding:20px;border:1px solid #e7ebf1;border-radius:12px}.section-heading{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:16px}.section-heading h3{margin:0;color:#2c394d}.section-heading p{margin:5px 0 0;color:#8995a6;font-size:12px}.scope-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 14px}.wide-field{grid-column:1/-1}.cell-subtitle{display:block;margin-top:4px;color:#8b96a7;font-size:11px}.employee-task-detail :deep(.el-collapse-item__header){border:0}.employee-task-detail :deep(.el-collapse-item__wrap){border:0}@media(max-width:1050px){.task-type-grid{grid-template-columns:1fr}}@media(max-width:700px){.scope-form-grid{grid-template-columns:1fr}.wide-field{grid-column:auto}.scope-drawer{padding:18px}}
</style>
