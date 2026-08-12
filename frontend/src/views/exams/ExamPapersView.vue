<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Collection,Delete,Document,EditPen,Plus,Search,View} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {parseJson,typeLabels} from './examUi'
import '@/styles/exam-center.css'

const papers=ref<any[]>([]),questions=ref<any[]>([]),banks=ref<any[]>([]),loading=ref(false),saving=ref(false)
const drawerVisible=ref(false),detailVisible=ref(false),selectedDetail=ref<any>(null),detailLoading=ref(false)
const keyword=ref(''),modeFilter=ref(''),questionKeyword=ref(''),questionType=ref(''),questionBankId=ref<number>()
const objectiveTypes=['SINGLE','MULTIPLE','TRUE_FALSE']
const emptyPaper=()=>({name:'',description:'',mode:'MANUAL',dynamicAssembly:false,randomizeQuestions:false,randomizeOptions:true,bankIds:[] as number[],selected:[] as number[],scores:{} as Record<number,number>,rules:{SINGLE:{count:0,score:5,tags:[] as string[]},MULTIPLE:{count:0,score:10,tags:[] as string[]},TRUE_FALSE:{count:0,score:5,tags:[] as string[]}}})
const paper=reactive<any>(emptyPaper())

const enabledQuestions=computed(()=>questions.value.filter(q=>truthy(q.enabled)&&truthy(q.bank_enabled)&&objectiveTypes.includes(q.question_type)))
const filteredPapers=computed(()=>papers.value.filter(row=>{
  const match=!keyword.value||`${row.name} ${row.description||''}`.toLowerCase().includes(keyword.value.trim().toLowerCase())
  return match&&(!modeFilter.value||row.assembly_mode===modeFilter.value)
}))
const selectableQuestions=computed(()=>enabledQuestions.value.filter(row=>{
  const matchText=!questionKeyword.value||row.stem.toLowerCase().includes(questionKeyword.value.trim().toLowerCase())
  return matchText&&(!questionType.value||row.question_type===questionType.value)&&(!questionBankId.value||row.bank_id===questionBankId.value)
}))
const paperTotal=computed(()=>paper.mode==='RANDOM'?Object.values(paper.rules).reduce((sum:number,x:any)=>sum+Number(x.count||0)*Number(x.score||0),0):paper.selected.reduce((sum:number,id:number)=>sum+Number(paper.scores[id]||0),0))
const paperCount=computed(()=>paper.mode==='RANDOM'?Object.values(paper.rules).reduce((sum:number,x:any)=>sum+Number(x.count||0),0):paper.selected.length)
const tagOptions=computed(()=>Array.from(new Set(questions.value.flatMap(row=>{const tags=parseJson(row.tags);return Array.isArray(tags)?tags:[]}))).sort())
const summary=computed(()=>({total:papers.value.length,manual:papers.value.filter(x=>x.assembly_mode==='MANUAL').length,random:papers.value.filter(x=>x.assembly_mode==='RANDOM').length,used:papers.value.filter(x=>Number(x.plan_count)>0).length}))
const selectedQuestions=computed(()=>paper.selected.map((id:number)=>questions.value.find(x=>x.id===id)).filter(Boolean))

function truthy(v:any){return v===true||v===1}
async function load(){
  loading.value=true
  try{
    const [paperRes,questionRes,bankRes]=await Promise.all([api.get<any,Envelope<any[]>>('/exams/papers'),api.get<any,Envelope<any[]>>('/exams/questions'),api.get<any,Envelope<any[]>>('/exams/question-banks')])
    papers.value=paperRes.data;questions.value=questionRes.data;banks.value=bankRes.data
  }finally{loading.value=false}
}
function openCreate(){
  Object.assign(paper,emptyPaper());questionKeyword.value='';questionType.value='';questionBankId.value=undefined
  questions.value.forEach(q=>paper.scores[q.id]=Number(q.default_score));drawerVisible.value=true
}
function availableCount(type:string){
  return enabledQuestions.value.filter(q=>q.question_type===type&&(!paper.bankIds.length||paper.bankIds.includes(q.bank_id))).length
}
async function createPaper(){
  if(!paper.name.trim())return ElMessage.warning('请输入试卷名称')
  if(paperCount.value===0)return ElMessage.warning('试卷至少需要一道题')
  if(Math.abs(paperTotal.value-100)>0.0001)return ElMessage.warning(`试卷总分必须为 100 分，当前 ${paperTotal.value} 分`)
  const manualQuestions=paper.mode==='MANUAL'?paper.selected.map((id:number,i:number)=>({questionId:id,score:Number(paper.scores[id]),sortOrder:i+1})):[]
  const randomRules=Object.entries(paper.rules).map(([type,x]:any)=>({type,count:Number(x.count),score:Number(x.score),tags:x.tags,bankIds:paper.bankIds}))
  saving.value=true
  try{
    await api.post('/exams/papers',{name:paper.name.trim(),description:paper.description?.trim(),randomAssembly:paper.mode==='RANDOM',dynamicAssembly:paper.mode==='RANDOM'&&paper.dynamicAssembly,randomizeQuestions:paper.randomizeQuestions,randomizeOptions:paper.randomizeOptions,questions:manualQuestions,randomRules})
    ElMessage.success('试卷创建成功');drawerVisible.value=false;await load()
  }finally{saving.value=false}
}
async function openDetail(row:any){
  selectedDetail.value=row;detailVisible.value=true;detailLoading.value=true
  try{selectedDetail.value=(await api.get<any,Envelope<any>>(`/exams/papers/${row.id}`)).data}
  finally{detailLoading.value=false}
}
async function deletePaper(row:any){
  await ElMessageBox.confirm('仅未用于考试计划的试卷可以删除，删除后无法恢复。','删除试卷',{type:'warning',confirmButtonText:'确认删除'})
  await api.delete(`/exams/papers/${row.id}`);ElMessage.success('试卷已删除');await load()
}
function modeLabel(row:any){if(truthy(row.dynamic_assembly))return '一人一卷';return row.assembly_mode==='RANDOM'?'随机组卷':'手动组卷'}
function detailRules(){return selectedDetail.value?.rules||[]}
onMounted(load)
</script>

<template>
  <div class="exam-module-page">
    <header class="exam-page-head">
      <div><span class="eyebrow">考试中心 · 试卷管理</span><h1>试卷管理</h1><p>试卷仅使用客观题，由系统自动评分，并在整场考试结束后统一发布成绩。</p></div>
      <div class="exam-head-actions"><el-button type="primary" :icon="Plus" @click="openCreate">新建试卷</el-button></div>
    </header>

    <section class="exam-summary-grid">
      <article class="exam-summary-card blue"><span class="exam-summary-icon"><el-icon><Document/></el-icon></span><div><small>试卷总数</small><strong>{{summary.total}}</strong><span>已沉淀版本</span></div></article>
      <article class="exam-summary-card green"><span class="exam-summary-icon"><el-icon><EditPen/></el-icon></span><div><small>手动组卷</small><strong>{{summary.manual}}</strong><span>固定题目与顺序</span></div></article>
      <article class="exam-summary-card violet"><span class="exam-summary-icon"><el-icon><Collection/></el-icon></span><div><small>随机组卷</small><strong>{{summary.random}}</strong><span>规则化抽题</span></div></article>
      <article class="exam-summary-card amber"><span class="exam-summary-icon"><el-icon><View/></el-icon></span><div><small>已用于计划</small><strong>{{summary.used}}</strong><span>受历史保护</span></div></article>
    </section>

    <section class="exam-workspace" v-loading="loading">
      <div class="exam-workspace-head"><div><h2>试卷库</h2><p>试卷一旦用于考试计划即保留为历史版本，避免后续修改影响既有考试。</p></div><span class="exam-result-count">共 {{papers.length}} 份</span></div>
      <div class="exam-filter-bar paper-library-filter"><el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索试卷名称或说明"/><el-select v-model="modeFilter" clearable placeholder="全部组卷方式"><el-option label="手动组卷" value="MANUAL"/><el-option label="随机组卷" value="RANDOM"/></el-select><el-button type="primary" :icon="Plus" @click="openCreate">新建试卷</el-button></div>
      <el-table :data="filteredPapers" class="exam-table" empty-text="暂无试卷">
        <el-table-column label="试卷" min-width="260"><template #default="s"><div class="paper-name"><span><el-icon><Document/></el-icon></span><div><strong>{{s.row.name}}</strong><small>{{s.row.description||'暂无试卷说明'}}</small></div></div></template></el-table-column>
        <el-table-column label="组卷方式" width="120"><template #default="s"><el-tag :type="s.row.assembly_mode==='RANDOM'?'success':'info'" effect="plain">{{modeLabel(s.row)}}</el-tag></template></el-table-column>
        <el-table-column label="结构" width="130"><template #default="s"><strong>{{s.row.question_count}}</strong> 题 / <strong>{{s.row.total_score}}</strong> 分</template></el-table-column>
        <el-table-column label="考试设置" min-width="170"><template #default="s"><div class="paper-options-cell"><span>{{truthy(s.row.randomize_questions)?'题序随机':'固定题序'}}</span><span>{{truthy(s.row.randomize_options)?'选项随机':'固定选项'}}</span></div></template></el-table-column>
        <el-table-column label="使用情况" width="108"><template #default="s"><el-tag :type="Number(s.row.plan_count)>0?'warning':'info'" effect="plain">{{Number(s.row.plan_count)>0?`${s.row.plan_count} 个计划`:'尚未使用'}}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="128" fixed="right"><template #default="s"><div class="exam-table-actions"><el-button link type="primary" @click="openDetail(s.row)">详情</el-button><el-button link type="danger" :disabled="Number(s.row.plan_count)>0" @click="deletePaper(s.row)">删除</el-button></div></template></el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="drawerVisible" size="860px" :close-on-click-modal="false">
      <template #header><div class="exam-drawer-title"><h3>新建试卷</h3><p>按步骤配置基本信息、组卷方式和考试随机策略。</p></div></template>
      <section class="paper-section">
        <div class="paper-section-title"><span>1</span><div><strong>基本信息</strong><small>名称用于考试计划选择，说明用于区分不同版本和适用场景。</small></div></div>
        <div class="exam-form-grid"><el-input v-model="paper.name" maxlength="128" show-word-limit placeholder="试卷名称"/><el-input v-model="paper.description" maxlength="500" placeholder="试卷说明（选填）"/></div>
      </section>
      <section class="paper-section">
        <div class="paper-section-title"><span>2</span><div><strong>组卷方式</strong><small>手动组卷适合固定考核；随机组卷适合复用和防止题目重复。</small></div></div>
        <el-radio-group v-model="paper.mode" class="mode-cards"><el-radio-button value="MANUAL">手动选题</el-radio-button><el-radio-button value="RANDOM">规则抽题</el-radio-button></el-radio-group>
        <template v-if="paper.mode==='MANUAL'">
          <div class="question-picker-filter"><el-input v-model="questionKeyword" clearable :prefix-icon="Search" placeholder="搜索题干"/><el-select v-model="questionBankId" clearable placeholder="全部题库"><el-option v-for="item in banks.filter(x=>truthy(x.enabled))" :key="item.id" :label="item.name" :value="item.id"/></el-select><el-select v-model="questionType" clearable placeholder="全部题型"><el-option v-for="key in objectiveTypes" :key="key" :label="typeLabels[key]" :value="key"/></el-select></div>
          <div class="question-picker">
            <label v-for="item in selectableQuestions" :key="item.id" :class="{selected:paper.selected.includes(item.id)}"><el-checkbox v-model="paper.selected" :value="item.id"/><span class="question-copy"><strong>{{item.stem}}</strong><small>{{item.bank_name}} · {{typeLabels[item.question_type]}}</small></span><el-input-number v-if="paper.selected.includes(item.id)" v-model="paper.scores[item.id]" :min="0.01" :precision="2" controls-position="right"/><em v-if="paper.selected.includes(item.id)">分</em></label>
            <el-empty v-if="!selectableQuestions.length" :image-size="70" description="没有符合条件的可用题目"/>
          </div>
        </template>
        <template v-else>
          <el-select v-model="paper.bankIds" multiple collapse-tags collapse-tags-tooltip clearable class="bank-source-select" placeholder="抽题范围：全部启用题库"><el-option v-for="item in banks.filter(x=>truthy(x.enabled))" :key="item.id" :label="item.name" :value="item.id"/></el-select>
          <el-table :data="objectiveTypes.map(type=>({type,...paper.rules[type]}))" class="rule-table">
            <el-table-column label="题型" width="105"><template #default="s">{{typeLabels[s.row.type]}}</template></el-table-column>
            <el-table-column label="可用量" width="90"><template #default="s">{{availableCount(s.row.type)}} 题</template></el-table-column>
            <el-table-column label="抽取题数" width="155"><template #default="s"><el-input-number v-model="paper.rules[s.row.type].count" :min="0" :max="availableCount(s.row.type)" controls-position="right"/></template></el-table-column>
            <el-table-column label="每题分值" width="155"><template #default="s"><el-input-number v-model="paper.rules[s.row.type].score" :min="0.01" :precision="2" controls-position="right"/></template></el-table-column>
            <el-table-column v-if="paper.dynamicAssembly" label="限定专业标签" min-width="180"><template #default="s"><el-select v-model="paper.rules[s.row.type].tags" multiple allow-create filterable collapse-tags placeholder="不限"><el-option v-for="tag in tagOptions" :key="tag" :label="tag" :value="tag"/></el-select></template></el-table-column>
            <el-table-column label="小计" width="88"><template #default="s"><strong>{{paper.rules[s.row.type].count*paper.rules[s.row.type].score}}</strong> 分</template></el-table-column>
          </el-table>
        </template>
      </section>
      <section class="paper-section">
        <div class="paper-section-title"><span>3</span><div><strong>考试策略</strong><small>随机策略只影响员工考试时的呈现，不改变试卷结构与满分。</small></div></div>
        <div class="strategy-grid"><el-checkbox v-if="paper.mode==='RANDOM'" v-model="paper.dynamicAssembly">一人一卷</el-checkbox><el-checkbox v-model="paper.randomizeQuestions">打乱题序</el-checkbox><el-checkbox v-model="paper.randomizeOptions">打乱选项</el-checkbox></div>
      </section>
      <template #footer><div class="paper-footer"><div><span>当前结构</span><strong :class="{valid:Math.abs(paperTotal-100)<.0001}">{{paperCount}} 题 / {{paperTotal}} 分</strong><small>满分必须为 100 分</small></div><div><el-button @click="drawerVisible=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="Math.abs(paperTotal-100)>=.0001" @click="createPaper">创建试卷</el-button></div></div></template>
    </el-drawer>

    <el-dialog v-model="detailVisible" width="720px" title="试卷详情">
      <div v-loading="detailLoading">
        <div class="paper-detail-head"><span><el-icon><Document/></el-icon></span><div><h3>{{selectedDetail?.name}}</h3><p>{{selectedDetail?.description||'暂无说明'}}</p></div><el-tag effect="plain">{{selectedDetail?modeLabel(selectedDetail):''}}</el-tag></div>
        <div class="detail-metrics"><div><span>题目数量</span><strong>{{selectedDetail?.question_count??selectedDetail?.questions?.length??'--'}}</strong></div><div><span>随机题序</span><strong>{{truthy(selectedDetail?.randomize_questions)?'是':'否'}}</strong></div><div><span>随机选项</span><strong>{{truthy(selectedDetail?.randomize_options)?'是':'否'}}</strong></div></div>
        <el-table v-if="selectedDetail?.questions" :data="selectedDetail.questions" max-height="330"><el-table-column prop="sort_order" label="#" width="50"/><el-table-column prop="bank_name" label="题库" width="120" show-overflow-tooltip/><el-table-column prop="stem" label="题干" min-width="260" show-overflow-tooltip/><el-table-column label="题型" width="90"><template #default="s">{{typeLabels[s.row.question_type]}}</template></el-table-column><el-table-column prop="score" label="分值" width="70"/></el-table>
        <el-table v-else :data="detailRules()" max-height="330"><el-table-column label="题型"><template #default="s">{{typeLabels[s.row.question_type]}}</template></el-table-column><el-table-column prop="count" label="题数"/><el-table-column prop="score" label="每题分值"/></el-table>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.paper-library-filter{grid-template-columns:minmax(260px,480px) 160px auto;justify-content:start}.paper-name{display:flex;align-items:center;gap:11px}.paper-name>span{display:grid;flex:0 0 40px;height:40px;place-items:center;border-radius:8px;color:#2678c9;background:#eaf4ff}.paper-name>div{display:flex;min-width:0;flex-direction:column;gap:4px}.paper-name strong,.paper-name small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.paper-name small{color:#8a95a6}.paper-options-cell{display:flex;gap:6px;flex-wrap:wrap;color:#7d899a;font-size:11px}.paper-options-cell span{padding:3px 7px;border-radius:5px;background:#f3f5f8}.paper-section{padding:0 0 22px;margin-bottom:22px;border-bottom:1px solid #edf0f4}.paper-section:last-of-type{border-bottom:0}.paper-section-title{display:flex;align-items:flex-start;gap:10px;margin-bottom:16px}.paper-section-title>span{display:grid;width:24px;height:24px;place-items:center;border-radius:50%;color:#2478c8;background:#e0effd;font-size:11px;font-weight:700}.paper-section-title>div{display:flex;flex-direction:column;gap:4px}.paper-section-title small{color:#8b96a7}.mode-cards{margin-bottom:14px}.question-picker-filter{display:grid;grid-template-columns:1fr 160px 130px;gap:8px}.question-picker{max-height:330px;margin-top:10px;border:1px solid #e4e9f0;border-radius:8px;overflow-y:auto}.question-picker>label{display:flex;min-height:54px;align-items:center;gap:9px;padding:8px 12px;border-bottom:1px solid #edf0f4}.question-picker>label:last-child{border:0}.question-picker>label.selected{background:#f7fbff}.question-copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:4px}.question-copy strong,.question-copy small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.question-copy small{color:#8b96a7}.question-picker em{color:#7d899a;font-size:12px;font-style:normal}.bank-source-select{width:100%;margin-bottom:10px}.strategy-grid{display:flex;gap:28px;padding:14px;border-radius:8px;background:#f7f9fc}.paper-footer{display:flex;width:100%;align-items:center;justify-content:space-between}.paper-footer>div:first-child{display:grid;grid-template-columns:auto auto;align-items:baseline;gap:3px 8px;text-align:left}.paper-footer span,.paper-footer small{color:#8b96a7}.paper-footer small{grid-column:1/-1}.paper-footer strong{color:#e6a23c;font-size:18px}.paper-footer strong.valid{color:#15966b}.paper-detail-head{display:flex;align-items:center;gap:12px;padding:14px;border-radius:9px;background:#f5f8fc}.paper-detail-head>span{display:grid;width:42px;height:42px;place-items:center;border-radius:9px;color:#2678c9;background:#e4f1fd}.paper-detail-head>div{min-width:0;flex:1}.paper-detail-head h3{margin:0}.paper-detail-head p{margin:5px 0 0;color:#8b96a7}.detail-metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:14px 0}.detail-metrics>div{padding:12px;border-radius:8px;background:#f7f9fc}.detail-metrics span{display:block;color:#8b96a7;font-size:11px}.detail-metrics strong{display:block;margin-top:5px}@media(max-width:760px){.paper-library-filter,.question-picker-filter{grid-template-columns:1fr}.strategy-grid{flex-direction:column;gap:12px}.paper-footer{align-items:flex-start;flex-direction:column;gap:12px}}
</style>
