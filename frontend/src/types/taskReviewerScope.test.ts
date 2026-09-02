import {describe,expect,it} from 'vitest'
import {reviewerScopePayload,scopeMode,type ReviewerScopeDraft} from './taskReviewerScope'

describe('task reviewer scopes',()=>{
  it('distinguishes none, uniform and conditional modes',()=>{
    expect(scopeMode([])).toBe('NONE')
    expect(scopeMode([{batchId:null,businessUnitId:null,classId:null,reviewerIds:[1]}])).toBe('UNIFORM')
    expect(scopeMode([{batchId:2026,businessUnitId:3,classId:null,reviewerIds:[1]}])).toBe('SCOPED')
  })

  it('creates an API payload without view-only fields',()=>{
    const scopes:ReviewerScopeDraft[]=[{id:8,batchId:2026,businessUnitId:3,classId:null,reviewerIds:[7,9],locked:true,label:'2026届 / 城轨 / 全部班级'}]
    expect(reviewerScopePayload(scopes)).toEqual([{id:8,batchId:2026,businessUnitId:3,classId:null,reviewerIds:[7,9]}])
  })
})
