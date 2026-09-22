import {onBeforeUnmount,ref} from 'vue'
import {api,type Envelope} from '@/api'
import type {PageResult} from './types'

export function usePortraitPage<T>(employeeId:()=>number|undefined,endpoint:string,extras:()=>Record<string,unknown>=()=>({})){
  const records=ref<T[]>([]);const total=ref(0);const page=ref(1);const size=ref(20)
  const loading=ref(false);const loaded=ref(false);const error=ref('');let controller:AbortController|undefined;let sequence=0
  async function load(nextPage=page.value){const id=employeeId();if(!id)return;const current=++sequence;controller?.abort();controller=new AbortController();loading.value=true;error.value=''
    try{const response=await api.get<any,Envelope<PageResult<T>>>(`/employees/${id}/portrait/${endpoint}`,{params:{page:nextPage,size:size.value,...extras()},signal:controller.signal,silentError:true} as any);if(current!==sequence)return;records.value=response.data.records;total.value=response.data.total;page.value=response.data.page;loaded.value=true}
    catch(e:any){if(e?.code==='ERR_CANCELED')return;if(current===sequence){records.value=[];total.value=0;loaded.value=false;error.value=e?.response?.data?.message||'数据加载失败'}}finally{if(current===sequence)loading.value=false}}
  function reset(){sequence++;controller?.abort();records.value=[];total.value=0;page.value=1;loaded.value=false;error.value=''}
  onBeforeUnmount(()=>controller?.abort());return{records,total,page,size,loading,loaded,error,load,reset}
}
export const dateTime=(value?:string)=>value?value.replace('T',' ').substring(0,16):'-'
export const date=(value?:string)=>value?value.substring(0,10):'-'
export const score=(value:any)=>value===null||value===undefined?'待评分':String(value)
const labels:Record<string,string>={
  NOT_SUBMITTED:'待提交',OVERDUE:'逾期未完成',PENDING_REVIEW:'待评分',RETURNED:'已退回',APPROVED:'已通过',
  UPCOMING:'未开始',OPEN:'进行中',ENDED:'已结束',DRAFT:'草稿',
  NOT_STARTED:'未参加',READY:'待参加',IN_PROGRESS:'进行中',COMPLETED:'已完成',ABSENT:'缺考',
  GRADED:'已阅卷',PUBLISHED:'已发布',SUBMITTED:'已评分',PENDING:'待评分',VOIDED:'已作废',SUPERSEDED:'已被新版本替代',
  ATTENDED:'已签到',PRESENT:'已签到',LATE:'迟到',LEAVE:'请假',MAKEUP:'补签',
  RECORDED:'已记录',MANUAL:'人工报备',APPROVED_CHANGE:'审批调站',ONBOARD:'入职',
  ATTENDANCE:'课程签到',TASK:'任务提交',TASK_REVIEW:'任务审核',EXAM:'考试交卷',
  EVALUATION:'评价发布',STATION:'服务站调整',LOCATION:'位置报备'
}
export const statusLabel=(value?:string)=>value?labels[value]||value:'-'
export const taskStatusLabel=(value?:string)=>statusLabel(value)
export const eventTypeLabel=(value?:string)=>statusLabel(value)
