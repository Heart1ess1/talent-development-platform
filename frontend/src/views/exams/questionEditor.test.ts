import {describe,expect,it} from 'vitest'
import {createEmptyQuestion,objectiveQuestionTypes,resetQuestionForType} from './questionEditor'

describe('question editor defaults',()=>{
  it('only offers objective question types',()=>{
    expect(objectiveQuestionTypes).toEqual(['SINGLE','MULTIPLE','TRUE_FALSE'])
  })

  it('does not prefill options or the correct answer',()=>{
    expect(createEmptyQuestion()).toMatchObject({type:'SINGLE',options:[],answer:null,score:null})
  })

  it('clears previous options and answers when the type changes',()=>{
    const question:any={type:'SINGLE',options:['选项A','选项B'],answer:'选项A'}
    resetQuestionForType(question,'MULTIPLE')
    expect(question).toMatchObject({type:'MULTIPLE',options:[],answer:[]})
    resetQuestionForType(question,'TRUE_FALSE')
    expect(question).toMatchObject({type:'TRUE_FALSE',options:[],answer:null})
  })
})
