import {describe,expect,it} from 'vitest'

const vueFiles=import.meta.glob('../**/*.vue',{
  eager:true,
  import:'default',
  query:'?raw'
}) as Record<string,string>

describe('下拉栏模糊查询',()=>{
  it('所有 el-select 均启用 filterable',()=>{
    const missing:string[]=[]
    let selectCount=0

    for(const [file,source] of Object.entries(vueFiles)){
      for(const match of source.matchAll(/<el-select\b[^>]*>/gs)){
        selectCount+=1
        if(!/(?<![-\w])filterable(?:\s|=|>|$)/.test(match[0])){
          const line=source.slice(0,match.index).split('\n').length
          missing.push(`${file}:${line}`)
        }
      }
    }

    expect(selectCount).toBeGreaterThan(0)
    expect(missing).toEqual([])
  })
})
