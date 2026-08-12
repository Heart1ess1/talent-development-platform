<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {CircleCheck,Clock,Document,Search,View} from '@element-plus/icons-vue'
import CourseMaterialPreview from '@/components/CourseMaterialPreview.vue'
import {api,type Envelope} from '@/api'
import {formatCourseDate,formatFileSize} from '@/utils/course'

const rows=ref<any[]>([]),loading=ref(false),keyword=ref(''),status=ref('ALL')
const previewOpen=ref(false),previewFile=ref<any>(null)
const filtered=computed(()=>rows.value.filter(row=>{
  const term=keyword.value.trim().toLowerCase()
  const match=!term||`${row.course_name} ${row.original_name}`.toLowerCase().includes(term)
  return match&&(status.value==='ALL'||(status.value==='LEARNED')===Boolean(row.learned))
}))
const learnedCount=computed(()=>rows.value.filter(row=>Boolean(row.learned)).length)
async function load(){loading.value=true;try{rows.value=(await api.get<any,Envelope<any[]>>('/course-materials/learning')).data}finally{loading.value=false}}
function open(row:any){previewFile.value=row;previewOpen.value=true}
onMounted(load)
</script>

<template>
  <div class="learning-page">
    <header class="learning-head"><div><span>课程学习 · 课件中心</span><h1>课件学习</h1><p>查看已安排给你的课件。课件仅支持在线预览，并带有你的姓名和工号水印。</p></div></header>
    <section class="learning-summary">
      <article><el-icon><Document/></el-icon><div><small>全部课件</small><strong>{{rows.length}}</strong></div></article>
      <article><el-icon><CircleCheck/></el-icon><div><small>已学习</small><strong>{{learnedCount}}</strong></div></article>
      <article><el-icon><Clock/></el-icon><div><small>未学习</small><strong>{{rows.length-learnedCount}}</strong></div></article>
    </section>
    <section class="learning-workspace">
      <div class="toolbar"><el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索课程或课件名称"/><el-radio-group v-model="status"><el-radio-button value="ALL">全部</el-radio-button><el-radio-button value="UNLEARNED">未学习</el-radio-button><el-radio-button value="LEARNED">已学习</el-radio-button></el-radio-group></div>
      <div v-loading="loading" class="material-grid">
        <article v-for="row in filtered" :key="row.id" class="learning-card">
          <div class="card-icon"><el-icon><Document/></el-icon></div>
          <div class="card-copy"><small>{{row.course_name}}</small><strong>{{row.original_name}}</strong><span>{{formatFileSize(row.size)}} · {{formatCourseDate(row.created_at)}}</span></div>
          <el-tag :type="row.learned?'success':'warning'" effect="plain">{{row.learned?'已学习':'未学习'}}</el-tag>
          <el-button type="primary" plain :icon="View" @click="open(row)">{{row.learned?'继续学习':'开始学习'}}</el-button>
        </article>
        <el-empty v-if="!filtered.length&&!loading" description="暂无符合条件的课件"/>
      </div>
    </section>
    <CourseMaterialPreview v-model="previewOpen" :material="previewFile" @learned="load"/>
  </div>
</template>

<style scoped>
.learning-page{padding:24px}.learning-head{margin-bottom:18px}.learning-head span{color:#3976bd;font-size:12px}.learning-head h1{margin:6px 0;color:#263247}.learning-head p{margin:0;color:#7b8798}.learning-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:16px}.learning-summary article{display:flex;align-items:center;gap:14px;padding:18px;border:1px solid #e5eaf1;border-radius:12px;background:#fff}.learning-summary .el-icon{padding:11px;border-radius:10px;color:#3976bd;background:#edf5ff;font-size:24px}.learning-summary small,.learning-summary strong{display:block}.learning-summary small{color:#8490a2}.learning-summary strong{margin-top:4px;color:#263247;font-size:24px}.learning-workspace{padding:18px;border:1px solid #e5eaf1;border-radius:12px;background:#fff}.toolbar{display:flex;justify-content:space-between;gap:12px;margin-bottom:16px}.toolbar .el-input{max-width:340px}.material-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;min-height:180px}.learning-card{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;padding:16px;border:1px solid #e4e9f0;border-radius:11px}.learning-card>.el-button{grid-column:2/4;justify-self:end}.card-icon{display:grid;width:44px;height:44px;place-items:center;border-radius:10px;color:#3976bd;background:#edf5ff;font-size:22px}.card-copy{display:flex;min-width:0;flex-direction:column;gap:4px}.card-copy small{color:#728096}.card-copy strong{overflow:hidden;color:#303c50;text-overflow:ellipsis;white-space:nowrap}.card-copy span{color:#919bac;font-size:11px}@media(max-width:800px){.learning-page{padding:14px}.learning-summary,.material-grid{grid-template-columns:1fr}.toolbar{align-items:stretch;flex-direction:column}.toolbar .el-input{max-width:none}}
</style>
