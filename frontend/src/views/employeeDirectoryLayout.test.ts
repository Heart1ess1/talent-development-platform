import {describe,expect,it} from 'vitest'
import {defaultDirectoryColumns,normalizeDirectoryColumns,reorderDirectoryColumns} from './employeeDirectoryLayout'

describe('employee directory column layout',()=>{
  it('keeps the locked name column first and restores missing columns',()=>{
    const columns=normalizeDirectoryColumns([{key:'school',width:240},{key:'employeeNo',width:150}])
    expect(columns[0]!.key).toBe('name')
    expect(columns[1]!.key).toBe('school')
    expect(columns).toHaveLength(defaultDirectoryColumns.length)
    expect(defaultDirectoryColumns.findIndex(column=>column.key==='gender')).toBeLessThan(defaultDirectoryColumns.findIndex(column=>column.key==='batch'))
    expect(defaultDirectoryColumns.findIndex(column=>column.key==='classPosition')).toBeGreaterThan(defaultDirectoryColumns.findIndex(column=>column.key==='class'))
    expect(defaultDirectoryColumns.findIndex(column=>column.key==='classPosition')).toBeLessThan(defaultDirectoryColumns.findIndex(column=>column.key==='businessUnit'))
  })

  it('clamps stored widths to safe limits',()=>{
    const columns=normalizeDirectoryColumns([{key:'education',width:9999},{key:'phone',width:1}])
    expect(columns.find(column=>column.key==='education')?.width).toBe(180)
    expect(columns.find(column=>column.key==='phone')?.width).toBe(110)
  })

  it('reorders business columns but leaves locked columns in place',()=>{
    const columns=normalizeDirectoryColumns(undefined)
    const reordered=reorderDirectoryColumns(columns,'school','batch')
    expect(reordered[0]!.key).toBe('name')
    expect(reordered.findIndex(column=>column.key==='school')).toBeLessThan(reordered.findIndex(column=>column.key==='batch'))
    expect(reorderDirectoryColumns(columns,'name','batch')).toEqual(columns)
  })
})
