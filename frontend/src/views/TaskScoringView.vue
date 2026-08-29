<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {ArrowLeft,Document,Download,Refresh,Search,UserFilled} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import TaskAttachmentsPanel from '@/components/TaskAttachmentsPanel.vue'
import {filterTaskScoringEmployees,taskScoringFilterOptions,type TaskScoringEmployeeFilters} from './taskScoringFilters'

const auth=useAuthStore(),route=useRoute(),router=useRouter()
const canManage=computed(()=>auth.can('task:manage'))
const loading=ref(false),tasks=ref<any[]>([]),selectedTask=ref<any>(),taskDrawer=ref(false),detailLoading=ref(false)
const reviewerOptions=ref<any[]>([]),selectedReviewerIds=ref<number[]>([]),savingReviewers=ref(false)
const filters=reactive({keyword:'',status:String(route.query.status||'')})
const employeeFilters=reactive<TaskScoringEmployeeFilters>({keyword:'',batchId:'',businessUnitId:'',classId:'',scoringStatus:''})
const submissionDialog=ref(false),submissionLoading=ref(false),submission=ref<any>(),scoring=ref(false)
const scoreForm=reactive<any>({decision:'APPROVE',score:null,comment:''})
const previewDialog=ref(false),previewType=ref<'PDF'|'IMAGE'|'TEXT'|'HTML'|'UNSUPPORTED'>('UNSUPPORTED'),previewUrl=ref(''),previewContent=ref(''),previewName=ref('')

const statusOptions=[
  {value:'',label:'全部任务'},
  {value:'MY_PENDING',label:'我的待评分'},
  {value:'UNASSIGNED',label:'未分配评分人'},
  {value:'SCORING',label:'评分中'},
  {value:'COMPLETED',label:'已完成'},
  {value:'NOT_STARTED',label:'未开始'}
]
const filteredTasks=computed(()=>!filters.status?tasks.value:tasks.value.filter(row=>row.scoring_status===filters.status))
const taskEmployees=computed<any[]>(()=>selectedTask.value?.assignments||[])
const filteredTaskEmployees=computed(()=>filterTaskScoringEmployees(taskEmployees.value,employeeFilters))
const employeeBatchOptions=computed(()=>taskScoringFilterOptions(taskEmployees.value,'batch_id','batch_name'))
const employeeBusinessUnitOptions=computed(()=>taskScoringFilterOptions(taskEmployees.value,'business_unit_id','business_unit_name'))
const employeeClassOptions=computed(()=>taskScoringFilterOptions(taskEmployees.value,'class_id','class_name'))
const hasEmployeeFilters=computed(()=>Boolean(employeeFilters.keyword.trim()||employeeFilters.batchId||employeeFilters.businessUnitId||employeeFilters.classId||employeeFilters.scoringStatus))
const metrics=computed(()=>({
  total:tasks.value.length,
  myPending:tasks.value.reduce((sum,row)=>sum+Number(row.my_pending_count||0),0),
  unassigned:tasks.value.filter(row=>row.scoring_status==='UNASSIGNED').length,
  scoring:tasks.value.filter(row=>row.scoring_status==='SCORING'||row.scoring_status==='MY_PENDING').length,
  completed:tasks.value.filter(row=>row.scoring_status==='COMPLETED').length
}))

function formatDate(value:any){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'--'}
function roleLabel(role:string){return ({MENTOR:'导师',STATION_MANAGER:'服务站负责人',TRAINING_ADMIN:'培训管理员',ADMIN:'管理员',SUPER_ADMIN:'超级管理员'} as Record<string,string>)[role]||role}
function statusLabel(status:string){return ({MY_PENDING:'我的待评分',UNASSIGNED:'待分配评分人',SCORING:'评分中',COMPLETED:'已完成',NOT_STARTED:'未开始'} as Record<string,string>)[status]||status}
function statusType(status:string){return status==='COMPLETED'?'success':status==='UNASSIGNED'?'danger':status==='MY_PENDING'?'warning':status==='SCORING'?'primary':'info'}
function assignmentStatus(row:any){
  if(row.status==='PENDING_REVIEW'&&!Number(row.reviewerCount))return '待分配评分人'
  if(row.status==='PENDING_REVIEW')return `待评分 ${row.submittedReviewCount||0}/${row.reviewerCount||0}`
  return ({NOT_SUBMITTED:'未提交',APPROVED:'已通过',RETURNED:'已退回',OVERDUE:'已逾期'} as Record<string,string>)[row.status]||row.status
}

async function load(){loading.value=true;try{tasks.value=(await api.get<any,Envelope<any[]>>('/task-scoring/tasks',{params:{keyword:filters.keyword||undefined}})).data}finally{loading.value=false}}
function resetEmployeeFilters(){Object.assign(employeeFilters,{keyword:'',batchId:'',businessUnitId:'',classId:'',scoringStatus:''})}
async function openTask(row:any){if(selectedTask.value?.id!==row.id)resetEmployeeFilters();taskDrawer.value=true;detailLoading.value=true;try{selectedTask.value=(await api.get<any,Envelope<any>>(`/task-scoring/tasks/${row.id}`)).data;selectedReviewerIds.value=selectedTask.value.reviewers.map((item:any)=>Number(item.id))}finally{detailLoading.value=false}}
async function loadReviewerOptions(){if(canManage.value)reviewerOptions.value=(await api.get<any,Envelope<any[]>>('/task-scoring/reviewer-options')).data}
async function saveReviewers(){
  savingReviewers.value=true
  try{await api.put(`/tasks/${selectedTask.value.id}/reviewers`,{reviewerIds:selectedReviewerIds.value});ElMessage.success('评分人已更新');await Promise.all([openTask({id:selectedTask.value.id}),load()])}
  finally{savingReviewers.value=false}
}
async function openSubmission(row:any){if(!row.submission_id)return;submissionDialog.value=true;submissionLoading.value=true;Object.assign(scoreForm,{decision:'APPROVE',score:null,comment:''});try{submission.value=(await api.get<any,Envelope<any>>(`/task-scoring/submissions/${row.submission_id}`)).data}finally{submissionLoading.value=false}}
async function submitScore(){
  if(scoreForm.decision==='APPROVE'&&(!Number.isInteger(scoreForm.score)||scoreForm.score<0||scoreForm.score>100))return ElMessage.warning('请填写 0 到 100 的整数评分')
  if(scoreForm.decision==='RETURN'&&!scoreForm.comment.trim())return ElMessage.warning('退回时必须填写意见')
  await ElMessageBox.confirm('评分提交后不能修改，确认提交本次评分？','确认评分',{type:'warning'})
  scoring.value=true
  try{await api.post(`/task-scoring/submissions/${submission.value.id}/reviews`,scoreForm);ElMessage.success(scoreForm.decision==='RETURN'?'成果已退回':'评分已提交');submissionDialog.value=false;await Promise.all([openTask({id:selectedTask.value.id}),load()])}
  finally{scoring.value=false}
}
async function resetScore(){
  await ElMessageBox.confirm('将清空该员工本轮所有评分并恢复为待评分，是否继续？','重置本轮评分',{type:'warning'})
  await api.post(`/task-scoring/submissions/${submission.value.id}/reset`)
  ElMessage.success('本轮评分已重置');submissionDialog.value=false;await Promise.all([openTask({id:selectedTask.value.id}),load()])
}
function saveBlob(blob:Blob,name:string){const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();URL.revokeObjectURL(url)}
async function downloadFile(file:any){const blob=await api.get<any,Blob>(`/files/${file.id}`,{responseType:'blob'});saveBlob(blob,file.original_name)}
function clearPreview(){if(previewUrl.value)URL.revokeObjectURL(previewUrl.value);previewUrl.value='';previewContent.value='';previewType.value='UNSUPPORTED'}
async function previewFile(file:any){
  clearPreview();previewName.value=file.original_name;const blob=await api.get<any,Blob>(`/files/${file.id}`,{responseType:'blob'});const ext=String(file.original_name||'').split('.').pop()?.toLowerCase()
  if(ext==='pdf'){previewType.value='PDF';previewUrl.value=URL.createObjectURL(blob)}
  else if(['png','jpg','jpeg','gif','webp'].includes(ext||'')){previewType.value='IMAGE';previewUrl.value=URL.createObjectURL(blob)}
  else if(['txt','csv','md','json'].includes(ext||'')){previewType.value='TEXT';previewContent.value=await blob.text()}
  else if(ext==='docx'){const mammoth=(await import('mammoth')).default;previewType.value='HTML';previewContent.value=(await mammoth.convertToHtml({arrayBuffer:await blob.arrayBuffer()},{externalFileAccess:false})).value}
  else previewType.value='UNSUPPORTED'
  previewDialog.value=true
}
function reviewStatusLabel(row:any){return row.status==='PENDING'?'待评分':row.status==='VOIDED'?'已作废':row.decision==='RETURN'?'已退回':'已评分'}
function reviewStatusType(row:any){return row.status==='PENDING'?'warning':row.status==='VOIDED'?'info':row.decision==='RETURN'?'danger':'success'}

onMounted(async()=>{await Promise.all([load(),loadReviewerOptions()])})
onBeforeUnmount(clearPreview)
</script>

<template>
  <div class="scoring-page">
    <header class="scoring-head">
      <div><span>培养计划 · 任务评分</span><h1>任务评分</h1><p>查看分配给你的任务并独立评分；管理员可以查看全部任务，但只能评分分配给自己的成果。</p></div>
      <div><el-button :icon="ArrowLeft" @click="router.push('/training-plans/tracking')">任务跟踪</el-button><el-button :icon="Refresh" @click="load">刷新</el-button></div>
    </header>

    <section class="metric-grid">
      <article><small>可见任务</small><strong>{{metrics.total}}</strong></article><article class="amber"><small>我的待评分</small><strong>{{metrics.myPending}}</strong></article><article class="red"><small>未分配评分人</small><strong>{{metrics.unassigned}}</strong></article><article class="blue"><small>评分中</small><strong>{{metrics.scoring}}</strong></article><article class="green"><small>已完成</small><strong>{{metrics.completed}}</strong></article>
    </section>

    <section class="scoring-panel">
      <div class="toolbar"><el-input v-model="filters.keyword" :prefix-icon="Search" clearable placeholder="搜索任务或评分人" @keyup.enter="load"/><el-select v-model="filters.status" filterable><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value"/></el-select><el-button type="primary" @click="load">查询</el-button></div>
      <el-table v-loading="loading" :data="filteredTasks" empty-text="暂无评分任务">
        <el-table-column label="任务" min-width="250"><template #default="{row}"><div class="task-cell"><strong>{{row.title}}</strong><small>截止 {{formatDate(row.deadline)}} · {{row.creator_name}}</small></div></template></el-table-column>
        <el-table-column label="评分人" min-width="200"><template #default="{row}">{{row.reviewer_names||'待分配'}}</template></el-table-column>
        <el-table-column label="成果进度" min-width="170"><template #default="{row}">{{row.approved_count}} 已完成 / {{row.submitted_count}} 已提交 / {{row.assignment_count}} 人</template></el-table-column>
        <el-table-column label="状态" width="130"><template #default="{row}"><el-tag :type="statusType(row.scoring_status)">{{statusLabel(row.scoring_status)}}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="{row}"><el-button link type="primary" @click="openTask(row)">{{row.my_pending_count?'开始评分':'查看详情'}}</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="taskDrawer" size="min(1120px,96vw)" class="scoring-drawer">
      <template #header><div class="drawer-head"><div><small>任务评分详情</small><h2>{{selectedTask?.title}}</h2></div><el-tag v-if="selectedTask?.reviewerLocked" type="warning">评分人已锁定</el-tag></div></template>
      <div v-loading="detailLoading" v-if="selectedTask">
        <el-descriptions :column="2" border><el-descriptions-item label="截止时间">{{formatDate(selectedTask.deadline)}}</el-descriptions-item><el-descriptions-item label="成果人数">{{selectedTask.assignments?.length||0}}</el-descriptions-item><el-descriptions-item label="任务说明" :span="2">{{selectedTask.description}}</el-descriptions-item><el-descriptions-item label="成果要求" :span="2">{{selectedTask.requirements||'--'}}</el-descriptions-item></el-descriptions>
        <TaskAttachmentsPanel v-if="selectedTask.attachments?.length" :items="selectedTask.attachments" title="任务评分参考资料" description="员工收到的任务附件快照"/>
        <section class="reviewer-panel"><div><h3>任务评分人</h3><p>评分开始后名单锁定；所有评分人通过后取一位小数平均分。</p></div><div v-if="canManage" class="reviewer-editor"><el-select v-model="selectedReviewerIds" multiple filterable collapse-tags collapse-tags-tooltip :disabled="selectedTask.reviewerLocked" placeholder="可暂不设置"><el-option v-for="item in reviewerOptions" :key="item.id" :label="`${item.display_name}（${roleLabel(item.role)}）`" :value="item.id"/></el-select><el-button type="primary" :loading="savingReviewers" :disabled="selectedTask.reviewerLocked" @click="saveReviewers">保存评分人</el-button></div><div v-else class="reviewer-tags"><el-tag v-for="item in selectedTask.reviewers" :key="item.id">{{item.display_name}}</el-tag><span v-if="!selectedTask.reviewers.length">尚未分配</span></div></section>
        <section class="employee-filters">
          <el-input v-model="employeeFilters.keyword" :prefix-icon="Search" clearable placeholder="搜索员工姓名、工号、批次、板块或班级"/>
          <el-select v-model="employeeFilters.batchId" clearable filterable placeholder="全部批次"><el-option v-for="item in employeeBatchOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
          <el-select v-model="employeeFilters.businessUnitId" clearable filterable placeholder="全部板块"><el-option v-for="item in employeeBusinessUnitOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
          <el-select v-model="employeeFilters.classId" clearable filterable placeholder="全部班级"><el-option v-for="item in employeeClassOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
          <el-select v-model="employeeFilters.scoringStatus" clearable filterable placeholder="全部评分状态"><el-option label="未提交" value="NOT_SUBMITTED"/><el-option label="待分配评分人" value="UNASSIGNED"/><el-option label="待评分" value="PENDING"/><el-option label="评分完成" value="APPROVED"/><el-option label="已退回" value="RETURNED"/><el-option label="已逾期" value="OVERDUE"/></el-select>
          <el-button :icon="Refresh" :disabled="!hasEmployeeFilters" @click="resetEmployeeFilters">重置</el-button>
          <span>显示 {{filteredTaskEmployees.length}} / {{taskEmployees.length}} 人</span>
        </section>
        <div class="employee-table-shell">
        <el-table :data="filteredTaskEmployees" empty-text="未找到符合条件的员工">
          <el-table-column label="员工" min-width="150"><template #default="{row}"><strong>{{row.employee_name}}</strong><br><small>{{row.employee_no}}</small></template></el-table-column>
          <el-table-column label="班级 / 职务" min-width="150"><template #default="{row}"><span>{{row.class_name||'未设置'}}</span><br><small>{{row.class_position_name||'未设置'}}</small></template></el-table-column>
          <el-table-column label="提交版本" width="110"><template #default="{row}">{{row.submission_id?`第 ${row.submission_version} 版`:'未提交'}}</template></el-table-column>
          <el-table-column label="提交时间" min-width="170"><template #default="{row}">{{formatDate(row.submitted_at)}}</template></el-table-column>
          <el-table-column label="评分状态" min-width="150"><template #default="{row}">{{assignmentStatus(row)}}</template></el-table-column>
          <el-table-column label="最终平均分" width="110"><template #default="{row}">{{row.final_score??'--'}}</template></el-table-column>
          <el-table-column label="操作" width="120"><template #default="{row}"><el-button v-if="row.submission_id" link :type="row.canScore?'warning':'primary'" @click="openSubmission(row)">{{row.canScore?'开始评分':'查看成果'}}</el-button></template></el-table-column>
        </el-table>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="submissionDialog" width="min(900px,94vw)" title="员工成果与评分" destroy-on-close>
      <div v-loading="submissionLoading" v-if="submission">
        <div class="submission-title"><span><strong>{{submission.employee_name}}</strong><small>{{submission.employee_no}} · 第 {{submission.submission_version}} 版</small></span><el-tag>{{submission.status}}</el-tag></div>
        <h3>提交说明</h3><p class="submission-content">{{submission.content||'未填写文字说明'}}</p>
        <el-table :data="submission.files" empty-text="未提交附件"><el-table-column prop="original_name" label="附件" min-width="260"/><el-table-column label="大小" width="100"><template #default="{row}">{{row.size?`${Math.ceil(row.size/1024)} KB`:'--'}}</template></el-table-column><el-table-column label="操作" width="140"><template #default="{row}"><el-button link @click="previewFile(row)">预览</el-button><el-button link @click="downloadFile(row)">下载</el-button></template></el-table-column></el-table>
        <h3>评分进度</h3><div class="review-list"><article v-for="item in submission.reviews" :key="item.reviewer_user_id"><span class="reviewer-avatar"><el-icon><UserFilled/></el-icon></span><span><strong>{{item.reviewer_name}}<small v-if="item.mine">（我）</small></strong><p v-if="item.decision">{{item.decision==='APPROVE'?`${item.score} 分`:'退回'}} · {{item.comment||'无意见'}}</p><p v-else>{{item.status==='SUBMITTED'?'已提交，完成后统一公开':'尚未评分'}}</p></span><el-tag :type="reviewStatusType(item)">{{reviewStatusLabel(item)}}</el-tag></article></div>
        <section v-if="submission.canScore" class="score-form"><h3>提交我的评分</h3><el-radio-group v-model="scoreForm.decision"><el-radio value="APPROVE">通过并评分</el-radio><el-radio value="RETURN">退回员工</el-radio></el-radio-group><el-input-number v-if="scoreForm.decision==='APPROVE'" v-model="scoreForm.score" :min="0" :max="100" :precision="0" :controls="false" placeholder="0-100"/><el-input v-model="scoreForm.comment" type="textarea" :rows="3" :placeholder="scoreForm.decision==='RETURN'?'退回意见（必填）':'评分意见（可选）'"/></section>
      </div>
      <template #footer><el-button v-if="canManage&&submission?.reviews?.some((x:any)=>x.status==='SUBMITTED')" type="danger" plain @click="resetScore">重置本轮评分</el-button><el-button @click="submissionDialog=false">关闭</el-button><el-button v-if="submission?.canScore" type="primary" :loading="scoring" @click="submitScore">确认提交评分</el-button></template>
    </el-dialog>

    <el-dialog v-model="previewDialog" :title="`预览：${previewName}`" width="min(920px,94vw)" @closed="clearPreview"><iframe v-if="previewType==='PDF'" :src="previewUrl" class="preview-frame"/><img v-else-if="previewType==='IMAGE'" :src="previewUrl" class="preview-image"/><div v-else-if="previewType==='HTML'" class="preview-html" v-html="previewContent"/><pre v-else-if="previewType==='TEXT'" class="preview-text">{{previewContent}}</pre><el-empty v-else description="该格式暂不支持在线预览，请下载后查看"/></el-dialog>
  </div>
</template>

<style scoped>
.scoring-page{padding:24px 28px 36px;color:#344054}.scoring-head{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.scoring-head>div:last-child{display:flex;gap:10px}.scoring-head span{color:#3979c3;font-size:12px;font-weight:700}.scoring-head h1{margin:5px 0;font-size:27px}.scoring-head p,.reviewer-panel p{margin:0;color:#8490a3;font-size:12px}.metric-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin:20px 0}.metric-grid article{padding:17px 19px;border:1px solid #e5eaf1;border-radius:11px;background:#fff}.metric-grid small,.metric-grid strong{display:block}.metric-grid small{color:#8792a4}.metric-grid strong{margin-top:7px;font-size:25px}.metric-grid .amber strong{color:#d48a1f}.metric-grid .red strong{color:#d45555}.metric-grid .blue strong{color:#3979c3}.metric-grid .green strong{color:#319269}.scoring-panel{overflow:hidden;border:1px solid #e5eaf1;border-radius:12px;background:#fff}.toolbar{display:flex;gap:10px;padding:16px;border-bottom:1px solid #edf0f4}.toolbar .el-input{max-width:360px}.toolbar .el-select{width:180px}.task-cell strong,.task-cell small{display:block}.task-cell small{margin-top:5px;color:#8a96a8}.drawer-head{display:flex;width:100%;align-items:center;justify-content:space-between}.drawer-head small{color:#3979c3}.drawer-head h2{margin:4px 0 0}.reviewer-panel{display:grid;grid-template-columns:minmax(220px,.6fr) minmax(360px,1fr);gap:20px;align-items:end;padding:18px;margin:16px 0;border:1px solid #e6ebf2;border-radius:10px;background:#f8fafc}.reviewer-panel h3{margin:0 0 5px}.reviewer-editor{display:flex;gap:9px}.reviewer-editor .el-select{flex:1}.reviewer-tags{display:flex;gap:8px;flex-wrap:wrap}.submission-title{display:flex;align-items:center;justify-content:space-between}.submission-title strong,.submission-title small{display:block}.submission-title small{margin-top:4px;color:#8a96a8}.submission-content{padding:13px;border-radius:8px;background:#f5f7fa;white-space:pre-wrap}.review-list{display:grid;gap:8px}.review-list article{display:grid;grid-template-columns:38px minmax(0,1fr) auto;gap:10px;align-items:center;padding:11px;border:1px solid #e7ebf1;border-radius:8px}.review-list strong,.review-list p{margin:0}.review-list p{margin-top:4px;color:#7d899a;font-size:12px}.reviewer-avatar{display:grid;width:36px;height:36px;place-items:center;border-radius:9px;color:#3979c3;background:#eaf3ff}.score-form{display:grid;gap:12px;padding:16px;margin-top:16px;border-radius:9px;background:#f7faff}.score-form h3{margin:0}.score-form .el-input-number{width:180px}.preview-frame{width:100%;height:68vh;border:0}.preview-image{display:block;max-width:100%;max-height:68vh;margin:auto}.preview-html,.preview-text{max-height:68vh;padding:20px;overflow:auto;white-space:pre-wrap}.preview-html :deep(img){max-width:100%}@media(max-width:900px){.metric-grid{grid-template-columns:repeat(2,1fr)}.reviewer-panel{grid-template-columns:1fr}}@media(max-width:620px){.scoring-page{padding:16px 12px}.scoring-head{flex-direction:column}.metric-grid{grid-template-columns:1fr}.toolbar{flex-direction:column}.toolbar .el-input,.toolbar .el-select{width:100%;max-width:none}}
.employee-filters{display:grid;grid-template-columns:minmax(230px,1.6fr) repeat(4,minmax(130px,1fr)) auto auto;gap:8px;align-items:center;padding:12px;margin-bottom:10px;border:1px solid #e7ebf1;border-radius:9px;background:#f8fafc}.employee-filters>span{color:#7f8a9a;font-size:12px;white-space:nowrap}.employee-table-shell{overflow:hidden;border:1px solid #e6eaf0;border-radius:9px}.employee-table-shell small{color:#8a96a8}:global(.scoring-drawer .el-drawer__body){overflow-y:auto}@media(max-width:1060px){.employee-filters{grid-template-columns:repeat(3,minmax(150px,1fr))}.employee-filters>span{justify-self:end}}@media(max-width:900px){.employee-filters{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:620px){.employee-filters{grid-template-columns:1fr}.employee-filters>span{justify-self:start}}
</style>
