<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Collection,Document,Download,Edit,FolderAdd,Plus,Search,Upload} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {parseJson,typeLabels} from './examUi'
import {createEmptyQuestion,objectiveQuestionTypes,resetQuestionForType} from './questionEditor'
import '@/styles/exam-center.css'

type ImportResult={imported:number;errors:{row:number;field:string;message:string}[]}
const banks=ref<any[]>([]),questions=ref<any[]>([]),activeBankId=ref<number>(),loading=ref(false)
const keyword=ref(''),typeFilter=ref(''),statusFilter=ref('')
const bankDialog=ref(false),questionDialog=ref(false),importDialog=ref(false),saving=ref(false),importing=ref(false),importFile=ref<File>()
const bank=reactive<any>({id:null,name:'',description:'',enabled:true})
const question=reactive<any>(createEmptyQuestion())

const enabledBanks=computed(()=>banks.value.filter(x=>truthy(x.enabled)))
const activeBank=computed(()=>banks.value.find(x=>x.id===activeBankId.value))
const filteredQuestions=computed(()=>questions.value.filter(row=>{
  const matchesBank=!activeBankId.value||row.bank_id===activeBankId.value
  const matchesKeyword=!keyword.value||`${row.stem} ${row.bank_name}`.toLowerCase().includes(keyword.value.trim().toLowerCase())
  const matchesType=!typeFilter.value||row.question_type===typeFilter.value
  const matchesStatus=!statusFilter.value||(statusFilter.value==='ENABLED'?truthy(row.enabled):!truthy(row.enabled))
  return matchesBank&&matchesKeyword&&matchesType&&matchesStatus
}))
const summary=computed(()=>({
  banks:banks.value.length,
  questions:questions.value.length,
  enabled:questions.value.filter(x=>truthy(x.enabled)&&truthy(x.bank_enabled)).length,
  disabled:questions.value.filter(x=>!truthy(x.enabled)||!truthy(x.bank_enabled)).length
}))
const tagOptions=computed(()=>Array.from(new Set(questions.value.flatMap(tagsOf))).sort())

function truthy(value:any){return value===true||value===1}
async function load(){
  loading.value=true
  try{
    const [bankRes,questionRes]=await Promise.all([
      api.get<any,Envelope<any[]>>('/exams/question-banks'),
      api.get<any,Envelope<any[]>>('/exams/questions')
    ])
    banks.value=bankRes.data;questions.value=questionRes.data
    if(!activeBankId.value&&banks.value.length)activeBankId.value=banks.value[0].id
  }finally{loading.value=false}
}
function openBank(row?:any){Object.assign(bank,row?{id:row.id,name:row.name,description:row.description||'',enabled:truthy(row.enabled)}:{id:null,name:'',description:'',enabled:true});bankDialog.value=true}
async function saveBank(){
  if(!bank.name.trim())return ElMessage.warning('请输入题库名称')
  saving.value=true
  try{
    const payload={name:bank.name.trim(),description:bank.description?.trim()||'',enabled:bank.enabled}
    if(bank.id)await api.put(`/exams/question-banks/${bank.id}`,payload)
    else await api.post('/exams/question-banks',payload)
    ElMessage.success(bank.id?'题库信息已更新':'题库已创建');bankDialog.value=false;await load()
  }finally{saving.value=false}
}
function questionTypeChanged(type:string){resetQuestionForType(question,type)}
function openQuestion(row?:any){
  if(row){
    Object.assign(question,{id:row.id,bankId:row.bank_id,type:row.question_type,stem:row.stem,options:parseJson(row.options_json)||[],answer:parseJson(row.answer_json),score:Number(row.default_score),explanation:row.explanation||'',tags:tagsOf(row)})
  }else Object.assign(question,{...createEmptyQuestion(),bankId:activeBankId.value||enabledBanks.value[0]?.id})
  questionDialog.value=true
}
async function saveQuestion(){
  if(!question.bankId)return ElMessage.warning('请选择所属题库')
  if(!question.stem.trim())return ElMessage.warning('请输入题干')
  if(['SINGLE','MULTIPLE'].includes(question.type)&&question.options.length<2)return ElMessage.warning('请至少填写两个选项')
  if(question.type==='SINGLE'&&(typeof question.answer!=='string'||!question.answer))return ElMessage.warning('请选择正确答案')
  if(question.type==='MULTIPLE'&&(!Array.isArray(question.answer)||!question.answer.length))return ElMessage.warning('请至少选择一个正确答案')
  if(question.type==='TRUE_FALSE'&&typeof question.answer!=='boolean')return ElMessage.warning('请选择正确或错误')
  const options=question.type==='TRUE_FALSE'?[true,false]:question.options
  saving.value=true
  try{
    const payload={bankId:question.bankId,type:question.type,stem:question.stem.trim(),options,answer:question.answer,score:question.score,explanation:question.explanation,tags:question.tags}
    if(question.id)await api.put(`/exams/questions/${question.id}`,payload)
    else await api.post('/exams/questions',payload)
    ElMessage.success(question.id?'题目已更新':'题目已加入题库');questionDialog.value=false;await load()
  }finally{saving.value=false}
}
async function toggleQuestion(row:any){await api.put(`/exams/questions/${row.id}/enabled`,{enabled:truthy(row.enabled)});ElMessage.success(truthy(row.enabled)?'题目已启用':'题目已停用');await load()}
async function deleteQuestion(row:any){
  await ElMessageBox.confirm('删除后无法恢复；若题目已被试卷使用，系统会自动阻止删除。','删除题目',{type:'warning',confirmButtonText:'确认删除'})
  await api.delete(`/exams/questions/${row.id}`);ElMessage.success('题目已删除');await load()
}
function pickImportFile(e:Event){importFile.value=(e.target as HTMLInputElement).files?.[0]}
async function downloadTemplate(){
  const blob=await api.get<any,Blob>('/exams/questions/template',{responseType:'blob'})
  const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='题库导入模板.xlsx';a.click();URL.revokeObjectURL(url)
}
async function importQuestions(){
  if(!activeBankId.value)return ElMessage.warning('请先选择目标题库')
  if(!importFile.value)return ElMessage.warning('请选择 Excel 文件')
  importing.value=true
  try{
    const form=new FormData();form.append('bankId',String(activeBankId.value));form.append('file',importFile.value)
    const result=(await api.post<any,Envelope<ImportResult>>('/exams/questions/import',form)).data
    const firstError=result.errors[0]
    if(firstError)ElMessage.error(`导入失败：第 ${firstError.row} 行 ${firstError.message}`)
    else{ElMessage.success(`成功导入 ${result.imported} 道题`);importFile.value=undefined;importDialog.value=false;await load()}
  }finally{importing.value=false}
}
function displayAnswer(row:any){const value=parseJson(row.answer_json);if(Array.isArray(value))return value.join('、');if(value===true)return '正确';if(value===false)return '错误';return value}
function tagsOf(row:any){const value=parseJson(row.tags);return Array.isArray(value)?value.filter(x=>typeof x==='string'):[]}
onMounted(load)
</script>

<template>
  <div class="exam-module-page">
    <header class="exam-page-head">
      <div><span class="eyebrow">考试中心 · 题库管理</span><h1>题库管理</h1><p>按业务主题建立独立题库，统一维护题目、答案、解析与启停状态。</p></div>
      <div class="exam-head-actions"><el-button :icon="Download" @click="downloadTemplate">下载模板</el-button><el-button :icon="Upload" @click="importDialog=true">批量导入</el-button><el-button type="primary" :icon="Plus" @click="openQuestion()">新增题目</el-button></div>
    </header>

    <section class="exam-summary-grid">
      <article class="exam-summary-card blue"><span class="exam-summary-icon"><el-icon><Collection/></el-icon></span><div><small>题库数量</small><strong>{{summary.banks}}</strong><span>{{enabledBanks.length}} 个启用</span></div></article>
      <article class="exam-summary-card violet"><span class="exam-summary-icon"><el-icon><Document/></el-icon></span><div><small>题目总数</small><strong>{{summary.questions}}</strong><span>全部题库</span></div></article>
      <article class="exam-summary-card green"><span class="exam-summary-icon"><el-icon><Document/></el-icon></span><div><small>可用题目</small><strong>{{summary.enabled}}</strong><span>可参与组卷</span></div></article>
      <article class="exam-summary-card amber"><span class="exam-summary-icon"><el-icon><Document/></el-icon></span><div><small>已停用</small><strong>{{summary.disabled}}</strong><span>保留历史记录</span></div></article>
    </section>

    <section class="exam-split-workspace" v-loading="loading">
      <aside class="exam-selector">
        <div class="exam-selector-head"><div><h2>题库目录</h2><span>{{banks.length}} 个题库</span></div><el-button link type="primary" :icon="FolderAdd" @click="openBank()">新建</el-button></div>
        <div class="exam-selector-list">
          <button v-for="item in banks" :key="item.id" :class="{active:activeBankId===item.id}" @click="activeBankId=item.id">
            <span class="selector-icon"><el-icon><Collection/></el-icon></span>
            <span class="selector-copy"><strong>{{item.name}}</strong><small>{{item.question_count}} 题 · {{item.enabled_count}} 可用</small></span>
            <el-tag size="small" :type="truthy(item.enabled)?'success':'info'" effect="plain">{{truthy(item.enabled)?'启用':'停用'}}</el-tag>
          </button>
          <el-empty v-if="!banks.length" :image-size="70" description="暂无题库"/>
        </div>
      </aside>

      <main class="exam-canvas">
        <div class="exam-workspace-head">
          <div><h2>{{activeBank?.name||'全部题目'}}</h2><p>{{activeBank?.description||'选择左侧题库查看和维护题目'}}</p></div>
          <div class="exam-head-actions"><el-button v-if="activeBank" :icon="Edit" @click="openBank(activeBank)">题库设置</el-button><el-button type="primary" :icon="Plus" @click="openQuestion()">新增题目</el-button></div>
        </div>
        <div class="exam-filter-bar question-filter">
          <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索题干或题库"/>
          <el-select v-model="typeFilter" clearable placeholder="全部题型"><el-option v-for="key in objectiveQuestionTypes" :key="key" :label="typeLabels[key]" :value="key"/></el-select>
          <el-select v-model="statusFilter" clearable placeholder="全部状态"><el-option label="已启用" value="ENABLED"/><el-option label="已停用" value="DISABLED"/></el-select>
          <span class="exam-result-count">显示 {{filteredQuestions.length}} / {{questions.length}} 道</span>
        </div>
        <el-table :data="filteredQuestions" class="exam-table" empty-text="当前题库暂无题目">
          <el-table-column label="题目" min-width="240"><template #default="s"><div class="question-title"><el-tag size="small" effect="plain">{{typeLabels[s.row.question_type]}}</el-tag><div><strong>{{s.row.stem}}</strong><span>{{s.row.bank_name}} · 默认 {{s.row.default_score}} 分</span></div></div></template></el-table-column>
          <el-table-column label="专业标签" min-width="110"><template #default="s"><div class="tag-list"><el-tag v-for="tag in tagsOf(s.row)" :key="tag" size="small" effect="plain">{{tag}}</el-tag><span v-if="!tagsOf(s.row).length" class="muted">公共题</span></div></template></el-table-column>
          <el-table-column label="正确答案" min-width="96" show-overflow-tooltip><template #default="s">{{displayAnswer(s.row)}}</template></el-table-column>
          <el-table-column label="状态" width="72"><template #default="s"><el-switch v-model="s.row.enabled" @change="toggleQuestion(s.row)"/></template></el-table-column>
          <el-table-column label="操作" width="104" fixed="right"><template #default="s"><div class="exam-table-actions"><el-button link type="primary" @click="openQuestion(s.row)">编辑</el-button><el-button link type="danger" @click="deleteQuestion(s.row)">删除</el-button></div></template></el-table-column>
        </el-table>
      </main>
    </section>

    <el-dialog v-model="bankDialog" :title="bank.id?'编辑题库':'新建题库'" width="520px">
      <div class="exam-dialog-intro"><span><el-icon><Collection/></el-icon></span><div><strong>建立清晰的题目边界</strong><p>建议按业务板块、专业方向或考试用途划分题库，名称应便于组卷人员识别。</p></div></div>
      <el-form label-position="top"><el-form-item label="题库名称" required><el-input v-model="bank.name" maxlength="128" show-word-limit placeholder="例如：机动车安全规范"/></el-form-item><el-form-item label="题库说明"><el-input v-model="bank.description" type="textarea" :rows="3" maxlength="500" show-word-limit/></el-form-item><el-form-item v-if="bank.id" label="题库状态"><el-switch v-model="bank.enabled" active-text="启用" inactive-text="停用"/></el-form-item></el-form>
      <template #footer><el-button @click="bankDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveBank">保存</el-button></template>
    </el-dialog>

    <el-drawer v-model="questionDialog" size="680px" :close-on-click-modal="false">
      <template #header><div class="exam-drawer-title"><h3>{{question.id?'编辑题目':'新增题目'}}</h3><p>配置题干、标准答案与解析，保存后即可参与组卷。</p></div></template>
      <el-form label-position="top">
        <div class="exam-form-grid"><el-form-item label="所属题库" required><el-select v-model="question.bankId" filterable><el-option v-for="item in enabledBanks" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item><el-form-item label="题型" required><el-select v-model="question.type" @change="questionTypeChanged"><el-option v-for="key in objectiveQuestionTypes" :key="key" :label="typeLabels[key]" :value="key"/></el-select></el-form-item></div>
        <el-form-item label="题干" required><el-input v-model="question.stem" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="请输入清晰、无歧义的题目描述"/></el-form-item>
        <el-form-item v-if="['SINGLE','MULTIPLE'].includes(question.type)" label="选项" required><el-select v-model="question.options" multiple allow-create filterable default-first-option placeholder="输入选项后按回车"/></el-form-item>
        <el-form-item label="正确答案" required><el-radio-group v-if="question.type==='TRUE_FALSE'" v-model="question.answer"><el-radio :value="true">正确</el-radio><el-radio :value="false">错误</el-radio></el-radio-group><el-select v-else v-model="question.answer" :multiple="question.type==='MULTIPLE'" placeholder="请先填写选项，再选择正确答案"><el-option v-for="item in question.options" :key="item" :label="item" :value="item"/></el-select></el-form-item>
        <div class="exam-form-grid"><el-form-item label="默认分值"><el-input-number v-model="question.score" :min="0.01" :precision="2" controls-position="right"/></el-form-item><el-form-item label="专业标签"><el-select v-model="question.tags" multiple allow-create filterable default-first-option collapse-tags placeholder="空白表示公共题"><el-option v-for="tag in tagOptions" :key="tag" :label="tag" :value="tag"/></el-select></el-form-item></div>
        <el-form-item label="答案解析"><el-input v-model="question.explanation" type="textarea" :rows="3" placeholder="用于阅卷复核和员工学习反馈"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="questionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveQuestion">保存题目</el-button></template>
    </el-drawer>

    <el-dialog v-model="importDialog" title="批量导入题目" width="560px">
      <div class="import-target"><span>导入到</span><strong>{{activeBank?.name||'请先选择题库'}}</strong></div>
      <div class="exam-upload-zone"><el-icon><Upload/></el-icon><strong>{{importFile?.name||'选择 Excel 题库文件'}}</strong><span>系统将先校验全部数据，通过后一次性写入</span><label><input type="file" accept=".xlsx,.xls" @change="pickImportFile">选择文件</label></div>
      <template #footer><el-button @click="downloadTemplate">下载模板</el-button><el-button @click="importDialog=false">取消</el-button><el-button type="primary" :loading="importing" @click="importQuestions">开始导入</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.question-filter{grid-template-columns:minmax(240px,1fr) 150px 150px auto}.question-title{display:flex;align-items:flex-start;gap:10px}.question-title>div{display:flex;min-width:0;flex-direction:column;gap:5px}.question-title strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.question-title span{color:#8b96a7;font-size:11px}.tag-list{display:flex;align-items:center;flex-wrap:wrap;gap:4px}.import-target{display:flex;gap:8px;margin-bottom:12px;color:#7d899a}.import-target strong{color:#344054}.exam-upload-zone{display:flex;min-height:190px;align-items:center;justify-content:center;flex-direction:column;border:1px dashed #cfd9e5;border-radius:10px;background:#fafcff}.exam-upload-zone>.el-icon{color:#3d83c8;font-size:34px}.exam-upload-zone strong{margin-top:10px}.exam-upload-zone span{margin:7px 0 14px;color:#8b96a7;font-size:12px}.exam-upload-zone label{padding:7px 14px;border-radius:6px;color:#fff;background:#409eff;cursor:pointer}.exam-upload-zone input{display:none}@media(max-width:900px){.question-filter{grid-template-columns:1fr}}
</style>
