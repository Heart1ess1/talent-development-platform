<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {Calendar,Clock,Collection,Delete,EditPen,Location,Plus,Refresh,Search,UserFilled} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {formatCourseDate,isCourseEnabled,sessionStatus,type Course,type CourseSession} from '@/utils/course'
import {loadDictionaryValues,type DictionaryOption} from '@/utils/masterData'

const route=useRoute()
const router=useRouter()
const courses=ref<Course[]>([])
const sessions=ref<CourseSession[]>([])
const employees=ref<any[]>([])
const classOptions=ref<DictionaryOption[]>([])
const enrollmentClassId=ref<number|null>(null)
const enrollments=ref<any[]>([])
const loading=ref(false)
const saving=ref(false)
const enrollLoading=ref(false)
const editorOpen=ref(false)
const enrollmentOpen=ref(false)
const editing=ref<CourseSession|null>(null)
const selectedSession=ref<CourseSession|null>(null)
const selectedEmployees=ref<number[]>([])
const filters=reactive({keyword:'',courseId:null as number|null,status:'ALL'})
const form=reactive<any>({courseId:null,title:'',location:'',hours:1,startsAt:'',endsAt:'',checkinStartsAt:'',checkinEndsAt:''})

const filteredSessions=computed(()=>{
  const term=filters.keyword.trim().toLowerCase()
  return sessions.value.filter(row=>{
    const state=sessionStatus(row).key
    return (!filters.courseId||row.course_id===filters.courseId)
      &&(filters.status==='ALL'||state===filters.status)
      &&(!term||`${row.course_name} ${row.title} ${row.location||''}`.toLowerCase().includes(term))
  })
})
const tabs=computed(()=>[
  {label:'全部场次',value:'ALL',count:sessions.value.length},
  {label:'未开始',value:'UPCOMING',count:sessions.value.filter(row=>sessionStatus(row).key==='UPCOMING').length},
  {label:'进行中',value:'ONGOING',count:sessions.value.filter(row=>sessionStatus(row).key==='ONGOING').length},
  {label:'已结束',value:'FINISHED',count:sessions.value.filter(row=>sessionStatus(row).key==='FINISHED').length}
])
const enrolledIds=computed(()=>new Set(enrollments.value.map(item=>item.employee_id)))
const availableEmployees=computed(()=>employees.value.filter(item=>(!enrollmentClassId.value||item.class_id===enrollmentClassId.value)&&!enrolledIds.value.has(item.id)))
const totalEnrollments=computed(()=>sessions.value.reduce((total,row)=>total+row.enrollment_count,0))
const totalAttendance=computed(()=>sessions.value.reduce((total,row)=>total+row.attendance_count,0))

function normalize(row:any,courseNames:Map<number,string>):CourseSession{
  const courseId=Number(row.course_id)
  return {
    ...row,
    course_id:courseId,
    course_name:row.course_name||courseNames.get(courseId)||`课程 #${courseId}`,
    hours:Number(row.hours||0),
    enrollment_count:Number(row.enrollment_count||0),
    attendance_count:Number(row.attendance_count||0)
  }
}
function inputDate(value:string){return String(value||'').replace(' ','T').substring(0,19)}
function pad(value:number){return String(value).padStart(2,'0')}
function localValue(date:Date,hour:number,minute=0){
  return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(hour)}:${pad(minute)}:00`
}

async function load(){
  loading.value=true
  try{
    const [courseResponse,sessionResponse]=await Promise.all([
      api.get<any,Envelope<Course[]>>('/courses'),
      api.get<any,Envelope<CourseSession[]>>('/sessions')
    ])
    const courseNames=new Map(courseResponse.data.map(row=>[Number(row.id),row.name]))
    courses.value=courseResponse.data.map(row=>({
      ...row,
      enabled:row.enabled===undefined?true:row.enabled,
      session_count:Number(row.session_count||0),
      material_count:Number(row.material_count||0)
    }))
    sessions.value=sessionResponse.data.map(row=>normalize(row,courseNames))
  }finally{
    loading.value=false
  }
}

function openCreate(){
  const next=new Date()
  next.setDate(next.getDate()+1)
  editing.value=null
  Object.assign(form,{
    courseId:filters.courseId||courses.value.find(isCourseEnabled)?.id||null,
    title:'',location:'',hours:1,
    startsAt:localValue(next,9),
    endsAt:localValue(next,11),
    checkinStartsAt:localValue(next,8,45),
    checkinEndsAt:localValue(next,9,15)
  })
  editorOpen.value=true
}

function openEdit(row:CourseSession){
  editing.value=row
  Object.assign(form,{
    courseId:row.course_id,title:row.title,location:row.location||'',hours:row.hours||1,
    startsAt:inputDate(row.starts_at),endsAt:inputDate(row.ends_at),
    checkinStartsAt:inputDate(row.checkin_starts_at),checkinEndsAt:inputDate(row.checkin_ends_at)
  })
  editorOpen.value=true
}

async function save(){
  if(!form.courseId)return ElMessage.warning('请选择课程')
  if(!form.title.trim())return ElMessage.warning('请输入场次名称')
  if(!form.startsAt||!form.endsAt||!form.checkinStartsAt||!form.checkinEndsAt)return ElMessage.warning('请完整填写场次与签到时间')
  saving.value=true
  try{
    const payload={...form,title:form.title.trim(),location:form.location.trim()}
    if(editing.value)await api.put(`/sessions/${editing.value.id}`,payload)
    else{
      const result=await api.post<any,Envelope<any>>('/sessions',payload)
      ElMessageBox.alert(`签到码：${result.data.checkinCode}`,'场次创建成功',{
        confirmButtonText:'知道了',type:'success'
      } as any)
    }
    editorOpen.value=false
    if(editing.value)ElMessage.success('场次已更新')
    await load()
  }finally{
    saving.value=false
  }
}

async function remove(row:CourseSession){
  if(row.attendance_count>0)return ElMessage.warning('该场次已有签到记录，不能删除')
  await ElMessageBox.confirm(
    `删除“${row.title}”将同时移除 ${row.enrollment_count} 条未签到人员安排。`,
    '删除培训场次',
    {confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'}
  )
  await api.delete(`/sessions/${row.id}`)
  ElMessage.success('场次已删除')
  await load()
}

async function openEnrollment(row:CourseSession){
  selectedSession.value=row
  selectedEmployees.value=[]
  enrollmentClassId.value=null
  enrollmentOpen.value=true
  enrollLoading.value=true
  try{
    const [enrollResponse,employeeResponse,classValues]=await Promise.all([
      api.get<any,Envelope<any[]>>(`/sessions/${row.id}/enrollments`),
      employees.value.length
        ?Promise.resolve({data:employees.value} as Envelope<any[]>)
        :api.get<any,Envelope<any>>('/employees',{params:{size:100}}),
      classOptions.value.length?Promise.resolve(classOptions.value):loadDictionaryValues('CLASS')
    ])
    enrollments.value=enrollResponse.data
    if(!employees.value.length)employees.value=(employeeResponse.data.records||employeeResponse.data).filter((item:any)=>item.status==='ACTIVE')
    classOptions.value=classValues
  }finally{
    enrollLoading.value=false
  }
}

async function addEnrollments(){
  if(!selectedSession.value||!selectedEmployees.value.length)return ElMessage.warning('请选择需要安排的员工')
  const response=await api.post<any,Envelope<number>>(`/sessions/${selectedSession.value.id}/enroll`,{employeeIds:selectedEmployees.value})
  ElMessage.success(`已新增 ${response.data} 条课程安排`)
  selectedEmployees.value=[]
  await openEnrollment(selectedSession.value)
  await load()
}

async function removeEnrollment(row:any){
  if(row.attendance_status)return ElMessage.warning('该员工已有签到记录，不能移除')
  await ElMessageBox.confirm(`确认移除 ${row.employee_name} 的本场培训安排？`,'移除人员',{
    confirmButtonText:'确认移除',cancelButtonText:'取消',type:'warning'
  })
  await api.delete(`/sessions/${selectedSession.value?.id}/enrollments/${row.employee_id}`)
  await openEnrollment(selectedSession.value!)
  await load()
}

async function copyCode(code?:string){
  if(!code)return
  await navigator.clipboard.writeText(code)
  ElMessage.success('签到码已复制')
}

onMounted(async()=>{
  const courseId=Number(route.query.courseId)
  if(Number.isFinite(courseId)&&courseId>0)filters.courseId=courseId
  await load()
})
</script>

<template>
  <div class="course-module-page">
    <header class="course-page-head">
      <div><span class="eyebrow">课程管理 · 场次安排</span><h1>场次安排</h1><p>将课程落地为具体培训，统一维护时间、地点、签到窗口和参加人员。</p></div>
      <div class="course-head-actions"><el-button :icon="Collection" @click="router.push('/courses/manage')">返回课程库</el-button><el-button type="primary" :icon="Plus" :disabled="!courses.length" @click="openCreate">新建场次</el-button></div>
    </header>

    <section class="course-summary-grid">
      <article class="course-summary-card blue"><span class="course-summary-icon"><el-icon><Calendar/></el-icon></span><div><small>全部场次</small><strong>{{sessions.length}}</strong><span>历史与未来培训</span></div></article>
      <article class="course-summary-card green"><span class="course-summary-icon"><el-icon><Clock/></el-icon></span><div><small>待开展</small><strong>{{tabs[1]?.count||0}}</strong><span>需要继续安排人员</span></div></article>
      <article class="course-summary-card violet"><span class="course-summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>累计安排</small><strong>{{totalEnrollments}}</strong><span>员工参训人次</span></div></article>
      <article class="course-summary-card amber"><span class="course-summary-icon"><el-icon><Collection/></el-icon></span><div><small>完成签到</small><strong>{{totalAttendance}}</strong><span>已形成签到记录</span></div></article>
    </section>

    <section class="course-workspace">
      <div class="course-workspace-head"><div><h2>培训场次</h2><p>先设置场次，再安排员工；签到码仅在签到窗口内有效。</p></div><span class="course-result-count">共 {{filteredSessions.length}} 个场次</span></div>
      <div class="course-tabs"><button v-for="tab in tabs" :key="tab.value" type="button" :class="{active:filters.status===tab.value}" @click="filters.status=tab.value">{{tab.label}}<span>{{tab.count}}</span></button></div>
      <div class="course-filter-bar">
        <el-input v-model="filters.keyword" :prefix-icon="Search" clearable placeholder="搜索课程、场次或地点"/>
        <el-select v-model="filters.courseId" clearable filterable placeholder="全部课程"><el-option v-for="course in courses" :key="course.id" :label="course.name" :value="course.id"/></el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建场次</el-button>
      </div>

      <div class="course-desktop-table">
        <el-table :data="filteredSessions" v-loading="loading" row-key="id">
          <el-table-column label="课程与场次" min-width="240"><template #default="{row}"><div class="course-name-cell"><span class="course-mark"><el-icon><Calendar/></el-icon></span><div><strong>{{row.course_name}}</strong><span>{{row.title}}</span></div></div></template></el-table-column>
          <el-table-column label="培训时间" min-width="200"><template #default="{row}"><div class="course-meta-cell"><strong>{{formatCourseDate(row.starts_at)}}</strong><span>至 {{formatCourseDate(row.ends_at)}}</span></div></template></el-table-column>
          <el-table-column label="地点 / 学时" min-width="140"><template #default="{row}"><div class="course-meta-cell"><strong>{{row.location||'待定'}}</strong><span>{{row.hours||'-'}} 学时</span></div></template></el-table-column>
          <el-table-column label="人员与签到" min-width="170"><template #default="{row}"><div class="session-progress"><div><span>已签到 {{row.attendance_count}}</span><strong>{{row.enrollment_count}} 人</strong></div><el-progress :percentage="row.enrollment_count?Math.round(row.attendance_count/row.enrollment_count*100):0" :stroke-width="6" :show-text="false"/></div></template></el-table-column>
          <el-table-column label="签到码" width="125"><template #default="{row}"><button class="checkin-code" type="button" title="点击复制" @click="copyCode(row.checkin_code)">{{row.checkin_code}}</button></template></el-table-column>
          <el-table-column label="状态" width="95"><template #default="{row}"><el-tag :type="sessionStatus(row).type">{{sessionStatus(row).label}}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="275" fixed="right"><template #default="{row}"><div class="course-table-actions"><el-button type="primary" plain size="small" :icon="UserFilled" @click="openEnrollment(row)">安排人员</el-button><el-button link :icon="EditPen" @click="openEdit(row)">编辑</el-button><el-button link type="danger" :icon="Delete" :disabled="row.attendance_count>0" @click="remove(row)">删除</el-button></div></template></el-table-column>
          <template #empty><div class="course-empty"><el-icon><Calendar/></el-icon><strong>暂无培训场次</strong><span>选择已启用课程并安排第一次培训</span><el-button type="primary" @click="openCreate">新建场次</el-button></div></template>
        </el-table>
      </div>

      <div class="course-mobile-list" v-loading="loading">
        <article v-for="row in filteredSessions" :key="row.id" class="mobile-course-card">
          <div class="mobile-course-head"><span class="course-mark"><el-icon><Calendar/></el-icon></span><div><strong>{{row.course_name}}</strong><span>{{row.title}} · {{row.location||'地点待定'}}</span></div><el-tag :type="sessionStatus(row).type" size="small">{{sessionStatus(row).label}}</el-tag></div>
          <div class="mobile-course-metrics"><span><strong>{{row.enrollment_count}}</strong> 人安排</span><span><strong>{{row.attendance_count}}</strong> 人签到</span><span>{{formatCourseDate(row.starts_at).slice(5)}}</span></div>
          <div class="mobile-course-actions"><el-button type="primary" plain @click="openEnrollment(row)">安排人员</el-button><el-button @click="copyCode(row.checkin_code)">签到码</el-button><el-button @click="openEdit(row)">编辑</el-button></div>
        </article>
      </div>
    </section>

    <el-dialog v-model="editorOpen" :title="editing?'编辑培训场次':'新建培训场次'" width="min(700px, 94vw)" destroy-on-close>
      <div class="course-dialog-intro"><span><el-icon><Calendar/></el-icon></span><div><strong>设置一次具体培训</strong><p>签到窗口建议覆盖开课前 15 分钟至开课后 15 分钟。</p></div></div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="课程" required><el-select v-model="form.courseId" filterable placeholder="选择已启用课程"><el-option v-for="course in courses.filter(isCourseEnabled)" :key="course.id" :label="course.name" :value="course.id"/></el-select></el-form-item>
          <el-form-item label="场次名称" required><el-input v-model="form.title" maxlength="128" placeholder="例如：第一期线下培训"/></el-form-item>
          <el-form-item label="培训地点"><el-input v-model="form.location" maxlength="128" :prefix-icon="Location" placeholder="教室、会议室或线上会议"/></el-form-item>
          <el-form-item label="课程学时"><el-input-number v-model="form.hours" :min="0.5" :max="999" :step="0.5"/></el-form-item>
          <el-form-item label="开始时间" required><el-date-picker v-model="form.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item>
          <el-form-item label="结束时间" required><el-date-picker v-model="form.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item>
          <el-form-item label="签到开始" required><el-date-picker v-model="form.checkinStartsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item>
          <el-form-item label="签到结束" required><el-date-picker v-model="form.checkinEndsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="editorOpen=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{editing?'保存修改':'创建场次'}}</el-button></template>
    </el-dialog>

    <el-drawer v-model="enrollmentOpen" title="场次人员安排" size="min(640px, 94vw)">
      <template v-if="selectedSession">
        <div class="enrollment-drawer-head"><div><strong>{{selectedSession.course_name}}</strong><span>场次：{{selectedSession.title}} · {{formatCourseDate(selectedSession.starts_at)}}</span></div><el-tag>{{enrollments.length}} 人</el-tag></div>
        <div class="enroll-form">
          <el-select v-model="enrollmentClassId" clearable filterable placeholder="按班级筛选员工"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
          <el-select v-model="selectedEmployees" multiple filterable collapse-tags collapse-tags-tooltip placeholder="选择需要参加的员工"><el-option v-for="person in availableEmployees" :key="person.id" :label="`${person.name} · ${person.employeeNo||person.employee_no}`" :value="person.id"/></el-select>
          <el-button type="primary" :disabled="!selectedEmployees.length" @click="addEnrollments">加入场次</el-button>
        </div>
        <div class="enrollment-list" v-loading="enrollLoading">
          <article v-for="person in enrollments" :key="person.employee_id" class="enrollment-item"><el-avatar :size="36">{{person.employee_name.slice(-1)}}</el-avatar><div><strong>{{person.employee_name}}</strong><span>{{person.employee_no}} · {{person.attendance_status?'已签到':'待签到'}}</span></div><el-button link type="danger" :disabled="Boolean(person.attendance_status)" @click="removeEnrollment(person)">移除</el-button></article>
          <div v-if="!enrollLoading&&!enrollments.length" class="course-empty"><el-icon><UserFilled/></el-icon><strong>尚未安排人员</strong><span>从上方选择员工加入本场培训</span></div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style src="@/styles/courses.css"></style>
