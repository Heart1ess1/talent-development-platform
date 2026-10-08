import {api, type Envelope} from '@/api'

export interface ArchiveStatus {
  jobId:string
  taskId:number
  state:'QUEUED'|'RUNNING'|'READY'|'FAILED'
  totalFiles:number
  processedFiles:number
  missingFiles:number
  currentFile:string
  filename:string
  size:number
  message:string
  expiresAt:string
}

export type ArchiveUpdate = {
  stage:'PREPARING'|'QUEUED'|'RUNNING'|'DOWNLOADING'|'DONE'
  job?:ArchiveStatus
  percent?:number
  loaded?:number
}

function wait(signal:AbortSignal) {
  return new Promise<void>((resolve,reject)=>{
    signal.throwIfAborted()
    const cancel=()=>{clearTimeout(timer);reject(new DOMException('Aborted','AbortError'))}
    const timer=setTimeout(()=>{signal.removeEventListener('abort',cancel);resolve()},2000)
    signal.addEventListener('abort',cancel,{once:true})
  })
}

export async function downloadTaskArchive(taskId:number,signal:AbortSignal,
  update:(value:ArchiveUpdate)=>void,existingJobId?:string) {
  const options={signal,silentError:true} as any
  update({stage:'PREPARING'})
  let job:ArchiveStatus
  if(existingJobId) {
    try {
      job=(await api.get<any,Envelope<ArchiveStatus>>(`/submission-archives/${existingJobId}`,options)).data
    } catch(error:any) {
      if(error.response?.status!==410) throw error
      job=(await api.post<any,Envelope<ArchiveStatus>>(`/tasks/${taskId}/submission-archives`,undefined,options)).data
    }
  } else {
    job=(await api.post<any,Envelope<ArchiveStatus>>(`/tasks/${taskId}/submission-archives`,undefined,options)).data
  }
  while(job.state==='QUEUED'||job.state==='RUNNING') {
    update({stage:job.state,job,percent:job.totalFiles ? Math.min(99,Math.floor(job.processedFiles/job.totalFiles*100)) : undefined})
    await wait(signal)
    job=(await api.get<any,Envelope<ArchiveStatus>>(`/submission-archives/${job.jobId}`,options)).data
  }
  if(job.state==='FAILED') throw new Error(job.message)
  signal.throwIfAborted()
  update({stage:'DOWNLOADING',job,percent:0,loaded:0})
  const blob=await api.get<any,Blob>(`/submission-archives/${job.jobId}/download`,{
    ...options,responseType:'blob',timeout:10*60*1000,
    onDownloadProgress:(event:any)=>{
      const total=event.total||job.size
      update({stage:'DOWNLOADING',job,loaded:event.loaded,
        percent:total ? Math.min(99,Math.floor(event.loaded/total*100)) : undefined})
    }
  })
  signal.throwIfAborted()
  update({stage:'DONE',job,percent:100,loaded:blob.size})
  return {blob,filename:job.filename,missingFiles:job.missingFiles}
}

export async function archiveErrorMessage(error:any) {
  if(error.response?.data instanceof Blob) {
    try {return JSON.parse(await error.response.data.text()).message||'下载失败，请重试'} catch {/* Fall through. */}
  }
  if(error.code==='ECONNABORTED') return '下载超时，请重试下载'
  return error.response?.data?.message||error.message||'打包下载失败，请重试'
}
