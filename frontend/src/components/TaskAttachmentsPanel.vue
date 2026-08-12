<script setup lang="ts">
import {ref,watch} from 'vue'
import {Delete,Document,Download,Paperclip,UploadFilled,View} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox,type UploadRequestOptions} from 'element-plus'
import {api,type Envelope} from '@/api'
import {formatCourseDate,formatFileSize} from '@/utils/course'
import {uploadWithStorageFallback} from '@/storageTransfer'

const props=withDefaults(defineProps<{
  items?:any[]
  listUrl?:string
  uploadUrl?:string
  deleteUrlPrefix?:string
  canManage?:boolean
  compact?:boolean
  title?:string
  description?:string
}>(),{
  items:()=>[],
  listUrl:'',
  uploadUrl:'',
  deleteUrlPrefix:'',
  canManage:false,
  compact:false,
  title:'任务附件',
  description:'随任务提供的说明、模板和参考资料'
})
const emit=defineEmits<{changed:[any[]]}>()
const rows=ref<any[]>([])
const loading=ref(false)
const uploading=ref(false)
const previewOpen=ref(false)
const previewLoadingId=ref<number|null>(null)
const previewFile=ref<any>(null)
const previewUrl=ref('')
const previewText=ref('')
const previewType=ref<'PDF'|'IMAGE'|'TEXT'|'HTML'|'UNSUPPORTED'>('UNSUPPORTED')

async function load(){
  if(!props.listUrl){
    rows.value=[...props.items]
    return
  }
  loading.value=true
  try{
    rows.value=(await api.get<any,Envelope<any[]>>(props.listUrl)).data
  }finally{
    loading.value=false
  }
}

async function upload(options:UploadRequestOptions){
  if(!props.uploadUrl)return
  uploading.value=true
  try{
    await uploadWithStorageFallback({
      file:options.file,
      legacyUrl:props.uploadUrl,
      ticketUrl:`${props.uploadUrl}/upload-ticket`,
      completeUrl:ticketId=>`${props.uploadUrl}/upload-complete/${ticketId}`
    })
    ElMessage.success('任务附件已上传')
    await load()
    emit('changed',rows.value)
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

async function getBlob(file:any,inline=false){
  return api.get<any,Blob>(`/task-attachments/${file.id}`,{
    params:{inline},
    responseType:'blob'
  })
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
  previewFile.value=file
  previewLoadingId.value=Number(file.id)
  try{
    const ext=extension(file.original_name)
    if(['ppt','pptx','xls','xlsx','zip','doc'].includes(ext)){
      previewType.value='UNSUPPORTED'
      previewOpen.value=true
      return
    }
    const blob=await getBlob(file,true)
    if(ext==='pdf'){
      previewType.value='PDF'
      previewUrl.value=URL.createObjectURL(blob)
    }else if(['png','jpg','jpeg'].includes(ext)){
      previewType.value='IMAGE'
      previewUrl.value=URL.createObjectURL(blob)
    }else if(['txt','md'].includes(ext)){
      previewType.value='TEXT'
      previewText.value=await blob.text()
    }else if(ext==='docx'){
      const mammoth=(await import('mammoth')).default
      const result=await mammoth.convertToHtml(
        {arrayBuffer:await blob.arrayBuffer()},
        {externalFileAccess:false}
      )
      previewType.value='HTML'
      previewText.value=sanitizeDocxHtml(result.value)
    }else{
      previewType.value='UNSUPPORTED'
    }
    previewOpen.value=true
  }finally{
    previewLoadingId.value=null
  }
}

async function download(file:any){
  const blob=await getBlob(file)
  const url=URL.createObjectURL(blob)
  const link=document.createElement('a')
  link.href=url
  link.download=file.original_name
  link.click()
  URL.revokeObjectURL(url)
}

async function remove(file:any){
  if(!props.deleteUrlPrefix)return
  await ElMessageBox.confirm(
    `确认删除附件“${file.original_name}”？已下发任务中的附件快照不会受影响。`,
    '删除任务附件',
    {confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'}
  )
  await api.delete(`${props.deleteUrlPrefix}/${file.id}`)
  ElMessage.success('任务附件已删除')
  await load()
  emit('changed',rows.value)
}

watch(()=>[props.listUrl,props.items] as const,load,{immediate:true,deep:true})
</script>

<template>
  <section class="task-attachment-panel" :class="{compact}" v-loading="loading">
    <div v-if="!compact" class="attachment-head">
      <div>
        <h4><el-icon><Paperclip/></el-icon>{{title}}</h4>
        <p>{{description}}</p>
      </div>
      <el-upload
        v-if="canManage&&uploadUrl"
        :show-file-list="false"
        :http-request="upload"
        accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.png,.jpg,.jpeg,.zip"
      >
        <el-button type="primary" plain :icon="UploadFilled" :loading="uploading">上传附件</el-button>
      </el-upload>
    </div>

    <div v-if="rows.length" class="attachment-list">
      <article v-for="file in rows" :key="file.id" class="attachment-item">
        <span class="attachment-icon"><el-icon><Document/></el-icon></span>
        <div class="attachment-copy">
          <button type="button" :title="`预览 ${file.original_name}`" @click="preview(file)">
            {{file.original_name}}
          </button>
          <span v-if="!compact">
            {{formatFileSize(file.size)}}<template v-if="file.uploader_name"> · {{file.uploader_name}}</template><template v-if="file.created_at"> · {{formatCourseDate(file.created_at)}}</template>
          </span>
        </div>
        <div v-if="!compact" class="attachment-actions">
          <el-button link :icon="View" :loading="previewLoadingId===Number(file.id)" @click="preview(file)">预览</el-button>
          <el-button link :icon="Download" @click="download(file)">下载</el-button>
          <el-button v-if="canManage&&deleteUrlPrefix" link type="danger" :icon="Delete" @click="remove(file)">删除</el-button>
        </div>
      </article>
    </div>
    <div v-else-if="!compact" class="attachment-empty">
      <el-icon><Paperclip/></el-icon>
      <strong>暂无任务附件</strong>
      <span>{{canManage?'可上传任务模板、说明文档或参考资料':'本任务未附带资料'}}</span>
    </div>

    <el-dialog v-model="previewOpen" :title="`附件预览 · ${previewFile?.original_name||''}`" width="min(960px, 94vw)" append-to-body @closed="clearPreview">
      <iframe v-if="previewType==='PDF'" :src="previewUrl" class="attachment-preview pdf-preview"/>
      <img v-else-if="previewType==='IMAGE'" :src="previewUrl" class="attachment-preview image-preview" :alt="previewFile?.original_name"/>
      <pre v-else-if="previewType==='TEXT'" class="text-preview">{{previewText}}</pre>
      <div v-else-if="previewType==='HTML'" class="docx-preview" v-html="previewText"></div>
      <div v-else class="unsupported-preview">
        <el-icon><Document/></el-icon>
        <strong>此类附件暂不支持在线预览</strong>
        <span>请下载后使用本地 Office 或解压工具查看。</span>
      </div>
      <template #footer>
        <el-button @click="previewOpen=false">关闭</el-button>
        <el-button type="primary" :icon="Download" @click="download(previewFile)">下载附件</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.task-attachment-panel{min-height:150px}
.attachment-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:13px}
.attachment-head h4{display:flex;align-items:center;gap:7px;margin:0;color:#344054;font-size:14px}.attachment-head p{margin:6px 0 0;color:#8993a4;font-size:12px}
.attachment-list{display:flex;flex-direction:column;gap:8px}
.attachment-item{display:flex;align-items:center;gap:10px;padding:10px 12px;border:1px solid #e5eaf1;border-radius:9px;background:#fff}
.attachment-icon{display:grid;flex:0 0 34px;height:34px;place-items:center;border-radius:9px;color:#347cc5;background:#edf6ff}
.attachment-copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:4px}.attachment-copy button{overflow:hidden;padding:0;border:0;color:#356fa9;background:none;text-align:left;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}.attachment-copy button:hover{text-decoration:underline}.attachment-copy span{color:#929cab;font-size:11px}
.attachment-actions{display:flex;align-items:center;white-space:nowrap}.attachment-actions .el-button{margin:0}
.attachment-empty{display:flex;min-height:135px;align-items:center;justify-content:center;flex-direction:column;border:1px dashed #dce3ec;border-radius:10px;color:#99a4b3}.attachment-empty .el-icon{font-size:27px}.attachment-empty strong{margin-top:8px;color:#647084;font-size:13px}.attachment-empty span{margin-top:5px;font-size:11px}
.compact{min-height:0}.compact .attachment-list{flex-direction:row;flex-wrap:wrap;gap:6px}.compact .attachment-item{max-width:230px;padding:5px 9px;border-color:#dce8f6;background:#f7fbff}.compact .attachment-icon{display:none}.compact .attachment-copy button{max-width:205px;font-size:12px}
.attachment-preview{width:100%;border:0}.pdf-preview{height:65vh}.image-preview{display:block;max-height:65vh;object-fit:contain}
.text-preview,.docx-preview{max-height:65vh;margin:0;padding:20px;overflow:auto;border-radius:8px;background:#f8fafc;white-space:pre-wrap;line-height:1.7}
.docx-preview{background:#fff;white-space:normal}.docx-preview :deep(img){max-width:100%}
.unsupported-preview{display:flex;min-height:320px;align-items:center;justify-content:center;flex-direction:column;color:#929dac}.unsupported-preview .el-icon{font-size:44px}.unsupported-preview strong{margin-top:13px;color:#566174}.unsupported-preview span{margin-top:7px;font-size:12px}
@media(max-width:700px){.attachment-head{align-items:stretch;flex-direction:column}.attachment-item{align-items:flex-start}.attachment-actions{flex-direction:column;align-items:flex-end}}
</style>
