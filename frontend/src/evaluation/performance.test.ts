import {describe,it,expect} from 'vitest'
import {validPerformanceRatios,filterPerformanceMembers,performanceGradeText,type PerformanceMember} from './performance'
describe('monthly performance form and result presentation',()=>{
  it('requires explicit percentages, exact total and two decimal places',()=>{
    expect(validPerformanceRatios([undefined,undefined,undefined])).toBe(false)
    expect(validPerformanceRatios([33.33,33.33,33.34])).toBe(true)
    expect(validPerformanceRatios([0,0,100])).toBe(true)
    for(const v of [[20,70,9.99],[-1,100,1],[20.001,69.999,10],[NaN,70,30]])expect(validPerformanceRatios(v)).toBe(false)
  })
  it('filters display rows without changing the grading cohort',()=>{
    const rows=[{employee_id:1,employee_no:'001',employee_name:'张三',participating:true,final_score:0,grade:'B'},{employee_id:2,employee_no:'002',employee_name:'李四',participating:false,final_score:null}] as PerformanceMember[]
    expect(filterPerformanceMembers(rows,'001','B')).toHaveLength(1)
    expect(filterPerformanceMembers(rows,'','EXCLUDED')).toHaveLength(1)
    expect(rows).toHaveLength(2)
    expect(performanceGradeText({performance_pending:true})).toBe('未划分')
    expect(performanceGradeText({performance_grade:'A',performance_pending:true})).toBe('A')
  })
})
