import {describe,expect,it} from 'vitest'
import {windowPayload} from './timeWindow'
import {emptyTemplate,componentDefinitions,monthlyComponentDefinitions,scoreText} from '@/evaluation/model'
describe('办理窗口与答辩默认值',()=>{
  it('日期偏移跨月后仍在当天最后一秒截止',()=>{const value=windowPayload({scheduleMode:'OFFSET',baseDate:'2026-09-30',offsetDays:3});expect(value.endsAt).toBe('2026-10-03T23:59:59')})
  it('手动窗口保留指定开始时间',()=>{const value=windowPayload({scheduleMode:'MANUAL',startsAt:'2026-11-01T09:00:00',endsAt:'2026-11-02T18:00:00'});expect(value.startsAt).toBe('2026-11-01T09:00:00')})
  it('旧模板权重不因新增答辩改变',()=>{const value=emptyTemplate();expect(value.defenseEnabled).toBe(false);expect(value.defenseWeight).toBe(0);expect(componentDefinitions).toHaveLength(6);expect(monthlyComponentDefinitions).toHaveLength(5);expect(monthlyComponentDefinitions.some(x=>String(x.code)==='DEFENSE')).toBe(false);expect(value.examWeight+value.taskWeight+value.mentorWeight+value.stationWeight+value.trainingWeight).toBe(100)})
  it('零分与空成绩保持不同',()=>{expect(scoreText(0)).toBe('0.00');expect(scoreText(85.2)).toBe('85.20');expect(scoreText(null)).toBe('—')})
})
