import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {describe,expect,it} from 'vitest'

const source=(relativePath:string)=>readFileSync(fileURLToPath(new URL(relativePath,import.meta.url)),'utf8')

describe('task scoring workbench navigation',()=>{
  it('registers the scoring route and menu behind task:score',()=>{
    const router=source('../router.ts')
    const layout=source('../layout/AppLayout.vue')
    expect(router).toContain("path:'task-scoring'")
    expect(router).toContain("permission:'task:score'")
    expect(layout).toContain('任务评分')
    expect(layout).toContain('/task-scoring')
  })

  it('keeps task tracking read-only and moves scoring into the workbench',()=>{
    const tracking=source('./TasksView.vue')
    const scoring=source('./TaskScoringView.vue')
    expect(tracking).not.toContain('待审核成果')
    expect(tracking).not.toContain('reviewAssignment(')
    expect(tracking).toContain('前往任务评分')
    expect(scoring).toContain('开始评分')
    expect(scoring).toContain('reviewerIds')
    expect(scoring).toContain('/task-scoring/submissions/')
    expect(scoring).toContain('employeeFilters')
    expect(scoring).not.toContain('max-height="560"')
    expect(scoring).toContain('size="min(1400px,98vw)"')
    expect(scoring).not.toContain('label="班级 / 职务"')
    expect(scoring).toContain('prop="batch_name" label="批次"')
    expect(scoring).toContain('prop="business_unit_name" label="板块"')
    expect(scoring).toContain('prop="class_name" label="班级"')
    expect(scoring).toContain('prop="reviewer_names" label="评分人"')
  })
})
