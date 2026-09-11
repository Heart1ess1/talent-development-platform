export interface AccountPersonnel {
  employee_id?:number|null
  employee_no?:string|null
  employee_name?:string|null
  batch_id?:number|null
  batch_name?:string|null
  business_unit_id?:number|null
  business_unit_name?:string|null
  class_id?:number|null
  class_name?:string|null
}
export interface AccountFilters {
  keyword:string
  role:string
  enabled:string
  batchId:number|string
  businessUnitId:number|string
  classId:number|string
  linked:string
}
type Account=AccountPersonnel&{username:string;display_name:string;role:string;enabled:boolean;station_names?:string;has_employee_profile?:boolean}

export function filterAccounts<T extends Account>(rows:T[],filters:AccountFilters):T[]{
  const keyword=filters.keyword.trim().toLowerCase()
  return rows.filter(row=>{
    const matchesKeyword=!keyword||[row.username,row.display_name,row.employee_name,row.employee_no,
      row.batch_name,row.business_unit_name,row.class_name,row.station_names]
      .some(value=>String(value??'').toLowerCase().includes(keyword))
    return matchesKeyword&&(!filters.role||row.role===filters.role)
      &&(!filters.enabled||(filters.enabled==='enabled'?Boolean(row.enabled):!row.enabled))
      &&(!filters.batchId||String(row.batch_id)===String(filters.batchId))
      &&(!filters.businessUnitId||String(row.business_unit_id)===String(filters.businessUnitId))
      &&(!filters.classId||String(row.class_id)===String(filters.classId))
      &&(!filters.linked||(filters.linked==='linked'?Boolean(row.has_employee_profile):!row.has_employee_profile))
  })
}

export function accountFilterOptions(rows:AccountPersonnel[],idKey:'batch_id'|'business_unit_id'|'class_id',labelKey:'batch_name'|'business_unit_name'|'class_name'){
  const options=new Map<number,{id:number;label:string}>()
  for(const row of rows){
    const id=row[idKey],label=row[labelKey]
    if(id!=null&&label)options.set(id,{id,label})
  }
  return [...options.values()].sort((a,b)=>a.label.localeCompare(b.label,'zh-CN'))
}
