export const typeLabels:Record<string,string>={SINGLE:'单选题',MULTIPLE:'多选题',TRUE_FALSE:'判断题',SHORT:'简答题'}
export const planPhaseLabels:Record<string,{label:string;type:'info'|'success'|'warning'|'danger'|'primary'}>={DRAFT:{label:'草稿',type:'info'},UPCOMING:{label:'未开始',type:'info'},OPEN:{label:'进行中',type:'success'},ENDED:{label:'已结束',type:'warning'}}
export const participationLabels:Record<string,{label:string;type:'info'|'success'|'warning'|'danger'|'primary'}>={NOT_STARTED:{label:'未开始',type:'info'},READY:{label:'待参加',type:'warning'},IN_PROGRESS:{label:'考试中',type:'primary'},PENDING_REVIEW:{label:'已提交，待阅卷',type:'warning'},COMPLETED:{label:'已完成',type:'success'},ABSENT:{label:'缺考',type:'danger'}}
export const resultStatusLabels:Record<string,{label:string;type:'success'|'danger'}>={COMPLETED:{label:'已完成',type:'success'},ABSENT:{label:'缺考',type:'danger'}}

export function dateTimeParts(value:any){
  if(typeof value!=='string')return {date:'--',time:''}
  const [date,time='']=value.replace(' ','T').split('T')
  return {date,time:time.slice(0,5)}
}
export function parseJson(value:any){if(typeof value!=='string')return value;try{return JSON.parse(value)}catch{return value}}
export function scoreMonth(value:any){return typeof value==='string'?value.slice(0,7):value}
