import {describe,expect,it} from 'vitest'
import {ICP_QUERY_URL,ICP_RECORD_NUMBER} from './compliance'

describe('website compliance configuration',()=>{
  it('uses the approved ICP record and official MIIT query site',()=>{
    expect(ICP_RECORD_NUMBER).toBe('湘ICP备2026035229号-1')
    const queryUrl=new URL(ICP_QUERY_URL)
    expect(queryUrl.protocol).toBe('https:')
    expect(queryUrl.hostname).toBe('beian.miit.gov.cn')
  })
})
