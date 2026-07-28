import {describe,expect,it} from 'vitest'
import {avatarUrl,nameInitial} from './avatar'

describe('avatar helpers',()=>{
  it('uses the final character to make the fallback avatar more distinguishable',()=>{
    expect(nameInitial('张三')).toBe('三')
    expect(nameInitial(' 新员工 ')).toBe('工')
    expect(nameInitial('Alex')).toBe('X')
  })

  it('provides a safe fallback and token URL',()=>{
    expect(nameInitial('')).toBe('用')
    expect(avatarUrl()).toBe('')
    expect(avatarUrl('token/value')).toBe('/api/v1/avatars/token%2Fvalue')
  })
})
