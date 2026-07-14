import {describe,expect,it} from 'vitest';import {completionRate,statusLabel} from './progress';
describe('progress helpers',()=>{it('handles empty task list',()=>expect(completionRate(0,0)).toBe(0));it('rounds completion percentage',()=>expect(completionRate(2,3)).toBe(67));it('maps workflow status',()=>expect(statusLabel('RETURNED')).toBe('已退回'))})

