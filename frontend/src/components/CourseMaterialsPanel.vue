<script setup lang="ts">
import {ref,watch} from 'vue'
import {Delete,Document,Download,UploadFilled,View} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox,type UploadRequestOptions} from 'element-plus'
import {api,type Envelope} from '@/api'
import {formatCourseDate,formatFileSize} from '@/utils/course'

const props=defineProps<{courseId:number|null;courseName:string;canManage:boolean}>()
const emit=defineEmits<{changed:[]}>()
const rows=ref<any[]>([])
const loading=ref(false)
const uploading=ref(false)
const previewOpen=ref(false)
const previewLoading=ref(false)
const previewFile=ref<any>(null)
const previewUrl=ref('')
const previewText=ref('')
const previewType=ref<'PDF'|'IMAGE'|'VIDEO'|'TEXT'|'HTML'|'UNSUPPORTED'>('UNSUPPORTED')

async function load(){
  if(!props.courseId){
    rows.value=[]
    return
  }
  loading.value=true
  try{
    rows.value=(await api.get<any,Envelope<any[]>>(`/courses/${props.courseId}/materials`)).data
  }finally{
    loading.value=false
  }
}

async function upload(options:UploadRequestOptions){
  if(!props.courseId)return
  uploading.value=true
  try{
    const form=new FormData()
    form.append('file',options.file)
    await api.post(`/courses/${props.courseId}/materials`,form)
    ElMessage.success('课件已上传')
    await load()
    emit('changed')
  }finally{
    uploading.value=false
  }
}

function extension(name:string){
  return name.toLowerCase().split('.').pop()||''
}

function sanitizeDocxHtml(html:string){
  const container=document.createElement('div')
  container.innerHTML=html
  container.querySelectorAll('script,style,iframe,object,embed,link,form').forEach(element=>element.remove())
  container.querySelectorAll('*').forEach(element=>{
    Array.from(element.attributes).forEach(attribute=>{
      const name=attribute.name.toLowerCase()
      const value=attribute.value.trim().toLowerCase()
      if(name.startsWith('on')||name==='style'||(name==='href'&&!value.startsWith('#'))||(name==='src'&&!value.startsWith('data:image/'))){
        element.removeAttribute(attribute.name)
      }
    })
  })
  return container.innerHTML
}

async function getBlob(file:any){
  return api.get<any,Blob>(`/course-materials/${file.id}`,{params:{inline:true},responseType:'blob'})
}

function clearPreview(){
  if(previewUrl.value)URL.revokeObjectURL(previewUrl.value)
  previewUrl.value=''
  previewText.value=''
  previewFile.value=null
  previewType.value='UNSUPPORTED'
}

async function preview(file:any){
  clearPreview()
  previewLoading.value=true
  previewFile.value=file
  try{
    const ext=extension(file.original_name)
    if(['ppt','pptx','xls','xlsx','zip'].includes(ext)){
      previewType.value='UNSUPPORTED'
      previewOpen.value=true
      return
    }
    const blob=await getBlob(file)
    if(ext==='pdf'){
      previewType.value='PDF'
      previewUrl.value=URL.createObjectURL(blob)
    }else if(['png','jpg','jpeg'].includes(ext)){
      previewType.value='IMAGE'
      previewUrl.value=URL.createObjectURL(blob)
    }else if(ext==='mp4'){
      previewType.value='VIDEO'
      previewUrl.value=URL.createObjectURL(blob)
    }else if(['txt','md'].includes(ext)){
      previewType.value='TEXT'
      previewText.value=await blob.text()
    }else if(ext==='docx'){
      const mammoth=(await import('mammoth')).default
      const result=await mammoth.convertToHtml({arrayBuffer:await blob.arrayBuffer()},{externalFileAccess:false})
      previewType.value='HTML'
      previewText.value=sanitizeDocxHtml(result.value)
    }else{
      previewType.value='UNSUPPORTED'
    }
    previewOpen.value=true
  }finally{
    previewLoading.value=false
  }
}

async function download(file:any){
  const blob=await api.get<any,Blob>(`/course-materials/${file.id}`,{responseType:'blob'})
  const url=URL.createObjectURL(blob)
  const link=document.createElement('a')
  link.href=url
  link.download=file.original_name
  link.click()
  URL.revokeObjectURL(url)
}

async function remove(file:any){
  await ElMessageBox.confirm(
    `确认删除课件“${file.original_name}”？已安排课程的员工将无法继续查看。`,
    '删除课程课件',
    {confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'}
  )
  await api.delete(`/course-materials/${file.id}`)
  ElMessage.success('课件已删除')
  await load()
  emit('changed')
}

watch(()=>props.courseId,load,{immediate:true})
</script>

<template>
  <section class="material-panel" v-loading="loading">
    <div class="material-head">
      <div>
        <h3>课程课件</h3>
        <p>资料归属于课程，后续场次与已安排员工均可复用。</p>
      </div>
      <el-upload
        v-if="canManage"
        :show-file-list="false"
        :http-request="upload"
        accept=".pdf,.ppt,.pptx,.doc,.docx,.xls,.xlsx,.txt,.md,.png,.jpg,.jpeg,.zip,.mp4"
      >
        <el-button type="primary" plain :icon="UploadFilled" :loading="uploading">上传课件</el-button>
      </el-upload>
    </div>

    <div v-if="rows.length" class="material-list">
      <article v-for="file in rows" :key="file.id" class="material-item">
        <span class="file-icon"><el-icon><Document/></el-icon></span>
        <div class="file-copy">
          <strong>{{file.original_name}}</strong>
          <span>{{formatFileSize(file.size)}} · {{file.uploader_name}} · {{formatCourseDate(file.created_at)}}</span>
        </div>
        <div class="file-actions">
          <el-button link :icon="View" :loading="previewLoading&&previewFile?.id===file.id" @click="preview(file)">预览</el-button>
          <el-button link :icon="Download" @click="download(file)">下载</el-button>
          <el-button v-if="canManage" link type="danger" :icon="Delete" @click="remove(file)">删除</el-button>
        </div>
      </article>
    </div>
    <div v-else class="material-empty">
      <el-icon><Document/></el-icon>
      <strong>暂无课程课件</strong>
      <span>{{canManage?'可上传讲义、演示文稿、视频或参考资料':'课程管理员尚未上传资料'}}</span>
    </div>

    <el-dialog v-model="previewOpen" :title="`课件预览 · ${previewFile?.original_name||''}`" width="min(960px, 94vw)" @closed="clearPreview">
      <iframe v-if="previewType==='PDF'" :src="previewUrl" class="material-preview pdf-preview"/>
      <img v-else-if="previewType==='IMAGE'" :src="previewUrl" class="material-preview image-preview" :alt="previewFile?.original_name"/>
      <video v-else-if="previewType==='VIDEO'" :src="previewUrl" class="material-preview video-preview" controls/>
      <pre v-else-if="previewType==='TEXT'" class="text-preview">{{previewText}}</pre>
      <div v-else-if="previewType==='HTML'" class="docx-preview" v-html="previewText"></div>
      <div v-else class="unsupported-preview">
        <el-icon><Document/></el-icon>
        <strong>此类课件暂不支持在线预览</strong>
        <span>请下载后使用本地 Office 或解压工具查看。</span>
      </div>
      <template #footer>
        <el-button @click="previewOpen=false">关闭</el-button>
        <el-button type="primary" :icon="Download" @click="download(previewFile)">下载课件</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.material-panel{min-height:180px}
.material-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:16px}
.material-head h3{margin:0;color:#263247;font-size:16px}.material-head p{margin:6px 0 0;color:#8993a4;font-size:12px}
.material-list{display:flex;flex-direction:column;gap:9px}
.material-item{display:flex;align-items:center;gap:12px;padding:12px 13px;border:1px solid #e5eaf1;border-radius:10px;background:#fff}
.file-icon{display:grid;flex:0 0 38px;height:38px;place-items:center;border-radius:10px;color:#3079c5;background:#eaf4ff;font-size:19px}
.file-copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:5px}.file-copy strong,.file-copy span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.file-copy strong{color:#344054;font-size:13px}.file-copy span{color:#8c96a6;font-size:11px}
.file-actions{display:flex;align-items:center;white-space:nowrap}.file-actions .el-button{margin:0}
.material-empty{display:flex;min-height:180px;align-items:center;justify-content:center;flex-direction:column;border:1px dashed #dce3ec;border-radius:11px;color:#99a4b3}
.material-empty .el-icon{margin-bottom:9px;font-size:30px}.material-empty strong{color:#5f6b7d;font-size:13px}.material-empty span{margin-top:6px;font-size:11px}
.material-preview{width:100%;border:0}.pdf-preview{height:65vh}.image-preview,.video-preview{display:block;max-height:65vh;object-fit:contain}
.text-preview,.docx-preview{max-height:65vh;margin:0;padding:20px;overflow:auto;border-radius:8px;background:#f8fafc;white-space:pre-wrap;line-height:1.7}
.docx-preview{background:#fff;white-space:normal}.docx-preview :deep(img){max-width:100%}
.unsupported-preview{display:flex;min-height:320px;align-items:center;justify-content:center;flex-direction:column;color:#929dac}.unsupported-preview .el-icon{font-size:44px}.unsupported-preview strong{margin-top:13px;color:#566174}.unsupported-preview span{margin-top:7px;font-size:12px}
@media(max-width:700px){.material-head{align-items:stretch;flex-direction:column}.material-item{align-items:flex-start}.file-actions{flex-direction:column;align-items:flex-end}}
</style>
