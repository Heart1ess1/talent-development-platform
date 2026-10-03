import type {Role} from './role'

export const APP_TITLE='人才培养平台'
const TITLE_SEPARATOR='｜'

type PageTitleValue=(role?:Role|null)=>string

const ROLE_PAGE_TITLES:Record<string,PageTitleValue>={
  '/dashboard':role=>role==='EMPLOYEE'?'个人学习主页':'培养运营工作台',
  '/location-reports':role=>role==='EMPLOYEE'?'位置报备':'人员流动',
  '/courses/attendance':role=>role==='EMPLOYEE'?'我的签到记录':'签到管理',
  '/tasks':role=>role==='EMPLOYEE'?'我的任务':'任务下发',
  '/evaluation/results':role=>role==='EMPLOYEE'?'我的综合评价':'评价结果中心',
  '/exams/results':role=>role==='EMPLOYEE'?'我的成绩':'成绩管理',
  '/profile':role=>role==='EMPLOYEE'?'个人资料':'账号设置'
}

function titleValue(path:string,role?:Role|null,routeTitle?:string){
  const roleSpecific=ROLE_PAGE_TITLES[path]
  if(roleSpecific)return roleSpecific(role)
  if(path.startsWith('/evaluation/assignments/'))return '评分任务详情'
  return routeTitle||'页面'
}

export function pageTitle(path:string,role?:Role|null,routeTitle?:string){
  return `${titleValue(path,role,routeTitle)}${TITLE_SEPARATOR}${APP_TITLE}`
}

export function pageTitleName(path:string,role?:Role|null,routeTitle?:string){
  return titleValue(path,role,routeTitle)
}
