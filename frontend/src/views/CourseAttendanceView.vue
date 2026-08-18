<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {useRouter} from 'vue-router'
import {Calendar,Collection,Download,Plus,Refresh,Search,UploadFilled,UserFilled} from '@element-plus/icons-vue'
import {ElMessage,type UploadRequestOptions} from 'element-plus'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {attendanceSourceLabel,formatCourseDate,type Course,type CourseSession} from '@/utils/course'
import {loadDictionaryValues,type DictionaryOption} from '@/utils/masterData'

const auth=useAuthStore()
const router=useRouter()
const isEmployee=computed(()=>auth.user?.role==='EMPLOYEE')
const canManageAttendance=computed(()=>auth.can('attendance:manage'))
const rows=ref<any[]>([])
const courses=ref<Course[]>([])
const sessions=ref<CourseSession[]>([])
const employees=ref<any[]>([])
const classOptions=ref<DictionaryOption[]>([])
const loading=ref(false)
const importing=ref(false)
const submitting=ref(false)
const manualOpen=ref(false)
const filters=reactive({keyword:'',classId:null as number|null,courseId:null as number|null,sessionId:null as number|null,source:'',dateRange:[] as string[]})
const manual=reactive({sessionId:null as number|null,employeeId:null as number|null,remark:''})
const manualClassId=ref<number|null>(null)
const summary=reactive({totalAttendance:0,todayAttendance:0,selfAttendance:0,manualAttendance:0})

const availableSessions=computed(()=>filters.courseId?sessions.value.filter(item=>item.course_id===filters.courseId):sessions.value)
const filteredManualEmployees=computed(()=>employees.value.filter(item=>!manualClassId.value||item.class_id===manualClassId.value))

function params(){
  return {
    keyword:filters.keyword.trim()||undefined,
    classId:filters.classId||undefined,
    courseId:filters.courseId||undefined,
    sessionId:filters.sessionId||undefined,
    source:filters.source||undefined,
    dateFrom:filters.dateRange[0]||undefined,
    dateTo:filters.dateRange[1]||undefined
  }
}

function deriveSummary(){
  const today=new Date().toISOString().slice(0,10)
  summary.totalAttendance=rows.value.length
  summary.todayAttendance=rows.value.filter(row=>String(row.checked_at).slice(0,10)===today).length
  summary.selfAttendance=rows.value.filter(row=>row.source==='SELF').length
  summary.manualAttendance=rows.value.filter(row=>row.source==='MANUAL').length
}

async function load(){
  loading.value=true
  try{
    const [attendanceResponse,courseResponse,sessionResponse,classValues]=await Promise.all([
      api.get<any,Envelope<any[]>>('/attendance',{params:params()}),
      api.get<any,Envelope<Course[]>>('/courses'),
      api.get<any,Envelope<CourseSession[]>>('/sessions'),
      loadDictionaryValues('CLASS')
    ])
    rows.value=attendanceResponse.data
    courses.value=courseResponse.data
    sessions.value=sessionResponse.data.map(row=>({...row,course_id:Number(row.course_id)}))
    classOptions.value=classValues
    deriveSummary()
    try{
      Object.assign(summary,(await api.get<any,Envelope<any>>('/attendance/summary',{silentError:true} as any)).data)
    }catch{
      // 兼容尚未重启的旧后端。
    }
    if(canManageAttendance.value&&!employees.value.length){
      const response=await api.get<any,Envelope<any>>('/employees',{params:{size:100}})
      employees.value=response.data.records.filter((item:any)=>item.status==='ACTIVE')
    }
  }finally{
    loading.value=false
  }
}

function search(){load()}
function reset(){
  Object.assign(filters,{keyword:'',classId:null,courseId:null,sessionId:null,source:'',dateRange:[]})
  load()
}

function openManual(){
  Object.assign(manual,{sessionId:null,employeeId:null,remark:''})
  manualClassId.value=null
  manualOpen.value=true
}

async function submitManual(){
  if(!manual.sessionId||!manual.employeeId)return ElMessage.warning('请选择培训场次和员工')
  submitting.value=true
  try{
    await api.post('/attendance/manual',manual)
    manualOpen.value=false
    ElMessage.success('签到已补录')
    await load()
  }finally{
    submitting.value=false
  }
}

function saveBlob(blob:Blob,name:string){
  const url=URL.createObjectURL(blob)
  const link=document.createElement('a')
  link.href=url
  link.download=name
  link.click()
  URL.revokeObjectURL(url)
}

async function downloadTemplate(){
  const blob=await api.get<any,Blob>('/imports/attendance/template',{responseType:'blob'})
  saveBlob(blob,'签到导入模板.xlsx')
}

async function importAttendance(options:UploadRequestOptions){
  importing.value=true
  try{
    const form=new FormData()
    form.append('file',options.file)
    const response=await api.post<any,Envelope<any>>('/imports/attendance',form)
    if(response.data.errors?.length)ElMessage.warning(`导入未写入：发现 ${response.data.errors.length} 条数据问题`)
    else ElMessage.success(`已导入 ${response.data.imported} 条签到记录`)
    await load()
  }finally{
    importing.value=false
  }
}
</script>

<template>
  <div class="course-module-page">
    <header class="course-page-head">
      <div><span class="eyebrow">{{isEmployee?'课程学习':'课程管理'}} · 签到记录</span><h1>{{isEmployee?'我的签到记录':'签到管理'}}</h1><p>{{isEmployee?'查看个人课程签到结果和补录说明。':'集中查询签到结果，处理人工补录和批量导入。'}}</p></div>
      <div class="course-head-actions">
        <el-button v-if="isEmployee" :icon="Collection" @click="router.push('/courses/my')">返回我的课程</el-button>
        <template v-else>
          <el-button :icon="Download" @click="downloadTemplate">下载导入模板</el-button>
          <el-upload v-if="canManageAttendance" :show-file-list="false" :http-request="importAttendance" accept=".xlsx"><el-button :icon="UploadFilled" :loading="importing">导入签到</el-button></el-upload>
          <el-button v-if="canManageAttendance" type="primary" :icon="Plus" @click="openManual">人工补录</el-button>
        </template>
      </div>
    </header>

    <section class="course-summary-grid">
      <article class="course-summary-card blue"><span class="course-summary-icon"><el-icon><Collection/></el-icon></span><div><small>签到记录</small><strong>{{summary.totalAttendance}}</strong><span>当前数据范围</span></div></article>
      <article class="course-summary-card green"><span class="course-summary-icon"><el-icon><Calendar/></el-icon></span><div><small>今日签到</small><strong>{{summary.todayAttendance}}</strong><span>今日新增记录</span></div></article>
      <article class="course-summary-card violet"><span class="course-summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>员工自助</small><strong>{{summary.selfAttendance}}</strong><span>签到码完成</span></div></article>
      <article class="course-summary-card amber"><span class="course-summary-icon"><el-icon><Plus/></el-icon></span><div><small>人工补录</small><strong>{{summary.manualAttendance}}</strong><span>管理员核实补录</span></div></article>
    </section>

    <section class="course-workspace">
      <div class="course-workspace-head"><div><h2>签到明细</h2><p>记录签到来源、时间和补录说明，便于后续核验。</p></div><span class="course-result-count">共 {{rows.length}} 条记录</span></div>
      <div v-if="!isEmployee" class="course-filter-bar">
        <el-input v-model="filters.keyword" :prefix-icon="Search" clearable placeholder="员工姓名、工号或课程" @keyup.enter="search"/>
        <el-select v-model="filters.classId" clearable filterable placeholder="全部班级"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
        <el-select v-model="filters.courseId" clearable filterable placeholder="全部课程" @change="filters.sessionId=null"><el-option v-for="course in courses" :key="course.id" :label="course.name" :value="course.id"/></el-select>
        <el-select v-model="filters.sessionId" clearable filterable placeholder="全部场次"><el-option v-for="session in availableSessions" :key="session.id" :label="session.title" :value="session.id"/></el-select>
        <el-select v-model="filters.source" clearable placeholder="签到来源"><el-option label="员工签到" value="SELF"/><el-option label="人工补录" value="MANUAL"/></el-select>
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" range-separator="至"/>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
      </div>

      <div class="course-desktop-table">
        <el-table :data="rows" v-loading="loading" row-key="id">
          <el-table-column v-if="!isEmployee" label="员工" min-width="170"><template #default="{row}"><div class="attendance-person"><el-avatar :size="36">{{row.employee_name?.slice(-1)}}</el-avatar><div><strong>{{row.employee_name}}</strong><span>{{row.employee_no}}</span></div></div></template></el-table-column>
          <el-table-column label="课程 / 场次" min-width="220"><template #default="{row}"><div class="course-meta-cell"><strong>{{row.course_name}}</strong><span>{{row.session_title}} · {{row.location||'地点待定'}}</span></div></template></el-table-column>
          <el-table-column label="签到结果" min-width="150"><template #default="{row}"><div class="attendance-status"><el-tag type="success" effect="light">已签到</el-tag><span>{{attendanceSourceLabel(row.source)}}</span></div></template></el-table-column>
          <el-table-column label="签到时间" min-width="160"><template #default="{row}">{{formatCourseDate(row.checked_at)}}</template></el-table-column>
          <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip><template #default="{row}">{{row.remark||'-'}}</template></el-table-column>
          <template #empty><div class="course-empty"><el-icon><Collection/></el-icon><strong>暂无签到记录</strong><span>{{isEmployee?'完成课程签到后会在这里形成记录':'调整筛选条件或进行人工补录'}}</span></div></template>
        </el-table>
      </div>

      <div class="course-mobile-list" v-loading="loading">
        <article v-for="row in rows" :key="row.id" class="mobile-course-card">
          <div class="mobile-course-head"><span class="course-mark"><el-icon><Collection/></el-icon></span><div><strong>{{isEmployee?row.course_name:row.employee_name}}</strong><span>{{row.session_title}} · {{row.location||'地点待定'}}</span></div><el-tag type="success" size="small">已签到</el-tag></div>
          <div class="mobile-course-metrics"><span>{{attendanceSourceLabel(row.source)}}</span><span>{{formatCourseDate(row.checked_at)}}</span></div>
          <p v-if="row.remark" class="course-result-count">{{row.remark}}</p>
        </article>
      </div>
    </section>

    <el-dialog v-model="manualOpen" title="人工补录签到" width="min(540px, 92vw)" destroy-on-close>
      <div class="course-dialog-intro"><span><el-icon><Plus/></el-icon></span><div><strong>核实后补录签到结果</strong><p>未在场次人员名单中的员工会自动加入该场次，并保留补录来源。</p></div></div>
      <el-form label-position="top">
        <el-form-item label="培训场次" required><el-select v-model="manual.sessionId" filterable placeholder="选择场次"><el-option v-for="session in sessions" :key="session.id" :label="`${session.course_name} · ${session.title}`" :value="session.id"/></el-select></el-form-item>
        <el-form-item label="班级筛选"><el-select v-model="manualClassId" clearable filterable placeholder="全部班级" @change="manual.employeeId=null"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select></el-form-item>
        <el-form-item label="员工" required><el-select v-model="manual.employeeId" filterable placeholder="搜索员工"><el-option v-for="person in filteredManualEmployees" :key="person.id" :label="`${person.name} · ${person.employeeNo||person.employee_no}`" :value="person.id"/></el-select></el-form-item>
        <el-form-item label="补录说明"><el-input v-model="manual.remark" type="textarea" :rows="3" maxlength="255" show-word-limit placeholder="例如：现场签到设备异常，经培训负责人确认"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="manualOpen=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitManual">确认补录</el-button></template>
    </el-dialog>
  </div>
</template>

<style src="@/styles/courses.css"></style>
