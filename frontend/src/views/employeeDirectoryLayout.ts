export type DirectoryColumnKey='name'|'employeeNo'|'gender'|'batch'|'class'|'classPosition'|'businessUnit'|'station'|'technicalMentor'|'skillMentor'|'school'|'major'|'education'|'phone'|'status'

export type DirectoryColumnLayout={
  key:DirectoryColumnKey
  label:string
  width:number
  minWidth:number
  maxWidth:number
  locked?:boolean
}

export const defaultDirectoryColumns:DirectoryColumnLayout[]=[
  {key:'name',label:'姓名',width:120,minWidth:90,maxWidth:260,locked:true},
  {key:'employeeNo',label:'工号',width:130,minWidth:100,maxWidth:240},
  {key:'gender',label:'性别',width:80,minWidth:68,maxWidth:130},
  {key:'batch',label:'批次',width:100,minWidth:80,maxWidth:220},
  {key:'class',label:'班级',width:120,minWidth:90,maxWidth:240},
  {key:'classPosition',label:'班级职务',width:120,minWidth:90,maxWidth:240},
  {key:'businessUnit',label:'所属板块',width:130,minWidth:100,maxWidth:280},
  {key:'station',label:'服务站点',width:170,minWidth:120,maxWidth:360},
  {key:'technicalMentor',label:'指导老师（技术）',width:150,minWidth:110,maxWidth:300},
  {key:'skillMentor',label:'指导老师（技能）',width:150,minWidth:110,maxWidth:300},
  {key:'school',label:'毕业学校',width:180,minWidth:120,maxWidth:360},
  {key:'major',label:'所学专业',width:170,minWidth:120,maxWidth:360},
  {key:'education',label:'学历',width:110,minWidth:90,maxWidth:180},
  {key:'phone',label:'联系方式',width:140,minWidth:110,maxWidth:260},
  {key:'status',label:'状态',width:90,minWidth:72,maxWidth:150}
]

export function clampDirectoryColumnWidth(column:DirectoryColumnLayout,width:number):number{
  return Math.min(column.maxWidth,Math.max(column.minWidth,Math.round(width)))
}

export function normalizeDirectoryColumns(raw:unknown):DirectoryColumnLayout[]{
  const stored=Array.isArray(raw)?raw:[]
  const byKey=new Map(stored.filter((item):item is {key:DirectoryColumnKey;width?:number}=>Boolean(item&&typeof item==='object'&&'key' in item)).map(item=>[item.key,item]))
  const orderedKeys=stored.map(item=>item&&typeof item==='object'&&'key' in item?String(item.key):'').filter(key=>defaultDirectoryColumns.some(column=>column.key===key))
  const missingKeys=defaultDirectoryColumns.map(column=>column.key).filter(key=>!orderedKeys.includes(key))
  const keys=[...orderedKeys,...missingKeys].filter((key,index,all)=>all.indexOf(key)===index) as DirectoryColumnKey[]
  const locked=defaultDirectoryColumns.filter(column=>column.locked).map(column=>column.key)
  const unlocked=keys.filter(key=>!locked.includes(key))
  return [...locked,...unlocked].map(key=>{
    const base=defaultDirectoryColumns.find(column=>column.key===key)!
    const width=Number(byKey.get(key)?.width)
    return {...base,width:Number.isFinite(width)?clampDirectoryColumnWidth(base,width):base.width}
  })
}

export function reorderDirectoryColumns(columns:DirectoryColumnLayout[],sourceKey:DirectoryColumnKey,targetKey:DirectoryColumnKey):DirectoryColumnLayout[]{
  const source=columns.find(column=>column.key===sourceKey)
  const target=columns.find(column=>column.key===targetKey)
  if(!source||!target||source.locked||target.locked||sourceKey===targetKey)return columns.map(column=>({...column}))
  const result=columns.map(column=>({...column}))
  const from=result.findIndex(column=>column.key===sourceKey)
  const to=result.findIndex(column=>column.key===targetKey)
  const [moved]=result.splice(from,1)
  if(moved)result.splice(to,0,moved)
  return result
}
