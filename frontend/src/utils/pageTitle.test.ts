import {describe,expect,it} from 'vitest'
import {pageTitle,pageTitleName} from './pageTitle'

describe('浏览器页面标题',()=>{
  it('统一拼接页面名称和平台名称',()=>{
    expect(pageTitle('/exams/plans',undefined,'考试计划')).toBe('考试计划｜人才培养平台')
    expect(pageTitleName('/exams/plans',undefined,'考试计划')).toBe('考试计划')
  })

  it('按角色区分共用业务页面',()=>{
    expect(pageTitle('/dashboard','EMPLOYEE','培养运营工作台')).toBe('个人学习主页｜人才培养平台')
    expect(pageTitle('/dashboard','ADMIN','培养运营工作台')).toBe('培养运营工作台｜人才培养平台')
    expect(pageTitle('/tasks','EMPLOYEE','任务下发')).toBe('我的任务｜人才培养平台')
    expect(pageTitle('/tasks','MENTOR','任务下发')).toBe('任务下发｜人才培养平台')
    expect(pageTitle('/courses/attendance','EMPLOYEE','签到管理')).toBe('我的签到记录｜人才培养平台')
    expect(pageTitle('/courses/attendance','TRAINING_ADMIN','签到管理')).toBe('签到管理｜人才培养平台')
  })

  it('为详情页和未知页面提供稳定标题',()=>{
    expect(pageTitle('/evaluation/assignments/42','ADMIN','评分任务详情')).toBe('评分任务详情｜人才培养平台')
    expect(pageTitle('/unknown','ADMIN')).toBe('页面｜人才培养平台')
    expect(pageTitle('/unknown','ADMIN')).not.toMatch(/https?:\/\//)
  })
})
