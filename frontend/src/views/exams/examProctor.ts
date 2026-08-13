import type {Envelope} from '@/api'

export type ProctorEventType='BLUR'|'HIDDEN'|'EXIT_FULLSCREEN'
export type ProctorEventResult={violationCount:number;allowedViolations:number;autoSubmitted:boolean;status:string}
export type ProctorEvent={type:ProctorEventType;eventId:string;detail:string}

export function examDeadlineMillis(value:unknown):number|null{
  if(typeof value!=='string'||!value.trim())return null
  const parsed=Date.parse(value.trim().replace(' ','T'))
  return Number.isFinite(parsed)?parsed:null
}

export function serverClockOffsetMillis(serverNowMillis:unknown,clientNowMillis=Date.now()):number{
  const parsed=Number(serverNowMillis)
  return Number.isFinite(parsed)?parsed-clientNowMillis:0
}

export function remainingExamSeconds(deadlineMillis:number,nowMillis=Date.now(),serverOffsetMillis=0):number{
  return Math.max(0,Math.ceil((deadlineMillis-(nowMillis+serverOffsetMillis))/1000))
}

export function formatExamCountdown(totalSeconds:number):string{
  const safe=Math.max(0,Math.floor(totalSeconds))
  const hours=Math.floor(safe/3600)
  const minutes=Math.floor((safe%3600)/60)
  const seconds=safe%60
  const paddedMinutes=String(minutes).padStart(2,'0')
  const paddedSeconds=String(seconds).padStart(2,'0')
  return hours>0?`${String(hours).padStart(2,'0')}:${paddedMinutes}:${paddedSeconds}`:`${paddedMinutes}:${paddedSeconds}`
}

export function createProctorEventId(){return globalThis.crypto?.randomUUID?.()??`exam-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`}

export async function postProctorEvent(attemptId:number,event:ProctorEvent):Promise<ProctorEventResult>{
  const token=globalThis.localStorage?.getItem('token')
  const response=await fetch(`/api/v1/exams/attempts/${attemptId}/events`,{
    method:'POST',
    headers:{
      'Content-Type':'application/json',
      'X-Request-Id':createProctorEventId(),
      ...(token?{Authorization:`Bearer ${token}`}:{})
    },
    body:JSON.stringify(event),
    credentials:'same-origin',
    keepalive:true
  })
  const envelope=await response.json() as Envelope<ProctorEventResult>
  if(!response.ok||envelope.code!==0)throw new Error(envelope.message||'异常行为记录失败')
  return envelope.data
}
