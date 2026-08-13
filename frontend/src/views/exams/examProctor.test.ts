import {describe,expect,it} from 'vitest'
import {createProctorEventId,examDeadlineMillis,formatExamCountdown,remainingExamSeconds,serverClockOffsetMillis} from './examProctor'

describe('exam proctor countdown',()=>{
  it('uses the server deadline and rounds partial seconds up',()=>{
    const deadline=examDeadlineMillis('2026-08-13T15:00:00')
    expect(deadline).not.toBeNull()
    expect(remainingExamSeconds(deadline!,deadline!-61_500)).toBe(62)
  })

  it('never shows a negative countdown',()=>{
    expect(remainingExamSeconds(1_000,2_000)).toBe(0)
  })

  it('uses the server clock instead of trusting the device clock',()=>{
    const clientNow=1_000
    const offset=serverClockOffsetMillis(4_000,clientNow)
    expect(offset).toBe(3_000)
    expect(remainingExamSeconds(10_000,clientNow,offset)).toBe(6)
  })

  it('formats minute and hour countdowns consistently',()=>{
    expect(formatExamCountdown(62)).toBe('01:02')
    expect(formatExamCountdown(3_661)).toBe('01:01:01')
    expect(formatExamCountdown(-1)).toBe('00:00')
  })

  it('creates an event id without requiring secure-context randomUUID',()=>{
    const original=globalThis.crypto
    Object.defineProperty(globalThis,'crypto',{value:{},configurable:true})
    expect(createProctorEventId()).toMatch(/^exam-/)
    Object.defineProperty(globalThis,'crypto',{value:original,configurable:true})
  })
})
