import {describe,expect,it} from 'vitest'
import {assignmentScoringState,filterTaskScoringEmployees,taskScoringFilterOptions,type TaskScoringEmployeeFilters} from './taskScoringFilters'

const rows=[
  {employee_name:'张三',employee_no:'001',batch_id:25,batch_name:'2025届',business_unit_id:10,business_unit_name:'制造板块',class_id:1,class_name:'一班',status:'NOT_SUBMITTED',reviewerCount:2},
  {employee_name:'李四',employee_no:'002',batch_id:26,batch_name:'2026届',business_unit_id:10,business_unit_name:'制造板块',class_id:1,class_name:'一班',status:'PENDING_REVIEW',reviewerCount:0},
  {employee_name:'王五',employee_no:'003',batch_id:26,batch_name:'2026届',business_unit_id:20,business_unit_name:'售后板块',class_id:2,class_name:'二班',status:'PENDING_REVIEW',reviewerCount:2},
  {employee_name:'赵六',employee_no:'004',batch_id:26,batch_name:'2026届',business_unit_id:20,business_unit_name:'售后板块',class_id:2,class_name:'二班',status:'APPROVED',reviewerCount:2}
]
const emptyFilters:TaskScoringEmployeeFilters={keyword:'',batchId:'',businessUnitId:'',classId:'',scoringStatus:''}

describe('task scoring employee filters',()=>{
  it('combines batch, business unit, class and keyword filters',()=>{
    expect(filterTaskScoringEmployees(rows,{...emptyFilters,keyword:'售后',batchId:26,businessUnitId:20,classId:2}).map(row=>row.employee_no)).toEqual(['003','004'])
  })

  it('distinguishes unassigned reviewers from pending scoring',()=>{
    expect(assignmentScoringState(rows[1])).toBe('UNASSIGNED')
    expect(assignmentScoringState(rows[2])).toBe('PENDING')
    expect(filterTaskScoringEmployees(rows,{...emptyFilters,scoringStatus:'UNASSIGNED'}).map(row=>row.employee_name)).toEqual(['李四'])
  })

  it('builds unique filter options from visible employees',()=>{
    expect(taskScoringFilterOptions(rows,'batch_id','batch_name')).toEqual([{id:25,label:'2025届'},{id:26,label:'2026届'}])
    expect(taskScoringFilterOptions(rows,'business_unit_id','business_unit_name')).toEqual([{id:10,label:'制造板块'},{id:20,label:'售后板块'}])
    expect(taskScoringFilterOptions(rows,'class_id','class_name')).toEqual([{id:1,label:'一班'},{id:2,label:'二班'}])
  })
})
