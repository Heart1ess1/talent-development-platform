import {describe,expect,it} from 'vitest'
import {
  assignmentChangesApplied,candidateQuery,emptyAssignmentFilters,matchesAssignmentFilters,
  mergeAdditions,rebaseAssignmentDraft,type AssignmentPerson,type AssignmentState
} from './courseAssignments'

const person=(id:number,patch:Partial<AssignmentPerson>={}):AssignmentPerson=>({
  employee_id:id,employee_no:`E${id}`,employee_name:`员工${id}`,status:'ACTIVE',
  batch_id:1,class_id:2,class_position_id:3,business_unit_id:4,station_id:5,
  mentor_user_id:6,skill_mentor_user_id:7,batch_name:'2026届',class_name:'一班',class_position_name:'班长',
  business_unit_name:'机车',station_name:'服务站',technical_mentor_name:'技术导师',skill_mentor_name:'技能导师',
  education:'BACHELOR',school:'交通大学',major:'车辆工程',...patch
})
const state=(records:AssignmentPerson[]):AssignmentState=>({records,baselineHash:'hash',enrolledCount:records.length,attendanceCount:records.filter(row=>row.attendance_status).length})

describe('场次人员筛选与草稿',()=>{
  it('多选字段内为或、字段间为且，关键词只匹配姓名或工号',()=>{
    const filters={...emptyAssignmentFilters(),batchIds:[1,9],classIds:[2,8],stationIds:[5],keyword:'e101',school:'交通',major:'车辆',educations:['BACHELOR']}
    expect(matchesAssignmentFilters(person(101),filters)).toBe(true)
    expect(matchesAssignmentFilters(person(101,{batch_id:9,class_id:8}),filters)).toBe(true)
    expect(matchesAssignmentFilters(person(101,{station_id:8}),filters)).toBe(false)
    expect(matchesAssignmentFilters(person(101,{education:'MASTER'}),filters)).toBe(false)
    expect(matchesAssignmentFilters(person(101),{...filters,keyword:'交通大学'})).toBe(false)
  })
  it('同名人员按ID区分，跨页和不同筛选追加，全选超过100人不截断',()=>{
    const first=Array.from({length:100},(_,i)=>person(i+1))
    const all=Array.from({length:235},(_,i)=>person(i+1,{employee_name:'同名员工'}))
    const selected=mergeAdditions(first,all)
    expect(selected).toHaveLength(235)
    expect(new Set(selected.map(row=>row.employee_id)).size).toBe(235)
    const excluded=selected.filter(row=>row.employee_id!==101)
    expect(excluded).toHaveLength(234)
    expect(excluded.some(row=>row.employee_id===101)).toBe(false)
    expect(mergeAdditions(excluded,[person(101)])).toHaveLength(235)
  })
  it('已选结果是固定快照，修改筛选只改变匹配计数，不改选择',()=>{
    const selected=mergeAdditions([],[person(1),person(2,{class_id:8})])
    const before=selected.map(row=>row.employee_id)
    const matching=selected.filter(row=>matchesAssignmentFilters(row,{...emptyAssignmentFilters(),classIds:[8]}))
    expect(matching.map(row=>row.employee_id)).toEqual([2])
    expect(selected.map(row=>row.employee_id)).toEqual(before)
    expect(emptyAssignmentFilters().classIds).toEqual([])
  })
  it('重复筛选参数使用相同键，并保留特殊文字，分页支持100以后的人员',()=>{
    const query=candidateQuery({...emptyAssignmentFilters(),keyword:' 张 三% ',batchIds:[1,2],educations:['本科','硕士']},6,20)
    expect(query.getAll('batchIds')).toEqual(['1','2'])
    expect(query.getAll('educations')).toEqual(['本科','硕士'])
    expect(query.get('keyword')).toBe('张 三%')
    expect(query.get('page')).toBe('6')
    expect(query.has('classIds')).toBe(false)
  })
  it('冲突重整保留尚未生效的增删和新签到待移除项供用户撤销',()=>{
    const latest=state([person(1,{attendance_status:'CHECKED_IN'}),person(3),person(8,{status:'INACTIVE'})])
    const rebased=rebaseAssignmentDraft(latest,[person(2),person(3)],[1,4])
    expect(rebased.additions.map(row=>row.employee_id)).toEqual([2])
    expect(rebased.removals).toEqual([1])
    expect(latest.records).toHaveLength(3)
    expect(latest.records[2]?.status).toBe('INACTIVE')
  })
  it('网络超时后只有全部增删均已生效才判断完成',()=>{
    const latest=state([person(2),person(3)])
    expect(assignmentChangesApplied(latest,[2,3],[1])).toBe(true)
    expect(assignmentChangesApplied(latest,[2,4],[1])).toBe(false)
    expect(assignmentChangesApplied(latest,[2],[3])).toBe(false)
  })
})
