import {describe,expect,it} from 'vitest'
import {attendanceSourceLabel,formatFileSize,sessionStatus} from './course'

describe('course presentation rules',()=>{
  it('derives the session lifecycle from its time range',()=>{
    const now=new Date('2026-07-28T10:00:00')
    expect(sessionStatus({starts_at:'2026-07-28T11:00:00',ends_at:'2026-07-28T12:00:00'},now).key).toBe('UPCOMING')
    expect(sessionStatus({starts_at:'2026-07-28T09:00:00',ends_at:'2026-07-28T11:00:00'},now).key).toBe('ONGOING')
    expect(sessionStatus({starts_at:'2026-07-28T08:00:00',ends_at:'2026-07-28T09:00:00'},now).key).toBe('FINISHED')
  })

  it('formats material sizes and attendance sources',()=>{
    expect(formatFileSize(2048)).toBe('2.0 KB')
    expect(attendanceSourceLabel('MANUAL')).toBe('人工补录')
  })
})
