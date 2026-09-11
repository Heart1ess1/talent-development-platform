import {describe,it,expect} from 'vitest'
import {filterAccounts,accountFilterOptions,type AccountFilters} from './userFilters'

const empty:AccountFilters={keyword:'',role:'',enabled:'',batchId:'',businessUnitId:'',classId:'',linked:''}
const rows=[
  {username:'alice',display_name:'张一',employee_no:'001234567890',role:'EMPLOYEE',enabled:true,has_employee_profile:true,batch_id:1,batch_name:'2026届',business_unit_id:2,business_unit_name:'售后',class_id:3,class_name:'一班'},
  {username:'bob',display_name:'张二',employee_no:'001234567891',role:'EMPLOYEE',enabled:false,has_employee_profile:true,batch_id:4,batch_name:'2025届',business_unit_id:2,business_unit_name:'售后',class_id:5,class_name:'一班'},
  {username:'admin',display_name:'管理员',role:'ADMIN',enabled:true,has_employee_profile:false}
]
describe('account personnel filters',()=>{
  it('combines personnel, role and enabled filters before pagination',()=>{
    expect(filterAccounts(rows,{...empty,batchId:'1',businessUnitId:2,classId:3,role:'EMPLOYEE',enabled:'enabled'})).toEqual([rows[0]])
    expect(filterAccounts(rows,{...empty,batchId:1,enabled:'disabled'})).toEqual([])
  })
  it('searches actual employee number and ownership names',()=>{
    expect(filterAccounts(rows,{...empty,keyword:' 001234567890 '})).toEqual([rows[0]])
    expect(filterAccounts(rows,{...empty,keyword:'售后'})).toHaveLength(2)
  })
  it('keeps unlinked accounts visible and filters them explicitly',()=>{
    expect(filterAccounts(rows,empty)).toHaveLength(3)
    expect(filterAccounts(rows,{...empty,linked:'unlinked'})).toEqual([rows[2]])
    expect(filterAccounts(rows,{...empty,linked:'linked',enabled:'disabled'})).toEqual([rows[1]])
  })
  it('deduplicates options by id and distinguishes identically named classes',()=>{
    expect(accountFilterOptions(rows,'business_unit_id','business_unit_name')).toEqual([{id:2,label:'售后'}])
    expect(accountFilterOptions(rows,'class_id','class_name')).toHaveLength(2)
    expect(filterAccounts(rows,{...empty,classId:5})).toEqual([rows[1]])
  })
})
