import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {describe,expect,it} from 'vitest'

const source=readFileSync(fileURLToPath(new URL('./TasksView.vue',import.meta.url)),'utf8')

describe('任务下发对象多选展示',()=>{
  it('计划和临时任务的已选项均逐个展示',()=>{
    const models=[
      'dispatch.batchIds','dispatch.classIds','dispatch.classPositionIds','dispatch.businessUnitIds','dispatch.stationIds',
      'manualDispatch.batchIds','manualDispatch.classIds','manualDispatch.classPositionIds','manualDispatch.businessUnitIds','manualDispatch.stationIds'
    ]

    for(const model of models){
      const select=source.match(new RegExp(`<el-select[^>]*v-model="${model.replace('.','\\.')}"[^>]*>`))?.[0]
      expect(select,model).toBeDefined()
      expect(select,model).toMatch(/\bmultiple\b/)
      expect(select,model).not.toContain('collapse-tags')
    }
  })
})
