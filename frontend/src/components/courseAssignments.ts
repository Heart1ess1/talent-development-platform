export interface AssignmentPerson {
  employee_id:number
  employee_no:string
  employee_name:string
  status:'ACTIVE'|'INACTIVE'
  batch_id:number|null
  class_id:number|null
  class_position_id:number|null
  business_unit_id:number|null
  station_id:number|null
  mentor_user_id:number|null
  skill_mentor_user_id:number|null
  batch_name:string|null
  class_name:string|null
  class_position_name:string|null
  business_unit_name:string|null
  station_name:string|null
  technical_mentor_name:string|null
  skill_mentor_name:string|null
  education:string|null
  school:string|null
  major:string|null
  attendance_status?:string|null
  assigned_at?:string|null
  checked_at?:string|null
}
export interface AssignmentFilters {
  keyword:string
  batchIds:number[]
  classIds:number[]
  classPositionIds:number[]
  businessUnitIds:number[]
  stationIds:number[]
  mentorIds:number[]
  skillMentorIds:number[]
  educations:string[]
  school:string
  major:string
}
export interface AssignmentState {records:AssignmentPerson[];baselineHash:string;enrolledCount:number;attendanceCount:number}
export interface AssignmentResult {added:number;removed:number;state:AssignmentState}
export interface CandidatePage {records:AssignmentPerson[];total:number;page:number;size:number}
export const emptyAssignmentFilters=():AssignmentFilters=>({keyword:'',batchIds:[],classIds:[],classPositionIds:[],businessUnitIds:[],stationIds:[],mentorIds:[],skillMentorIds:[],educations:[],school:'',major:''})

export function candidateQuery(filters:AssignmentFilters,page:number,size:number):URLSearchParams {
  const result=new URLSearchParams({page:String(page),size:String(size)})
  for(const [key,value] of Object.entries(filters)) {
    if(Array.isArray(value)) value.forEach(item=>result.append(key,String(item)))
    else if(value.trim()) result.set(key,value.trim())
  }
  return result
}

export function matchesAssignmentFilters(row:AssignmentPerson,filters:AssignmentFilters):boolean {
  const includes=(value:string|null|undefined,term:string)=>String(value||'').toLocaleLowerCase().includes(term.trim().toLocaleLowerCase())
  const groups:[number[],number|null][]=[
    [filters.batchIds,row.batch_id],[filters.classIds,row.class_id],[filters.classPositionIds,row.class_position_id],
    [filters.businessUnitIds,row.business_unit_id],[filters.stationIds,row.station_id],
    [filters.mentorIds,row.mentor_user_id],[filters.skillMentorIds,row.skill_mentor_user_id]
  ]
  return (!filters.keyword.trim()||includes(row.employee_name,filters.keyword)||includes(row.employee_no,filters.keyword))
    &&groups.every(([values,value])=>!values.length||(value!==null&&values.includes(value)))
    &&(!filters.educations.length||filters.educations.includes(row.education||''))
    &&includes(row.school,filters.school)&&includes(row.major,filters.major)
}

export function mergeAdditions(existing:AssignmentPerson[],incoming:AssignmentPerson[]):AssignmentPerson[] {
  return [...new Map([...existing,...incoming].map(row=>[row.employee_id,row])).values()]
}

export function rebaseAssignmentDraft(state:AssignmentState,additions:AssignmentPerson[],removals:number[]) {
  const enrolled=new Set(state.records.map(row=>row.employee_id))
  return {additions:additions.filter(row=>!enrolled.has(row.employee_id)),removals:removals.filter(id=>enrolled.has(id))}
}

export function assignmentChangesApplied(state:AssignmentState,addIds:number[],removeIds:number[]):boolean {
  const enrolled=new Set(state.records.map(row=>row.employee_id))
  return addIds.every(id=>enrolled.has(id))&&removeIds.every(id=>!enrolled.has(id))
}
