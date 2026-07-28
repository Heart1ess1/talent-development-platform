import {describe,expect,it} from 'vitest'
import {roleLabel} from './role'

describe('role labels',()=>{
  it('keeps role codes in data while presenting Chinese labels',()=>{
    expect(roleLabel('EMPLOYEE')).toBe('员工')
    expect(roleLabel('STATION_MANAGER')).toBe('服务站负责人')
    expect(roleLabel('TRAINING_ADMIN')).toBe('培训管理员')
    expect(roleLabel('SUPER_ADMIN')).toBe('超级管理员')
  })

  it('handles missing and future role values safely',()=>{
    expect(roleLabel()).toBe('未分配')
    expect(roleLabel('CUSTOM_ROLE')).toBe('CUSTOM_ROLE')
  })
})
