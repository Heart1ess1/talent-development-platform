export type ReviewerScopeMode='NONE'|'UNIFORM'|'SCOPED'

export interface ReviewerScopeDraft{
  id?:number|null
  batchId:number|null
  businessUnitId:number|null
  classId:number|null
  reviewerIds:number[]
  locked?:boolean
  label?:string
  coveredEmployees?:number
}

export function scopeMode(scopes:ReviewerScopeDraft[]):ReviewerScopeMode{
  if(!scopes.length)return 'NONE'
  const first=scopes[0]!
  return scopes.length===1&&!first.batchId&&!first.businessUnitId&&!first.classId?'UNIFORM':'SCOPED'
}

export function reviewerScopePayload(scopes:ReviewerScopeDraft[]){
  return scopes.map(item=>({
    id:item.id||null,
    batchId:item.batchId||null,
    businessUnitId:item.businessUnitId||null,
    classId:item.classId||null,
    reviewerIds:[...item.reviewerIds]
  }))
}
