<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api,type Envelope} from '@/api'
import {parseJson,typeLabels} from './examUi'

type ImportResult={imported:number;errors:{row:number;field:string;message:string}[]}
const questions=ref<any[]>([]),questionKeyword=ref(''),questionType=ref(''),importFile=ref<File>(),importing=ref(false)
const question=reactive<any>({type:'SINGLE',stem:'',options:['选项A','选项B'],answer:'选项A',score:5,explanation:'',tags:[]})
const enabledQuestions=computed(()=>questions.value.filter(q=>q.enabled===true||q.enabled===1))
const filteredQuestions=computed(()=>questions.value.filter(q=>(!questionType.value||q.question_type===questionType.value)&&(!questionKeyword.value||q.stem.includes(questionKeyword.value))))
const tagOptions=computed(()=>Array.from(new Set(questions.value.flatMap(row=>tagsOf(row)))).sort())

async function load(){questions.value=(await api.get<any,Envelope<any[]>>('/exams/questions')).data}
function questionTypeChanged(){
  if(question.type==='TRUE_FALSE'){question.options=[];question.answer=true}
  else{question.options=['选项A','选项B'];question.answer=question.type==='MULTIPLE'?[]:'选项A'}
}
async function createQuestion(){
  const options=question.type==='TRUE_FALSE'?[true,false]:question.options
  await api.post('/exams/questions',{...question,options})
  ElMessage.success('题目已加入题库');question.stem='';question.explanation='';question.tags=[];await load()
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
  try{
    const form=new FormData();form.append('file',importFile.value)
    const result=(await api.post<any,Envelope<ImportResult>>('/exams/questions/import',form)).data
    const firstError=result.errors[0]
    if(firstError)ElMessage.error(`导入失败：第${firstError.row}行 ${firstError.message}`)
    else{ElMessage.success(`成功导入 ${result.imported} 道题`);importFile.value=undefined;await load()}
  }finally{importing.value=false}
}
function displayAnswer(row:any){const value=parseJson(row.answer_json);if(Array.isArray(value))return value.join('、');if(value===true)return '正确';if(value===false)return '错误';return value}
function tagsOf(row:any){const value=parseJson(row.tags);return Array.isArray(value)?value.filter(x=>typeof x==='string'):[]}
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><div><h2>题库管理</h2><p class="muted">维护单选题、多选题和判断题，支持 Excel 批量导入</p></div></div>
    <el-card>
      <template #header><div class="card-head"><span>题目库</span><span class="muted">共 {{questions.length}} 道题，可用 {{enabledQuestions.length}} 道</span></div></template>
      <el-tabs>
        <el-tab-pane label="题库列表">
          <div class="toolbar"><el-input v-model="questionKeyword" clearable placeholder="搜索题干" style="width:260px"/><el-select v-model="questionType" clearable placeholder="全部题型" style="width:150px"><el-option v-for="(label,key) in typeLabels" :key="key" :label="label" :value="key"/></el-select></div>
          <el-table :data="filteredQuestions" max-height="560" empty-text="题库暂无题目">
            <el-table-column prop="id" label="ID" width="70"/><el-table-column label="题型" width="100"><template #default="s">{{typeLabels[s.row.question_type]}}</template></el-table-column><el-table-column prop="stem" label="题干" min-width="260" show-overflow-tooltip/><el-table-column label="专业标签" min-width="150"><template #default="s"><div class="tag-list"><el-tag v-for="tag in tagsOf(s.row)" :key="tag" size="small" effect="plain">{{tag}}</el-tag><span v-if="!tagsOf(s.row).length" class="muted">公共题</span></div></template></el-table-column><el-table-column label="正确答案" min-width="140"><template #default="s">{{displayAnswer(s.row)}}</template></el-table-column><el-table-column prop="default_score" label="默认分值" width="100"/><el-table-column label="启用" width="90"><template #default="s"><el-switch v-model="s.row.enabled" @change="toggleQuestion(s.row)"/></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="手动新增">
          <el-form label-width="90px" class="question-form">
            <el-form-item label="题型"><el-radio-group v-model="question.type" @change="questionTypeChanged"><el-radio-button value="SINGLE">单选题</el-radio-button><el-radio-button value="MULTIPLE">多选题</el-radio-button><el-radio-button value="TRUE_FALSE">判断题</el-radio-button></el-radio-group></el-form-item>
            <el-form-item label="题干"><el-input v-model="question.stem" type="textarea" :rows="3" placeholder="请输入题干"/></el-form-item>
            <el-form-item v-if="question.type!=='TRUE_FALSE'" label="选项"><el-select v-model="question.options" multiple allow-create filterable default-first-option placeholder="输入选项后按回车" style="width:100%"/></el-form-item>
            <el-form-item label="正确答案"><el-radio-group v-if="question.type==='TRUE_FALSE'" v-model="question.answer"><el-radio :value="true">正确</el-radio><el-radio :value="false">错误</el-radio></el-radio-group><el-select v-else v-model="question.answer" :multiple="question.type==='MULTIPLE'" placeholder="请选择正确答案" style="width:100%"><el-option v-for="o in question.options" :key="o" :label="o" :value="o"/></el-select></el-form-item>
            <el-form-item label="专业标签"><el-select v-model="question.tags" multiple allow-create filterable default-first-option placeholder="不填写表示公共题" style="width:100%"><el-option v-for="tag in tagOptions" :key="tag" :label="tag" :value="tag"/></el-select></el-form-item>
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
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.card-head{display:flex;align-items:center;justify-content:space-between}.question-form{max-width:760px}.import-panel{padding:12px 4px}.tag-list{display:flex;align-items:center;flex-wrap:wrap;gap:4px}
</style>
