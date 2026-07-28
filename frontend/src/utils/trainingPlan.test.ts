import {describe,expect,it} from 'vitest'
import {formatPlanDate,planStatus} from './trainingPlan'

describe('training plan presentation',()=>{
  it('distinguishes drafts, disabled plans and active plans',()=>{
    expect(planStatus({enabled:false,task_count:0}).key).toBe('DRAFT')
    expect(planStatus({enabled:false,task_count:2}).key).toBe('DISABLED')
    expect(planStatus({enabled:true,task_count:2}).key).toBe('ACTIVE')
  })

  it('marks legacy enabled plans without tasks as incomplete',()=>{
    expect(planStatus({enabled:true,task_count:0}).key).toBe('INCOMPLETE')
  })

  it('formats database date values for display',()=>{
    expect(formatPlanDate('2026-07-28T09:30:00')).toBe('2026-07-28 09:30')
    expect(formatPlanDate()).toBe('-')
  })
})
