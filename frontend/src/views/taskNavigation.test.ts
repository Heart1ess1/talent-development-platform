import {describe,expect,it} from 'vitest'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {taskIdForEmployeeAssignment,taskIdForManagedTask} from './taskNavigation'

const tasksViewSource=readFileSync(fileURLToPath(new URL('./TasksView.vue',import.meta.url)),'utf8')

describe('任务导航 ID边界',()=>{
  it('员工分配记录只使用 task_id，不回退到 assignment id',()=>{
    expect(taskIdForEmployeeAssignment({id:501,task_id:27})).toBe(27)
    expect(taskIdForEmployeeAssignment({id:501})).toBeNull()
    expect(taskIdForEmployeeAssignment({id:27,task_id:null})).toBeNull()
  })

  it('管理端任务记录使用任务自身 id',()=>{
    expect(taskIdForManagedTask({id:27,task_id:501})).toBe(27)
    expect(taskIdForManagedTask({id:'27'})).toBe(27)
    expect(taskIdForManagedTask({id:0})).toBeNull()
  })

  it('页面调用任务与提交接口时保持两种 ID语义',()=>{
    expect(tasksViewSource).toContain('employee.value ? taskIdForEmployeeAssignment(task) : taskIdForManagedTask(task)')
    expect(tasksViewSource).toContain('`/assignments/${row.id}/submissions`')
    expect(tasksViewSource).toContain('open(row,\'VIEW\')')
  })
})
