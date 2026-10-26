<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Search,Refresh,ArrowDown,ArrowUp} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import type {AxiosRequestConfig} from 'axios'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {formatCourseDate,type CourseSession} from '@/utils/course'
import {loadDictionaryValues,loadEnabledBusinessUnits} from '@/utils/masterData'
import {
  assignmentChangesApplied,candidateQuery,emptyAssignmentFilters,matchesAssignmentFilters,
  mergeAdditions,rebaseAssignmentDraft,type AssignmentFilters,type AssignmentPerson,
  type AssignmentResult,type AssignmentState,type CandidatePage
} from './courseAssignments'

const props=defineProps<{session:CourseSession}>()
const emit=defineEmits<{close:[];saved:[]}>()
const state=ref<AssignmentState>({records:[],baselineHash:'',enrolledCount:0,attendanceCount:0})
const additions=ref<AssignmentPerson[]>([])
const removals=ref<number[]>([])
const candidates=ref<AssignmentPerson[]>([])
const total=ref(0)
const page=ref(1)
const size=ref(20)
const loading=ref(false)
const stateLoading=ref(false)
const resolving=ref(false)
const saving=ref(false)
const uncertain=ref(false)
const error=ref('')
const candidateError=ref('')
const stateError=ref('')
const masterError=ref('')
const notice=ref('')
const advanced=ref(false)
const mobileTab=ref('candidates')
const filterDraft=reactive(emptyAssignmentFilters())
const appliedFilters=ref(emptyAssignmentFilters())
const rosterKeyword=ref('')
const rosterMode=ref('ALL')
const attendanceMode=ref('ALL')
const rosterPage=ref(1)
const rosterSize=ref(20)
const bulkIds=ref<number[]>([])
let sequence=0
let alive=true
type FilterKey='batchIds'|'classIds'|'classPositionIds'|'businessUnitIds'|'stationIds'|'mentorIds'|'skillMentorIds'|'educations'
type Option={id:number|string;label:string}
const options=reactive<Record<FilterKey,Option[]>>({batchIds:[],classIds:[],classPositionIds:[],businessUnitIds:[],stationIds:[],mentorIds:[],skillMentorIds:[],educations:[]})
const commonFields:{key:FilterKey;label:string}[]=[{key:'batchIds',label:'届别／批次'},{key:'classIds',label:'班级'},{key:'classPositionIds',label:'班级职务'},{key:'businessUnitIds',label:'所属板块'},{key:'stationIds',label:'服务站'}]
const extraFields:{key:FilterKey;label:string}[]=[{key:'mentorIds',label:'技术导师'},{key:'skillMentorIds',label:'技能导师'},{key:'educations',label:'学历'}]
const addIds=computed(()=>new Set(additions.value.map(row=>row.employee_id)))
const removeIds=computed(()=>new Set(removals.value))
const dirty=computed(()=>additions.value.length>0||removals.value.length>0)
const locked=computed(()=>saving.value||resolving.value||uncertain.value||!state.value.baselineHash)
const selectedInResults=computed(()=>additions.value.filter(row=>matchesAssignmentFilters(row,appliedFilters.value)).length)
const allPageSelected=computed(()=>candidates.value.length>0&&candidates.value.every(row=>addIds.value.has(row.employee_id)))
const somePageSelected=computed(()=>candidates.value.some(row=>addIds.value.has(row.employee_id))&&!allPageSelected.value)
const finalCount=computed(()=>state.value.enrolledCount+additions.value.length-removals.value.length)
const roster=computed(()=>[
  ...state.value.records.map(row=>({...row,change:removeIds.value.has(row.employee_id)?'REMOVE' as const:'EXISTING' as const})),
  ...additions.value.map(row=>({...row,change:'ADD' as const}))
])
type RosterRow=(typeof roster.value)[number]
const filteredRoster=computed(()=>{
  const term=rosterKeyword.value.trim().toLowerCase()
  return roster.value.filter(row=>(!term||`${row.employee_name} ${row.employee_no}`.toLowerCase().includes(term))
    &&(rosterMode.value==='ALL'||(rosterMode.value==='CHANGED'?row.change!=='EXISTING':row.change===rosterMode.value))
    &&(attendanceMode.value==='ALL'||(attendanceMode.value==='CHECKED'?Boolean(row.attendance_status):!row.attendance_status)))
})
const rosterPages=computed(()=>Math.max(1,Math.ceil(filteredRoster.value.length/rosterSize.value)))
const effectiveRosterPage=computed(()=>Math.min(rosterPage.value,rosterPages.value))
const rosterRows=computed(()=>filteredRoster.value.slice((effectiveRosterPage.value-1)*rosterSize.value,effectiveRosterPage.value*rosterSize.value))
const removablePage=computed(()=>rosterRows.value.filter(row=>!row.attendance_status&&row.change!=='REMOVE'))
const allRosterSelected=computed(()=>removablePage.value.length>0&&removablePage.value.every(row=>bulkIds.value.includes(row.employee_id)))
const someRosterSelected=computed(()=>removablePage.value.some(row=>bulkIds.value.includes(row.employee_id))&&!allRosterSelected.value)
const blockedRemoval=computed(()=>state.value.records.some(row=>removeIds.value.has(row.employee_id)&&row.attendance_status))
const show=(value:string|null|undefined)=>value||'—'
watch(roster,rows=>{const allowed=new Set(rows.filter(row=>!row.attendance_status&&row.change!=='REMOVE').map(row=>row.employee_id));bulkIds.value=bulkIds.value.filter(id=>allowed.has(id))})
const base=()=>`/sessions/${props.session.id}`
const message=(err:unknown,fallback:string)=>{
  const response=err as {response?:{data?:{message?:string}};message?:string}
  return response.response?.data?.message||fallback
}
const silent:AxiosRequestConfig&{silentError:boolean}={silentError:true}

async function loadCandidates(){
  const request=++sequence
  loading.value=true;candidateError.value=''
  try{
    const response=await api.get<unknown,Envelope<CandidatePage>>(`${base()}/enrollment-candidates?${candidateQuery(appliedFilters.value,page.value,size.value)}`,silent)
    if(!alive||request!==sequence)return
    candidates.value=response.data.records;total.value=response.data.total
    if(page.value>1&&!candidates.value.length&&total.value>0){page.value=Math.ceil(total.value/size.value);await loadCandidates()}
  }catch(err){if(alive&&request===sequence){candidateError.value=message(err,'候选人员加载失败，请重试');candidates.value=[];total.value=0}}
  finally{if(alive&&request===sequence)loading.value=false}
}

async function loadState(preserve=false){
  stateLoading.value=true;stateError.value=''
  try{
    const response=await api.get<unknown,Envelope<AssignmentState>>(`${base()}/assignment-state`,silent)
    if(!alive)return
    if(preserve){
      const rebased=rebaseAssignmentDraft(response.data,additions.value,removals.value)
      additions.value=rebased.additions;removals.value=rebased.removals
    }
    state.value=response.data;bulkIds.value=[]
  }catch(err){if(alive)stateError.value=message(err,'本场人员名单加载失败，请重试')}
  finally{if(alive)stateLoading.value=false}
}

async function loadMasters(){
  masterError.value=''
  type Master={id:number;name?:string;display_name?:string;displayName?:string;enabled?:boolean|number}
  const name=(row:Master)=>row.name||row.display_name||row.displayName||String(row.id)
  try{
    const [batches,units,stations,mentors,classes,positions,education]=await Promise.all([
      api.get<unknown,Envelope<Master[]>>('/batches',silent),loadEnabledBusinessUnits(),
      api.get<unknown,Envelope<Master[]>>('/stations',silent),api.get<unknown,Envelope<Master[]>>('/mentors',silent),
      loadDictionaryValues('CLASS'),loadDictionaryValues('CLASS_POSITION'),loadDictionaryValues('EDUCATION')
    ])
    if(!alive)return
    options.batchIds=batches.data.map(row=>({id:row.id,label:name(row)}))
    options.businessUnitIds=units.map(row=>({id:row.id,label:row.name}))
    options.stationIds=stations.data.filter(row=>row.enabled).map(row=>({id:row.id,label:name(row)}))
    options.mentorIds=mentors.data.map(row=>({id:row.id,label:name(row)}));options.skillMentorIds=options.mentorIds
    options.classIds=classes.map(row=>({id:row.id,label:row.label}));options.classPositionIds=positions.map(row=>({id:row.id,label:row.label}))
    options.educations=education.map(row=>({id:row.value,label:row.label}))
  }catch{if(alive)masterError.value='部分筛选选项加载失败，可重试；姓名／工号查询仍可使用。'}
}

function query(){appliedFilters.value=JSON.parse(JSON.stringify(filterDraft)) as AssignmentFilters;page.value=1;void loadCandidates()}
function reset(){Object.assign(filterDraft,emptyAssignmentFilters());query()}
function toggle(row:AssignmentPerson){
  if(locked.value||loading.value)return
  additions.value=addIds.value.has(row.employee_id)?additions.value.filter(item=>item.employee_id!==row.employee_id):mergeAdditions(additions.value,[row])
}
function selectPage(){
  if(locked.value)return
  if(allPageSelected.value){const ids=new Set(candidates.value.map(row=>row.employee_id));additions.value=additions.value.filter(row=>!ids.has(row.employee_id))}
  else additions.value=mergeAdditions(additions.value,candidates.value)
}
function cellClick(row:AssignmentPerson,column:{type:string;property:string},_cell:HTMLElement,event:Event){
  if(column.type==='expand'||column.property==='_select'||(event.target as HTMLElement).closest('button,a,input,.el-checkbox'))return
  toggle(row)
}
async function selectAll(){
  if(locked.value||loading.value)return
  resolving.value=true;error.value=''
  try{
    const response=await api.post<unknown,Envelope<AssignmentPerson[]>>(`${base()}/enrollment-candidates/resolve`,appliedFilters.value,silent)
    if(!alive)return
    additions.value=mergeAdditions(additions.value,response.data)
    ElMessage.success(`已选择本次筛选命中的 ${response.data.length} 人，可逐人取消`)
  }catch(err){if(alive)error.value=message(err,'全部筛选结果选择失败，请重试')}
  finally{if(alive)resolving.value=false}
}
function toggleBulk(row:RosterRow){
  if(locked.value||row.attendance_status||row.change==='REMOVE')return
  bulkIds.value=bulkIds.value.includes(row.employee_id)?bulkIds.value.filter(id=>id!==row.employee_id):[...bulkIds.value,row.employee_id]
}
function selectRosterPage(){
  if(allRosterSelected.value){const ids=new Set(removablePage.value.map(row=>row.employee_id));bulkIds.value=bulkIds.value.filter(id=>!ids.has(id))}
  else bulkIds.value=[...new Set([...bulkIds.value,...removablePage.value.map(row=>row.employee_id)])]
}
function removeRow(row:RosterRow){
  if(locked.value)return
  if(row.change==='ADD')additions.value=additions.value.filter(item=>item.employee_id!==row.employee_id)
  else if(row.change==='REMOVE')removals.value=removals.value.filter(id=>id!==row.employee_id)
  else if(!row.attendance_status)removals.value=[...new Set([...removals.value,row.employee_id])]
  bulkIds.value=bulkIds.value.filter(id=>id!==row.employee_id)
}
function removeBulk(){
  for(const row of roster.value.filter(row=>bulkIds.value.includes(row.employee_id)&&!row.attendance_status&&row.change!=='REMOVE'))removeRow(row)
  bulkIds.value=[]
}
async function close(){
  if(saving.value||resolving.value)return
  if(dirty.value||uncertain.value){
    try{await ElMessageBox.confirm(uncertain.value?'保存结果尚未核对，关闭后请重新打开检查实际名单。':'本次人员调整尚未保存，是否放弃变更？','关闭人员安排',{
      confirmButtonText:'放弃变更并关闭',cancelButtonText:'继续编辑',type:'warning',closeOnClickModal:false
    })}catch{return}
  }
  emit('close')
}
function accept(stateValue:AssignmentState){
  state.value=stateValue;additions.value=[];removals.value=[];bulkIds.value=[];error.value='';notice.value='';uncertain.value=false
  emit('saved');void loadCandidates()
}
async function verifySave(add:number[],remove:number[]){
  try{
    const response=await api.get<unknown,Envelope<AssignmentState>>(`${base()}/assignment-state`,silent)
    if(!alive)return
    uncertain.value=false
    if(assignmentChangesApplied(response.data,add,remove)){
      accept(response.data);ElMessage.success('已核对，人员安排已保存')
    }else{
      const rebased=rebaseAssignmentDraft(response.data,additions.value,removals.value)
      state.value=response.data;additions.value=rebased.additions;removals.value=rebased.removals;bulkIds.value=[]
      notice.value='已读取最新名单，未完成的调整已保留，请核对后再次保存。';error.value='';void loadCandidates()
    }
  }catch{if(alive){uncertain.value=true;error.value='暂时无法核对保存结果。请点击“核对保存结果”，核对完成后才能继续操作。'}}
}
async function save(){
  if(locked.value||!dirty.value||blockedRemoval.value)return
  saving.value=true;error.value='';notice.value=''
  const add=additions.value.map(row=>row.employee_id),remove=[...removals.value]
  try{
    const response=await api.post<unknown,Envelope<AssignmentResult>>(`${base()}/enrollment-changes`,{addIds:add,removeIds:remove,baselineHash:state.value.baselineHash},silent)
    if(!alive)return
    accept(response.data.state);ElMessage.success(`安排已保存：新增 ${response.data.added} 人，移除 ${response.data.removed} 人`)
  }catch(err){
    if(!alive)return
    const status=(err as {response?:{status:number}}).response?.status
    if(!status){uncertain.value=true;await verifySave(add,remove)}
    else if(status===409){
      notice.value=message(err,'名单已变化，请重新核对');await loadState(true);void loadCandidates()
    }else error.value=message(err,'保存失败，本次调整已保留')
  }finally{if(alive)saving.value=false}
}
async function retryVerification(){
  if(saving.value)return
  saving.value=true
  await verifySave(additions.value.map(row=>row.employee_id),[...removals.value])
  saving.value=false
}
onMounted(()=>{void loadState();void loadCandidates();void loadMasters()})
onBeforeUnmount(()=>{alive=false;sequence++})
</script>

<template>
  <el-config-provider :locale="zhCn">
  <el-dialog :model-value="true" title="场次人员安排" width="min(1440px, 96vw)" top="5vh" class="session-assignment-dialog" append-to-body :close-on-click-modal="false" :before-close="close">
    <div class="assignment-summary">
      <div class="assignment-session"><strong>{{session.course_name}}</strong><span>场次：{{session.title}}</span><span>{{formatCourseDate(session.starts_at)}} 至 {{formatCourseDate(session.ends_at)}} · {{session.delivery_mode==='ONLINE'?'线上会议':session.location||'地点待定'}}</span></div>
      <div class="assignment-metrics"><span>已安排 <b>{{state.enrolledCount}}</b> 人</span><span>已签到 <b>{{state.attendanceCount}}</b> 人</span></div>
    </div>
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" show-icon/>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert v-if="blockedRemoval" title="待移除人员中有人已签到，请撤销对应移除操作后再保存。" type="error" :closable="false"/>
    <div class="assignment-mobile-tabs"><el-radio-group v-model="mobileTab"><el-radio-button value="candidates">选择人员</el-radio-button><el-radio-button value="roster">本场名单（{{finalCount}}）</el-radio-button></el-radio-group></div>
    <div class="assignment-columns">
      <section class="assignment-pane assignment-candidates" :class="{'assignment-mobile-hidden':mobileTab!=='candidates'}">
        <div class="assignment-pane-title"><h3>选择参训人员</h3><span>仅展示尚未安排的在用员工</span></div>
        <div class="assignment-filters" :class="{'is-busy':locked}">
          <label><span>姓名／工号</span><el-input v-model="filterDraft.keyword" placeholder="搜索姓名或工号" :prefix-icon="Search" clearable :disabled="locked" @keyup.enter="query"/></label>
          <label v-for="field in commonFields" :key="field.key"><span>{{field.label}}</span><el-select v-model="filterDraft[field.key]" multiple filterable clearable collapse-tags collapse-tags-tooltip :placeholder="`全部${field.label}`" :disabled="locked"><el-option v-for="item in options[field.key]" :key="item.id" :label="item.label" :value="item.id"/></el-select></label>
          <template v-if="advanced">
            <label v-for="field in extraFields" :key="field.key"><span>{{field.label}}</span><el-select v-model="filterDraft[field.key]" multiple filterable clearable collapse-tags collapse-tags-tooltip :placeholder="`全部${field.label}`" :disabled="locked"><el-option v-for="item in options[field.key]" :key="item.id" :label="item.label" :value="item.id"/></el-select></label>
            <label><span>毕业院校</span><el-input v-model="filterDraft.school" placeholder="搜索毕业院校" clearable :disabled="locked" @keyup.enter="query"/></label>
            <label><span>专业</span><el-input v-model="filterDraft.major" placeholder="搜索专业" clearable :disabled="locked" @keyup.enter="query"/></label>
          </template>
        </div>
        <div class="assignment-filter-actions"><el-button type="primary" :icon="Search" :disabled="locked" @click="query">查询</el-button><el-button :disabled="locked" @click="reset">重置</el-button><el-button link type="primary" :icon="advanced?ArrowUp:ArrowDown" @click="advanced=!advanced">{{advanced?'收起筛选':'更多筛选'}}</el-button></div>
        <div v-if="masterError" class="assignment-inline-error">{{masterError}}<el-button link type="primary" @click="loadMasters">重试</el-button></div>
        <div class="assignment-selection-bar"><span>当前结果选中 <b>{{selectedInResults}}</b> 人 · 本次累计新增 <b>{{additions.length}}</b> 人</span><el-button link type="primary" :loading="resolving" :disabled="locked||loading||Boolean(candidateError)||!total" @click="selectAll">全选全部筛选结果（{{total}} 人）</el-button><el-button link :disabled="locked||!additions.length" @click="additions=[]">清空本次新增</el-button></div>
        <div v-if="candidateError" class="assignment-load-error"><span>{{candidateError}}</span><el-button :icon="Refresh" @click="loadCandidates">重新加载</el-button></div>
        <div v-else class="assignment-table-wrap"><el-table height="100%" :data="candidates" v-loading="loading" row-key="employee_id" class="assignment-table" @cell-click="cellClick" :row-class-name="({row}:{row:AssignmentPerson})=>addIds.has(row.employee_id)?'assignment-selected':''">
          <el-table-column prop="_select" width="52" class-name="assignment-check-column"><template #header><el-checkbox aria-label="全选当前页" :model-value="allPageSelected" :indeterminate="somePageSelected" :disabled="locked||loading||!candidates.length" @change="selectPage"/></template><template #default="{row}"><el-checkbox :aria-label="`选择 ${row.employee_name}`" :model-value="addIds.has(row.employee_id)" :disabled="locked||loading" @click.stop @change="toggle(row)"/></template></el-table-column>
          <el-table-column type="expand" width="36"><template #default="{row}"><dl class="assignment-person-detail"><div><dt>班级职务</dt><dd>{{show(row.class_position_name)}}</dd></div><div><dt>技术导师</dt><dd>{{show(row.technical_mentor_name)}}</dd></div><div><dt>技能导师</dt><dd>{{show(row.skill_mentor_name)}}</dd></div><div><dt>学历</dt><dd>{{show(row.education)}}</dd></div><div><dt>毕业院校</dt><dd>{{show(row.school)}}</dd></div><div><dt>专业</dt><dd>{{show(row.major)}}</dd></div></dl></template></el-table-column>
          <el-table-column label="姓名／工号" min-width="115"><template #default="{row}"><div class="assignment-person"><strong>{{row.employee_name}}</strong><span>{{row.employee_no}}</span></div></template></el-table-column>
          <el-table-column prop="batch_name" label="届别" min-width="90" show-overflow-tooltip/>
          <el-table-column prop="class_name" label="班级" min-width="85" show-overflow-tooltip/>
          <el-table-column prop="business_unit_name" label="所属板块" min-width="100" show-overflow-tooltip/>
          <el-table-column prop="station_name" label="服务站" min-width="105" show-overflow-tooltip/>
          <template #empty><el-empty description="无匹配人员，请调整筛选条件" :image-size="55"/></template>
        </el-table></div>
        <div class="assignment-pagination"><el-pagination :current-page="page" v-model:page-size="size" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" :pager-count="5" :disabled="locked||loading" @current-change="page=$event;loadCandidates()" @size-change="page=1;loadCandidates()"/></div>
      </section>

      <section class="assignment-pane assignment-roster" :class="{'assignment-mobile-hidden':mobileTab!=='roster'}" v-loading="stateLoading">
        <div class="assignment-pane-title"><h3>本场人员名单</h3><el-button link type="primary" :disabled="locked" @click="rosterMode='CHANGED';rosterPage=1">查看待变更（{{additions.length+removals.length}}）</el-button></div>
        <div class="assignment-roster-filters"><el-input v-model="rosterKeyword" :prefix-icon="Search" placeholder="搜索本场姓名／工号" clearable @input="rosterPage=1"/><el-select v-model="rosterMode" filterable @change="rosterPage=1"><el-option label="全部人员" value="ALL"/><el-option label="已安排" value="EXISTING"/><el-option label="待新增" value="ADD"/><el-option label="待移除" value="REMOVE"/><el-option label="待变更" value="CHANGED"/></el-select><el-select v-model="attendanceMode" filterable @change="rosterPage=1"><el-option label="全部签到状态" value="ALL"/><el-option label="已签到" value="CHECKED"/><el-option label="未签到" value="UNCHECKED"/></el-select></div>
        <div class="assignment-selection-bar"><span>匹配 {{filteredRoster.length}} 人 · 已选 {{bulkIds.length}} 人</span><el-button link type="danger" :disabled="locked||!bulkIds.length" @click="removeBulk">批量移除／撤销新增</el-button><el-button v-if="bulkIds.length" link :disabled="locked" @click="bulkIds=[]">取消选择</el-button></div>
        <p class="assignment-roster-help">已有签到记录的人员保留在名单中，不可移除。</p>
        <div v-if="stateError" class="assignment-load-error"><span>{{stateError}}</span><el-button :icon="Refresh" @click="loadState(true)">重新加载名单</el-button></div>
        <div v-else class="assignment-table-wrap"><el-table height="100%" :data="rosterRows" row-key="employee_id" class="assignment-table">
          <el-table-column width="52" class-name="assignment-check-column"><template #header><el-checkbox aria-label="全选本页可移除人员" :model-value="allRosterSelected" :indeterminate="someRosterSelected" :disabled="locked||!removablePage.length" @change="selectRosterPage"/></template><template #default="{row}"><el-checkbox :aria-label="`选择本场人员 ${row.employee_name}`" :model-value="bulkIds.includes(row.employee_id)" :disabled="locked||Boolean(row.attendance_status)||row.change==='REMOVE'" @change="toggleBulk(row)"/></template></el-table-column>
          <el-table-column label="姓名／工号" min-width="110"><template #default="{row}"><div class="assignment-person"><strong>{{row.employee_name}}</strong><span>{{row.employee_no}}</span><el-tag v-if="row.status!=='ACTIVE'" size="small" type="info">已停用</el-tag></div></template></el-table-column>
          <el-table-column label="班级／组织" min-width="110" show-overflow-tooltip><template #default="{row}"><div class="assignment-person"><span>{{show(row.class_name)}}</span><span>{{show(row.business_unit_name)}} · {{show(row.station_name)}}</span></div></template></el-table-column>
          <el-table-column label="状态" width="83"><template #default="{row}"><div class="assignment-status"><el-tag size="small" :type="row.change==='ADD'?'success':row.change==='REMOVE'?'danger':'info'">{{row.change==='ADD'?'待新增':row.change==='REMOVE'?'待移除':'已安排'}}</el-tag><el-tag v-if="row.attendance_status" size="small" type="success">已签到</el-tag></div></template></el-table-column>
          <el-table-column label="操作" width="70" fixed="right"><template #default="{row}"><el-tooltip :disabled="!row.attendance_status||row.change==='REMOVE'" content="已有签到记录，不可移除"><span><el-button link :type="row.change==='EXISTING'?'danger':'primary'" :disabled="locked||(Boolean(row.attendance_status)&&row.change!=='REMOVE')" @click="removeRow(row)">{{row.change==='EXISTING'?'移除':'撤销'}}</el-button></span></el-tooltip></template></el-table-column>
          <template #empty><el-empty :description="roster.length?'本场名单中无匹配人员':'本场尚未安排人员，请在左侧勾选添加'" :image-size="55"/></template>
        </el-table></div>
        <div class="assignment-pagination"><el-pagination :current-page="effectiveRosterPage" v-model:page-size="rosterSize" :total="filteredRoster.length" :page-sizes="[20,50,100]" layout="total, sizes, prev, next" @current-change="rosterPage=$event" @size-change="rosterPage=1"/></div>
      </section>
    </div>
    <template #footer><div class="assignment-footer"><div>原已安排 <b>{{state.enrolledCount}}</b> 人，新增 <b class="assignment-green">{{additions.length}}</b> 人，移除 <b class="assignment-red">{{removals.length}}</b> 人，保存后 <b>{{finalCount}}</b> 人<span>勾选、移除均在保存后生效</span></div><div><el-button :disabled="saving||resolving" @click="close">取消</el-button><el-button v-if="uncertain" type="warning" :loading="saving" @click="retryVerification">核对保存结果</el-button><el-button v-else type="primary" :loading="saving" :disabled="locked||!dirty||blockedRemoval||Boolean(stateError)" @click="save">保存安排</el-button></div></div></template>
  </el-dialog>
  </el-config-provider>
</template>

<style>
.session-assignment-dialog{display:flex;flex-direction:column;height:90dvh;max-height:90dvh;margin-bottom:0;border-radius:10px;color:#263247}
.session-assignment-dialog .el-dialog__header{flex:none;margin:0;padding:3px 8px 14px;border-bottom:1px solid #edf0f4}
.session-assignment-dialog .el-dialog__body{display:flex;flex:1;min-height:0;flex-direction:column;gap:10px;overflow:hidden;padding:14px 8px 0}
.session-assignment-dialog .el-dialog__footer{flex:none;padding:14px 8px 2px;border-top:1px solid #e5e9f0;margin-top:12px}
.assignment-summary{display:flex;flex:none;gap:16px;align-items:center;justify-content:space-between;padding:12px 14px;border:1px solid #e5edf7;border-radius:8px;background:#f4f8fd}
.assignment-session{display:flex;min-width:0;flex-direction:column;gap:4px;line-height:1.5;overflow-wrap:anywhere}.assignment-session strong{font-size:14px}.assignment-session span{font-size:12px;color:#728095}
.assignment-metrics{display:flex;flex:none;gap:14px;font-size:12px;color:#728095}.assignment-metrics b{color:#2678c9;font-size:20px}
.assignment-columns{display:grid;grid-template-columns:minmax(0,3fr) minmax(0,2fr);gap:14px;min-height:0;flex:1}
.assignment-pane{display:flex;min-width:0;min-height:0;flex-direction:column;border:1px solid #e5e9f0;border-radius:8px;overflow:auto;background:#fff}
.assignment-pane-title{display:flex;flex:none;justify-content:space-between;align-items:center;gap:8px;padding:12px;background:#f8fafd;border-bottom:1px solid #edf0f4}.assignment-pane-title h3{margin:0;font-size:14px}.assignment-pane-title>span{font-size:11px;color:#8792a3}
.assignment-filters{display:grid;flex:0 1 auto;min-height:92px;max-height:32vh;overflow:auto;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px 10px;padding:12px}.assignment-filters label{display:flex;min-width:0;flex-direction:column;gap:5px}.assignment-filters label>span{font-size:12px;color:#667085}.assignment-filters .el-select{width:100%}.assignment-filter-actions{display:flex;flex:none;padding:0 12px 10px;align-items:center;gap:8px}.assignment-filter-actions .el-button{margin:0}
.assignment-selection-bar{display:flex;flex:none;flex-wrap:wrap;gap:4px 12px;align-items:center;min-height:38px;padding:6px 12px;border-top:1px solid #edf0f4;background:#f5f9ff;font-size:12px;color:#667085}.assignment-selection-bar b{color:#2678c9}.assignment-selection-bar .el-button{margin:0;font-size:12px;white-space:normal;height:auto;min-height:26px;text-align:left}.assignment-selection-bar>span{margin-right:auto}
.assignment-table-wrap{flex:1 1 0;min-height:128px;overflow:hidden}.assignment-table{width:100%;height:100%}.assignment-table .cell{font-size:12px}.assignment-table .el-table__row{cursor:pointer}.assignment-table .assignment-selected{--el-table-tr-bg-color:#f0f7ff}
.assignment-check-column .cell{display:flex;align-items:center;justify-content:center;padding:0!important}.assignment-check-column .el-checkbox{display:flex;width:100%;min-height:36px;justify-content:center;margin:0}.assignment-check-column .el-checkbox__inner{width:17px;height:17px}.assignment-check-column .el-checkbox__inner:after{left:5px;top:2px}
.assignment-person{display:flex;min-width:0;flex-direction:column;gap:3px;line-height:1.5}.assignment-person strong{color:#263247;font-size:13px}.assignment-person span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#7e899b;font-size:11px}.assignment-person .el-tag{align-self:flex-start}.assignment-status{display:flex;gap:5px;flex-direction:column;align-items:flex-start}
.assignment-person-detail{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:0;padding:14px;background:#f8fafd}.assignment-person-detail div{min-width:0}.assignment-person-detail dt{font-size:11px;color:#8792a3}.assignment-person-detail dd{margin:4px 0 0;font-size:12px;overflow-wrap:anywhere}
.assignment-roster-filters{display:grid;flex:none;grid-template-columns:1fr 1fr;gap:8px;padding:12px}.assignment-roster-filters>.el-input{grid-column:1/-1}.assignment-roster-help{flex:none;margin:0;padding:7px 12px;color:#8792a3;font-size:11px}.assignment-roster .assignment-person span{max-width:180px}
.assignment-pagination{display:flex;flex:none;justify-content:center;padding:10px 4px;border-top:1px solid #edf0f4;background:#fff}.assignment-pagination .el-pagination{flex-wrap:wrap;justify-content:center;gap:4px}.assignment-pagination .el-pagination__sizes{margin:0}.assignment-pagination .el-select{width:110px}.assignment-pagination .el-pagination__total{margin:0}
.assignment-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;text-align:left;font-size:12px;color:#667085}.assignment-footer>div:first-child{line-height:1.7}.assignment-footer>div:last-child{display:flex;flex:none;gap:8px}.assignment-footer .el-button{margin:0}.assignment-footer b{color:#2678c9}.assignment-footer .assignment-green{color:#15966b}.assignment-footer .assignment-red{color:#e45c5c}.assignment-footer>div:first-child>span{display:block;font-size:11px;color:#8792a3}
.assignment-inline-error{flex:none;padding:6px 12px;color:#c45656;font-size:12px}.assignment-load-error{display:flex;flex:1;min-height:180px;align-items:center;justify-content:center;gap:12px;flex-direction:column;padding:16px;color:#c45656;font-size:13px}.assignment-mobile-tabs{display:none}
@media(max-width:1100px) and (min-width:901px){.assignment-filters{grid-template-columns:repeat(2,minmax(0,1fr))}.assignment-columns{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.assignment-metrics{flex-direction:column;gap:2px}}
@media(max-width:900px){.session-assignment-dialog{width:96vw!important;height:94dvh;max-height:94dvh;margin-top:3vh!important;padding:12px}.session-assignment-dialog .el-dialog__body{padding:10px 0 0}.assignment-summary{align-items:flex-start;padding:10px;gap:8px}.assignment-session strong{font-size:13px}.assignment-session span{font-size:11px}.assignment-metrics{flex-direction:column;gap:4px;white-space:nowrap}.assignment-metrics b{font-size:17px}.assignment-mobile-tabs{display:block;flex:none}.assignment-mobile-tabs .el-radio-group{display:flex}.assignment-mobile-tabs .el-radio-button{flex:1}.assignment-mobile-tabs .el-radio-button__inner{width:100%}.assignment-columns{display:flex;flex-direction:column;min-height:240px}.assignment-pane{flex:1}.assignment-mobile-hidden{display:none}.assignment-filters{grid-template-columns:repeat(2,minmax(0,1fr));padding:10px;gap:8px}.assignment-footer{flex-wrap:wrap;gap:8px}.assignment-footer>div:last-child{width:100%;justify-content:flex-end}.assignment-person-detail{grid-template-columns:repeat(2,minmax(0,1fr))}.assignment-pagination .el-pagination__total{font-size:11px}.assignment-table-wrap{min-height:128px}.assignment-pane-title>span{font-size:10px}}
</style>
