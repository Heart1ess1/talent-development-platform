export type ExamProctorMode='FULLSCREEN_STRICT'|'MOBILE_COMPATIBLE'

type WebkitDocument=Document&{
  webkitFullscreenEnabled?:boolean
  webkitFullscreenElement?:Element|null
  webkitExitFullscreen?:()=>void|Promise<void>
}

type WebkitElement=HTMLElement&{
  webkitRequestFullscreen?:()=>void|Promise<void>
}

export type ExamClientProfile={
  fullscreenCapable:boolean
  ios:boolean
  wechat:boolean
  standalone:boolean
  preferredMode:ExamProctorMode
  clientContext:string
}

export type ExamClientProfileInput={
  userAgent:string
  maxTouchPoints:number
  standalone:boolean
  fullscreenCapable:boolean
}

export function examFullscreenElement(doc:Document=document):Element|null{
  return doc.fullscreenElement||(doc as WebkitDocument).webkitFullscreenElement||null
}

export function supportsExamFullscreen(doc:Document=document):boolean{
  const root=doc.documentElement as WebkitElement
  const standard=doc.fullscreenEnabled===true&&typeof root.requestFullscreen==='function'
  const webkit=(doc as WebkitDocument).webkitFullscreenEnabled!==false&&typeof root.webkitRequestFullscreen==='function'
  return standard||webkit
}

export async function requestExamFullscreen(doc:Document=document):Promise<boolean>{
  const root=doc.documentElement as WebkitElement
  try{
    if(doc.fullscreenEnabled===true&&typeof root.requestFullscreen==='function')await root.requestFullscreen()
    else if((doc as WebkitDocument).webkitFullscreenEnabled!==false&&typeof root.webkitRequestFullscreen==='function')await root.webkitRequestFullscreen()
    else return false
    return Boolean(examFullscreenElement(doc))
  }catch{return false}
}

export async function exitExamFullscreen(doc:Document=document):Promise<void>{
  try{
    if(doc.fullscreenElement&&typeof doc.exitFullscreen==='function')await doc.exitFullscreen()
    else if((doc as WebkitDocument).webkitFullscreenElement&&typeof (doc as WebkitDocument).webkitExitFullscreen==='function')await (doc as WebkitDocument).webkitExitFullscreen!()
  }catch{/* 页面关闭时退出全屏失败无需阻断清理 */}
}

export function buildExamClientProfile(input:ExamClientProfileInput):ExamClientProfile{
  const ua=input.userAgent||''
  const ios=/iPhone|iPad|iPod/i.test(ua)||(/Macintosh/i.test(ua)&&input.maxTouchPoints>1)
  const wechat=/MicroMessenger/i.test(ua)
  const preferredMode:ExamProctorMode=input.fullscreenCapable?'FULLSCREEN_STRICT':'MOBILE_COMPATIBLE'
  const context=[
    ios?'ios':'non-ios',
    wechat?'wechat':'browser',
    input.standalone?'standalone':'browser-tab',
    input.fullscreenCapable?'fullscreen-api':'no-fullscreen-api',
    ua
  ].join(';').slice(0,255)
  return {fullscreenCapable:input.fullscreenCapable,ios,wechat,standalone:input.standalone,preferredMode,clientContext:context}
}

export function detectExamClientProfile():ExamClientProfile{
  const nav=navigator as Navigator&{standalone?:boolean}
  const standalone=nav.standalone===true||window.matchMedia?.('(display-mode: standalone)').matches===true
  return buildExamClientProfile({
    userAgent:nav.userAgent||'',
    maxTouchPoints:Number(nav.maxTouchPoints||0),
    standalone,
    fullscreenCapable:supportsExamFullscreen(document)
  })
}

export function proctorModeForFullscreenResult(enteredFullscreen:boolean):ExamProctorMode{
  return enteredFullscreen?'FULLSCREEN_STRICT':'MOBILE_COMPATIBLE'
}
