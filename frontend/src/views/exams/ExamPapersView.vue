<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api,type Envelope} from '@/api'
import {typeLabels} from './examUi'

const papers=ref<any[]>([]),questions=ref<any[]>([])
const paper=reactive<any>({name:'',description:'',randomAssembly:false,randomizeQuestions:false,randomizeOptions:true,selected:[],scores:{},rules:{SINGLE:{count:0,score:5},MULTIPLE:{count:0,score:10},TRUE_FALSE:{count:0,score:5}}})
const enabledQuestions=computed(()=>questions.value.filter(q=>q.enabled===true||q.enabled===1))
const paperTotal=computed(()=>paper.randomAssembly?Object.values(paper.rules).reduce((sum:number,x:any)=>sum+Number(x.count||0)*Number(x.score||0),0):paper.selected.reduce((sum:number,id:number)=>sum+Number(paper.scores[id]||0),0))
const paperCount=computed(()=>paper.randomAssembly?Object.values(paper.rules).reduce((sum:number,x:any)=>sum+Number(x.count||0),0):paper.selected.length)

async function load(){
  const [paperRes,questionRes]=await Promise.all([api.get<any,Envelope<any[]>>('/exams/papers'),api.get<any,Envelope<any[]>>('/exams/questions')])
  papers.value=paperRes.data;questions.value=questionRes.data
  questions.value.forEach(q=>{if(paper.scores[q.id]==null)paper.scores[q.id]=Number(q.default_score)})
}
async function createPaper(){
  if(!paper.name.trim())return ElMessage.warning('请填写试卷名称')
  if(paperCount.value===0)return ElMessage.warning('试卷至少需要一道题')
  if(Math.abs(paperTotal.value-100)>0.0001)return ElMessage.warning(`试卷总分必须为100分，当前${paperTotal.value}分`)
  const questions=paper.randomAssembly?[]:paper.selected.map((id:number,i:number)=>({questionId:id,score:Number(paper.scores[id]),sortOrder:i+1}))
  const randomRules=Object.entries(paper.rules).map(([type,x]:any)=>({type,count:Number(x.count),score:Number(x.score)}))
  await api.post('/exams/papers',{name:paper.name,description:paper.description,randomAssembly:paper.randomAssembly,randomizeQuestions:paper.randomizeQuestions,randomizeOptions:paper.randomizeOptions,questions,randomRules})
  ElMessage.success('试卷创建成功');paper.name='';paper.description='';paper.selected=[];await load()
}
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><div><h2>试卷管理</h2><p class="muted">支持按题型随机组卷或从题库手动选题，总分必须为 100 分</p></div></div>
    <el-card class="section-card">
      <template #header><div class="card-head"><span>组建试卷</span><el-tag :type="Math.abs(paperTotal-100)<0.0001?'success':'warning'">{{paperCount}} 题 / {{paperTotal}} 分</el-tag></div></template>
      <div class="form-grid"><el-input v-model="paper.name" placeholder="试卷名称"/><el-input v-model="paper.description" placeholder="试卷说明（选填）"/></div>
      <div class="paper-options"><el-checkbox v-model="paper.randomAssembly">随机组卷</el-checkbox><el-checkbox v-model="paper.randomizeQuestions">考试时打乱题序</el-checkbox><el-checkbox v-model="paper.randomizeOptions">考试时打乱选项</el-checkbox></div>
      <el-alert v-if="paper.randomAssembly" title="创建试卷时从当前启用题库中按题型随机抽取；每题分值 × 题数合计必须为100分。" type="info" :closable="false"/>
      <el-table v-if="paper.randomAssembly" :data="Object.keys(typeLabels).map(type=>({type,...paper.rules[type]}))" class="paper-table">
        <el-table-column label="题型"><template #default="s">{{typeLabels[s.row.type]}}</template></el-table-column><el-table-column label="题库可用量"><template #default="s">{{enabledQuestions.filter(q=>q.question_type===s.row.type).length}}</template></el-table-column><el-table-column label="抽取题数"><template #default="s"><el-input-number v-model="paper.rules[s.row.type].count" :min="0" :max="enabledQuestions.filter(q=>q.question_type===s.row.type).length"/></template></el-table-column><el-table-column label="每题分值"><template #default="s"><el-input-number v-model="paper.rules[s.row.type].score" :min="0.01" :precision="2"/></template></el-table-column><el-table-column label="小计"><template #default="s">{{paper.rules[s.row.type].count*paper.rules[s.row.type].score}} 分</template></el-table-column>
      </el-table>
      <div v-else class="manual-paper-list">
        <div v-for="q in enabledQuestions" :key="q.id" class="question-pick"><el-checkbox v-model="paper.selected" :value="q.id"><el-tag size="small" effect="plain">{{typeLabels[q.question_type]}}</el-tag> {{q.stem}}</el-checkbox><el-input-number v-if="paper.selected.includes(q.id)" v-model="paper.scores[q.id]" :min="0.01" :precision="2"/><span v-if="paper.selected.includes(q.id)">分</span></div>
        <el-empty v-if="!enabledQuestions.length" description="题库暂无可用题目" :image-size="70"/>
      </div>
      <div class="paper-submit"><span :class="{'score-ok':Math.abs(paperTotal-100)<0.0001}">当前总分：{{paperTotal}} / 100</span><el-button type="primary" :disabled="Math.abs(paperTotal-100)>=0.0001" @click="createPaper">创建试卷</el-button></div>
    </el-card>
    <el-card><template #header>试卷库</template><el-table :data="papers" empty-text="暂无试卷"><el-table-column prop="name" label="试卷名称"/><el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip/><el-table-column label="组卷方式" width="110"><template #default="s"><el-tag :type="s.row.assembly_mode==='RANDOM'?'success':'info'">{{s.row.assembly_mode==='RANDOM'?'随机组卷':'手动组卷'}}</el-tag></template></el-table-column><el-table-column prop="question_count" label="题数" width="90"/><el-table-column prop="total_score" label="总分" width="90"/></el-table></el-card>
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.section-card{margin-bottom:16px}.card-head{display:flex;align-items:center;justify-content:space-between}.paper-options{display:flex;gap:24px;margin:18px 0}.paper-table{margin-top:16px}.manual-paper-list{margin-top:16px;border:1px solid #e4e7ed;border-radius:6px;max-height:480px;overflow:auto}.question-pick{display:flex;align-items:center;gap:12px;min-height:52px;padding:8px 14px;border-bottom:1px solid #ebeef5}.question-pick:last-child{border-bottom:0}.question-pick .el-checkbox{flex:1;height:auto}.paper-submit{display:flex;align-items:center;justify-content:flex-end;gap:20px;margin-top:18px;font-weight:600}.score-ok{color:#16a34a}.form-grid{margin-bottom:16px}.form-grid>*{width:100%}
</style>
