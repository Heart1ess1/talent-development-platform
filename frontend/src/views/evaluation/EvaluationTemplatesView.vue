<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {CopyDocument,Delete,Plus,Setting,SwitchButton} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {componentDefinitions,emptyTemplate,monthText,schemeStatus} from '@/evaluation/model'
import '@/styles/evaluation-center.css'

const templates=ref<any[]>([]),schemes=ref<any[]>([]),batches=ref<any[]>([]),loading=ref(false),saving=ref(false)
const sourceOptions=reactive<{tasks:any[];exams:any[]}>({tasks:[],exams:[]})
const editorOpen=ref(false),editingId=ref<number>(),editor=reactive<any>(emptyTemplate())
const applyOpen=ref(false),application=reactive<any>({templateId:null,batchId:null,effectiveMonth:new Date().toISOString().slice(0,7)})
const weightTotal=computed(()=>Number(componentDefinitions.reduce((sum,item)=>sum+(editor[item.enabled]?Number(editor[item.weight]||0):0),0).toFixed(2)))
const quarterTotal=computed(()=>Number((Number(editor.quarterMonth1Weight||0)+Number(editor.quarterMonth2Weight||0)+Number(editor.quarterMonth3Weight||0)).toFixed(2)))
const activeTemplates=computed(()=>templates.value.filter(x=>x.status==='ACTIVE'))
const draftSchemes=computed(()=>schemes.value.filter(x=>x.status==='DRAFT'))
const publishedSchemes=computed(()=>schemes.value.filter(x=>x.status!=='DRAFT'))
const examWeightTotal=computed(()=>sourceWeightTotal('examSourceWeights'))
const taskWeightTotal=computed(()=>sourceWeightTotal('taskSourceWeights'))

function truthy(value:any){return value===true||value===1||value==='1'}
function resetEditor(value:any=emptyTemplate()){Object.keys(editor).forEach(key=>delete editor[key]);Object.assign(editor,value)}
function openCreate(){editingId.value=undefined;resetEditor();editorOpen.value=true}
function fromRow(row:any){const value:any={name:row.name,description:row.description||'',stationAggregationMode:row.station_aggregation_mode||'AUTO_BY_DAYS',quarterMonth1Weight:Number(row.quarter_month1_weight),quarterMonth2Weight:Number(row.quarter_month2_weight),quarterMonth3Weight:Number(row.quarter_month3_weight),bonusCap:Number(row.bonus_cap),deductionCap:Number(row.deduction_cap),examSourceWeights:(row.examSourceWeights||[]).map((x:any)=>({sourceId:Number(x.sourceId),weight:Number(x.weight)})),taskSourceWeights:(row.taskSourceWeights||[]).map((x:any)=>({sourceId:Number(x.sourceId),weight:Number(x.weight)}))};for(const item of componentDefinitions){const prefix=item.code.toLowerCase();value[item.enabled]=truthy(row[`${prefix}_enabled`]);value[item.weight]=Number(row[`${prefix}_weight`]);value[item.maxScore]=Number(row[`${prefix}_max_score`]||100)}return value}
function openEdit(row:any){editingId.value=row.id;resetEditor(fromRow(row));editorOpen.value=true}
function toggle(item:any){if(!editor[item.enabled])editor[item.weight]=0;else if(Number(editor[item.weight])<=0)editor[item.weight]=10}
async function load(){loading.value=true;try{const [templateResponse,schemeResponse,batchResponse,sourceResponse]=await Promise.all([api.get<any,Envelope<any[]>>('/evaluation/templates'),api.get<any,Envelope<any[]>>('/evaluation/schemes'),api.get<any,Envelope<any[]>>('/batches'),api.get<any,Envelope<any>>('/evaluation/source-options')]);templates.value=templateResponse.data;schemes.value=schemeResponse.data;batches.value=batchResponse.data.filter(x=>truthy(x.enabled));sourceOptions.tasks=sourceResponse.data.tasks;sourceOptions.exams=sourceResponse.data.exams}finally{loading.value=false}}
function sourceWeightTotal(key:'examSourceWeights'|'taskSourceWeights'){return Number((editor[key]||[]).reduce((sum:number,x:any)=>sum+Number(x.weight||0),0).toFixed(2))}
function addSourceWeight(key:'examSourceWeights'|'taskSourceWeights'){editor[key].push({sourceId:null,weight:10})}
function removeSourceWeight(key:'examSourceWeights'|'taskSourceWeights',index:number){editor[key].splice(index,1)}
async function save(){if(!editor.name.trim())return ElMessage.warning('请输入模板名称');if(weightTotal.value!==100)return ElMessage.warning('启用项权重合计必须为100%');if(quarterTotal.value!==100)return ElMessage.warning('季度三个月权重合计必须为100%');if(examWeightTotal.value>100||taskWeightTotal.value>100)return ElMessage.warning('任务或考试已指定权重不能超过100%');if([...editor.examSourceWeights,...editor.taskSourceWeights].some((x:any)=>!x.sourceId))return ElMessage.warning('请完整选择需要单独赋权的任务或考试');for(const rows of [editor.examSourceWeights,editor.taskSourceWeights])if(new Set(rows.map((x:any)=>x.sourceId)).size!==rows.length)return ElMessage.warning('同一个任务或考试不能重复配置权重');saving.value=true;try{if(editingId.value)await api.put(`/evaluation/templates/${editingId.value}`,editor);else await api.post('/evaluation/templates',editor);ElMessage.success(editingId.value?'模板已更新，既有月份方案不受影响':'评价模板已创建');editorOpen.value=false;await load()}finally{saving.value=false}}
async function copy(row:any){const id=(await api.post<any,Envelope<number>>(`/evaluation/templates/${row.id}/copy`)).data;await load();const created=templates.value.find(x=>x.id===id);if(created)openEdit(created);ElMessage.success('已复制为独立模板，请修改名称和规则')}
async function removeTemplate(row:any){await ElMessageBox.confirm(`删除“${row.name}”后不能再用于新月份，已应用方案和历史结果不会改变。`,'删除评价模板',{type:'warning'});await api.delete(`/evaluation/templates/${row.id}`);ElMessage.success('模板已删除');await load()}
function openApply(row?:any){application.templateId=row?.id||activeTemplates.value[0]?.id||null;application.batchId=null;application.effectiveMonth=new Date().toISOString().slice(0,7);applyOpen.value=true}
async function applyTemplate(){if(!application.templateId||!application.batchId||!application.effectiveMonth)return ElMessage.warning('请选择模板、批次和生效月份');await api.post('/evaluation/templates/apply',application);ElMessage.success('已生成方案草稿，确认后请发布');applyOpen.value=false;await load()}
async function publishScheme(row:any){await ElMessageBox.confirm(`发布后，“${row.batch_name}”从 ${monthText(row.effective_month)} 起采用此规则，确认发布吗？`,'发布月度方案',{type:'warning'});await api.post(`/evaluation/schemes/${row.id}/publish`);ElMessage.success('方案已发布并开始按生效月份匹配');await load()}
async function deleteScheme(row:any){await ElMessageBox.confirm('确认删除该方案？历史汇总快照不会被删除。','删除方案');await api.delete(`/evaluation/schemes/${row.id}`);ElMessage.success('方案已删除');await load()}
onMounted(load)
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · 评价模板</span><h1>评价模板与月度方案</h1><p>模板负责沉淀通用评分结构；应用模板时再选择批次和月份，生成独立方案快照。之后修改模板不会改写历史月份。</p></div>
      <div class="evaluation-head-actions"><el-button :icon="SwitchButton" :disabled="!activeTemplates.length" @click="openApply()">应用到月份</el-button><el-button type="primary" :icon="Plus" @click="openCreate">新建模板</el-button></div>
    </header>

    <section class="evaluation-summary-grid">
      <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Setting/></el-icon></span><div><small>可用模板</small><strong>{{activeTemplates.length}}</strong><span>可应用到任意批次月份</span></div></article>
      <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><CopyDocument/></el-icon></span><div><small>待发布方案</small><strong>{{draftSchemes.length}}</strong><span>仍可删除或重新应用</span></div></article>
      <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><SwitchButton/></el-icon></span><div><small>已发布方案</small><strong>{{publishedSchemes.filter(x=>x.status==='PUBLISHED').length}}</strong><span>按批次和月份自动匹配</span></div></article>
      <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><Setting/></el-icon></span><div><small>历史方案</small><strong>{{publishedSchemes.filter(x=>x.status==='RETIRED').length}}</strong><span>用于历史快照追溯</span></div></article>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>模板库</h2><p>建议按培养类型维护少量稳定模板；月度差异通过复制模板后调整，避免直接修改历史规则。</p></div></div>
      <div v-if="templates.length" class="template-list">
        <article v-for="row in templates" :key="row.id" class="template-card">
          <div class="template-card-head"><div><h3>{{row.name}}</h3><p>{{row.description||'暂无模板说明'}}</p></div><el-tag :type="row.status==='ACTIVE'?'success':'info'" effect="plain">{{row.status==='ACTIVE'?'可用':'停用'}}</el-tag></div>
          <div class="template-metrics"><span>启用评分项<b>{{componentDefinitions.filter(x=>truthy(row[`${x.code.toLowerCase()}_enabled`])).length}} 项</b></span><span>任务 / 考试重点<b>{{row.taskSourceWeights?.length||0}} / {{row.examSourceWeights?.length||0}} 项</b></span><span>站点汇总<b>{{row.station_aggregation_mode==='PRIMARY_STATION'?'主站计分':'按在站天数'}}</b></span><span>已应用<b>{{row.applied_count||0}} 次</b></span><span>更新人<b>{{row.updater_name}}</b></span></div>
          <div class="template-actions"><el-button link type="primary" @click="openApply(row)">应用</el-button><el-button link @click="openEdit(row)">编辑</el-button><el-button link @click="copy(row)">复制</el-button><el-button link type="danger" @click="removeTemplate(row)">删除</el-button></div>
        </article>
      </div>
      <div v-else class="evaluation-empty">还没有评价模板。先创建一个模板，再应用到具体批次和月份。</div>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>待发布方案</h2><p>应用模板只生成草稿；发布后才会成为批次在对应月份的正式计算规则。</p></div></div>
      <el-table :data="draftSchemes" empty-text="暂无待发布方案">
        <el-table-column prop="batch_name" label="培养批次" min-width="140"/><el-table-column prop="template_name" label="来源模板" min-width="150"><template #default="s">{{s.row.template_name||'历史手工方案'}}</template></el-table-column><el-table-column label="生效月份" width="105"><template #default="s">{{monthText(s.row.effective_month)}}</template></el-table-column><el-table-column prop="version" label="版本" width="75"><template #default="s">V{{s.row.version}}</template></el-table-column><el-table-column label="操作" width="150"><template #default="s"><el-button link type="primary" @click="publishScheme(s.row)">发布</el-button><el-button link type="danger" @click="deleteScheme(s.row)">删除</el-button></template></el-table-column>
      </el-table>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>已发布与历史方案</h2><p>同一批次新方案发布后，旧方案自动转为历史版本；已经生成的结果仍保留快照。</p></div></div>
      <el-table :data="publishedSchemes" empty-text="暂无已发布方案">
        <el-table-column prop="batch_name" label="培养批次" min-width="140"/><el-table-column prop="template_name" label="来源模板" min-width="150"><template #default="s">{{s.row.template_name||'历史手工方案'}}</template></el-table-column><el-table-column label="生效月份" width="105"><template #default="s">{{monthText(s.row.effective_month)}}</template></el-table-column><el-table-column prop="version" label="版本" width="75"><template #default="s">V{{s.row.version}}</template></el-table-column><el-table-column label="状态" width="100"><template #default="s"><el-tag :type="s.row.status==='PUBLISHED'?'success':'info'" effect="plain">{{schemeStatus(s.row.status)}}</el-tag></template></el-table-column><el-table-column label="操作" width="90"><template #default="s"><el-button link type="danger" @click="deleteScheme(s.row)">删除</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="editorOpen" size="min(760px, 94vw)" :close-on-click-modal="false">
      <template #header><div><h3 style="margin:0">{{editingId?'编辑评价模板':'新建评价模板'}}</h3><p class="evaluation-muted">设置每项满分与综合权重；自动来源会先换算到本项满分，再参与百分制综合计算。</p></div></template>
      <el-form label-position="top">
        <el-form-item label="模板名称" required><el-input v-model="editor.name" maxlength="128" show-word-limit placeholder="例如：生产一线新员工月度评价"/></el-form-item>
        <el-form-item label="适用说明"><el-input v-model="editor.description" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明适用人群、评价目标和使用注意事项"/></el-form-item>
        <div class="template-component-row" style="font-size:12px;color:#8a96a8"><span>启用</span><span>评分项</span><span>本项满分</span><span>综合权重</span></div>
        <div v-for="item in componentDefinitions" :key="item.code" class="template-component-row"><el-switch v-model="editor[item.enabled]" @change="toggle(item)"/><div><strong>{{item.label}}</strong><small>{{item.description}}</small></div><el-input-number v-model="editor[item.maxScore]" :min="1" :max="999.99" :precision="2" controls-position="right"/><el-input-number v-model="editor[item.weight]" :disabled="!editor[item.enabled]" :min="0" :max="100" :precision="2" controls-position="right"/></div>
        <div class="template-total"><span>启用项权重合计</span><strong :class="{bad:weightTotal!==100}">{{weightTotal.toFixed(2)}}%</strong></div>
        <el-form-item v-if="editor.stationEnabled" label="跨站月份的站点评价汇总方式" style="margin-top:18px">
          <el-radio-group v-model="editor.stationAggregationMode"><el-radio-button value="AUTO_BY_DAYS">按实际在站天数加权</el-radio-button><el-radio-button value="PRIMARY_STATION">仅采用在站最久站点</el-radio-button></el-radio-group>
          <div class="evaluation-muted" style="margin-top:8px">月度评分时管理员仍可针对个别员工手动调整站点占比，不会修改模板。</div>
        </el-form-item>
        <el-divider content-position="left">任务与考试内部权重</el-divider>
        <el-alert title="只配置重点项目即可" description="已指定项目按设置权重计算；当月其他项目自动平分剩余权重。若当月只命中已指定项目，系统会按比例归一化到 100%。" type="info" :closable="false" show-icon/>
        <section class="source-weight-section">
          <div class="source-weight-head"><div><strong>任务成果权重</strong><small>例如重点任务 40%，其他任务自动平分剩余 60%</small></div><el-button :icon="Plus" @click="addSourceWeight('taskSourceWeights')">添加重点任务</el-button></div>
          <div v-for="(row,index) in editor.taskSourceWeights" :key="`task-${index}`" class="source-weight-row"><el-select v-model="row.sourceId" filterable placeholder="选择任务"><el-option v-for="option in sourceOptions.tasks" :key="option.id" :value="option.id" :label="option.name"/></el-select><el-input-number v-model="row.weight" :min="0.01" :max="100" :precision="2" controls-position="right"/><span>%</span><el-button circle text type="danger" :icon="Delete" @click="removeSourceWeight('taskSourceWeights',index)"/></div>
          <div class="template-total"><span>已指定任务权重</span><strong :class="{bad:taskWeightTotal>100}">{{taskWeightTotal.toFixed(2)}}%</strong></div>
        </section>
        <section class="source-weight-section">
          <div class="source-weight-head"><div><strong>考试权重</strong><small>适合区分安全考试、技能考试等不同重点</small></div><el-button :icon="Plus" @click="addSourceWeight('examSourceWeights')">添加重点考试</el-button></div>
          <div v-for="(row,index) in editor.examSourceWeights" :key="`exam-${index}`" class="source-weight-row"><el-select v-model="row.sourceId" filterable placeholder="选择考试"><el-option v-for="option in sourceOptions.exams" :key="option.id" :value="option.id" :label="`${option.name}（${String(option.score_month).slice(0,7)}）`"/></el-select><el-input-number v-model="row.weight" :min="0.01" :max="100" :precision="2" controls-position="right"/><span>%</span><el-button circle text type="danger" :icon="Delete" @click="removeSourceWeight('examSourceWeights',index)"/></div>
          <div class="template-total"><span>已指定考试权重</span><strong :class="{bad:examWeightTotal>100}">{{examWeightTotal.toFixed(2)}}%</strong></div>
        </section>
        <el-divider content-position="left">季度与加扣分</el-divider>
        <div class="evaluation-filters"><el-form-item label="第1月权重"><el-input-number v-model="editor.quarterMonth1Weight" :min="0.01" :precision="2"/></el-form-item><el-form-item label="第2月权重"><el-input-number v-model="editor.quarterMonth2Weight" :min="0.01" :precision="2"/></el-form-item><el-form-item label="第3月权重"><el-input-number v-model="editor.quarterMonth3Weight" :min="0.01" :precision="2"/></el-form-item></div>
        <div class="template-total"><span>季度权重合计</span><strong :class="{bad:quarterTotal!==100}">{{quarterTotal.toFixed(2)}}%</strong></div>
        <div class="evaluation-filters" style="margin-top:15px"><el-form-item label="加分上限"><el-input-number v-model="editor.bonusCap" :min="0" :max="100" :precision="2"/></el-form-item><el-form-item label="扣分上限"><el-input-number v-model="editor.deductionCap" :min="0" :max="100" :precision="2"/></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="editorOpen=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="weightTotal!==100||quarterTotal!==100||!editor.name.trim()" @click="save">保存模板</el-button></template>
    </el-drawer>

    <el-dialog v-model="applyOpen" title="应用模板到月份" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-alert title="应用后生成独立方案草稿；发布前不会影响当前计算。" type="info" :closable="false" show-icon/>
      <el-form label-position="top" style="margin-top:18px"><el-form-item label="评价模板" required><el-select v-model="application.templateId" style="width:100%"><el-option v-for="row in activeTemplates" :key="row.id" :value="row.id" :label="row.name"/></el-select></el-form-item><el-form-item label="培养批次" required><el-select v-model="application.batchId" filterable style="width:100%"><el-option v-for="row in batches" :key="row.id" :value="row.id" :label="row.name"/></el-select></el-form-item><el-form-item label="生效月份" required><el-date-picker v-model="application.effectiveMonth" type="month" value-format="YYYY-MM" style="width:100%"/></el-form-item></el-form>
      <template #footer><el-button @click="applyOpen=false">取消</el-button><el-button type="primary" @click="applyTemplate">生成方案草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.source-weight-section{margin-top:16px;padding:16px;border:1px solid #e7ebf2;border-radius:12px;background:#fafbfd}.source-weight-head{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:12px}.source-weight-head div{display:flex;flex-direction:column;gap:4px}.source-weight-head small{color:#8490a3}.source-weight-row{display:grid;grid-template-columns:minmax(220px,1fr) 150px 18px 36px;gap:8px;align-items:center;margin:8px 0}.source-weight-row .el-select{width:100%}
</style>
