export type ComponentCode='EXAM'|'TASK'|'MENTOR'|'STATION'|'TRAINING'

export interface ScoreComponent{
  code:ComponentCode
  enabled:boolean
  weight:number
  fullScore:number
  sourceType:'AUTO'|'MANUAL'
  sourceScore:number|null
  overrideScore:number|null
  effectiveScore:number|null
  weightedScore:number|null
  status:string
  comment?:string
  evaluatorName?:string
  overrideReason?:string
  overrideBy?:string
  breakdown?:any[]
  submittedCount?:number
  requiredCount?:number
  partialScore?:number|null
  aggregationMode?:'AUTO_BY_DAYS'|'PRIMARY_STATION'|'MANUAL'
}

export const componentDefinitions=[
  {code:'EXAM' as const,label:'考试成绩',shortLabel:'考试',description:'自动汇总当月已发布考试成绩',enabled:'examEnabled',weight:'examWeight',maxScore:'examMaxScore'},
  {code:'TASK' as const,label:'任务成果',shortLabel:'任务',description:'自动汇总当月任务审核得分',enabled:'taskEnabled',weight:'taskWeight',maxScore:'taskMaxScore'},
  {code:'MENTOR' as const,label:'导师评价',shortLabel:'导师',description:'技术导师与技能导师分别评分后自动取平均',enabled:'mentorEnabled',weight:'mentorWeight',maxScore:'mentorMaxScore'},
  {code:'STATION' as const,label:'站点评价',shortLabel:'站点',description:'支持按当月各站实际在站天数加权',enabled:'stationEnabled',weight:'stationWeight',maxScore:'stationMaxScore'},
  {code:'TRAINING' as const,label:'培训评价',shortLabel:'培训方',description:'培训管理员评价学习与综合表现',enabled:'trainingEnabled',weight:'trainingWeight',maxScore:'trainingMaxScore'}
]

export const componentLabels=Object.fromEntries(componentDefinitions.map(x=>[x.code,x.label])) as Record<ComponentCode,string>
export const componentStatusLabels:Record<string,string>={DISABLED:'未启用',PENDING:'待评分',AUTOMATIC:'已自动取分',OVERRIDDEN:'已人工核定',SUBMITTED:'已提交'}

export function emptyTemplate(){return {
  name:'',description:'',
  examEnabled:true,examWeight:20,examMaxScore:100,
  taskEnabled:true,taskWeight:30,taskMaxScore:100,
  mentorEnabled:true,mentorWeight:15,mentorMaxScore:100,
  stationEnabled:true,stationWeight:15,stationMaxScore:100,stationAggregationMode:'AUTO_BY_DAYS',
  trainingEnabled:true,trainingWeight:20,trainingMaxScore:100,
  quarterMonth1Weight:33.33,quarterMonth2Weight:33.33,quarterMonth3Weight:33.34,
  bonusCap:10,deductionCap:10,
  examSourceWeights:[] as Array<{sourceId:number|null;weight:number}>,
  taskSourceWeights:[] as Array<{sourceId:number|null;weight:number}>
}}

export function scoreText(value:unknown){return value===null||value===undefined?'—':Number(value).toFixed(2)}
export function monthText(value:unknown){return value?String(value).slice(0,7):'—'}
export function schemeStatus(value:string){return ({DRAFT:'草稿',PUBLISHED:'当前生效',RETIRED:'历史版本'} as Record<string,string>)[value]||value}
