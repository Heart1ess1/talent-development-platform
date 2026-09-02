export interface TaskScoringEmployeeFilters {
  keyword:string
  batchId:number|string|''
  businessUnitId:number|string|''
  classId:number|string|''
  scoringStatus:string
}

export type TaskScoreSortOrder='ascending'|'descending'|null

export function assignmentScoringState(row:any){
  if(row.status==='PENDING_REVIEW'&&!Number(row.reviewerCount))return 'UNASSIGNED'
  if(row.status==='PENDING_REVIEW')return 'PENDING'
  return String(row.status||'')
}

export function filterTaskScoringEmployees(rows:any[],filters:TaskScoringEmployeeFilters){
  const keyword=filters.keyword.trim().toLowerCase()
  return rows.filter(row=>{
    const matchesKeyword=!keyword||[
      row.employee_name,row.employee_no,row.batch_name,row.business_unit_name,row.class_name
    ].some(value=>String(value||'').toLowerCase().includes(keyword))
    return matchesKeyword
      && (!filters.batchId||String(row.batch_id)===String(filters.batchId))
      && (!filters.businessUnitId||String(row.business_unit_id)===String(filters.businessUnitId))
      && (!filters.classId||String(row.class_id)===String(filters.classId))
      && (!filters.scoringStatus||assignmentScoringState(row)===filters.scoringStatus)
  })
}

export function sortTaskScoringEmployees(rows:any[],order:TaskScoreSortOrder){
  if(!order)return [...rows]
  return [...rows].sort((left,right)=>{
    const leftScore=left.final_score===null||left.final_score===undefined||left.final_score===''?null:Number(left.final_score)
    const rightScore=right.final_score===null||right.final_score===undefined||right.final_score===''?null:Number(right.final_score)
    if(leftScore===null&&rightScore===null)return 0
    if(leftScore===null)return 1
    if(rightScore===null)return -1
    return order==='descending'?rightScore-leftScore:leftScore-rightScore
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
