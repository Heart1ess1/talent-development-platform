export interface TaskScoringEmployeeFilters {
  keyword:string
  classId:number|string|''
  classPositionId:number|string|''
  submissionStatus:''|'SUBMITTED'|'NOT_SUBMITTED'
  scoringStatus:string
}

export function assignmentScoringState(row:any){
  if(row.status==='PENDING_REVIEW'&&!Number(row.reviewerCount))return 'UNASSIGNED'
  if(row.status==='PENDING_REVIEW')return 'PENDING'
  return String(row.status||'')
}

export function filterTaskScoringEmployees(rows:any[],filters:TaskScoringEmployeeFilters){
  const keyword=filters.keyword.trim().toLowerCase()
  return rows.filter(row=>{
    const matchesKeyword=!keyword||[
      row.employee_name,row.employee_no,row.class_name,row.class_position_name
    ].some(value=>String(value||'').toLowerCase().includes(keyword))
    const submitted=Boolean(row.submission_id)
    return matchesKeyword
      && (!filters.classId||String(row.class_id)===String(filters.classId))
      && (!filters.classPositionId||String(row.class_position_id)===String(filters.classPositionId))
      && (!filters.submissionStatus||(filters.submissionStatus==='SUBMITTED'?submitted:!submitted))
      && (!filters.scoringStatus||assignmentScoringState(row)===filters.scoringStatus)
  })
}

export function taskScoringFilterOptions(rows:any[],idKey:string,labelKey:string){
  const values=new Map<string,{id:number|string,label:string}>()
  for(const row of rows){
    if(row[idKey]===null||row[idKey]===undefined||!row[labelKey])continue
    values.set(String(row[idKey]),{id:row[idKey],label:String(row[labelKey])})
  }
  return [...values.values()]
}
