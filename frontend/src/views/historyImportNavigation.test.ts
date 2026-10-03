import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {describe,expect,it} from 'vitest'

const source=(relativePath:string)=>readFileSync(fileURLToPath(new URL(relativePath,import.meta.url)),'utf8')

describe('历史导入导航',()=>{
  it('在综合评价菜单下提供历史评价导入入口',()=>{
    const layout=source('../layout/AppLayout.vue')
    expect(layout).toContain("to:'/evaluation/results/history-imports'")
    expect(layout).toContain("label:'历史评价导入'")
    expect(layout).toContain("auth.can('history:import')")
  })

  it('在考试中心菜单下提供历史成绩导入入口',()=>{
    const layout=source('../layout/AppLayout.vue')
    expect(layout).toContain("to:'/exams/results/history-imports'")
    expect(layout).toContain("label:'历史成绩导入'")
  })

  it('保留两个历史导入页面的路由注册',()=>{
    const router=source('../router.ts')
    expect(router).toContain("path:'evaluation/results/history-imports'")
    expect(router).toContain("path:'exams/results/history-imports'")
  })
})
