export interface PerformanceMember {
  employee_id:number;employee_no:string;employee_name:string;participating:boolean|number
  exclusion_reason?:string;summary_id?:number;summary_version?:number;final_score:number|null
  rank_no?:number;grade?:string;old_score?:number|null;old_grade?:string;error?:string
  missing_items?:string;waiver_reason?:string;revision_override_score?:number;revision_group_id?:number
}
export interface PerformanceGroup {
  id:number;talent_batch_id:number;batch_name:string;period_month:string;version:number;status:string
  ratio_a:number;ratio_b:number;ratio_c:number;generation:number;participant_count:number
  targets?:number[];actual?:number[];tie_expanded?:boolean;members:PerformanceMember[]
  distribution_snapshot?:string|{targets:number[];actual:number[];tie_expanded:boolean}
}
export interface PerformancePreview {
  groups:PerformanceGroup[];revisionMembers:PerformanceMember[];revisionIds:number[]
  blockingMessages:string[];canPublish:boolean;reviewToken:string
}
export function validPerformanceRatios(values:unknown[]):boolean {
  return values.length===3&&values.every(x=>typeof x==='number'&&Number.isFinite(x)&&x>=0&&x<=100&&Math.abs(x*100-Math.round(x*100))<1e-7)
    &&values.reduce<number>((sum,x)=>sum+Math.round(Number(x)*100),0)===10000
}
export function filterPerformanceMembers(rows:PerformanceMember[],keyword:string,grade:string):PerformanceMember[]{
  const q=keyword.trim().toLowerCase()
  return rows.filter(r=>(!q||`${r.employee_name} ${r.employee_no}`.toLowerCase().includes(q))&&(!grade||(grade==='EXCLUDED'?!r.participating:r.grade===grade)))
}
export function performanceGradeText(row:{performance_grade?:string;performance_pending?:boolean}):string {
  return row.performance_grade||'未划分'
}
