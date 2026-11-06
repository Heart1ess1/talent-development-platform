<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {scoreText} from '@/evaluation/model'
import {loadEnabledBusinessUnits,loadDictionaryValues} from '@/utils/masterData'
import '@/styles/evaluation-center.css'

const route=useRoute()
const tasks=ref<any[]>([]),batches=ref<any[]>([]),units=ref<any[]>([]),classes=ref<any[]>([]),stations=ref<any[]>([]),candidates=ref<any[]>([]),grades=ref<any[]>([]),selected=ref<any>()
const loading=ref(false),saving=ref(false),editorOpen=ref(false),gradeOpen=ref(false),fileInput=ref<HTMLInputElement>(),importRevision=ref(false),importReason=ref('')
const importDetail=ref<any>(),importBatches=ref<any[]>([]),editImportOpen=ref(false),importRow=reactive<any>({}),historyOpen=ref(false),historyRows=ref<any[]>([])
const filters=reactive<any>({month:String(route.query.month||new Date().toISOString().slice(0,7)),batchId:null})
const peopleFilters=reactive<any>({keyword:'',businessUnitId:null,classId:null,stationId:null})
const selectableCandidates=computed(()=>candidates.value.filter(x=>!x.conflict))
const form=reactive<any>({id:null,name:'',batchId:null,month:filters.month,fullScore:100,defenseDate:null,note:'',employeeIds:[],version:0})
const grade=reactive<any>({employeeId:null,score:null,comment:'',revisionOf:null,revisionReason:'',employeeName:'',draftId:null})
const states:any={DRAFT:'草稿',ACTIVE:'已启用',ENDED:'已结束',CANCELLED:'已取消',PUBLISHED:'已发布',VALID:'待发布',ERROR:'校验错误',SUPERSEDED:'历史版本'}
async function load(){
  loading.value=true
  try{
    tasks.value=(await api.get<any,Envelope<any[]>>('/evaluation/defenses/tasks',{params:{month:filters.month||undefined,batchId:filters.batchId||undefined}})).data
    if(selected.value)await openTask(tasks.value.find(x=>x.id===selected.value.id)||null)
    else if(route.query.taskId)await openTask(tasks.value.find(x=>x.id===Number(route.query.taskId))||null)
  }finally{loading.value=false}
}
async function candidatesLoad(){
  if(!form.batchId||!form.month){candidates.value=[];return}
  candidates.value=(await api.get<any,Envelope<any[]>>('/evaluation/defenses/tasks/employees',{params:{batchId:form.batchId,month:form.month,taskId:form.id||undefined,...Object.fromEntries(Object.entries(peopleFilters).filter(([,v])=>v!=null&&v!==''))}})).data
}
function create(){
  Object.assign(form,{id:null,name:'',batchId:filters.batchId||batches.value[0]?.id,month:filters.month,fullScore:100,defenseDate:null,note:'',employeeIds:[],version:0})
  Object.assign(peopleFilters,{keyword:'',businessUnitId:null,classId:null,stationId:null});editorOpen.value=true;void candidatesLoad()
}
async function editTask(row:any){
  Object.assign(form,{id:row.id,name:row.name,batchId:row.talent_batch_id,month:String(row.period_month).slice(0,7),fullScore:Number(row.full_score),defenseDate:row.defense_date,note:row.note||'',version:row.version})
  form.employeeIds=(await api.get<any,Envelope<any[]>>('/evaluation/defenses/tasks/'+row.id+'/participants')).data.filter(x=>x.active_flag).map(x=>x.id)
  Object.assign(peopleFilters,{keyword:'',businessUnitId:null,classId:null,stationId:null});editorOpen.value=true;await candidatesLoad()
}
function togglePerson(row:any,value:boolean){form.employeeIds=value?[...new Set([...form.employeeIds,row.id])]:form.employeeIds.filter((id:number)=>id!==row.id)}
function selectAll(){form.employeeIds=[...new Set([...form.employeeIds,...selectableCandidates.value.map(x=>x.id)])]}
async function saveTask(){
  if(!form.name.trim()||!form.batchId||!form.month||!form.employeeIds.length)return ElMessage.warning('请填写任务名称、批次、月份并选择参与人员')
  saving.value=true
  try{if(form.id)await api.put('/evaluation/defenses/tasks/'+form.id,{...form,employeeIds:[...form.employeeIds]});else await api.post('/evaluation/defenses/tasks',{...form,employeeIds:[...form.employeeIds]});ElMessage.success('任务草稿已保存');editorOpen.value=false;await load()}finally{saving.value=false}
}
async function action(row:any,action:string){
  const labels:any={activate:'启用',end:'结束',cancel:'取消',delete:'删除'}
  await ElMessageBox.confirm('确认'+labels[action]+'“'+row.name+'”？','答辩任务')
  if(action==='delete')await api.delete('/evaluation/defenses/tasks/'+row.id,{params:{version:row.version}})
  else await api.post('/evaluation/defenses/tasks/'+row.id+'/'+action,{version:row.version})
  ElMessage.success('操作完成');await load()
}
async function openTask(row:any){
  selected.value=row;importDetail.value=undefined
  if(!row){grades.value=[];importBatches.value=[];return}
  const [g,b]=await Promise.all([api.get<any,Envelope<any[]>>('/evaluation/defenses/tasks/'+row.id+'/grades'),api.get<any,Envelope<any[]>>('/evaluation/defenses/tasks/'+row.id+'/imports')])
  grades.value=g.data;importBatches.value=b.data
}
function editGrade(row:any){
  Object.assign(grade,{employeeId:row.employee_id,employeeName:row.employee_name,score:row.score??null,comment:row.comment||'',revisionOf:row.status==='PUBLISHED'?row.id:row.revision_of||null,revisionReason:row.revision_reason||'',draftId:row.id,imported:row.import_batch_id!=null})
  gradeOpen.value=true
}
async function saveGrade(publish:boolean){
  if(grade.score==null)return ElMessage.warning('请填写答辩成绩')
  if(grade.revisionOf&&!grade.revisionReason.trim())return ElMessage.warning('请填写修订原因')
  saving.value=true
  try{
    const result=await api.post<any,Envelope<number>>('/evaluation/defenses/tasks/'+selected.value.id+'/grades',grade)
    if(publish)await api.post('/evaluation/defenses/tasks/grades/'+result.data+'/publish')
    ElMessage.success(publish?'答辩成绩已发布':'成绩草稿已保存');gradeOpen.value=false;await load()
  }finally{saving.value=false}
}
async function publishDraft(row:any){await ElMessageBox.confirm('确认校验并发布本人成绩？','发布成绩');await api.post('/evaluation/defenses/tasks/grades/'+row.id+'/publish');ElMessage.success('已发布');await load()}
async function upload(event:Event){
  const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file||!selected.value)return
  if(importRevision.value&&!importReason.value.trim()){input.value='';return ElMessage.warning('请填写导入修订原因')}
  const body=new FormData();body.append('file',file);saving.value=true
  try{
    importDetail.value=(await api.post<any,Envelope<any>>('/evaluation/defenses/tasks/'+selected.value.id+'/imports',body,{params:{revision:importRevision.value,revisionReason:importReason.value||undefined}})).data
    importBatches.value=(await api.get<any,Envelope<any[]>>('/evaluation/defenses/tasks/'+selected.value.id+'/imports')).data
    ElMessage.success('已解析，请核对下方导入预览')
  }finally{input.value='';saving.value=false}
}
async function openImport(id:number){importDetail.value=(await api.get<any,Envelope<any>>('/evaluation/defenses/tasks/'+selected.value.id+'/imports/'+id)).data}
function editRow(row:any){Object.assign(importRow,{...row});editImportOpen.value=true}
async function saveRow(){importDetail.value=(await api.put<any,Envelope<any>>('/evaluation/defenses/tasks/'+selected.value.id+'/imports/'+importDetail.value.id+'/rows/'+importRow.id,importRow)).data;editImportOpen.value=false}
async function validateImport(){importDetail.value=(await api.post<any,Envelope<any>>('/evaluation/defenses/tasks/'+selected.value.id+'/imports/'+importDetail.value.id+'/validate')).data}
async function publishImport(){
  await ElMessageBox.confirm('确认当前任务整批成绩无误并发布？','发布导入');saving.value=true
  try{await api.post('/evaluation/defenses/tasks/'+selected.value.id+'/imports/'+importDetail.value.id+'/publish');ElMessage.success('整批成绩已发布');await load()}finally{saving.value=false}
}
async function history(row:any){historyRows.value=(await api.get<any,Envelope<any[]>>('/evaluation/defenses/tasks/'+selected.value.id+'/history',{params:{employeeId:row.employee_id}})).data;historyOpen.value=true}
async function download(path:string,name:string){const blob=await api.get<any,Blob>(path,{responseType:'blob'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();URL.revokeObjectURL(url)}
onMounted(async()=>{
  const [b,u,c,s]=await Promise.all([api.get<any,Envelope<any[]>>('/batches'),loadEnabledBusinessUnits(),loadDictionaryValues('CLASS'),api.get<any,Envelope<any[]>>('/stations')])
  batches.value=b.data;units.value=u;classes.value=c;stations.value=s.data;await load()
})
</script>
<template>
<div class="evaluation-module-page" v-loading="loading">
  <header class="evaluation-page-head"><div><span class="eyebrow">综合评价 · 月度答辩</span><h1>答辩任务</h1><p>按月创建任务，由管理员录入或导入最终成绩；发布后进入对应月度评价。</p></div><div class="evaluation-head-actions"><el-date-picker v-model="filters.month" type="month" value-format="YYYY-MM" :clearable="false" @change="load"/><el-button type="primary" @click="create">新建答辩任务</el-button></div></header>
  <section class="evaluation-workspace">
    <div class="defense-filters"><el-select v-model="filters.batchId" clearable filterable placeholder="全部培养批次" @change="load"><el-option v-for="b in batches" :key="b.id" :label="b.name" :value="b.id"/></el-select><el-button @click="load">刷新</el-button></div>
    <el-table :data="tasks" row-key="id" highlight-current-row @current-change="openTask" empty-text="当前月份暂无答辩任务">
      <el-table-column prop="name" label="任务名称" min-width="160"/><el-table-column prop="batch_name" label="培养批次" min-width="130"/><el-table-column label="计分月份" width="110"><template #default="s">{{String(s.row.period_month).slice(0,7)}}</template></el-table-column><el-table-column prop="participant_count" label="参与人数" width="85"/><el-table-column prop="not_entered_count" label="未录入" width="80"/><el-table-column prop="draft_count" label="待发布" width="80"/><el-table-column prop="published_count" label="已发布" width="80"/><el-table-column label="状态" width="90"><template #default="s">{{states[s.row.status]}}</template></el-table-column>
      <el-table-column label="操作" width="300"><template #default="s"><el-button link type="primary" @click.stop="openTask(s.row)">成绩管理</el-button><el-button v-if="s.row.status==='DRAFT'" link @click.stop="editTask(s.row)">编辑</el-button><el-button v-if="s.row.status==='DRAFT'" link type="primary" @click.stop="action(s.row,'activate')">启用</el-button><el-button v-if="s.row.status==='DRAFT'" link type="danger" @click.stop="action(s.row,'delete')">删除</el-button><el-button v-if="s.row.status==='ACTIVE'" link @click.stop="action(s.row,'end')">结束</el-button><el-button v-if="['DRAFT','ACTIVE'].includes(s.row.status)" link type="danger" @click.stop="action(s.row,'cancel')">取消</el-button></template></el-table-column>
    </el-table>
  </section>
  <section v-if="selected" class="evaluation-workspace">
    <div class="evaluation-workspace-head"><div><h2>{{selected.name}} · {{String(selected.period_month).slice(0,7)}}</h2><p>满分 {{scoreText(selected.full_score)}} · {{selected.note||'无任务说明'}}</p></div><div class="evaluation-head-actions"><el-button @click="download('/evaluation/defenses/tasks/template','答辩成绩导入模板.xlsx')">下载导入模板</el-button><el-button @click="download('/evaluation/defenses/tasks/'+selected.id+'/export','答辩任务成绩.xlsx')">导出成绩</el-button></div></div>
    <p class="evaluation-muted">启用任务后可导入本任务人员的最终成绩，核对后整批发布；导出包含人员、计分月份、满分和成绩状态。</p>
    <div v-if="['ACTIVE','ENDED'].includes(selected.status)" class="defense-filters"><el-checkbox v-model="importRevision">修订已有成绩</el-checkbox><el-input v-if="importRevision" v-model="importReason" placeholder="导入修订原因" maxlength="1000"/><el-button :loading="saving" @click="fileInput?.click()">导入 Excel</el-button><input ref="fileInput" type="file" accept=".xlsx,.xls" hidden @change="upload"/></div>
    <el-table :data="grades"><el-table-column prop="employee_no" label="工号"/><el-table-column prop="employee_name" label="姓名"/><el-table-column label="成绩 / 满分"><template #default="s">{{scoreText(s.row.score)}} / {{scoreText(selected.full_score)}}</template></el-table-column><el-table-column label="状态"><template #default="s">{{states[s.row.status]||'未录入'}}</template></el-table-column><el-table-column prop="comment" label="评价意见" min-width="160"/><el-table-column label="操作" width="230"><template #default="s"><el-button v-if="['ACTIVE','ENDED'].includes(selected.status)" link type="primary" @click="editGrade(s.row)">{{s.row.status==='PUBLISHED'?'修订':'录入成绩'}}</el-button><el-button v-if="['DRAFT','VALID'].includes(s.row.status)&&!s.row.import_batch_id&&['ACTIVE','ENDED'].includes(selected.status)" link @click="publishDraft(s.row)">发布</el-button><el-button v-if="s.row.id" link @click="history(s.row)">版本历史</el-button></template></el-table-column></el-table>
    <div v-if="importBatches.length" class="defense-filters import-history"><span>导入批次</span><el-button v-for="b in importBatches" :key="b.id" @click="openImport(b.id)">#{{b.id}} {{b.original_filename}} · {{states[b.status]}}</el-button></div>
    <template v-if="importDetail"><h3>导入预览 #{{importDetail.id}}</h3><el-table :data="importDetail.rows" max-height="400"><el-table-column prop="row_no" label="行" width="60"/><el-table-column prop="employee_no" label="工号"/><el-table-column prop="employee_name" label="姓名"/><el-table-column prop="raw_score" label="原始成绩"/><el-table-column label="状态"><template #default="s">{{states[s.row.status]}}</template></el-table-column><el-table-column prop="error_message" label="错误" min-width="220"/><el-table-column label="操作"><template #default="s"><el-button v-if="importDetail.status==='DRAFT'&&['ACTIVE','ENDED'].includes(selected.status)" link @click="editRow(s.row)">逐行修改</el-button></template></el-table-column></el-table><div class="defense-filters"><el-button v-if="importDetail.status==='DRAFT'" @click="validateImport">重新校验</el-button><el-button v-if="importDetail.status==='DRAFT'" type="primary" :loading="saving" :disabled="!['ACTIVE','ENDED'].includes(selected.status)||importDetail.rows.some((x:any)=>x.status!=='VALID')" @click="publishImport">整批发布</el-button></div></template>
  </section>
  <el-dialog v-model="editorOpen" :title="form.id?'编辑答辩任务':'新建答辩任务'" width="min(960px,95vw)" :close-on-click-modal="false">
    <el-form label-position="top"><div class="defense-filters"><el-form-item label="任务名称" required><el-input v-model="form.name" maxlength="128"/></el-form-item><el-form-item label="培养批次" required><el-select v-model="form.batchId" filterable @change="form.employeeIds=[];candidatesLoad()"><el-option v-for="b in batches" :key="b.id" :label="b.name" :value="b.id"/></el-select></el-form-item><el-form-item label="计分月份" required><el-date-picker v-model="form.month" type="month" value-format="YYYY-MM" :clearable="false" @change="form.employeeIds=[];candidatesLoad()"/></el-form-item><el-form-item label="答辩满分" required><el-input-number v-model="form.fullScore" :min="0.01" :max="999.99" :precision="2"/></el-form-item><el-form-item label="答辩日期"><el-date-picker v-model="form.defenseDate" type="date" value-format="YYYY-MM-DD"/></el-form-item></div><el-form-item label="任务说明"><el-input v-model="form.note" type="textarea" maxlength="1000"/></el-form-item>
      <div class="defense-filters people-filters"><el-select v-model="form.batchId" aria-label="人员培养批次" filterable placeholder="培养批次" @change="form.employeeIds=[];candidatesLoad()"><el-option v-for="b in batches" :key="b.id" :label="b.name" :value="b.id"/></el-select><el-select v-model="peopleFilters.businessUnitId" filterable clearable placeholder="全部板块" @change="candidatesLoad"><el-option v-for="u in units" :key="u.id" :label="u.name" :value="u.id"/></el-select><el-select v-model="peopleFilters.classId" filterable clearable placeholder="全部班级" @change="candidatesLoad"><el-option v-for="c in classes" :key="c.id" :label="c.label" :value="c.id"/></el-select><el-select v-model="peopleFilters.stationId" filterable clearable placeholder="全部站点" @change="candidatesLoad"><el-option v-for="s in stations" :key="s.id" :label="s.name" :value="s.id"/></el-select><el-input v-model="peopleFilters.keyword" clearable placeholder="姓名或工号" @keyup.enter="candidatesLoad" @clear="candidatesLoad"/><el-button @click="candidatesLoad">查询人员</el-button></div>
      <div class="defense-filters"><span>共 {{candidates.length}} 人，可选 {{selectableCandidates.length}} 人；已选 {{form.employeeIds.length}} 人（同批次内切换筛选保留）</span><el-button :disabled="!selectableCandidates.length" @click="selectAll">全选</el-button><el-button @click="form.employeeIds=[]">清空已选</el-button></div>
      <el-table :data="candidates" row-key="id" max-height="300"><el-table-column label="选择" width="70"><template #default="s"><el-checkbox :model-value="form.employeeIds.includes(s.row.id)" :disabled="!!s.row.conflict" @change="(v:any)=>togglePerson(s.row,!!v)"/></template></el-table-column><el-table-column prop="employee_no" label="工号"/><el-table-column prop="employee_name" label="姓名"/><el-table-column prop="business_unit_name" label="板块"/><el-table-column prop="class_name" label="班级"/><el-table-column prop="station_name" label="站点"/><el-table-column prop="conflict_task_name" label="同月冲突任务"/></el-table>
      <el-alert title="切换培养批次或计分月份会清空已选人员；启用后计分月份、满分和人员锁定，后续新增员工不会自动加入。" type="info" :closable="false"/>
    </el-form><template #footer><el-button @click="editorOpen=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveTask">保存任务草稿</el-button></template>
  </el-dialog>
  <el-dialog v-model="gradeOpen" title="录入答辩最终成绩" width="min(520px,95vw)" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="员工">{{grade.employeeName}}</el-form-item><el-form-item :label="'答辩成绩（满分 '+scoreText(selected?.full_score)+'）'"><el-input-number v-model="grade.score" :min="0" :max="Number(selected?.full_score||0)" :precision="2" :step="0.01"/></el-form-item><el-form-item label="评价意见"><el-input v-model="grade.comment" type="textarea" maxlength="1000"/></el-form-item><el-form-item v-if="grade.revisionOf" label="成绩修订原因" required><el-input v-model="grade.revisionReason" type="textarea" maxlength="1000"/></el-form-item><el-alert v-if="grade.revisionOf" title="原正式月评在关联修订审核通过前保持有效。" :closable="false"/></el-form><template #footer><el-button @click="gradeOpen=false">取消</el-button><el-button :loading="saving" @click="saveGrade(false)">保存草稿</el-button><el-button type="primary" :loading="saving" @click="saveGrade(true)">校验并发布</el-button></template></el-dialog>
  <el-dialog v-model="editImportOpen" title="修改导入行" width="min(520px,95vw)"><el-form label-position="top"><el-form-item label="工号"><el-input v-model="importRow.employee_no"/></el-form-item><el-form-item label="姓名"><el-input v-model="importRow.employee_name"/></el-form-item><el-form-item label="原始成绩（空值与0分不同）"><el-input v-model="importRow.raw_score"/></el-form-item><el-form-item label="评价意见"><el-input v-model="importRow.comment"/></el-form-item></el-form><template #footer><el-button @click="editImportOpen=false">取消</el-button><el-button type="primary" @click="saveRow">保存并重新校验</el-button></template></el-dialog>
  <el-dialog v-model="historyOpen" title="答辩成绩版本历史" width="min(900px,95vw)"><el-table :data="historyRows"><el-table-column prop="version" label="版本"/><el-table-column prop="score" label="成绩"/><el-table-column label="状态"><template #default="s">{{states[s.row.status]}}</template></el-table-column><el-table-column prop="revision_reason" label="修订原因"/><el-table-column prop="creator_name" label="录入人"/><el-table-column prop="published_at" label="发布时间" min-width="170"/></el-table></el-dialog>
</div>
</template>
<style scoped>.defense-filters{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin-bottom:16px}.defense-filters>*{min-width:180px}.evaluation-filters{display:flex;flex-wrap:wrap;gap:12px}.evaluation-filters>.el-form-item{min-width:180px}</style>
