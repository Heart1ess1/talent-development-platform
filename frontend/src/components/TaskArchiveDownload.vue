<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {FolderOpened} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {archiveErrorMessage,downloadTaskArchive,type ArchiveUpdate} from '@/taskArchive'
import {formatFileSize} from '@/utils/course'

const props=defineProps<{taskId:number;disabled?:boolean}>()
const open=ref(false)
const busy=ref(false)
const progress=ref<ArchiveUpdate>({stage:'PREPARING'})
const error=ref('')
let controller:AbortController|undefined
let jobId:string|undefined

const label=computed(()=>{
  if(error.value)return error.value
  const value=progress.value
  if(value.stage==='PREPARING')return '正在准备文件…'
  if(value.stage==='QUEUED')return '等待打包，任务已进入队列…'
  if(value.stage==='RUNNING')return `正在打包：${value.job?.processedFiles||0} / ${value.job?.totalFiles||0} 个附件`
  if(value.stage==='DOWNLOADING')return `正在下载：${formatFileSize(value.loaded||0)} / ${formatFileSize(value.job?.size||0)}`
  return '下载已准备好，请查看浏览器下载记录'
})

async function start(retry=false) {
  if(busy.value)return
  open.value=true
  busy.value=true
  error.value=''
  controller=new AbortController()
  const current=controller
  try {
    const result=await downloadTaskArchive(props.taskId,current.signal,value=>{
      progress.value=value
      if(value.job)jobId=value.job.jobId
    },retry ? jobId : undefined)
    const url=URL.createObjectURL(result.blob)
    const link=document.createElement('a')
    link.href=url
    link.download=result.filename
    link.click()
    setTimeout(()=>URL.revokeObjectURL(url),60000)
    if(result.missingFiles)ElMessage.warning(`有 ${result.missingFiles} 个附件缺失，请查看 ZIP 内的缺失说明`)
  } catch(failure:any) {
    if(!current.signal.aborted) {
      if(progress.value.job?.state==='FAILED'||failure.response?.status===410)jobId=undefined
      // A failed build must create a new job; a failed download can reuse the completed ZIP.
      if(progress.value.stage!=='DOWNLOADING')jobId=undefined
      error.value=await archiveErrorMessage(failure)
    }
  } finally {
    if(controller===current)busy.value=false
  }
}

function stop() {
  controller?.abort()
  controller=undefined
  busy.value=false
  open.value=false
}
watch(()=>props.taskId,()=>{stop();jobId=undefined})
watch(open,value=>{if(!value)stop()})
onBeforeUnmount(stop)
</script>

<template>
  <el-button :icon="FolderOpened" :loading="busy" :disabled="disabled" @click="start(progress.stage!=='DONE')">打包下载文件</el-button>
  <el-dialog v-model="open" title="打包下载文件" width="min(520px, calc(100vw - 32px))"
    append-to-body :close-on-click-modal="false">
    <div class="archive-progress" role="status" aria-live="polite">
      <p class="archive-label" :class="{failed:error}">{{label}}</p>
      <el-progress :percentage="progress.percent??0" :indeterminate="busy&&progress.percent===undefined"
        :status="error?'exception':progress.stage==='DONE'?'success':undefined" />
      <p v-if="progress.stage==='RUNNING'&&progress.job?.currentFile" class="archive-file">正在处理：{{progress.job.currentFile}}</p>
      <p v-if="progress.job?.missingFiles" class="archive-warning">{{progress.job.missingFiles}} 个附件缺失，将在 ZIP 内附缺失说明。</p>
      <p class="archive-note">包含当前及历史提交。关闭窗口不会中止后台打包，可再次点击查看进度。</p>
    </div>
    <template #footer>
      <el-button v-if="error" type="primary" @click="start(true)">{{progress.stage==='DOWNLOADING'?'重试下载':'重新打包'}}</el-button>
      <el-button v-if="progress.stage==='DONE'&&!busy&&!error" @click="start(true)">再次下载</el-button>
      <el-button @click="stop">{{busy?'关闭进度窗口':'关闭'}}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.archive-progress{padding:4px 0}.archive-label{margin:0 0 16px;color:#334155;font-size:15px;line-height:1.6}.archive-label.failed{color:var(--el-color-danger)}.archive-file{margin:14px 0;color:#64748b;overflow-wrap:anywhere;font-size:12px}.archive-note{margin:18px 0 0;color:#8894a5;font-size:12px;line-height:1.7}.archive-warning{color:var(--el-color-warning);font-size:12px;line-height:1.6}
</style>
