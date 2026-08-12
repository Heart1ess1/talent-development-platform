<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {Document,Search,View} from '@element-plus/icons-vue'
import CourseMaterialPreview from '@/components/CourseMaterialPreview.vue'
import {api,type Envelope} from '@/api'
import {formatCourseDate,formatFileSize} from '@/utils/course'

const rows=ref<any[]>([]),learners=ref<any[]>([]),loading=ref(false),detailLoading=ref(false),keyword=ref('')
const detailOpen=ref(false),selected=ref<any>(null),previewOpen=ref(false),previewFile=ref<any>(null)
const filtered=computed(()=>rows.value.filter(row=>!keyword.value||`${row.course_name} ${row.original_name}`.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const totals=computed(()=>({materials:rows.value.length,assigned:rows.value.reduce((sum,row)=>sum+Number(row.assigned_count||0),0),learned:rows.value.reduce((sum,row)=>sum+Number(row.learned_count||0),0)}))
async function load(){loading.value=true;try{rows.value=(await api.get<any,Envelope<any[]>>('/course-materials/manage')).data}finally{loading.value=false}}
async function openDetail(row:any){selected.value=row;detailOpen.value=true;detailLoading.value=true;try{learners.value=(await api.get<any,Envelope<any[]>>(`/course-materials/manage/${row.id}/learners`)).data}finally{detailLoading.value=false}}
function openPreview(row:any){previewFile.value=row;previewOpen.value=true}
function duration(seconds:number){const value=Number(seconds||0);const hours=Math.floor(value/3600),minutes=Math.floor(value%3600/60),rest=value%60;return hours?`${hours}小时${minutes}分`:minutes?`${minutes}分${rest}秒`:`${rest}秒`}
onMounted(load)
</script>

<template>
  <div class="manage-page">
    <header class="manage-head"><div><span>课程管理 · 学习跟踪</span><h1>课件管理</h1><p>按课件查看覆盖人数与学习完成情况，员工的学习次数和时长仅在管理端可见。</p></div></header>
    <section class="manage-summary"><article><small>课件总数</small><strong>{{totals.materials}}</strong></article><article><small>应学习人次</small><strong>{{totals.assigned}}</strong></article><article><small>已学习人次</small><strong>{{totals.learned}}</strong></article><article><small>未学习人次</small><strong>{{totals.assigned-totals.learned}}</strong></article></section>
    <section class="manage-workspace">
      <div class="toolbar"><div><h2>课件学习概览</h2><p>同一课程多个培训场次的员工按工号去重统计。</p></div><el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索课程或课件"/></div>
      <el-table :data="filtered" v-loading="loading" row-key="id" empty-text="暂无课件">
        <el-table-column label="课件" min-width="260"><template #default="s"><div class="material-name"><span><el-icon><Document/></el-icon></span><div><strong>{{s.row.original_name}}</strong><small>{{s.row.course_name}} · {{formatFileSize(s.row.size)}} · {{formatCourseDate(s.row.created_at)}}</small></div></div></template></el-table-column>
        <el-table-column prop="assigned_count" label="应学习" width="90" align="center"/><el-table-column prop="learned_count" label="已学习" width="90" align="center"/><el-table-column prop="unlearned_count" label="未学习" width="90" align="center"/>
        <el-table-column label="完成率" width="160"><template #default="s"><el-progress :percentage="s.row.assigned_count?Math.round(s.row.learned_count*100/s.row.assigned_count):0"/></template></el-table-column>
        <el-table-column label="操作" width="160"><template #default="s"><el-button link :icon="View" @click="openPreview(s.row)">预览</el-button><el-button link type="primary" @click="openDetail(s.row)">学习明细</el-button></template></el-table-column>
      </el-table>
    </section>
    <el-drawer v-model="detailOpen" size="min(900px, 94vw)">
      <template #header><div><h3>{{selected?.original_name}}</h3><p>{{selected?.course_name}} · 已学习 {{selected?.learned_count||0}} 人，未学习 {{selected?.unlearned_count||0}} 人</p></div></template>
      <el-table :data="learners" v-loading="detailLoading" empty-text="该课件尚未安排员工">
        <el-table-column prop="employee_no" label="工号" width="120"/><el-table-column prop="employee_name" label="姓名" min-width="100"/><el-table-column prop="batch_name" label="批次" min-width="100"/><el-table-column prop="station_name" label="板块" min-width="110"/>
        <el-table-column label="学习状态" width="90"><template #default="s"><el-tag :type="s.row.learned?'success':'warning'" effect="plain">{{s.row.learned?'已学习':'未学习'}}</el-tag></template></el-table-column>
        <el-table-column prop="view_count" label="学习次数" width="90" align="center"/><el-table-column label="学习时长" width="120"><template #default="s">{{duration(s.row.duration_seconds)}}</template></el-table-column>
      </el-table>
    </el-drawer>
    <CourseMaterialPreview v-model="previewOpen" :material="previewFile"/>
  </div>
</template>

<style scoped>
.manage-page{padding:24px}.manage-head{margin-bottom:18px}.manage-head span{color:#3976bd;font-size:12px}.manage-head h1{margin:6px 0;color:#263247}.manage-head p,.toolbar p,.el-drawer p{margin:0;color:#7b8798}.manage-summary{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:16px}.manage-summary article{padding:18px;border:1px solid #e5eaf1;border-radius:12px;background:#fff}.manage-summary small,.manage-summary strong{display:block}.manage-summary small{color:#8490a2}.manage-summary strong{margin-top:5px;color:#263247;font-size:26px}.manage-workspace{padding:18px;border:1px solid #e5eaf1;border-radius:12px;background:#fff}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-bottom:14px}.toolbar h2,.el-drawer h3{margin:0 0 5px;color:#2c384b}.toolbar .el-input{width:300px}.material-name{display:flex;align-items:center;gap:11px}.material-name>span{display:grid;width:38px;height:38px;place-items:center;border-radius:9px;color:#3976bd;background:#edf5ff}.material-name div{display:flex;min-width:0;flex-direction:column;gap:4px}.material-name strong,.material-name small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.material-name small{color:#8995a6}@media(max-width:800px){.manage-page{padding:14px}.manage-summary{grid-template-columns:repeat(2,1fr)}.toolbar{align-items:stretch;flex-direction:column}.toolbar .el-input{width:auto}}
</style>
