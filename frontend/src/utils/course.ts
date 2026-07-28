export type Course = {
  id:number
  name:string
  description?:string|null
  enabled:boolean|number
  session_count:number
  material_count:number
  enrollment_count?:number
  creator_name?:string
  created_at?:string
}

export type CourseSession = {
  id:number
  course_id:number
  course_name:string
  title:string
  location?:string|null
  hours?:number|null
  starts_at:string
  ends_at:string
  checkin_starts_at:string
  checkin_ends_at:string
  checkin_code?:string
  enrollment_count:number
  attendance_count:number
}

export function isCourseEnabled(course:Pick<Course,'enabled'>){
  return course.enabled===true||course.enabled===1
}

export function formatCourseDate(value?:string|null){
  if(!value)return '-'
  return value.substring(0,16).replace('T',' ')
}

export function sessionStatus(session:Pick<CourseSession,'starts_at'|'ends_at'>,now=new Date()){
  const start=new Date(String(session.starts_at).replace(' ','T'))
  const end=new Date(String(session.ends_at).replace(' ','T'))
  if(now<start)return {key:'UPCOMING',label:'未开始',type:'primary' as const}
  if(now<=end)return {key:'ONGOING',label:'进行中',type:'success' as const}
  return {key:'FINISHED',label:'已结束',type:'info' as const}
}

export function attendanceSourceLabel(source?:string){
  return source==='SELF'?'员工签到':source==='MANUAL'?'人工补录':source||'-'
}

export function formatFileSize(value:unknown){
  const size=Number(value||0)
  if(size<1024)return `${size} B`
  if(size<1024*1024)return `${(size/1024).toFixed(1)} KB`
  return `${(size/1024/1024).toFixed(1)} MB`
}
