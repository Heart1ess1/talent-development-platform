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
  expiresAt:string
}

let capabilitiesPromise:Promise<StorageCapabilities>|null=null

export async function storageCapabilities(){
  if(!capabilitiesPromise){
    capabilitiesPromise=api.get<any,Envelope<StorageCapabilities>>('/storage/capabilities',{silentError:true} as any)
      .then(response=>response.data)
      .catch(()=>({directUpload:false,signedDownload:false}))
  }
  return capabilitiesPromise
}

export async function createUploadTicket(ticketUrl:string,file:File){
  const response=await api.post<any,Envelope<UploadTicket>>(ticketUrl,{
    originalName:file.name,
    contentType:file.type||'application/octet-stream',
    size:file.size
  })
  const ticket=response.data
  const uploadResponse=await fetch(ticket.uploadUrl,{
    method:ticket.method||'PUT',
    headers:ticket.headers||{},
    body:file
  })
  if(!uploadResponse.ok){
    throw new Error(`OSS 上传失败（HTTP ${uploadResponse.status}）`)
  }
  return ticket
}

export async function uploadWithStorageFallback(options:{
  file:File
  legacyUrl:string
  ticketUrl:string
  completeUrl:(ticketId:string)=>string
}){
  const capabilities=await storageCapabilities()
  if(capabilities.directUpload){
    const ticket=await createUploadTicket(options.ticketUrl,options.file)
    return api.post(options.completeUrl(ticket.ticketId))
  }
  const form=new FormData()
  form.append('file',options.file)
  return api.post(options.legacyUrl,form)
}
