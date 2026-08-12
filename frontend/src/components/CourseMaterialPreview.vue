<script setup lang="ts">
import {onBeforeUnmount,ref,watch} from 'vue'
import {Document} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'

const props=defineProps<{modelValue:boolean;material:any|null}>()
const emit=defineEmits<{"update:modelValue":[value:boolean];learned:[]}>()
const loading=ref(false),sessionId=ref<number|null>(null),pageUrls=ref<string[]>([])
let heartbeatTimer:number|undefined

function releasePages(){
  for(const url of pageUrls.value)URL.revokeObjectURL(url)
  pageUrls.value=[]
}
async function closeSession(){
  if(heartbeatTimer)window.clearInterval(heartbeatTimer)
  heartbeatTimer=undefined
  const id=sessionId.value,materialId=props.material?.id
  sessionId.value=null
  if(id&&materialId)await api.post(`/course-materials/${materialId}/preview-sessions/${id}/close`,undefined,{silentError:true} as any).catch(()=>{})
  releasePages()
}
async function openSession(){
  if(!props.modelValue||!props.material)return
  loading.value=true
  try{
    const result=(await api.post<any,Envelope<{sessionId:number;pageCount:number}>>(`/course-materials/${props.material.id}/preview-sessions`)).data
    sessionId.value=result.sessionId
    const urls:string[]=[]
    for(let page=1;page<=result.pageCount;page++){
      const blob=await api.get<any,Blob>(`/course-materials/${props.material.id}/preview-sessions/${result.sessionId}/pages/${page}`,{responseType:'blob'})
      urls.push(URL.createObjectURL(blob))
    }
    pageUrls.value=urls
    heartbeatTimer=window.setInterval(()=>{
      if(sessionId.value)void api.post(`/course-materials/${props.material.id}/preview-sessions/${sessionId.value}/heartbeat`,undefined,{silentError:true} as any).catch(()=>{})
    },30000)
    emit('learned')
  }catch(error){
    emit('update:modelValue',false)
    await closeSession()
    throw error
  }finally{loading.value=false}
}
async function close(){
  emit('update:modelValue',false)
}
watch(()=>props.modelValue,value=>{if(value)void openSession();else void closeSession()})
onBeforeUnmount(()=>{void closeSession()})
</script>

<template>
  <el-dialog :model-value="modelValue" :title="`课件预览 · ${material?.original_name||''}`" width="min(980px, 96vw)" destroy-on-close :close-on-click-modal="false" @close="close">
    <div v-loading="loading" class="secure-preview" @contextmenu.prevent @dragstart.prevent>
      <el-alert title="课件仅供在线学习，页面已绑定当前账号水印；系统不提供原文件下载。" type="warning" :closable="false" show-icon/>
      <div v-if="pageUrls.length" class="preview-pages">
        <figure v-for="(url,index) in pageUrls" :key="url">
          <img :src="url" :alt="`第 ${index+1} 页`" draggable="false"/>
          <figcaption>第 {{index+1}} / {{pageUrls.length}} 页</figcaption>
        </figure>
      </div>
      <div v-else-if="!loading" class="preview-empty"><el-icon><Document/></el-icon><span>暂无可预览页面</span></div>
    </div>
    <template #footer><el-button type="primary" @click="close">结束学习并关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.secure-preview{min-height:320px}.preview-pages{display:flex;flex-direction:column;gap:20px;margin-top:16px;padding:16px;background:#e9eef5;user-select:none}.preview-pages figure{margin:0;text-align:center}.preview-pages img{display:block;width:100%;height:auto;margin:auto;box-shadow:0 4px 18px rgba(15,23,42,.16);pointer-events:none}.preview-pages figcaption{margin-top:7px;color:#68758a;font-size:12px}.preview-empty{display:flex;min-height:300px;align-items:center;justify-content:center;gap:8px;color:#8995a7}.preview-empty .el-icon{font-size:26px}
</style>
