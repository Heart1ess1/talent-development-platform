import {describe,expect,it} from 'vitest'
import {enabledMasterData} from './masterData'

describe('master data options',()=>{
  it('keeps enabled business units across JDBC boolean representations',()=>{
    const options=[
      {id:1,name:'机动车',enabled:true},
      {id:2,name:'城轨',enabled:1},
      {id:3,name:'历史板块',enabled:false},
      {id:4,name:'停用板块',enabled:0}
    ]

    expect(enabledMasterData(options).map(item=>item.name)).toEqual(['机动车','城轨'])
  })
})
