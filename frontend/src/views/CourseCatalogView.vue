<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {useRouter} from 'vue-router'
import {Collection,Delete,Document,EditPen,FolderOpened,Plus,Refresh,Search,SwitchButton,UserFilled} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import CourseMaterialsPanel from '@/components/CourseMaterialsPanel.vue'
import {api,type Envelope} from '@/api'
import {formatCourseDate,isCourseEnabled,type Course} from '@/utils/course'

const router=useRouter()
const rows=ref<Course[]>([])
const loading=ref(false)
const saving=ref(false)
const keyword=ref('')
const statusFilter=ref('ALL')
const editorOpen=ref(false)
const materialOpen=ref(false)
const editing=ref<Course|null>(null)
const materialCourse=ref<Course|null>(null)
const form=reactive({name:'',description:''})
const summary=reactive({totalCourses:0,enabledCourses:0,upcomingSessions:0,totalMaterials:0,totalEnrollments:0})

const filteredRows=computed(()=>{
  const term=keyword.value.trim().toLowerCase()
  return rows.value.filter(row=>{
    const matchesStatus=statusFilter.value==='ALL'
      ||(statusFilter.value==='ENABLED'&&isCourseEnabled(row))
      ||(statusFilter.value==='DISABLED'&&!isCourseEnabled(row))
    return matchesStatus&&(!term||`${row.name} ${row.description||''} ${row.creator_name||''}`.toLowerCase().includes(term))
  })
})
const tabs=computed(()=>[
  {label:'全部课程',value:'ALL',count:rows.value.length},
  {label:'已启用',value:'ENABLED',count:rows.value.filter(isCourseEnabled).length},
  {label:'已停用',value:'DISABLED',count:rows.value.filter(row=>!isCourseEnabled(row)).length}
])

function normalize(row:any):Course{
  return {
    ...row,
    enabled:row.enabled===undefined?true:row.enabled,
    session_count:Number(row.session_count||0),
    material_count:Number(row.material_count||0),
    enrollment_count:Number(row.enrollment_count||0)
  }
}

function deriveSummary(){
  summary.totalCourses=rows.value.length
  summary.enabledCourses=rows.value.filter(isCourseEnabled).length
  summary.totalMaterials=rows.value.reduce((total,row)=>total+row.material_count,0)
  summary.totalEnrollments=rows.value.reduce((total,row)=>total+Number(row.enrollment_count||0),0)
}

async function load(){
  loading.value=true
  try{
    rows.value=(await api.get<any,Envelope<Course[]>>('/courses',{params:{includeDisabled:true}})).data.map(normalize)
    deriveSummary()
    try{
      Object.assign(summary,(await api.get<any,Envelope<any>>('/courses/summary',{silentError:true} as any)).data)
    }catch{
      // 兼容尚未重启的旧后端。
    }
  }finally{
    loading.value=false
  }
}

function openCreate(){
  editing.value=null
  Object.assign(form,{name:'',description:''})
  editorOpen.value=true
}

function openEdit(row:Course){
  editing.value=row
  Object.assign(form,{name:row.name,description:row.description||''})
  editorOpen.value=true
}

async function save(){
  if(!form.name.trim())return ElMessage.warning('请输入课程名称')
  saving.value=true
  try{
    const payload={name:form.name.trim(),description:form.description.trim()}
    if(editing.value)await api.put(`/courses/${editing.value.id}`,payload)
    else await api.post('/courses',payload)
    editorOpen.value=false
    ElMessage.success(editing.value?'课程已更新':'课程已创建')
    await load()
  }finally{
    saving.value=false
  }
}

async function toggle(row:Course){
  const enabling=!isCourseEnabled(row)
  await ElMessageBox.confirm(
    enabling?'启用后可继续开设培训场次。':'停用后不能新增或调整场次，历史安排与签到记录仍会保留。',
    enabling?'确认启用课程':'确认停用课程',
    {confirmButtonText:enabling?'启用':'停用',cancelButtonText:'取消',type:enabling?'success':'warning'}
  )
  await api.put(`/courses/${row.id}/enabled`,{enabled:enabling})
  ElMessage.success(enabling?'课程已启用':'课程已停用')
  await load()
}

async function remove(row:Course){
  if(row.session_count>0)return ElMessage.warning('该课程已有培训场次，请停用后保留历史')
  if(row.material_count>0)return ElMessage.warning('请先在课件资料中删除全部附件')
  await ElMessageBox.confirm(`确认永久删除课程“${row.name}”？`,'删除课程',{
    confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'
  })
  await api.delete(`/courses/${row.id}`)
  ElMessage.success('课程已删除')
  await load()
}

function openMaterials(row:Course){
  materialCourse.value=row
  materialOpen.value=true
}

function openSessions(row?:Course){
  router.push({path:'/courses/sessions',query:row?{courseId:String(row.id)}:undefined})
}
</script>

<template>
  <div class="course-module-page">
    <header class="course-page-head">
      <div>
        <span class="eyebrow">课程管理 · 课程库</span>
        <h1>课程库</h1>
        <p>维护可复用的课程内容与课件资料，培训日期、地点和参加人员在场次安排中配置。</p>
      </div>
      <div class="course-head-actions">
        <el-button :icon="Collection" @click="openSessions()">场次安排</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建课程</el-button>
      </div>
    </header>

    <section class="course-summary-grid">
      <article class="course-summary-card blue"><span class="course-summary-icon"><el-icon><Collection/></el-icon></span><div><small>全部课程</small><strong>{{summary.totalCourses}}</strong><span>课程资产总数</span></div></article>
      <article class="course-summary-card green"><span class="course-summary-icon"><el-icon><SwitchButton/></el-icon></span><div><small>已启用课程</small><strong>{{summary.enabledCourses}}</strong><span>可继续安排场次</span></div></article>
      <article class="course-summary-card violet"><span class="course-summary-icon"><el-icon><Document/></el-icon></span><div><small>课件资料</small><strong>{{summary.totalMaterials}}</strong><span>跨场次复用</span></div></article>
      <article class="course-summary-card amber"><span class="course-summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>累计课程安排</small><strong>{{summary.totalEnrollments}}</strong><span>员工参训人次</span></div></article>
    </section>

    <section class="course-workspace">
      <div class="course-workspace-head">
        <div><h2>课程资产</h2><p>课程内容和课件统一维护，避免每次开课重复上传。</p></div>
        <span class="course-result-count">共 {{filteredRows.length}} 门课程</span>
      </div>
      <div class="course-tabs">
        <button v-for="tab in tabs" :key="tab.value" type="button" :class="{active:statusFilter===tab.value}" @click="statusFilter=tab.value">{{tab.label}}<span>{{tab.count}}</span></button>
      </div>
      <div class="course-filter-bar course-catalog-filter">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索课程名称、说明或创建人"/>
        <div class="course-filter-actions">
          <el-button :icon="Refresh" @click="load">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建课程</el-button>
        </div>
      </div>

      <div class="course-desktop-table course-catalog-table">
        <el-table :data="filteredRows" v-loading="loading" row-key="id">
          <el-table-column label="课程" min-width="250">
            <template #default="{row}">
              <div class="course-name-cell"><span class="course-mark"><el-icon><Collection/></el-icon></span><div><strong>{{row.name}}</strong><span>{{row.description||'尚未填写课程说明'}}</span></div></div>
            </template>
          </el-table-column>
          <el-table-column label="内容资产" min-width="155">
            <template #default="{row}"><div class="course-meta-cell"><strong>{{row.material_count}} 份课件</strong><span>课程级资料统一复用</span></div></template>
          </el-table-column>
          <el-table-column label="培训使用" min-width="165">
            <template #default="{row}"><div class="course-meta-cell"><strong>{{row.session_count}} 个场次</strong><span>累计安排 {{row.enrollment_count}} 人次</span></div></template>
          </el-table-column>
          <el-table-column label="创建信息" min-width="155">
            <template #default="{row}"><div class="course-meta-cell"><strong>{{row.creator_name||'管理员'}}</strong><span>{{formatCourseDate(row.created_at)}}</span></div></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{row}"><el-tag :type="isCourseEnabled(row)?'success':'info'">{{isCourseEnabled(row)?'已启用':'已停用'}}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="350" fixed="right">
            <template #default="{row}">
              <div class="course-table-actions">
                <el-button type="primary" plain size="small" :icon="FolderOpened" @click="openMaterials(row)">课件资料</el-button>
                <el-button link @click="openSessions(row)">场次</el-button>
                <el-button link :icon="EditPen" @click="openEdit(row)">编辑</el-button>
                <el-button link @click="toggle(row)">{{isCourseEnabled(row)?'停用':'启用'}}</el-button>
                <el-button link type="danger" :icon="Delete" :disabled="row.session_count>0||row.material_count>0" @click="remove(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
          <template #empty><div class="course-empty"><el-icon><Collection/></el-icon><strong>暂无课程</strong><span>先建立课程内容，再上传课件并安排培训场次</span><el-button class="course-empty-action" type="primary" :icon="Plus" @click="openCreate">新建课程</el-button></div></template>
        </el-table>
      </div>

      <div class="course-mobile-list" v-loading="loading">
        <div v-if="filteredRows.length===0" class="course-empty course-mobile-empty">
          <el-icon><Collection/></el-icon>
          <strong>暂无课程</strong>
          <span>先建立课程内容，再上传课件并安排培训场次</span>
          <el-button class="course-empty-action" type="primary" :icon="Plus" @click="openCreate">新建课程</el-button>
        </div>
        <article v-for="row in filteredRows" :key="row.id" class="mobile-course-card">
          <div class="mobile-course-head"><span class="course-mark"><el-icon><Collection/></el-icon></span><div><strong>{{row.name}}</strong><span>{{row.description||'尚未填写课程说明'}}</span></div><el-tag :type="isCourseEnabled(row)?'success':'info'" size="small">{{isCourseEnabled(row)?'启用':'停用'}}</el-tag></div>
          <div class="mobile-course-metrics"><span><strong>{{row.material_count}}</strong> 份课件</span><span><strong>{{row.session_count}}</strong> 个场次</span><span><strong>{{row.enrollment_count}}</strong> 人次</span></div>
          <div class="mobile-course-actions"><el-button type="primary" plain @click="openMaterials(row)">课件</el-button><el-button @click="openSessions(row)">场次</el-button><el-button @click="openEdit(row)">编辑</el-button></div>
        </article>
      </div>
    </section>

    <el-dialog v-model="editorOpen" :title="editing?'编辑课程':'新建课程'" width="min(560px, 92vw)" destroy-on-close>
      <div class="course-dialog-intro"><span><el-icon><Collection/></el-icon></span><div><strong>建立可复用的课程资产</strong><p>课程只描述稳定内容，培训时间与参加人员在场次中安排。</p></div></div>
      <el-form label-position="top">
        <el-form-item label="课程名称" required><el-input v-model="form.name" maxlength="128" show-word-limit placeholder="例如：售后服务流程基础"/></el-form-item>
        <el-form-item label="课程说明"><el-input v-model="form.description" type="textarea" :rows="4" maxlength="4000" show-word-limit placeholder="说明学习目标、适用对象和主要内容"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="editorOpen=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{editing?'保存修改':'创建课程'}}</el-button></template>
    </el-dialog>

    <el-drawer v-model="materialOpen" :title="materialCourse?.name||'课程课件'" size="min(720px, 94vw)">
      <CourseMaterialsPanel :course-id="materialCourse?.id||null" :course-name="materialCourse?.name||''" can-manage @changed="load"/>
    </el-drawer>
  </div>
</template>

<style src="@/styles/courses.css"></style>
