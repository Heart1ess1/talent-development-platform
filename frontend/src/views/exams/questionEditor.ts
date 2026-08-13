export const objectiveQuestionTypes=['SINGLE','MULTIPLE','TRUE_FALSE'] as const

export function createEmptyQuestion(){
  return {id:null,bankId:null,type:'SINGLE',stem:'',options:[] as string[],answer:null as string|string[]|boolean|null,score:5,explanation:'',tags:[] as string[]}
}

export function resetQuestionForType(question:any,type:string){
  question.type=type
  question.options=[]
  question.answer=type==='MULTIPLE'?[]:null
}
