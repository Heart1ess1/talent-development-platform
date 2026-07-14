<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue'
import {api,type Envelope} from '@/api'
import {ElMessage} from 'element-plus'
import {useAuthStore} from '@/stores/auth'

type ImportResult={imported:number;errors:{row:number;field:string;message:string}[]}
const typeLabels:Record<string,string>={SINGLE:'单选题',MULTIPLE:'多选题',TRUE_FALSE:'判断题'}
const auth=useAuthStore(),canManage=computed(()=>auth.can('exam:manage'))
const plans=ref<any[]>([]),papers=ref<any[]>([]),questions=ref<any[]>([]),employees=ref<any[]>([]),review=ref<any[]>([]),results=ref<any[]>([])
const dialog=ref(false),reviewDialog=ref(false),attempt=ref<any>(),reviewAttempt=ref<any>(),importFile=ref<File>(),importing=ref(false)
const questionKeyword=ref(''),questionType=ref('')
const answers=reactive<Record<number,any>>({}),grades=reactive<Record<number,number>>({})
const question=reactive<any>({type:'SINGLE',stem:'',options:['选项A','选项B'],answer:'选项A',score:5,explanation:''})
const paper=reactive<any>({name:'',description:'',randomAssembly:false,randomizeQuestions:false,randomizeOptions:true,selected:[],scores:{},rules:{SINGLE:{count:0,score:5},MULTIPLE:{count:0,score:10},TRUE_FALSE:{count:0,score:5}}})
const plan=reactive<any>({paperId:null,name:'',batchId:null,startsAt:'',endsAt:'',durationMinutes:60,maxAttempts:1,scoreMonth:new Date().toISOString().slice(0,7),employeeIds:[]})

const enabledQuestions=computed(()=>questions.value.filter(q=>q.enabled===true||q.enabled===1))
const filteredQuestions=computed(()=>questions.value.filter(q=>(!questionType.value||q.question_type===questionType.value)&&(!questionKeyword.value||q.stem.includes(questionKeyword.value))))
const paperTotal=computed(()=>paper.randomAssembly?Object.values(paper.rules).reduce((sum:number,x:any)=>sum+Number(x.count||0)*Number(x.score||0),0):paper.selected.reduce((sum:number,id:number)=>sum+Number(paper.scores[id]||0),0))
const paperCount=computed(()=>paper.randomAssembly?Object.values(paper.rules).reduce((sum:number,x:any)=>sum+Number(x.count||0),0):paper.selected.length)

async function load(){
  plans.value=(await api.get<any,Envelope<any[]>>('/exams/plans')).data
  results.value=(await api.get<any,Envelope<any[]>>('/exams/results')).data
  if(canManage.value){
    const [paperRes,questionRes,reviewRes,employeeRes]=await Promise.all([
      api.get<any,Envelope<any[]>>('/exams/papers'),api.get<any,Envelope<any[]>>('/exams/questions'),api.get<any,Envelope<any[]>>('/exams/review'),api.get<any,Envelope<any>>('/employees',{params:{size:100}})
    ])
    papers.value=paperRes.data;questions.value=questionRes.data;review.value=reviewRes.data;employees.value=employeeRes.data.records
    questions.value.forEach(q=>{if(paper.scores[q.id]==null)paper.scores[q.id]=Number(q.default_score)})
  }
}
function questionTypeChanged(){
  if(question.type==='TRUE_FALSE'){question.options=[];question.answer=true}
  else{question.options=['选项A','选项B'];question.answer=question.type==='MULTIPLE'?[]:'选项A'}
}
async function createQuestion(){
  const options=question.type==='TRUE_FALSE'?[true,false]:question.options
  await api.post('/exams/questions',{...question,options})
  ElMessage.success('题目已加入题库')
  question.stem='';question.explanation='';await load()
}
async function toggleQuestion(row:any){await api.put(`/exams/questions/${row.id}/enabled`,{enabled:row.enabled});ElMessage.success(row.enabled?'题目已启用':'题目已停用')}
function pickImportFile(e:Event){importFile.value=(e.target as HTMLInputElement).files?.[0]}
async function downloadTemplate(){
  const blob=await api.get<any,Blob>('/exams/questions/template',{responseType:'blob'})
  const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='题库导入模板.xlsx';a.click();URL.revokeObjectURL(url)
}
async function importQuestions(){
  if(!importFile.value)return ElMessage.warning('请先选择 Excel 文件')
  importing.value=true
  try{const form=new FormData();form.append('file',importFile.value);const result=(await api.post<any,Envelope<ImportResult>>('/exams/questions/import',form)).data
    const firstError=result.errors[0]
    if(firstError){ElMessage.error(`导入失败：第${firstError.row}行 ${firstError.message}`)}else{ElMessage.success(`成功导入 ${result.imported} 道题`);importFile.value=undefined;await load()}
  }finally{importing.value=false}
}
async function createPaper(){
  if(!paper.name.trim())return ElMessage.warning('请填写试卷名称')
  if(paperCount.value===0)return ElMessage.warning('试卷至少需要一道题')
  if(Math.abs(paperTotal.value-100)>0.0001)return ElMessage.warning(`试卷总分必须为100分，当前${paperTotal.value}分`)
  const questions=paper.randomAssembly?[]:paper.selected.map((id:number,i:number)=>({questionId:id,score:Number(paper.scores[id]),sortOrder:i+1}))
  const randomRules=Object.entries(paper.rules).map(([type,x]:any)=>({type,count:Number(x.count),score:Number(x.score)}))
  await api.post('/exams/papers',{name:paper.name,description:paper.description,randomAssembly:paper.randomAssembly,randomizeQuestions:paper.randomizeQuestions,randomizeOptions:paper.randomizeOptions,questions,randomRules})
  ElMessage.success('试卷创建成功');paper.name='';paper.selected=[];await load()
}
async function createPlan(){await api.post('/exams/plans',plan);ElMessage.success('考试计划已创建');await load()}
async function publishPlan(id:number){await api.post(`/exams/plans/${id}/publish`);await load()}
async function start(row:any){attempt.value=(await api.post<any,Envelope<any>>(`/exams/plans/${row.id}/attempts`)).data;attempt.value.questions.forEach((q:any)=>answers[q.id]=q.saved_answer??(q.question_type==='MULTIPLE'?[]:null));dialog.value=true;document.addEventListener('visibilitychange',visibility);document.addEventListener('fullscreenchange',fullscreen);document.documentElement.requestFullscreen?.().catch(()=>{})}
async function save(q:any){await api.put(`/exams/attempts/${attempt.value.id}/answers`,{questionId:q.id,answer:answers[q.id]})}
async function submit(){await api.post(`/exams/attempts/${attempt.value.id}/submit`);closeExam();ElMessage.success('试卷已提交');await load()}
function closeExam(){dialog.value=false;document.removeEventListener('visibilitychange',visibility);document.removeEventListener('fullscreenchange',fullscreen);if(document.fullscreenElement)document.exitFullscreen().catch(()=>{})}
function event(type:string,detail:string){if(attempt.value)api.post(`/exams/attempts/${attempt.value.id}/events`,{type,detail})}
function visibility(){if(document.hidden)event('HIDDEN','页面进入后台')}
function fullscreen(){if(!document.fullscreenElement&&dialog.value)event('EXIT_FULLSCREEN','退出全屏')}
async function openReview(row:any){reviewAttempt.value=(await api.get<any,Envelope<any>>(`/exams/attempts/${row.id}`)).data;reviewAttempt.value.questions.filter((q:any)=>q.question_type==='SHORT').forEach((q:any)=>grades[q.id]=0);reviewDialog.value=true}
async function grade(q:any){await api.put(`/exams/attempts/${reviewAttempt.value.id}/questions/${q.id}/grade`,{score:grades[q.id],comment:''});ElMessage.success('评分已保存')}
async function publishResult(){await api.post(`/exams/attempts/${reviewAttempt.value.id}/publish`);reviewDialog.value=false;await load()}
function parse(value:any){if(typeof value!=='string')return value;try{return JSON.parse(value)}catch{return value}}
function displayAnswer(row:any){const value=parse(row.answer_json);if(Array.isArray(value))return value.join('、');if(value===true)return '正确';if(value===false)return '错误';return value}
function options(q:any){const value=parse(q.options_json);return Array.isArray(value)?value:[]}
function optionLabel(o:any){return o===true?'正确':o===false?'错误':String(o)}
onBeforeUnmount(closeExam);onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>考试中心</h2></div>

    <el-card class="section-card">
      <template #header>考试计划</template>
      <el-table :data="plans" empty-text="暂无考试计划">
        <el-table-column prop="name" label="考试"/><el-table-column prop="paper_name" label="试卷"/><el-table-column prop="starts_at" label="开始时间"/><el-table-column prop="ends_at" label="结束时间"/><el-table-column prop="duration_minutes" label="时长(分钟)"/>
        <el-table-column label="操作" width="120"><template #default="s"><el-button v-if="!canManage" link type="primary" @click="start(s.row)">进入考试</el-button><el-button v-if="canManage&&s.row.status==='DRAFT'" link type="primary" @click="publishPlan(s.row.id)">发布</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <template v-if="canManage">
      <el-card class="section-card">
        <template #header><div class="card-head"><span>题库管理</span><span class="muted">共 {{questions.length}} 道题，可用 {{enabledQuestions.length}} 道</span></div></template>
        <el-tabs>
          <el-tab-pane label="题库列表">
            <div class="toolbar"><el-input v-model="questionKeyword" clearable placeholder="搜索题干" style="width:260px"/><el-select v-model="questionType" clearable placeholder="全部题型" style="width:150px"><el-option v-for="(label,key) in typeLabels" :key="key" :label="label" :value="key"/></el-select></div>
            <el-table :data="filteredQuestions" max-height="430" empty-text="题库暂无题目">
              <el-table-column prop="id" label="ID" width="70"/><el-table-column label="题型" width="100"><template #default="s">{{typeLabels[s.row.question_type]}}</template></el-table-column><el-table-column prop="stem" label="题干" min-width="280" show-overflow-tooltip/><el-table-column label="正确答案" min-width="150"><template #default="s">{{displayAnswer(s.row)}}</template></el-table-column><el-table-column prop="default_score" label="默认分值" width="100"/><el-table-column label="启用" width="90"><template #default="s"><el-switch v-model="s.row.enabled" @change="toggleQuestion(s.row)"/></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="手动新增">
            <el-form label-width="90px" class="question-form">
              <el-form-item label="题型"><el-radio-group v-model="question.type" @change="questionTypeChanged"><el-radio-button value="SINGLE">单选题</el-radio-button><el-radio-button value="MULTIPLE">多选题</el-radio-button><el-radio-button value="TRUE_FALSE">判断题</el-radio-button></el-radio-group></el-form-item>
              <el-form-item label="题干"><el-input v-model="question.stem" type="textarea" :rows="3" placeholder="请输入题干"/></el-form-item>
              <el-form-item v-if="question.type!=='TRUE_FALSE'" label="选项"><el-select v-model="question.options" multiple allow-create filterable default-first-option placeholder="输入选项后按回车" style="width:100%"/></el-form-item>
              <el-form-item label="正确答案"><el-radio-group v-if="question.type==='TRUE_FALSE'" v-model="question.answer"><el-radio :value="true">正确</el-radio><el-radio :value="false">错误</el-radio></el-radio-group><el-select v-else v-model="question.answer" :multiple="question.type==='MULTIPLE'" placeholder="请选择正确答案" style="width:100%"><el-option v-for="o in question.options" :key="o" :label="o" :value="o"/></el-select></el-form-item>
              <el-form-item label="答案解析"><el-input v-model="question.explanation" type="textarea" :rows="2"/></el-form-item>
              <el-form-item label="默认分值"><el-input-number v-model="question.score" :min="0.01" :precision="2"/></el-form-item>
              <el-form-item><el-button type="primary" @click="createQuestion">保存到题库</el-button></el-form-item>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="Excel 导入">
            <div class="import-panel"><p>请先下载模板并按填写说明录入。系统会校验全部行，校验通过后一次性导入。</p><div class="toolbar"><el-button @click="downloadTemplate">下载 Excel 模板</el-button><input type="file" accept=".xlsx,.xls" @change="pickImportFile"><el-button type="primary" :loading="importing" @click="importQuestions">开始导入</el-button></div><p v-if="importFile" class="muted">已选择：{{importFile.name}}</p></div>
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <el-card class="section-card">
        <template #header><div class="card-head"><span>组建试卷</span><el-tag :type="Math.abs(paperTotal-100)<0.0001?'success':'warning'">{{paperCount}} 题 / {{paperTotal}} 分</el-tag></div></template>
        <div class="form-grid"><el-input v-model="paper.name" placeholder="试卷名称"/><el-input v-model="paper.description" placeholder="试卷说明（选填）"/></div>
        <div class="paper-options"><el-checkbox v-model="paper.randomAssembly">随机组卷</el-checkbox><el-checkbox v-model="paper.randomizeQuestions">考试时打乱题序</el-checkbox><el-checkbox v-model="paper.randomizeOptions">考试时打乱选项</el-checkbox></div>
        <el-alert v-if="paper.randomAssembly" title="随机组卷将在创建试卷时，从当前启用题库中按题型随机抽取；每题分值 × 题数合计必须为100分。" type="info" :closable="false"/>
        <el-table v-if="paper.randomAssembly" :data="Object.keys(typeLabels).map(type=>({type,...paper.rules[type]}))" class="paper-table">
          <el-table-column label="题型"><template #default="s">{{typeLabels[s.row.type]}}</template></el-table-column><el-table-column label="题库可用量"><template #default="s">{{enabledQuestions.filter(q=>q.question_type===s.row.type).length}}</template></el-table-column><el-table-column label="抽取题数"><template #default="s"><el-input-number v-model="paper.rules[s.row.type].count" :min="0" :max="enabledQuestions.filter(q=>q.question_type===s.row.type).length"/></template></el-table-column><el-table-column label="每题分值"><template #default="s"><el-input-number v-model="paper.rules[s.row.type].score" :min="0.01" :precision="2"/></template></el-table-column><el-table-column label="小计"><template #default="s">{{paper.rules[s.row.type].count*paper.rules[s.row.type].score}} 分</template></el-table-column>
        </el-table>
        <div v-else class="manual-paper-list">
          <div v-for="q in enabledQuestions" :key="q.id" class="question-pick"><el-checkbox v-model="paper.selected" :value="q.id"><el-tag size="small" effect="plain">{{typeLabels[q.question_type]}}</el-tag> {{q.stem}}</el-checkbox><el-input-number v-if="paper.selected.includes(q.id)" v-model="paper.scores[q.id]" :min="0.01" :precision="2"/><span v-if="paper.selected.includes(q.id)">分</span></div>
          <el-empty v-if="!enabledQuestions.length" description="题库暂无可用题目" :image-size="70"/>
        </div>
        <div class="paper-submit"><span :class="{'score-ok':Math.abs(paperTotal-100)<0.0001}">当前总分：{{paperTotal}} / 100</span><el-button type="primary" :disabled="Math.abs(paperTotal-100)>=0.0001" @click="createPaper">创建试卷</el-button></div>
      </el-card>

      <el-card class="section-card">
        <template #header>试卷库</template>
        <el-table :data="papers" empty-text="暂无试卷"><el-table-column prop="name" label="试卷名称"/><el-table-column label="组卷方式"><template #default="s"><el-tag :type="s.row.assembly_mode==='RANDOM'?'success':'info'">{{s.row.assembly_mode==='RANDOM'?'随机组卷':'手动组卷'}}</el-tag></template></el-table-column><el-table-column prop="question_count" label="题数"/><el-table-column prop="total_score" label="总分"/></el-table>
      </el-card>

      <el-card class="section-card"><template #header>创建考试计划</template><div class="form-grid"><el-input v-model="plan.name" placeholder="考试名称"/><el-select v-model="plan.paperId" placeholder="选择试卷"><el-option v-for="x in papers" :key="x.id" :label="x.name" :value="x.id"/></el-select><el-date-picker v-model="plan.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开始时间"/><el-date-picker v-model="plan.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="结束时间"/><el-input-number v-model="plan.durationMinutes" :min="1"/><el-input-number v-model="plan.maxAttempts" :min="1"/><el-date-picker v-model="plan.scoreMonth" type="month" value-format="YYYY-MM" placeholder="计分月份"/><el-select v-model="plan.employeeIds" multiple filterable placeholder="参考人员"><el-option v-for="x in employees" :key="x.id" :label="x.name" :value="x.id"/></el-select></div><el-button type="primary" @click="createPlan">创建计划</el-button></el-card>
      <el-card class="section-card"><template #header>阅卷队列</template><el-table :data="review"><el-table-column prop="employee_name" label="员工"/><el-table-column prop="exam_name" label="考试"/><el-table-column prop="status" label="状态"/><el-table-column prop="objective_score" label="客观分"/><el-table-column prop="event_count" label="异常事件"/><el-table-column label="操作"><template #default="s"><el-button link @click="openReview(s.row)">阅卷/发布</el-button></template></el-table-column></el-table></el-card>
    </template>

    <el-card class="section-card"><template #header>已发布成绩</template><el-table :data="results"><el-table-column prop="employee_name" label="员工"/><el-table-column prop="exam_name" label="考试"/><el-table-column prop="total_score" label="成绩"/><el-table-column prop="score_month" label="计分月份"/></el-table></el-card>

    <el-dialog v-model="dialog" :title="attempt?.exam_name" width="760px" :close-on-click-modal="false" :show-close="false"><div v-for="(q,i) in attempt?.questions" :key="q.id" class="exam-question"><h4>{{i+1}}. {{q.stem}}（{{q.score}}分）</h4><el-radio-group v-if="['SINGLE','TRUE_FALSE'].includes(q.question_type)" v-model="answers[q.id]" @change="save(q)"><el-radio v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-radio></el-radio-group><el-checkbox-group v-else v-model="answers[q.id]" @change="save(q)"><el-checkbox v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-checkbox></el-checkbox-group></div><template #footer><el-button type="primary" @click="submit">提交试卷</el-button></template></el-dialog>
    <el-dialog v-model="reviewDialog" title="主观题阅卷"><div v-for="q in reviewAttempt?.questions?.filter((x:any)=>x.question_type==='SHORT')" :key="q.id"><h4>{{q.stem}}（{{q.score}}分）</h4><p>考生答案：{{q.saved_answer}}</p><el-input-number v-model="grades[q.id]" :min="0" :max="Number(q.score)"/><el-button @click="grade(q)">保存评分</el-button></div><template #footer><el-button type="primary" @click="publishResult">发布成绩</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.section-card{margin-bottom:16px}.card-head{display:flex;align-items:center;justify-content:space-between}.question-form{max-width:760px}.import-panel{padding:12px 4px}.paper-options{display:flex;gap:24px;margin:18px 0}.paper-table{margin-top:16px}.manual-paper-list{margin-top:16px;border:1px solid #e4e7ed;border-radius:6px;max-height:420px;overflow:auto}.question-pick{display:flex;align-items:center;gap:12px;min-height:52px;padding:8px 14px;border-bottom:1px solid #ebeef5}.question-pick:last-child{border-bottom:0}.question-pick .el-checkbox{flex:1;height:auto}.paper-submit{display:flex;align-items:center;justify-content:flex-end;gap:20px;margin-top:18px;font-weight:600}.score-ok{color:#16a34a}.exam-question{padding:8px 0 16px;border-bottom:1px solid #ebeef5}.form-grid{margin-bottom:16px}.form-grid>*{width:100%}
</style>
