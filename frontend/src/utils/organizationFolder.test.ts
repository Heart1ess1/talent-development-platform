import {describe,it,expect} from 'vitest'
import {inFolder} from './organizationFolder'
describe('organization folder filtering',()=>{
  const rows=[{id:1,folder_id:null},{id:2,folder_id:5},{id:3,folder_id:6}]
  it('shows all, uncategorized and only the selected folder',()=>{
    expect(rows.filter(row=>inFolder(row,'ALL'))).toHaveLength(3)
    expect(rows.filter(row=>inFolder(row,'NONE')).map(row=>row.id)).toEqual([1])
    expect(rows.filter(row=>inFolder(row,5)).map(row=>row.id)).toEqual([2])
    expect(rows.filter(row=>inFolder(row,8))).toEqual([])
  })
  it('keeps historical records without a folder accessible',()=>{
    expect(inFolder({},'NONE')).toBe(true)
    expect(inFolder({},5)).toBe(false)
  })
})
