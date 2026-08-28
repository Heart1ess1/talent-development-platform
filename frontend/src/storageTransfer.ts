import axios from 'axios'
import {api,type Envelope} from '@/api'

export interface StorageCapabilities{
  directUpload:boolean
  signedDownload:boolean
}

export interface UploadTicket{
  ticketId:string
  uploadUrl:string
  method:string
  headers:Record<string,string>
  formFields:Record<string,string>
  expiresAt:string
}

export interface UploadProgress{
  loaded:number
  total?:number
  percent?:number
}

export type UploadProgressHandler=(progress:UploadProgress)=>void

let capabilitiesPromise:Promise<StorageCapabilities>|null=null

export async function storageCapabilities(){
  if(!capabilitiesPromise){
    capabilitiesPromise=api.get<any,Envelope<StorageCapabilities>>('/storage/capabilities',{silentError:true} as any)
      .then(response=>response.data)
      .catch(()=>({directUpload:false,signedDownload:false}))
  }
  return capabilitiesPromise
}

export async function abandonUploadTickets(ticketIds:string[]){
  const uniqueIds=[...new Set(ticketIds.filter(Boolean))]
  await Promise.allSettled(uniqueIds.map(ticketId=>
    api.delete(`/storage/upload-tickets/${encodeURIComponent(ticketId)}`,{silentError:true} as any)
  ))
}

export async function createUploadTicket(ticketUrl:string,file:File,onProgress?:UploadProgressHandler){
  const response=await api.post<any,Envelope<UploadTicket>>(ticketUrl,{
    originalName:file.name,
    contentType:file.type||'application/octet-stream',
    size:file.size
  })
  const ticket=response.data
  const isFormUpload=(ticket.method||'PUT').toUpperCase()==='POST'
  let body:BodyInit=file
  if(isFormUpload){
    const form=new FormData()
    for(const [name,value] of Object.entries(ticket.formFields||{}))form.append(name,value)
    form.append('file',file)
    body=form
  }
  try{
    await axios.request({
      url:ticket.uploadUrl,
      method:ticket.method||'PUT',
      headers:isFormUpload?{}:(ticket.headers||{}),
      data:body,
      onUploadProgress:event=>{
        const total=event.total&&event.total>0?event.total:undefined
        onProgress?.({
          loaded:event.loaded,
          total,
          percent:total?Math.min(100,Math.round(event.loaded/total*100)):undefined
        })
      }
    })
  }catch(error){
    await abandonUploadTickets([ticket.ticketId])
    if(axios.isAxiosError(error)&&error.response?.status){
      throw new Error(`OSS 上传失败（HTTP ${error.response.status}）`)
    }
    throw error
  }
  return ticket
}

export async function uploadWithStorageFallback(options:{
  file:File
  legacyUrl:string
  ticketUrl:string
  completeUrl:(ticketId:string)=>string
  onProgress?:UploadProgressHandler
}){
  const capabilities=await storageCapabilities()
  if(capabilities.directUpload){
    const ticket=await createUploadTicket(options.ticketUrl,options.file,options.onProgress)
    try{
      return await api.post(options.completeUrl(ticket.ticketId))
    }catch(error){
      await abandonUploadTickets([ticket.ticketId])
      throw error
    }
  }
  const form=new FormData()
  form.append('file',options.file)
  return api.post(options.legacyUrl,form)
}
