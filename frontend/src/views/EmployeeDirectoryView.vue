<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue'
import {useRouter} from 'vue-router'
import {
  Clock,
  Download,
  Edit,
  MapLocation,
  Plus,
  Refresh,
  Search,
  Setting,
  UploadFilled,
  View
} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'

type DirectoryRow=Record<string,any>
type StationChange=Record<string,any>
type ProfileMode='view'|'edit'|'create'

const auth=useAuthStore()
const router=useRouter()
const canWrite=computed(()=>auth.can('employee:write'))
const canExport=computed(()=>auth.can('employee:export'))
const canMaster=computed(()=>auth.can('master:manage'))
const canViewHistory=computed(()=>auth.can('employee:read'))

const rows=ref<DirectoryRow[]>([])
const total=ref(0)
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

const isNarrow=ref(false)
let narrowMedia:MediaQueryList|undefined

const filters=reactive({
  keyword:'',
  batchId:null as number|null,
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
  status:'ACTIVE'
})
const employeeForm=reactive(emptyEmployeeForm())

const appliedFilterCount=computed(
  ()=>Object.values(filters).filter(value=>value!==''&&value!==null).length
)
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
    const response=await api.get<any,Envelope<any>>('/employee-directory',{params:requestParams()})
    rows.value=response.data.records
    total.value=response.data.total
    selectedRows.value=[]
  }finally{
    loading.value=false
  }
}

async function loadMasters(){
  const [batchResponse,unitResponse,stationResponse,mentorResponse]=await Promise.all([
    api.get<any,Envelope<any[]>>('/batches'),
    api.get<any,Envelope<any[]>>('/business-units'),
    api.get<any,Envelope<any[]>>('/stations'),
    api.get<any,Envelope<any[]>>('/mentors')
  ])
  batches.value=batchResponse.data
  businessUnits.value=unitResponse.data
  stations.value=stationResponse.data
  mentors.value=mentorResponse.data
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

function fillEmployeeForm(row:DirectoryRow){
  Object.assign(employeeForm,{
    employeeNo:row.employee_no||'',
    name:row.name||'',
    batchId:row.batch_id??null,
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
      ElMessage.success('人员信息已更新')
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
    download(blob,'人员信息.xlsx')
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
        <h2>人员信息</h2>
        <span class="result-count">{{total}} 人</span>
      </div>
      <div class="header-actions">
        <el-tooltip content="刷新" placement="bottom">
          <el-button :icon="Refresh" circle aria-label="刷新" :loading="loading" @click="load"/>
        </el-tooltip>
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
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option label="在职" value="ACTIVE"/>
          <el-option label="停用" value="INACTIVE"/>
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
              v-for="item in ['专科','本科','硕士','博士']"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
        </div>
      </el-collapse-transition>
    </section>

    <section class="directory-table">
      <div class="table-bar">
        <span>查询结果 <strong>{{total}}</strong></span>
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
                  v-if="isNarrow"
                  :type="row.status==='ACTIVE'?'success':'info'"
                  effect="light"
                  size="small"
                >
                  {{statusLabel(row.status)}}
                </el-tag>
              </span>
            </button>
          </template>
        </el-table-column>
        <el-table-column v-else label="姓名" width="110" fixed show-overflow-tooltip>
          <template #default="{row}">
            <button class="desktop-name-button" type="button" @click="showDetails(row)">
              {{row.name}}
            </button>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="工号" width="120" show-overflow-tooltip>
          <template #default="{row}">{{row.employee_no}}</template>
        </el-table-column>

        <el-table-column v-if="!isNarrow" label="批次" width="88">
          <template #default="{row}">{{display(row.batch_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="所属板块" width="110">
          <template #default="{row}">{{display(row.business_unit_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="服务站点" width="150">
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
        <el-table-column v-if="!isNarrow" label="指导老师（技术）" width="160">
          <template #default="{row}">{{display(row.technical_mentor_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="指导老师（技能）" width="160">
          <template #default="{row}">{{display(row.skill_mentor_name)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="毕业学校" min-width="130" show-overflow-tooltip>
          <template #default="{row}">{{display(row.school)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="所学专业" min-width="120" show-overflow-tooltip>
          <template #default="{row}">{{display(row.major)}}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="学历" width="76" show-overflow-tooltip>
          <template #default="{row}">{{display(row.education)}}</template>
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
              <el-tooltip v-if="canWrite" content="编辑人员" placement="top">
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
        <el-table-column v-if="!isNarrow&&canWrite" label="编辑" width="62" fixed="right" align="center">
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

    <el-drawer
      v-model="profileOpen"
      :size="drawerSize"
      class="employee-drawer"
      destroy-on-close
    >
      <template #header>
        <div class="drawer-header">
          <div v-if="selectedEmployee" class="drawer-heading">
            <div class="employee-avatar">{{selectedEmployee.name?.substring(0,1)}}</div>
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
            v-if="profileMode==='view'&&canWrite"
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
        <section class="detail-section">
          <h3>组织与培养关系</h3>
          <dl class="detail-grid">
            <div><dt>批次</dt><dd>{{display(selectedEmployee.batch_name)}}</dd></div>
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
              <el-select v-model="employeeForm.education" clearable allow-create filterable>
                <el-option
                  v-for="item in ['专科','本科','硕士','博士']"
                  :key="item"
                  :label="item"
                  :value="item"
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
              <el-input v-model="employeeForm.politicalStatus"/>
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
.directory-page{min-width:0}
.directory-header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}
.title-group{display:flex;align-items:baseline;gap:12px}
.title-group h2{margin:0;font-size:24px;line-height:1.3}
.result-count{font-size:13px;color:#7b8794}
.header-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end}
.data-tools{display:flex;flex-direction:column;align-items:stretch}
.data-tools .el-button{justify-content:flex-start;margin:0;width:100%}
.data-tools :deep(.el-upload){width:100%}
.data-tools .el-divider{margin:8px 0}
.filter-surface{background:#fff;border:1px solid #e5e9f0;border-radius:6px;padding:14px 16px;margin-bottom:14px}
.filter-grid{display:grid;grid-template-columns:minmax(240px,1.5fr) repeat(4,minmax(125px,1fr)) auto;gap:10px;align-items:center}
.filter-actions{display:flex;gap:8px;white-space:nowrap}
.advanced-filter-grid{display:grid;grid-template-columns:repeat(3,minmax(180px,240px));gap:10px;padding-top:12px;border-top:1px solid #edf0f4;margin-top:12px}
.directory-table{background:#fff;border:1px solid #e5e9f0;border-radius:6px;overflow:hidden}
.table-bar{height:48px;padding:0 14px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #edf0f4;color:#667085;font-size:13px}
.table-bar strong{color:#253043;font-size:15px;margin-left:4px}
.page-size{width:112px}
.selection-bar{min-height:46px;display:flex;align-items:center;gap:8px;padding:8px 14px;background:#edf6ff;border-bottom:1px solid #d7eafd;color:#475467;font-size:13px}
.selection-bar strong{color:#1769aa}
.people-table{width:100%}
.name-button{display:flex;flex-direction:column;align-items:flex-start;gap:2px;border:0;background:transparent;padding:4px 0;color:inherit;cursor:pointer;text-align:left}
.desktop-name-button{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:0;background:transparent;padding:0;color:#253043;font:inherit;font-weight:600;cursor:pointer;text-align:left}
.desktop-name-button:hover{color:#1769aa}
.employee-name{font-weight:600;color:#253043}
.employee-number{font-size:12px;color:#8a94a3}
.employee-number-line{display:flex;align-items:center;gap:6px}
.employee-number-line .el-tag{height:18px;padding:0 5px}
.name-button:hover .employee-name{color:#1769aa}
.station-button{max-width:100%;justify-content:flex-start}
.change-count{margin-left:6px;color:#7b8794;font-size:12px}
.mobile-organization{display:flex;flex-direction:column;gap:2px;line-height:1.35}
.mobile-organization span{font-size:12px;color:#7b8794}
.mobile-organization .el-button{justify-content:flex-start;margin-left:0}
.row-actions{display:flex;justify-content:center;gap:2px}
.row-actions .el-button+.el-button{margin-left:0}
.pagination-row{display:flex;justify-content:flex-end;padding:14px 16px;border-top:1px solid #edf0f4}
.drawer-header{display:flex;align-items:center;justify-content:space-between;gap:16px;width:100%}
.drawer-heading{display:flex;align-items:center;gap:12px;min-width:0}
.employee-avatar{width:40px;height:40px;display:grid;place-items:center;border-radius:6px;background:#e8f2fb;color:#1769aa;font-size:18px;font-weight:700;flex:0 0 auto}
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
@media(max-width:1280px){
  .filter-grid{grid-template-columns:minmax(220px,1.5fr) repeat(3,minmax(130px,1fr))}
  .filter-actions{grid-column:span 2;justify-content:flex-end}
}
@media(max-width:800px){
  .directory-header{align-items:flex-start;flex-direction:column}
  .header-actions{width:100%;display:grid;grid-template-columns:auto 1fr 1fr}
  .header-actions .el-button:not(.is-circle){width:100%;margin:0}
  .header-actions .el-badge{width:100%}
  .header-actions .el-badge .el-button{width:100%}
  .filter-grid{grid-template-columns:1fr 1fr}
  .keyword-filter{grid-column:1/-1}
  .filter-actions{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr}
  .filter-actions .el-button:first-child{grid-column:1/-1}
  .filter-actions .el-button{margin:0}
  .advanced-filter-grid{grid-template-columns:1fr}
  .selection-bar{flex-wrap:wrap}
  .table-bar{padding:0 10px}
  .pagination-row{justify-content:center;padding:12px 8px}
  .detail-grid,.employee-form .form-grid{grid-template-columns:1fr}
  .current-station{align-items:flex-start;flex-wrap:wrap}
  .current-station span:last-child{width:100%;margin-left:0}
  :deep(.employee-drawer){max-width:100%}
  :deep(.station-history-dialog){width:calc(100% - 24px)!important;margin-top:5vh}
  :deep(.el-popover.el-popper){max-width:calc(100vw - 24px)}
}
</style>
