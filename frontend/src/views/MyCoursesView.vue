<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {useRouter} from 'vue-router'
import {Calendar,Collection,Document,Location,Position,Right,UserFilled} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import CourseMaterialsPanel from '@/components/CourseMaterialsPanel.vue'
import {api,type Envelope} from '@/api'
import {formatCourseDate,sessionStatus,type Course,type CourseSession} from '@/utils/course'

const router=useRouter()
const courses=ref<Course[]>([])
const sessions=ref<CourseSession[]>([])
const attendance=ref<any[]>([])
const loading=ref(false)
const submitting=ref(false)
const code=ref('')
const materialOpen=ref(false)
const materialCourse=ref<Course|null>(null)
const statusFilter=ref('ALL')

const attendedSessionIds=computed(()=>new Set(attendance.value.map(item=>Number(item.session_id))))
const filteredSessions=computed(()=>sessions.value.filter(row=>statusFilter.value==='ALL'||sessionStatus(row).key===statusFilter.value))
const upcomingCount=computed(()=>sessions.value.filter(row=>sessionStatus(row).key==='UPCOMING').length)
const materialCount=computed(()=>courses.value.reduce((total,row)=>total+Number(row.material_count||0),0))
const tabs=computed(()=>[
  {label:'全部安排',value:'ALL',count:sessions.value.length},
  {label:'未开始',value:'UPCOMING',count:upcomingCount.value},
  {label:'进行中',value:'ONGOING',count:sessions.value.filter(row=>sessionStatus(row).key==='ONGOING').length},
  {label:'已结束',value:'FINISHED',count:sessions.value.filter(row=>sessionStatus(row).key==='FINISHED').length}
])

async function load(){
  loading.value=true
  try{
    const [courseResponse,sessionResponse,attendanceResponse]=await Promise.all([
      api.get<any,Envelope<Course[]>>('/courses'),
      api.get<any,Envelope<CourseSession[]>>('/sessions'),
      api.get<any,Envelope<any[]>>('/attendance')
    ])
    courses.value=courseResponse.data.map(row=>({...row,material_count:Number(row.material_count||0),session_count:Number(row.session_count||0)}))
    sessions.value=sessionResponse.data.map(row=>({...row,course_id:Number(row.course_id),enrollment_count:Number(row.enrollment_count||0),attendance_count:Number(row.attendance_count||0)}))
    attendance.value=attendanceResponse.data
  }finally{
    loading.value=false
  }
}

async function checkin(){
  if(!/^\d{6}$/.test(code.value))return ElMessage.warning('请输入 6 位数字签到码')
  submitting.value=true
  try{
    await api.post('/attendance/checkin',{code:code.value})
    code.value=''
    ElMessage.success('签到成功')
    await load()
  }finally{
    submitting.value=false
  }
}

function openMaterials(session:CourseSession){
  materialCourse.value=courses.value.find(course=>course.id===session.course_id)||{
    id:session.course_id,name:session.course_name,enabled:true,session_count:0,material_count:0
  }
  materialOpen.value=true
}

onMounted(load)
</script>

<template>
  <div class="course-module-page">
    <header class="course-page-head">
      <div><span class="eyebrow">课程学习 · 我的课程</span><h1>我的课程</h1><p>查看培训安排、课程课件并在有效时间内完成签到。</p></div>
      <div class="course-head-actions"><el-button :icon="Collection" @click="router.push('/courses/attendance')">签到记录</el-button></div>
    </header>

    <section class="sign-panel">
      <span><el-icon><Position/></el-icon></span>
      <div class="sign-copy"><strong>课程签到</strong><p>签到码仅在管理员设置的时间窗口内有效，请勿代签。</p></div>
      <div class="sign-input"><el-input v-model="code" maxlength="6" inputmode="numeric" placeholder="输入 6 位签到码" @keyup.enter="checkin"/><el-button type="primary" :loading="submitting" @click="checkin">确认签到</el-button></div>
    </section>

    <section class="course-summary-grid" style="margin-top:14px">
      <article class="course-summary-card blue"><span class="course-summary-icon"><el-icon><Calendar/></el-icon></span><div><small>课程安排</small><strong>{{sessions.length}}</strong><span>全部培训场次</span></div></article>
      <article class="course-summary-card green"><span class="course-summary-icon"><el-icon><Right/></el-icon></span><div><small>待参加</small><strong>{{upcomingCount}}</strong><span>即将开始的培训</span></div></article>
      <article class="course-summary-card violet"><span class="course-summary-icon"><el-icon><Document/></el-icon></span><div><small>可用课件</small><strong>{{materialCount}}</strong><span>已安排课程资料</span></div></article>
      <article class="course-summary-card amber"><span class="course-summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>已签到</small><strong>{{attendance.length}}</strong><span>个人签到记录</span></div></article>
    </section>

    <section class="course-workspace">
      <div class="course-workspace-head"><div><h2>培训日程</h2><p>按时间查看本人已被安排的课程场次。</p></div><span class="course-result-count">共 {{filteredSessions.length}} 个场次</span></div>
      <div class="course-tabs"><button v-for="tab in tabs" :key="tab.value" type="button" :class="{active:statusFilter===tab.value}" @click="statusFilter=tab.value">{{tab.label}}<span>{{tab.count}}</span></button></div>
      <div class="my-course-grid" v-loading="loading" style="padding:14px">
        <article v-for="session in filteredSessions" :key="session.id" class="my-session-card">
          <div class="my-session-head"><div><strong>{{session.title}}</strong><span>{{session.course_name}}</span></div><el-tag :type="attendedSessionIds.has(session.id)?'success':sessionStatus(session).type">{{attendedSessionIds.has(session.id)?'已签到':sessionStatus(session).label}}</el-tag></div>
          <div class="my-session-time">
            <div><small>培训时间</small><strong>{{formatCourseDate(session.starts_at)}} 至 {{formatCourseDate(session.ends_at).slice(11)}}</strong></div>
            <div><small>{{session.delivery_mode==='ONLINE'?'会议链接':'地点'}} / 学时</small><strong class="session-location"><el-link v-if="session.delivery_mode==='ONLINE'&&session.meeting_url" :href="session.meeting_url" target="_blank" rel="noopener noreferrer" type="primary">{{session.meeting_url}}</el-link><template v-else>{{session.location||'待定'}}</template><span> · {{session.hours||'-'}} 学时</span></strong></div>
          </div>
          <div class="my-session-actions"><span class="course-result-count"><el-icon><Location/></el-icon> 签到 {{formatCourseDate(session.checkin_starts_at).slice(11)}}—{{formatCourseDate(session.checkin_ends_at).slice(11)}}</span><el-button link :icon="Document" @click="openMaterials(session)">查看课件</el-button></div>
        </article>
        <div v-if="!loading&&!filteredSessions.length" class="course-empty" style="grid-column:1/-1"><el-icon><Calendar/></el-icon><strong>暂无匹配的课程安排</strong><span>新的培训场次安排后会显示在这里</span></div>
      </div>
    </section>

    <el-drawer v-model="materialOpen" :title="materialCourse?.name||'课程课件'" size="min(720px, 94vw)">
      <CourseMaterialsPanel :course-id="materialCourse?.id||null" :course-name="materialCourse?.name||''" :can-manage="false"/>
    </el-drawer>
  </div>
</template>

<style src="@/styles/courses.css"></style>
