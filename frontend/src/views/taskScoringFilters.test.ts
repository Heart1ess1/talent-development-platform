import {describe,expect,it} from 'vitest'
import {assignmentScoringState,filterTaskScoringEmployees,taskScoringFilterOptions,type TaskScoringEmployeeFilters} from './taskScoringFilters'

const rows=[
  {employee_name:'张三',employee_no:'001',class_id:1,class_name:'一班',class_position_id:11,class_position_name:'班长',submission_id:null,status:'NOT_SUBMITTED',reviewerCount:2},
  {employee_name:'李四',employee_no:'002',class_id:1,class_name:'一班',class_position_id:12,class_position_name:'学员',submission_id:20,status:'PENDING_REVIEW',reviewerCount:0},
  {employee_name:'王五',employee_no:'003',class_id:2,class_name:'二班',class_position_id:12,class_position_name:'学员',submission_id:21,status:'PENDING_REVIEW',reviewerCount:2},
  {employee_name:'赵六',employee_no:'004',class_id:2,class_name:'二班',class_position_id:12,class_position_name:'学员',submission_id:22,status:'APPROVED',reviewerCount:2}
]
const emptyFilters:TaskScoringEmployeeFilters={keyword:'',classId:'',classPositionId:'',submissionStatus:'',scoringStatus:''}

describe('task scoring employee filters',()=>{
  it('combines keyword, class, position and submission filters',()=>{
    expect(filterTaskScoringEmployees(rows,{...emptyFilters,keyword:'学员',classId:2,classPositionId:12,submissionStatus:'SUBMITTED'}).map(row=>row.employee_no)).toEqual(['003','004'])
  })

  it('distinguishes unassigned reviewers from pending scoring',()=>{
    expect(assignmentScoringState(rows[1])).toBe('UNASSIGNED')
    expect(assignmentScoringState(rows[2])).toBe('PENDING')
    expect(filterTaskScoringEmployees(rows,{...emptyFilters,scoringStatus:'UNASSIGNED'}).map(row=>row.employee_name)).toEqual(['李四'])
  })

  it('builds unique sorted filter options from visible employees',()=>{
    expect(taskScoringFilterOptions(rows,'class_id','class_name')).toEqual([{id:1,label:'一班'},{id:2,label:'二班'}])
  })
})
