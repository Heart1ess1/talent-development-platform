<script setup lang="ts">
import {ref,watch} from 'vue'
import {Delete,Document,UploadFilled,View} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox,type UploadRequestOptions} from 'element-plus'
import CourseMaterialPreview from '@/components/CourseMaterialPreview.vue'
import {api,type Envelope} from '@/api'
import {formatCourseDate,formatFileSize} from '@/utils/course'
import {uploadWithStorageFallback} from '@/storageTransfer'

const props=defineProps<{courseId:number|null;courseName:string;canManage:boolean}>()
const emit=defineEmits<{changed:[]}>()
const rows=ref<any[]>([]),loading=ref(false),uploading=ref(false)
const previewOpen=ref(false),previewFile=ref<any>(null)

async function load(){
  if(!props.courseId){rows.value=[];return}
  loading.value=true
  try{rows.value=(await api.get<any,Envelope<any[]>>(`/courses/${props.courseId}/materials`)).data}
  finally{loading.value=false}
}
async function upload(options:UploadRequestOptions){
  if(!props.courseId)return
  uploading.value=true
  try{
    await uploadWithStorageFallback({
      file:options.file,
      legacyUrl:`/courses/${props.courseId}/materials`,
      ticketUrl:`/courses/${props.courseId}/materials/upload-ticket`,
      completeUrl:ticketId=>`/courses/${props.courseId}/materials/upload-complete/${ticketId}`
    })
    ElMessage.success('课件已上传');await load();emit('changed')
  }finally{uploading.value=false}
}
function preview(file:any){previewFile.value=file;previewOpen.value=true}
async function remove(file:any){
  await ElMessageBox.confirm(`确认删除课件“${file.original_name}”？相关学习记录也会一并删除。`,'删除课程课件',{confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'})
  await api.delete(`/course-materials/${file.id}`)
  ElMessage.success('课件已删除');await load();emit('changed')
}
watch(()=>props.courseId,load,{immediate:true})
</script>

<template>
  <section class="material-panel" v-loading="loading">
    <div class="material-head">
      <div><h3>课程课件</h3><p>仅支持带员工姓名、工号水印的在线预览，不提供原文件下载。</p></div>
      <el-upload v-if="canManage" :show-file-list="false" :http-request="upload" accept=".doc,.docx,.pdf,.ppt,.pptx,.ofd,.png,.jpg,.jpeg">
        <el-button type="primary" plain :icon="UploadFilled" :loading="uploading">上传课件</el-button>
      </el-upload>
    </div>
    <el-alert v-if="canManage" title="支持 Word、PDF、PPT、OFD 和图片课件；系统统一转换为逐页水印图片，不向员工提供原文件下载。" type="info" :closable="false"/>
    <div v-if="rows.length" class="material-list">
      <article v-for="file in rows" :key="file.id" class="material-item">
        <span class="file-icon"><el-icon><Document/></el-icon></span>
        <div class="file-copy"><strong>{{file.original_name}}</strong><span>{{formatFileSize(file.size)}} · {{file.uploader_name}} · {{formatCourseDate(file.created_at)}}</span></div>
        <div class="file-actions"><el-button link :icon="View" @click="preview(file)">安全预览</el-button><el-button v-if="canManage" link type="danger" :icon="Delete" @click="remove(file)">删除</el-button></div>
      </article>
    </div>
    <div v-else class="material-empty"><el-icon><Document/></el-icon><strong>暂无课程课件</strong><span>{{canManage?'上传 Word、PDF、PPT、OFD 或图片课件后，已安排员工即可在线学习':'课程管理员尚未上传课件'}}</span></div>
    <CourseMaterialPreview v-model="previewOpen" :material="previewFile"/>
  </section>
</template>

<style scoped>
.material-panel{min-height:180px}.material-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:16px}.material-head h3{margin:0;color:#263247;font-size:16px}.material-head p{margin:6px 0 0;color:#8993a4;font-size:12px}.material-list{display:flex;flex-direction:column;gap:9px;margin-top:14px}.material-item{display:flex;align-items:center;gap:12px;padding:12px 13px;border:1px solid #e5eaf1;border-radius:10px;background:#fff}.file-icon{display:grid;flex:0 0 38px;height:38px;place-items:center;border-radius:10px;color:#3079c5;background:#eaf4ff;font-size:19px}.file-copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:5px}.file-copy strong,.file-copy span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.file-copy strong{color:#344054;font-size:13px}.file-copy span{color:#8c96a6;font-size:11px}.file-actions{display:flex;align-items:center;white-space:nowrap}.file-actions .el-button{margin:0}.material-empty{display:flex;min-height:180px;align-items:center;justify-content:center;flex-direction:column;border:1px dashed #dce3ec;border-radius:11px;color:#99a4b3}.material-empty .el-icon{margin-bottom:9px;font-size:30px}.material-empty strong{color:#5f6b7d;font-size:13px}.material-empty span{margin-top:6px;font-size:11px}@media(max-width:700px){.material-head{align-items:stretch;flex-direction:column}.material-item{align-items:flex-start}.file-actions{flex-direction:column;align-items:flex-end}}
</style>
