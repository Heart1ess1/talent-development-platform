import {api,type Envelope} from '@/api'

export interface MasterDataOption {
  id:number
  name:string
  enabled?:boolean|number|string|null
}

export interface DictionaryOption {
  id:number
  value:string
  label:string
  sortOrder:number
  enabled:boolean
}

export function enabledMasterData<T extends MasterDataOption>(items:T[]):T[]{
  return items.filter(item=>item.enabled===true||item.enabled===1||item.enabled==='1')
}

export async function loadEnabledBusinessUnits():Promise<MasterDataOption[]>{
  const response=await api.get<any,Envelope<MasterDataOption[]>>('/business-units')
  return enabledMasterData(response.data)
}

export async function loadDictionaryValues(typeCode:string):Promise<DictionaryOption[]>{
  const response=await api.get<any,Envelope<DictionaryOption[]>>(`/dictionaries/${typeCode}/values`)
  return response.data
}
