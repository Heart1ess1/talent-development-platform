<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue'
import {useRouter} from 'vue-router'
import {
  CircleCheck,
  Clock,
  Connection,
  Download,
  Edit,
  MapLocation,
  OfficeBuilding,
  Plus,
  Refresh,
  Search,
  Setting,
  UploadFilled,
  UserFilled,
  View
} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {avatarUrl,nameInitial} from '@/utils/avatar'
import {
  loadDictionaryValues,
  loadEnabledBusinessUnits,
  type DictionaryOption
} from '@/utils/masterData'

type DirectoryRow=Record<string,any>
type StationChange=Record<string,any>
type ProfileMode='view'|'edit'|'create'
type DirectorySummary={
  totalEmployees:number
  activeEmployees:number
  inactiveEmployees:number
  stationAssigned:number
  mentorReady:number
}

const auth=useAuthStore()
const router=useRouter()
const canWrite=computed(()=>auth.can('employee:write'))
const canEdit=computed(()=>auth.can('employee:update'))
const canExport=computed(()=>auth.can('employee:export'))
const canMaster=computed(()=>auth.can('master:manage'))
const canViewHistory=computed(()=>auth.can('employee:read'))

const rows=ref<DirectoryRow[]>([])
const total=ref(0)
const summary=reactive<DirectorySummary>({
  totalEmployees:0,
  activeEmployees:0,
  inactiveEmployees:0,
  stationAssigned:0,
  mentorReady:0
})
const page=ref(1)
const pageSize=ref(20)
const loading=ref(false)
const exporting=ref(false)
const importing=ref(false)
const saving=ref(false)
const pendingCount=ref(0)
const selectedRows=ref<DirectoryRow[]>([])
const tableRef=ref<any>()
const advancedFilters=ref(false)
const dataToolsOpen=ref(false)

const profileOpen=ref(false)
const profileMode=ref<ProfileMode>('view')
const selectedEmployee=ref<DirectoryRow|null>(null)
const editingId=ref<number|null>(null)

const historyOpen=ref(false)
const historyLoading=ref(false)
const historyEmployee=ref<DirectoryRow|null>(null)
const historyData=ref<StationChange[]>([])

const bulkOpen=ref(false)
const bulkSaving=ref(false)
const bulkMentorType=ref<'TECHNICAL'|'SKILL'>('TECHNICAL')
const bulkMentorId=ref<number|null>(null)

const batches=ref<any[]>([])
const businessUnits=ref<any[]>([])
const stations=ref<any[]>([])
const mentors=ref<any[]>([])
const educationOptions=ref<DictionaryOption[]>([])
const politicalStatusOptions=ref<DictionaryOption[]>([])
const classOptions=ref<DictionaryOption[]>([])

const isNarrow=ref(false)
let narrowMedia:MediaQueryList|undefined

const filters=reactive({
  keyword:'',
  batchId:null as number|null,
  classId:null as number|null,
  businessUnitId:null as number|null,
  stationId:null as number|null,
  mentorId:null as number|null,
  skillMentorId:null as number|null,
  education:'',
  status:''
})

const emptyEmployeeForm=()=>({
  employeeNo:'',
  name:'',
  batchId:null as number|null,
  classId:null as number|null,
  businessUnitId:null as number|null,
  stationId:null as number|null,
  mentorUserId:null as number|null,
  skillMentorUserId:null as number|null,
  school:'',
  major:'',
  education:'',
  birthDate:null as string|null,
  nativePlace:'',
  residence:'',
  phone:'',
  email:'',
  onboardDate:null as string|null,
  politicalStatus:'',
  hobbies:'',
  speciality:'',
  idCard:'',
  notes:'',
  status:'ACTIVE'
})
const employeeForm=reactive(emptyEmployeeForm())

const appliedFilterCount=computed(
  ()=>Object.values(filters).filter(value=>value!==''&&value!==null).length
)
const summaryParams=computed(()=>({
  keyword:filters.keyword||undefined,
  batchId:filters.batchId||undefined,
  classId:filters.classId||undefined,
  businessUnitId:filters.businessUnitId||undefined,
  stationId:filters.stationId||undefined,
  mentorId:filters.mentorId||undefined,
  skillMentorId:filters.skillMentorId||undefined,
  education:filters.education||undefined
}))
const stationCoverage=computed(()=>summary.totalEmployees
  ?Math.round(summary.stationAssigned/summary.totalEmployees*100)
  :0)
const mentorCoverage=computed(()=>summary.totalEmployees
  ?Math.round(summary.mentorReady/summary.totalEmployees*100)
  :0)
const statusTabs=computed(()=>[
  {label:'全部人员',value:'',count:summary.totalEmployees},
  {label:'在职',value:'ACTIVE',count:summary.activeEmployees},
  {label:'停用',value:'INACTIVE',count:summary.inactiveEmployees}
])
const initialStationName=computed(()=>{
  const oldest=historyData.value[historyData.value.length-1]
  if(oldest)return oldest.current_station_name||'未分配'
  return historyEmployee.value?.station_name||'未分配'
})
const drawerSize=computed(()=>profileMode.value==='view'?'560px':'760px')
const drawerTitle=computed(
  ()=>profileMode.value==='create'?'新增人员':profileMode.value==='edit'?'编辑人员':'人员档案'
)

function requestParams(){
  return {...filters,page:page.value,size:pageSize.value}
}

function display(value:any){
  return value===null||value===undefined||value===''?'-':String(value)
}

function formatDate(value:any){
  return value?String(value).substring(0,10):'-'
}

function formatDateTime(value:any){
  return value?String(value).replace('T',' ').substring(0,16):'-'
}

function maskedIdCard(value:any){
  const text=String(value||'')
  if(!text)return '-'
  if(text.length<11)return text
  return `${text.substring(0,6)}********${text.substring(text.length-4)}`
}

function statusLabel(status:string){
  return status==='ACTIVE'?'在职':status==='INACTIVE'?'停用':status||'-'
}

async function load(){
  loading.value=true
  try{
    const [listResponse,summaryResponse]=await Promise.all([
      api.get<any,Envelope<any>>('/employee-directory',{params:requestParams()}),
      api.get<any,Envelope<DirectorySummary>>('/employee-directory/summary',{
        params:summaryParams.value
      })
    ])
    rows.value=listResponse.data.records
    total.value=listResponse.data.total
    Object.assign(summary,summaryResponse.data)
    selectedRows.value=[]
  }finally{
    loading.value=false
  }
}

async function loadMasters(){
  const [
    batchResponse,
    unitOptions,
    stationResponse,
    mentorResponse,
    educationValues,
    politicalStatusValues,
    classValues
  ]=await Promise.all([
    api.get<any,Envelope<any[]>>('/batches'),
    loadEnabledBusinessUnits(),
    api.get<any,Envelope<any[]>>('/stations'),
    api.get<any,Envelope<any[]>>('/mentors'),
    loadDictionaryValues('EDUCATION'),
    loadDictionaryValues('POLITICAL_STATUS'),
    loadDictionaryValues('CLASS')
  ])
  batches.value=batchResponse.data
  businessUnits.value=unitOptions
  stations.value=stationResponse.data.filter(item=>item.enabled)
  mentors.value=mentorResponse.data
  educationOptions.value=educationValues
  politicalStatusOptions.value=politicalStatusValues
  classOptions.value=classValues
}

async function loadPending(){
  if(!canMaster.value)return
  try{
    const response=await api.get<any,Envelope<any[]>>(
      '/station-change-requests',
      {params:{status:'PENDING'}}
    )
    pendingCount.value=response.data.length
  }catch{
    pendingCount.value=0
  }
}

function search(){
  page.value=1
  load()
}

function reset(){
  Object.assign(filters,{
    keyword:'',
    batchId:null,
    classId:null,
    businessUnitId:null,
    stationId:null,
    mentorId:null,
    skillMentorId:null,
    education:'',
    status:''
  })
  search()
}

function changePageSize(){
  page.value=1
  load()
}

function changeStatus(value:string){
  filters.status=value
  search()
}

function fillEmployeeForm(row:DirectoryRow){
  Object.assign(employeeForm,{
    employeeNo:row.employee_no||'',
    name:row.name||'',
    batchId:row.batch_id??null,
    classId:row.class_id??null,
    businessUnitId:row.business_unit_id??null,
    stationId:row.station_id??null,
    mentorUserId:row.mentor_user_id??null,
    skillMentorUserId:row.skill_mentor_user_id??null,
    school:row.school||'',
    major:row.major||'',
    education:row.education||'',
    birthDate:row.birth_date||null,
    nativePlace:row.native_place||'',
    residence:row.residence||'',
    phone:row.phone||'',
    email:row.email||'',
    onboardDate:row.onboard_date||null,
    politicalStatus:row.political_status||'',
    hobbies:row.hobbies||'',
    speciality:row.speciality||'',
    idCard:row.id_card||'',
    notes:row.notes||'',
    status:row.status||'ACTIVE'
  })
}

function showDetails(row:DirectoryRow){
  selectedEmployee.value=row
  editingId.value=row.id
  profileMode.value='view'
  profileOpen.value=true
}

function openCreate(){
  selectedEmployee.value=null
  editingId.value=null
  Object.assign(employeeForm,emptyEmployeeForm())
  profileMode.value='create'
  profileOpen.value=true
}

function openEdit(row:DirectoryRow){
  selectedEmployee.value=row
  editingId.value=row.id
  fillEmployeeForm(row)
  profileMode.value='edit'
  profileOpen.value=true
}

function editCurrent(){
  if(!selectedEmployee.value)return
  fillEmployeeForm(selectedEmployee.value)
  profileMode.value='edit'
}

async function saveEmployee(){
  if(!employeeForm.employeeNo.trim())return ElMessage.warning('请填写工号')
  if(!employeeForm.name.trim())return ElMessage.warning('请填写姓名')
  saving.value=true
  try{
    if(editingId.value){
      await api.put(`/employees/${editingId.value}`,employeeForm)
      ElMessage.success('人员台账已更新')
    }else{
      await api.post('/employees',employeeForm)
      ElMessage.success('人员已新增')
    }
    profileOpen.value=false
    await Promise.all([load(),loadPending()])
  }finally{
    saving.value=false
  }
}

function openBulk(type:'TECHNICAL'|'SKILL'){
  if(!selectedRows.value.length)return
  bulkMentorType.value=type
  bulkMentorId.value=null
  bulkOpen.value=true
}

async function bindMentor(){
  if(!bulkMentorId.value)return ElMessage.warning('请选择导师')
  bulkSaving.value=true
  try{
    const response=await api.post<any,Envelope<number>>('/employees/bind-mentor',{
      employeeIds:selectedRows.value.map(row=>row.id),
      mentorUserId:bulkMentorId.value,
      mentorType:bulkMentorType.value
    })
    ElMessage.success(`已更新 ${response.data} 人`)
    bulkOpen.value=false
    await load()
  }finally{
    bulkSaving.value=false
  }
}

function clearSelection(){
  tableRef.value?.clearSelection()
  selectedRows.value=[]
}

async function showStationHistory(row:DirectoryRow){
  historyEmployee.value=row
  historyData.value=[]
  historyOpen.value=true
  historyLoading.value=true
  try{
    historyData.value=(
      await api.get<any,Envelope<StationChange[]>>(
        `/station-change-requests/employee/${row.id}`
      )
    ).data
  }finally{
    historyLoading.value=false
  }
}

async function exportRows(){
  exporting.value=true
  try{
    const blob=await api.get<any,Blob>(
      '/employee-directory/export',
      {params:filters,responseType:'blob'}
    )
    download(blob,'人员台账.xlsx')
  }finally{
    exporting.value=false
  }
}

async function downloadTemplate(){
  const blob=await api.get<any,Blob>(
    '/imports/employees/template',
    {responseType:'blob'}
  )
  download(blob,'新员工导入模板.xlsx')
}

async function upload(options:any){
  importing.value=true
  try{
    const formData=new FormData()
    formData.append('file',options.file)
    const response=await api.post<any,Envelope<any>>('/imports/employees',formData)
    const result=response.data
    if(result.errors.length){
      const first=result.errors[0]
      ElMessage.error(`第 ${first.row} 行 ${first.field}：${first.message}`)
    }else{
      ElMessage.success(`已导入 ${result.imported} 人`)
      await load()
    }
  }finally{
    importing.value=false
  }
}

function download(blob:Blob,name:string){
  const url=URL.createObjectURL(blob)
  const link=document.createElement('a')
  link.href=url
  link.download=name
  link.click()
  URL.revokeObjectURL(url)
}

async function addMaster(type:'batches'|'business-units'|'stations'){
  const title={
    batches:'新增批次',
    'business-units':'新增所属板块',
    stations:'新增服务站点'
  }[type]
  try{
    const {value}=await ElMessageBox.prompt('请输入名称',title,{
      inputPattern:/\S/,
      inputErrorMessage:'名称不能为空'
    })
    await api.post(`/${type}`,{name:value.trim()})
    ElMessage.success(`${title}成功`)
    await loadMasters()
  }catch(error){
    if(error!=='cancel')throw error
  }
}

async function addClass(){
  try{
    const {value}=await ElMessageBox.prompt('请输入班级名称','新增班级',{
      inputPattern:/\S/,
      inputErrorMessage:'班级名称不能为空'
    })
    const name=value.trim()
    await api.post('/dictionaries/CLASS/values',{
      value:name,
      label:name,
      sortOrder:classOptions.value.length*10+10,
      enabled:true
    })
    ElMessage.success('新增班级成功')
    dataToolsOpen.value=false
    await loadMasters()
  }catch(error){
    if(error!=='cancel')throw error
  }
}

function syncNarrow(event:MediaQueryList|MediaQueryListEvent){
  isNarrow.value=event.matches
}

onMounted(()=>{
  narrowMedia=window.matchMedia('(max-width: 800px)')
  syncNarrow(narrowMedia)
  narrowMedia.addEventListener('change',syncNarrow)
  load()
  loadMasters()
  loadPending()
})

onBeforeUnmount(()=>narrowMedia?.removeEventListener('change',syncNarrow))
</script>

<template>
  <div class="page directory-page">
    <header class="directory-header">
      <div class="title-group">
        <div class="eyebrow">人员管理 · 人员台账</div>
        <h1>人员台账</h1>
        <p>集中维护新员工档案、组织归属和培养关系，快速识别待完善的人员信息。</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
        <el-badge v-if="canMaster" :value="pendingCount" :hidden="!pendingCount">
          <el-button :icon="Clock" @click="router.push('/station-change-review')">调站审批</el-button>
        </el-badge>
        <el-popover
          v-if="canWrite||canMaster"
          v-model:visible="dataToolsOpen"
          trigger="click"
          placement="bottom-end"
          :width="220"
        >
          <template #reference>
            <el-button :icon="Setting">数据工具</el-button>
          </template>
          <div class="data-tools">
            <el-button v-if="canWrite" text :icon="Download" @click="downloadTemplate">
              下载导入模板
            </el-button>
            <el-upload
              v-if="canWrite"
              :show-file-list="false"
              :http-request="upload"
              accept=".xlsx"
            >
              <el-button text :icon="UploadFilled" :loading="importing">导入 Excel</el-button>
            </el-upload>
            <template v-if="canMaster">
              <el-divider/>
              <el-button text :icon="Plus" @click="addMaster('batches')">新增批次</el-button>
              <el-button text :icon="Plus" @click="addClass">新增班级</el-button>
              <el-button text :icon="Plus" @click="addMaster('business-units')">
                新增所属板块
              </el-button>
              <el-button text :icon="Plus" @click="addMaster('stations')">
                新增服务站点
              </el-button>
            </template>
          </div>
        </el-popover>
        <el-button
          v-if="canExport"
          :icon="Download"
          :loading="exporting"
          @click="exportRows"
        >
          导出
        </el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
          新增人员
        </el-button>
      </div>
    </header>

    <section class="directory-summary-grid" aria-label="人员概览">
      <article class="directory-summary-card">
        <span class="summary-visual blue"><el-icon><UserFilled/></el-icon></span>
        <div><small>人员总数</small><strong>{{summary.totalEmployees}}</strong><span>当前权限范围</span></div>
      </article>
      <article class="directory-summary-card">
        <span class="summary-visual green"><el-icon><CircleCheck/></el-icon></span>
        <div><small>在职人员</small><strong>{{summary.activeEmployees}}</strong><span>{{summary.inactiveEmployees}} 人已停用</span></div>
      </article>
      <article class="directory-summary-card">
        <span class="summary-visual amber"><el-icon><OfficeBuilding/></el-icon></span>
        <div><small>站点已分配</small><strong>{{summary.stationAssigned}}</strong><span>覆盖率 {{stationCoverage}}%</span></div>
      </article>
      <article class="directory-summary-card">
        <span class="summary-visual violet"><el-icon><Connection/></el-icon></span>
        <div><small>双导师已配置</small><strong>{{summary.mentorReady}}</strong><span>完整度 {{mentorCoverage}}%</span></div>
      </article>
    </section>

    <section class="directory-workspace">
      <div class="workspace-heading">
        <div>
          <h2>人员档案</h2>
          <p>查看组织归属、培养关系与基础档案，点击姓名可快速打开完整资料。</p>
        </div>
        <span class="workspace-count">共 {{total}} 人</span>
      </div>
      <div class="directory-status-tabs">
        <button
          v-for="tab in statusTabs"
          :key="tab.value||'ALL'"
          type="button"
          :class="{active:filters.status===tab.value}"
          @click="changeStatus(tab.value)"
        >
          {{tab.label}}<span>{{tab.count}}</span>
        </button>
      </div>

    <section class="filter-surface" aria-label="人员筛选">
      <div class="filter-grid">
        <el-input
          v-model="filters.keyword"
          :prefix-icon="Search"
          placeholder="姓名、工号、电话或邮箱"
          clearable
          class="keyword-filter"
          @keyup.enter="search"
        />
        <el-select v-model="filters.batchId" placeholder="批次" clearable>
          <el-option v-for="item in batches" :key="item.id" :label="item.name" :value="item.id"/>
        </el-select>
        <el-select v-model="filters.classId" placeholder="班级" clearable filterable>
          <el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/>
        </el-select>
        <el-select v-model="filters.businessUnitId" placeholder="所属板块" clearable filterable>
          <el-option
            v-for="item in businessUnits"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
        <el-select v-model="filters.stationId" placeholder="服务站点" clearable filterable>
          <el-option v-for="item in stations" :key="item.id" :label="item.name" :value="item.id"/>
        </el-select>
        <div class="filter-actions">
          <el-button text @click="advancedFilters=!advancedFilters">
            {{advancedFilters?'收起筛选':'更多筛选'}}
          </el-button>
          <el-button type="primary" :icon="Search" @click="search">查询</el-button>
          <el-button :disabled="!appliedFilterCount" @click="reset">
            重置<span v-if="appliedFilterCount">（{{appliedFilterCount}}）</span>
          </el-button>
        </div>
      </div>
      <el-collapse-transition>
        <div v-if="advancedFilters" class="advanced-filter-grid">
          <el-select v-model="filters.mentorId" placeholder="指导老师（技术）" clearable filterable>
            <el-option
              v-for="item in mentors"
              :key="item.id"
              :label="item.display_name"
              :value="item.id"
            />
          </el-select>
          <el-select
            v-model="filters.skillMentorId"
            placeholder="指导老师（技能）"
            clearable
            filterable
          >
            <el-option
              v-for="item in mentors"
              :key="item.id"
              :label="item.display_name"
              :value="item.id"
            />
          </el-select>
          <el-select v-model="filters.education" placeholder="学历" clearable>
            <el-option
              v-for="item in educationOptions"
              :key="item.id"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </div>
      </el-collapse-transition>
    </section>

    <section class="directory-table">
      <div class="table-bar">
        <span>
          {{appliedFilterCount?'当前筛选结果':'全部人员'}}
          <strong>{{total}}</strong>
          <small v-if="appliedFilterCount">已应用 {{appliedFilterCount}} 个条件</small>
        </span>
        <el-select
          v-model="pageSize"
          class="page-size"
          aria-label="每页条数"
          @change="changePageSize"
        >
          <el-option :value="20" label="20 条/页"/>
          <el-option :value="50" label="50 条/页"/>
          <el-option :value="100" label="100 条/页"/>
        </el-select>
      </div>

      <div v-if="selectedRows.length" class="selection-bar">
        <span>已选择 <strong>{{selectedRows.length}}</strong> 人</span>
        <el-button size="small" @click="openBulk('TECHNICAL')">设置技术导师</el-button>
        <el-button size="small" @click="openBulk('SKILL')">设置技能导师</el-button>
        <el-button size="small" text @click="clearSelection">取消选择</el-button>
      </div>

      <el-table
        ref="tableRef"
        :data="rows"
        v-loading="loading"
        stripe
        row-key="id"
        empty-text="当前条件下暂无人员"
        class="people-table"
        @selection-change="(selection:DirectoryRow[])=>selectedRows=selection"
      >
        <el-table-column v-if="canWrite&&!isNarrow" type="selection" width="44" fixed/>
        <el-table-column v-if="isNarrow" label="姓名 / 工号" width="145" fixed>
          <template #default="{row}">
            <button class="name-button" type="button" @click="showDetails(row)">
              <span class="employee-name">{{row.name}}</span>
              <span class="employee-number-line">
                <span class="employee-number">{{row.employee_no}}</span>
                <el-tag
                  :type="row.status==='ACTIVE'?'success':'info'"
                  effect="light"
                  size="small"
                >
                  {{statusLabel(row.status)}}
                </el-tag>
              </span>
              <span class="employee-class">{{row.class_name||'未分班'}}</span>
            </button>
          </template>
        </el-table-column>
        <el-table-column v-else label="姓名" min-width="110" fixed show-overflow-tooltip>
          <template #default="{row}">
            <button class="desktop-name-button" type="button" @click="showDetails(row)">
              {{row.name}}
            </button>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="工号" min-width="120" show-overflow-tooltip>
          <template #default="{row}">{{row.employee_no}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="批次" width="88">
          <template #default="{row}">{{display(row.batch_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="班级" min-width="110" show-overflow-tooltip>
          <template #default="{row}">{{display(row.class_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="所属板块" min-width="110">
          <template #default="{row}">{{display(row.business_unit_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="服务站点" min-width="150">
          <template #default="{row}">
            <el-button
              v-if="canViewHistory"
              link
              type="primary"
              :icon="MapLocation"
              class="station-button"
              @click.stop="showStationHistory(row)"
            >
              {{row.station_name||'未分配'}}
              <span v-if="Number(row.station_change_count)" class="change-count">
                {{row.station_change_count}} 次
              </span>
            </el-button>
            <span v-else>{{row.station_name||'未分配'}}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="指导老师（技术）" min-width="160">
          <template #default="{row}">{{display(row.technical_mentor_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="指导老师（技能）" min-width="160">
          <template #default="{row}">{{display(row.skill_mentor_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="毕业学校" width="140" show-overflow-tooltip>
          <template #default="{row}">{{display(row.school)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="所学专业" width="130" show-overflow-tooltip>
          <template #default="{row}">{{display(row.major)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="学历" width="76" show-overflow-tooltip>
          <template #default="{row}">{{display(row.education)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="联系方式" min-width="130" show-overflow-tooltip>
          <template #default="{row}">{{display(row.phone)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="状态" width="72">
          <template #default="{row}">
            <el-tag :type="row.status==='ACTIVE'?'success':'info'" effect="light" size="small">
              {{statusLabel(row.status)}}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isNarrow" label="板块 / 站点" width="145">
          <template #default="{row}">
            <div class="mobile-organization">
              <span>{{display(row.business_unit_name)}}</span>
              <el-button
                link
                type="primary"
                :icon="MapLocation"
                @click.stop="showStationHistory(row)"
              >
                {{row.station_name||'未分配'}}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="isNarrow" label="操作" width="62" fixed="right" align="center">
          <template #default="{row}">
            <div class="row-actions">
              <el-tooltip content="查看完整档案" placement="top">
                <el-button
                  :icon="View"
                  link
                  aria-label="查看完整档案"
                  @click="showDetails(row)"
                />
              </el-tooltip>
              <el-tooltip v-if="canEdit" content="编辑人员" placement="top">
                <el-button
                  :icon="Edit"
                  link
                  type="primary"
                  aria-label="编辑人员"
                  @click="openEdit(row)"
                />
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-else label="详情" width="62" fixed="right" align="center">
          <template #default="{row}">
            <el-tooltip content="查看完整档案" placement="top">
              <el-button
                :icon="View"
                link
                aria-label="查看完整档案"
                @click="showDetails(row)"
              />
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow&&canEdit" label="编辑" width="70" fixed="right" align="center">
          <template #default="{row}">
            <el-tooltip content="编辑人员" placement="top">
              <el-button
                :icon="Edit"
                link
                type="primary"
                aria-label="编辑人员"
                @click="openEdit(row)"
              />
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div class="people-empty">
            <el-icon><UserFilled/></el-icon>
            <strong>暂无符合条件的人员</strong>
            <span>调整筛选条件，或新增一名人员后再查看</span>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
              新增人员
            </el-button>
          </div>
        </template>
      </el-table>

      <div class="pagination-row">
        <el-pagination
          v-model:current-page="page"
          :total="total"
          :page-size="pageSize"
          layout="total,prev,pager,next"
          @current-change="load"
        />
      </div>
    </section>
    </section>

    <el-drawer
      v-model="profileOpen"
      :size="drawerSize"
      class="employee-drawer"
      destroy-on-close
    >
      <template #header>
        <div class="drawer-header">
          <div v-if="selectedEmployee" class="drawer-heading">
            <el-avatar
              :size="46"
              :src="avatarUrl(selectedEmployee.avatar_token)"
              class="employee-avatar"
              shape="square"
            >{{nameInitial(selectedEmployee.name)}}</el-avatar>
            <div>
              <div class="drawer-name">{{selectedEmployee.name}}</div>
              <div class="drawer-number">{{selectedEmployee.employee_no}}</div>
            </div>
            <el-tag :type="selectedEmployee.status==='ACTIVE'?'success':'info'" effect="light">
              {{statusLabel(selectedEmployee.status)}}
            </el-tag>
          </div>
          <div v-else class="drawer-form-title">{{drawerTitle}}</div>
          <el-button
            v-if="profileMode==='view'&&canEdit"
            :icon="Edit"
            type="primary"
            plain
            @click="editCurrent"
          >
            编辑
          </el-button>
        </div>
      </template>

      <template v-if="profileMode==='view'&&selectedEmployee">
        <section class="detail-section portrait-section">
          <h3>证件照</h3>
          <div class="portrait-view">
            <el-avatar
              :size="128"
              :src="avatarUrl(selectedEmployee.avatar_token)"
              class="employee-portrait"
              shape="square"
            >{{nameInitial(selectedEmployee.name)}}</el-avatar>
            <div>
              <strong>{{selectedEmployee.avatar_token?'本人证件照':'暂未上传证件照'}}</strong>
              <p>
                {{selectedEmployee.avatar_token
                  ?'该照片由员工本人在个人资料中维护，并同步作为平台账号头像。'
                  :'当前显示姓名末字作为默认头像；员工上传证件照后会自动更新。'}}
              </p>
            </div>
          </div>
        </section>
        <section class="detail-section">
          <h3>组织与培养关系</h3>
          <dl class="detail-grid">
            <div><dt>批次</dt><dd>{{display(selectedEmployee.batch_name)}}</dd></div>
            <div><dt>班级</dt><dd>{{display(selectedEmployee.class_name)}}</dd></div>
            <div><dt>所属板块</dt><dd>{{display(selectedEmployee.business_unit_name)}}</dd></div>
            <div>
              <dt>服务站点</dt>
              <dd>
                <el-button
                  link
                  type="primary"
                  :icon="MapLocation"
                  @click="showStationHistory(selectedEmployee)"
                >
                  {{selectedEmployee.station_name||'未分配'}}
                </el-button>
              </dd>
            </div>
            <div>
              <dt>指导老师（技术）</dt>
              <dd>{{display(selectedEmployee.technical_mentor_name)}}</dd>
            </div>
            <div>
              <dt>指导老师（技能）</dt>
              <dd>{{display(selectedEmployee.skill_mentor_name)}}</dd>
            </div>
            <div><dt>入职日期</dt><dd>{{formatDate(selectedEmployee.onboard_date)}}</dd></div>
          </dl>
        </section>
        <section class="detail-section">
          <h3>教育经历</h3>
          <dl class="detail-grid">
            <div><dt>毕业学校</dt><dd>{{display(selectedEmployee.school)}}</dd></div>
            <div><dt>所学专业</dt><dd>{{display(selectedEmployee.major)}}</dd></div>
            <div><dt>学历</dt><dd>{{display(selectedEmployee.education)}}</dd></div>
            <div><dt>特长</dt><dd>{{display(selectedEmployee.speciality)}}</dd></div>
          </dl>
        </section>
        <section class="detail-section">
          <h3>个人信息</h3>
          <dl class="detail-grid">
            <div><dt>身份证号码</dt><dd>{{maskedIdCard(selectedEmployee.id_card)}}</dd></div>
            <div><dt>出生日期</dt><dd>{{formatDate(selectedEmployee.birth_date)}}</dd></div>
            <div><dt>籍贯</dt><dd>{{display(selectedEmployee.native_place)}}</dd></div>
            <div><dt>政治面貌</dt><dd>{{display(selectedEmployee.political_status)}}</dd></div>
            <div><dt>住址（公司）</dt><dd>{{display(selectedEmployee.residence)}}</dd></div>
            <div><dt>兴趣爱好</dt><dd>{{display(selectedEmployee.hobbies)}}</dd></div>
          </dl>
        </section>
        <section class="detail-section">
          <h3>联系方式</h3>
          <dl class="detail-grid">
            <div><dt>私人邮箱</dt><dd>{{display(selectedEmployee.email)}}</dd></div>
            <div><dt>联系方式</dt><dd>{{display(selectedEmployee.phone)}}</dd></div>
            <div><dt>状态</dt><dd>{{statusLabel(selectedEmployee.status)}}</dd></div>
          </dl>
        </section>
        <section class="detail-section notes-detail-section">
          <h3>备注</h3>
          <p class="notes-content">{{selectedEmployee.notes||'暂无备注'}}</p>
        </section>
      </template>

      <el-form v-else label-position="top" class="employee-form">
        <section class="form-section">
          <h3>基本信息</h3>
          <div class="form-grid">
            <el-form-item label="姓名" required>
              <el-input v-model="employeeForm.name"/>
            </el-form-item>
            <el-form-item label="工号" required>
              <el-input v-model="employeeForm.employeeNo"/>
            </el-form-item>
            <el-form-item label="批次">
              <el-select v-model="employeeForm.batchId" clearable>
                <el-option
                  v-for="item in batches"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="班级">
              <el-select v-model="employeeForm.classId" clearable filterable>
                <el-option
                  v-for="item in classOptions"
                  :key="item.id"
                  :label="item.label"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="employeeForm.status">
                <el-option label="在职" value="ACTIVE"/>
                <el-option label="停用" value="INACTIVE"/>
              </el-select>
            </el-form-item>
          </div>
        </section>

        <section class="form-section">
          <h3>组织与培养关系</h3>
          <div class="form-grid">
            <el-form-item label="所属板块">
              <el-select v-model="employeeForm.businessUnitId" clearable filterable>
                <el-option
                  v-for="item in businessUnits"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="服务站点">
              <el-select v-model="employeeForm.stationId" clearable filterable>
                <el-option
                  v-for="item in stations"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="指导老师（技术）">
              <el-select v-model="employeeForm.mentorUserId" clearable filterable>
                <el-option
                  v-for="item in mentors"
                  :key="item.id"
                  :label="item.display_name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="指导老师（技能）">
              <el-select v-model="employeeForm.skillMentorUserId" clearable filterable>
                <el-option
                  v-for="item in mentors"
                  :key="item.id"
                  :label="item.display_name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="入职日期">
              <el-date-picker
                v-model="employeeForm.onboardDate"
                type="date"
                value-format="YYYY-MM-DD"
              />
            </el-form-item>
          </div>
        </section>

        <section class="form-section">
          <h3>教育经历</h3>
          <div class="form-grid">
            <el-form-item label="毕业学校">
              <el-input v-model="employeeForm.school"/>
            </el-form-item>
            <el-form-item label="所学专业">
              <el-input v-model="employeeForm.major"/>
            </el-form-item>
            <el-form-item label="学历">
              <el-select v-model="employeeForm.education" clearable filterable>
                <el-option
                  v-for="item in educationOptions"
                  :key="item.id"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="特长">
              <el-input v-model="employeeForm.speciality"/>
            </el-form-item>
          </div>
        </section>

        <section class="form-section">
          <h3>个人信息</h3>
          <div class="form-grid">
            <el-form-item label="身份证号码">
              <el-input v-model="employeeForm.idCard"/>
            </el-form-item>
            <el-form-item label="出生日期">
              <el-date-picker
                v-model="employeeForm.birthDate"
                type="date"
                value-format="YYYY-MM-DD"
              />
            </el-form-item>
            <el-form-item label="籍贯">
              <el-input v-model="employeeForm.nativePlace"/>
            </el-form-item>
            <el-form-item label="政治面貌">
              <el-select v-model="employeeForm.politicalStatus" clearable filterable>
                <el-option
                  v-for="item in politicalStatusOptions"
                  :key="item.id"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="住址（公司）">
              <el-input v-model="employeeForm.residence"/>
            </el-form-item>
            <el-form-item label="兴趣爱好">
              <el-input v-model="employeeForm.hobbies"/>
            </el-form-item>
          </div>
        </section>

        <section class="form-section">
          <h3>联系方式</h3>
          <div class="form-grid">
            <el-form-item label="私人邮箱">
              <el-input v-model="employeeForm.email"/>
            </el-form-item>
            <el-form-item label="联系方式">
              <el-input v-model="employeeForm.phone"/>
            </el-form-item>
          </div>
        </section>
        <section class="form-section notes-form-section">
          <h3>备注</h3>
          <el-form-item label="人员备注">
            <el-input
              v-model="employeeForm.notes"
              type="textarea"
              :rows="8"
              maxlength="10000"
              show-word-limit
              resize="vertical"
              placeholder="可记录需要长期保留的人员情况、培养补充说明等"
            />
          </el-form-item>
        </section>
      </el-form>

      <template v-if="profileMode!=='view'" #footer>
        <div class="drawer-footer">
          <el-button @click="profileOpen=false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveEmployee">保存</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="historyOpen"
      width="620px"
      class="station-history-dialog"
      destroy-on-close
    >
      <template #header>
        <div v-if="historyEmployee" class="history-heading">
          <el-icon><MapLocation/></el-icon>
          <div>
            <div class="history-title">服务站变更记录</div>
            <div class="history-person">{{historyEmployee.name}} · {{historyEmployee.employee_no}}</div>
          </div>
        </div>
      </template>

      <div v-loading="historyLoading" class="history-body">
        <div v-if="historyEmployee" class="current-station">
          <span>当前服务站点</span>
          <strong>{{historyEmployee.station_name||'未分配'}}</strong>
          <span v-if="historyData.length">累计变更 {{historyData.length}} 次</span>
        </div>

        <div v-if="historyData.length" class="station-timeline">
          <article v-for="(item,index) in historyData" :key="item.id" class="timeline-item">
            <div class="timeline-rail">
              <span class="timeline-dot" :class="{current:index===0}"></span>
            </div>
            <div class="timeline-content">
              <div class="timeline-title">
                <strong>{{item.requested_station_name||'未分配'}}</strong>
                <el-tag v-if="index===0" type="success" effect="light" size="small">当前</el-tag>
              </div>
              <div class="station-route">
                {{item.current_station_name||'未分配'}}
                <span>→</span>
                {{item.requested_station_name||'未分配'}}
              </div>
              <div class="timeline-meta">
                <span>{{formatDateTime(item.effective_at)}}</span>
                <span>审批人：{{item.reviewer_name||'-'}}</span>
              </div>
              <div v-if="item.review_comment" class="timeline-comment">{{item.review_comment}}</div>
            </div>
          </article>
          <article class="timeline-item initial-item">
            <div class="timeline-rail"><span class="timeline-dot initial"></span></div>
            <div class="timeline-content">
              <div class="timeline-title">
                <strong>{{initialStationName}}</strong>
                <el-tag type="info" effect="plain" size="small">初始</el-tag>
              </div>
              <div class="timeline-meta">
                <span>{{formatDate(historyEmployee?.onboard_date)}}</span>
                <span>入职时分配</span>
              </div>
            </div>
          </article>
        </div>

        <el-empty
          v-else-if="!historyLoading"
          :description="historyEmployee?.station_name?'暂无服务站变更记录':'尚未分配服务站'"
          :image-size="88"
        />
      </div>
    </el-dialog>

    <el-dialog
      v-model="bulkOpen"
      :title="bulkMentorType==='TECHNICAL'?'批量设置技术导师':'批量设置技能导师'"
      width="420px"
    >
      <el-form label-position="top">
        <el-form-item label="导师">
          <el-select v-model="bulkMentorId" filterable placeholder="选择导师" style="width:100%">
            <el-option
              v-for="item in mentors"
              :key="item.id"
              :label="item.display_name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bulkOpen=false">取消</el-button>
        <el-button type="primary" :loading="bulkSaving" @click="bindMentor">确认设置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.directory-page{min-width:0;min-height:100%;padding:22px 22px 42px;background:#f5f7fb;color:#172033;box-sizing:border-box}
.directory-header{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:22px}
.title-group{min-width:0;max-width:430px}
.eyebrow{margin-bottom:7px;color:#356bd8;font-size:12px;font-weight:700;letter-spacing:.12em}
.title-group h1{margin:0;font-size:28px;line-height:1.25;letter-spacing:-.02em}
.title-group p{margin:8px 0 0;color:#64748b;font-size:14px}
.header-actions{display:flex;align-items:center;gap:8px;flex-wrap:nowrap;justify-content:flex-end}
.header-actions .el-button{height:38px;border-radius:9px;margin:0}
.data-tools{display:flex;flex-direction:column;align-items:stretch}
.data-tools .el-button{justify-content:flex-start;margin:0;width:100%}
.data-tools :deep(.el-upload){width:100%}
.data-tools .el-divider{margin:8px 0}
.directory-summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:18px}
.directory-summary-card{display:flex;min-width:0;align-items:center;gap:13px;padding:17px 18px;border:1px solid #e7ebf2;border-radius:12px;background:#fff;box-shadow:0 4px 15px rgba(32,51,82,.025)}
.summary-visual{display:grid;width:42px;height:42px;flex:0 0 auto;place-items:center;border-radius:11px}.summary-visual .el-icon{font-size:21px}
.summary-visual.blue{color:#3978d1;background:#eaf3ff}.summary-visual.green{color:#15976d;background:#e9f8f2}.summary-visual.amber{color:#c88716;background:#fff5e5}.summary-visual.violet{color:#7167d9;background:#f0efff}
.directory-summary-card>div{display:grid;min-width:0;grid-template-columns:auto 1fr;align-items:baseline;column-gap:7px}.directory-summary-card small{grid-column:1/-1;color:#7b8798;font-size:12px}.directory-summary-card strong{color:#1f2a3d;font-size:23px;line-height:1.2}.directory-summary-card div span{overflow:hidden;color:#9aa3b1;font-size:11px;text-overflow:ellipsis;white-space:nowrap}
.directory-workspace{overflow:hidden;border:1px solid #e4e9f1;border-radius:14px;background:#fff;box-shadow:0 5px 18px rgba(32,51,82,.04)}
.workspace-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding:19px 20px 14px}
.workspace-heading h2{margin:0;color:#273348;font-size:18px}.workspace-heading p{margin:5px 0 0;color:#8b96a7;font-size:12px}.workspace-count{padding-top:3px;color:#8b96a7;font-size:12px}
.directory-status-tabs{display:flex;gap:5px;padding:0 18px;border-bottom:1px solid #edf0f4;overflow-x:auto;scrollbar-width:none}.directory-status-tabs::-webkit-scrollbar{display:none}
.directory-status-tabs button{position:relative;flex:0 0 auto;padding:10px 12px;border:0;background:transparent;color:#667085;cursor:pointer;font-size:13px}
.directory-status-tabs button span{display:inline-grid;min-width:19px;height:19px;margin-left:4px;place-items:center;border-radius:10px;background:#f1f3f6;color:#7b8798;font-size:10px}
.directory-status-tabs button.active{color:#1976c8;font-weight:700}.directory-status-tabs button.active:after{position:absolute;right:8px;bottom:-1px;left:8px;height:2px;background:#409eff;content:""}
.filter-surface{padding:14px 18px;border-bottom:1px solid #edf0f4;background:#fff}
.filter-grid{display:grid;grid-template-columns:minmax(250px,1.6fr) repeat(4,minmax(120px,1fr)) auto;gap:9px;align-items:center}
.filter-actions{display:flex;gap:8px;white-space:nowrap}
.filter-actions .el-button{margin:0}
.advanced-filter-grid{display:grid;grid-template-columns:repeat(3,minmax(180px,240px));gap:10px;padding-top:12px;border-top:1px solid #edf0f4;margin-top:12px}
.directory-table{background:#fff}
.table-bar{height:48px;padding:0 14px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #edf0f4;color:#667085;font-size:13px}
.table-bar strong{color:#253043;font-size:15px;margin-left:4px}
.table-bar small{margin-left:8px;color:#98a2b3;font-size:11px}
.page-size{width:112px}
.selection-bar{min-height:46px;display:flex;align-items:center;gap:8px;padding:8px 14px;background:#edf6ff;border-bottom:1px solid #d7eafd;color:#475467;font-size:13px}
.selection-bar strong{color:#1769aa}
.people-table{width:calc(100% - 24px);margin:0 12px}
.people-table :deep(.el-table__cell){padding:10px 0}.people-table :deep(th.el-table__cell){padding:9px 0;background:#fafbfd;color:#667085;font-weight:600}
.name-button{display:flex;flex-direction:column;align-items:flex-start;gap:2px;border:0;background:transparent;padding:4px 0;color:inherit;cursor:pointer;text-align:left}
.desktop-name-button{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:0;background:transparent;padding:0;color:#253043;font:inherit;font-weight:600;cursor:pointer;text-align:left}
.desktop-name-button:hover{color:#1769aa}
.employee-name{font-weight:600;color:#253043}
.employee-number{font-size:12px;color:#8a94a3}
.employee-number-line{display:flex;align-items:center;gap:6px}
.employee-number-line .el-tag{height:18px;padding:0 5px}
.employee-class{font-size:12px;color:#667085}
.name-button:hover .employee-name{color:#1769aa}
.station-button{max-width:100%;justify-content:flex-start}
.change-count{margin-left:6px;color:#7b8794;font-size:12px}
.mobile-organization{display:flex;flex-direction:column;gap:2px;line-height:1.35}
.mobile-organization span{font-size:12px;color:#7b8794}
.mobile-organization .el-button{justify-content:flex-start;margin-left:0}
.row-actions{display:flex;justify-content:center;gap:2px}
.row-actions .el-button+.el-button{margin-left:0}
.people-empty{display:flex;min-height:280px;align-items:center;justify-content:center;flex-direction:column;color:#98a2b3}.people-empty>.el-icon{margin-bottom:10px;font-size:38px}.people-empty>strong{color:#596579;font-size:14px}.people-empty>span{margin:7px 0 15px;font-size:11px}
.pagination-row{display:flex;justify-content:flex-end;padding:14px 16px;border-top:1px solid #edf0f4}
.drawer-header{display:flex;align-items:center;justify-content:space-between;gap:16px;width:100%}
.drawer-heading{display:flex;align-items:center;gap:12px;min-width:0}
.employee-avatar{width:46px;height:46px;display:grid;place-items:center;border-radius:7px;background:#e8f2fb;color:#1769aa;font-size:18px;font-weight:700;flex:0 0 auto}
.employee-avatar :deep(img){object-fit:cover}
.portrait-section{padding-top:4px}
.portrait-view{display:flex;align-items:center;gap:18px;padding:14px;border:1px solid #e7ebf1;border-radius:8px;background:#fafbfc}
.employee-portrait{width:96px!important;height:128px!important;flex:0 0 auto;border:1px solid #dfe5ed;border-radius:6px;background:#e8f2fb;color:#1769aa;font-size:28px;font-weight:700}
.employee-portrait :deep(img){object-fit:cover}
.portrait-view strong{color:#344054;font-size:14px}
.portrait-view p{max-width:300px;margin:6px 0 0;color:#7b8798;font-size:12px;line-height:1.65}
.drawer-name{font-size:18px;font-weight:700;color:#253043}
.drawer-number{font-size:12px;color:#7b8794;margin-top:2px}
.drawer-heading .el-tag{margin-left:4px}
.drawer-form-title{font-size:18px;font-weight:700;color:#253043}
.detail-section,.form-section{padding:2px 0 22px;border-bottom:1px solid #edf0f4;margin-bottom:20px}
.detail-section:last-child,.form-section:last-child{border-bottom:0;margin-bottom:0}
.detail-section h3,.form-section h3{font-size:14px;margin:0 0 14px;color:#253043}
.detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px 20px;margin:0}
.detail-grid div{min-width:0}
.detail-grid dt{font-size:12px;color:#8a94a3;margin-bottom:5px}
.detail-grid dd{margin:0;color:#344054;overflow-wrap:anywhere}
.notes-content{min-height:72px;margin:0;padding:13px 14px;border-radius:7px;background:#f7f9fc;color:#475467;line-height:1.75;overflow-wrap:anywhere;white-space:pre-wrap}
.notes-form-section :deep(.el-textarea__inner){line-height:1.7}
.employee-form .form-grid{grid-template-columns:1fr 1fr;gap:0 16px}
.employee-form :deep(.el-select),.employee-form :deep(.el-date-editor){width:100%}
.drawer-footer{display:flex;justify-content:flex-end;gap:8px}
.history-heading{display:flex;align-items:center;gap:12px}
.history-heading .el-icon{width:38px;height:38px;border-radius:6px;background:#e8f2fb;color:#1769aa;font-size:20px}
.history-title{font-size:18px;font-weight:700;color:#253043}
.history-person{font-size:13px;color:#7b8794;margin-top:3px}
.history-body{min-height:160px}
.current-station{display:flex;align-items:center;gap:10px;background:#f5f8fb;border:1px solid #e5e9f0;padding:12px 14px;border-radius:6px;margin-bottom:22px;font-size:13px;color:#7b8794}
.current-station strong{font-size:15px;color:#253043}
.current-station span:last-child{margin-left:auto}
.station-timeline{padding:2px 6px 0}
.timeline-item{display:grid;grid-template-columns:24px minmax(0,1fr);gap:14px;min-height:116px}
.timeline-rail{position:relative;display:flex;justify-content:center}
.timeline-rail:after{content:'';position:absolute;top:19px;bottom:-3px;width:2px;background:#dce3eb}
.timeline-dot{position:relative;z-index:1;width:12px;height:12px;margin-top:5px;border-radius:50%;background:#fff;border:3px solid #409eff;box-shadow:0 0 0 3px #e9f3ff}
.timeline-dot.current{border-color:#32a852;box-shadow:0 0 0 3px #e8f7ec}
.timeline-content{padding-bottom:22px;min-width:0}
.timeline-title{display:flex;align-items:center;gap:8px;color:#253043}
.timeline-title strong{font-size:16px}
.station-route{font-size:13px;color:#596579;margin-top:7px}
.station-route span{color:#98a2b3;margin:0 6px}
.timeline-meta{display:flex;flex-wrap:wrap;gap:6px 16px;font-size:12px;color:#8a94a3;margin-top:7px}
.timeline-comment{font-size:12px;color:#596579;background:#f7f8fa;border-left:2px solid #b9c2ce;padding:6px 9px;margin-top:8px}
.initial-item{min-height:72px}
.initial-item .timeline-rail:after{display:none}
.timeline-dot.initial{border-color:#aeb7c3;box-shadow:0 0 0 3px #f0f2f5}
@media(max-width:1180px){
  .directory-header{align-items:flex-start;flex-direction:column}.title-group{max-width:none}.header-actions{width:100%;flex-wrap:wrap;justify-content:flex-start}
  .filter-grid{grid-template-columns:minmax(220px,1.5fr) repeat(3,minmax(130px,1fr))}
  .filter-actions{grid-column:span 2;justify-content:flex-end}
  .directory-summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
}
@media(max-width:800px){
  .directory-page{padding:18px 12px 80px}
  .directory-header{align-items:flex-start;flex-direction:column}
  .title-group h1{font-size:24px}.title-group p{line-height:1.65}
  .header-actions{width:100%;display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}
  .header-actions .el-button:not(.is-circle){width:100%;margin:0}
  .header-actions .el-badge{width:100%}
  .header-actions .el-badge .el-button{width:100%}
  .directory-summary-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}
  .directory-summary-card{padding:13px 11px}.summary-visual{width:36px;height:36px}.directory-summary-card strong{font-size:19px}
  .workspace-heading{padding:16px 14px 11px}.workspace-heading p{display:none}
  .directory-status-tabs{padding:0 8px}
  .filter-surface{padding:13px 14px}
  .filter-grid{grid-template-columns:1fr 1fr}
  .keyword-filter{grid-column:1/-1}
  .filter-actions{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr}
  .filter-actions .el-button:first-child{grid-column:1/-1}
  .filter-actions .el-button{margin:0}
  .advanced-filter-grid{grid-template-columns:1fr}
  .selection-bar{flex-wrap:wrap}
  .table-bar{padding:0 10px}
  .people-table{width:calc(100% - 16px);margin:0 8px}
  .pagination-row{justify-content:center;padding:12px 8px}
  .detail-grid,.employee-form .form-grid{grid-template-columns:1fr}
  .portrait-view{align-items:flex-start}
  .current-station{align-items:flex-start;flex-wrap:wrap}
  .current-station span:last-child{width:100%;margin-left:0}
  :deep(.employee-drawer){max-width:100%}
  :deep(.station-history-dialog){width:calc(100% - 24px)!important;margin-top:5vh}
  :deep(.el-popover.el-popper){max-width:calc(100vw - 24px)}
}
</style>
