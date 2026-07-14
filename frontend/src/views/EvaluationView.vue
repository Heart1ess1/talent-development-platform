<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue';
import {api,type Envelope} from '@/api';
import {ElMessage,ElMessageBox} from 'element-plus';
import {useAuthStore} from '@/stores/auth';

type ComponentCode='EXAM'|'TASK'|'MENTOR'|'STATION'|'TRAINING';
interface ScoreComponent{code:ComponentCode;enabled:boolean;weight:number;sourceType:'AUTO'|'MANUAL';sourceScore:number|null;overrideScore:number|null;effectiveScore:number|null;weightedScore:number|null;status:string;comment?:string;evaluatorName?:string;overrideReason?:string;overrideBy?:string}

const auth=useAuthStore();
const canManage=computed(()=>auth.can('evaluation:manage'));
const canSubmit=computed(()=>auth.can('evaluation:submit'));
const isAdmin=computed(()=>['ADMIN','SUPER_ADMIN'].includes(auth.user?.role||''));
const isEmployee=computed(()=>auth.user?.role==='EMPLOYEE');
const employees=ref<any[]>([]),selected=ref<number>(),selectedMonth=ref(new Date().toISOString().slice(0,7));
const detail=ref<any>(),summaries=ref<any[]>([]),schemes=ref<any[]>([]),batches=ref<any[]>([]),loading=ref(false);
const inputs=reactive<Record<ComponentCode,{score:number;comment:string}>>({EXAM:{score:80,comment:''},TASK:{score:80,comment:''},MENTOR:{score:80,comment:''},STATION:{score:80,comment:''},TRAINING:{score:80,comment:''}});
const adjustment=reactive({type:'BONUS',points:1,reason:''});
const editingSchemeId=ref<number>();
const labels:Record<string,string>={EXAM:'考试成绩',TASK:'任务成绩',MENTOR:'导师评价',STATION:'站点评价',TRAINING:'培训评价'};
const statusLabels:Record<string,string>={DISABLED:'已关闭',PENDING:'待评分',AUTOMATIC:'自动取分',OVERRIDDEN:'人工覆盖',SUBMITTED:'已提交'};
const schemeComponents=[
  {code:'EXAM',label:'考试',enabled:'examEnabled',weight:'examWeight'},
  {code:'TASK',label:'任务',enabled:'taskEnabled',weight:'taskWeight'},
  {code:'MENTOR',label:'导师',enabled:'mentorEnabled',weight:'mentorWeight'},
  {code:'STATION',label:'站长',enabled:'stationEnabled',weight:'stationWeight'},
  {code:'TRAINING',label:'培训方',enabled:'trainingEnabled',weight:'trainingWeight'}
] as const;
const scheme=reactive<any>({batchId:null,effectiveMonth:selectedMonth.value,examEnabled:true,examWeight:20,taskEnabled:true,taskWeight:30,mentorEnabled:true,mentorWeight:15,stationEnabled:true,stationWeight:15,trainingEnabled:true,trainingWeight:20,quarterMonth1Weight:33.33,quarterMonth2Weight:33.33,quarterMonth3Weight:33.34,bonusCap:10,deductionCap:10});
const schemeWeightTotal=computed(()=>schemeComponents.reduce((n,x)=>n+Number(scheme[x.weight]||0),0));
const quarterWeightTotal=computed(()=>Number(scheme.quarterMonth1Weight||0)+Number(scheme.quarterMonth2Weight||0)+Number(scheme.quarterMonth3Weight||0));
const draftSchemes=computed(()=>schemes.value.filter(x=>x.status==='DRAFT'));
const publishedSchemes=computed(()=>schemes.value.filter(x=>x.status!=='DRAFT'));
function inputFor(code:string){return inputs[code as ComponentCode]}

function componentEditable(code:ComponentCode){
  const expected:Record<string,ComponentCode>={MENTOR:'MENTOR',STATION_MANAGER:'STATION',TRAINING_ADMIN:'TRAINING'};
  return canSubmit.value&&expected[auth.user?.role||'']===code&&!detail.value?.locked;
}
function scoreText(value:any){return value===null||value===undefined?'—':Number(value).toFixed(2)}
function snapshot(row:any){return row.summary_type==='MONTH'?row.component_snapshot?.components:row.quarter_snapshot?.months}
function toggleScheme(item:any){if(!scheme[item.enabled])scheme[item.weight]=0;else if(Number(scheme[item.weight])<=0)scheme[item.weight]=10}
function editScheme(row:any){editingSchemeId.value=row.id;Object.assign(scheme,{batchId:row.batch_id,effectiveMonth:String(row.effective_month).slice(0,7),examEnabled:!!row.exam_enabled,examWeight:Number(row.exam_weight),taskEnabled:!!row.task_enabled,taskWeight:Number(row.task_weight),mentorEnabled:!!row.mentor_enabled,mentorWeight:Number(row.mentor_weight),stationEnabled:!!row.station_enabled,stationWeight:Number(row.station_weight),trainingEnabled:!!row.training_enabled,trainingWeight:Number(row.training_weight),quarterMonth1Weight:Number(row.quarter_month1_weight),quarterMonth2Weight:Number(row.quarter_month2_weight),quarterMonth3Weight:Number(row.quarter_month3_weight),bonusCap:Number(row.bonus_cap),deductionCap:Number(row.deduction_cap)})}
function cancelEdit(){editingSchemeId.value=undefined}

async function load(){
  const employeeResponse=await api.get<any,Envelope<any>>('/employees',{params:{size:100}});employees.value=employeeResponse.data.records;
  if(!selected.value&&employees.value.length)selected.value=employees.value[0].id;
  if(canManage.value){const [s,b]=await Promise.all([api.get<any,Envelope<any[]>>('/evaluation/schemes'),api.get<any,Envelope<any[]>>('/batches')]);schemes.value=s.data;batches.value=b.data}
  await loadSelected();
}
async function loadSelected(){
  if(!selected.value)return;loading.value=true;
  try{
    const jobs:any[]=[api.get<any,Envelope<any[]>>('/evaluation/summaries',{params:{employeeId:selected.value}})];
    if(!isEmployee.value)jobs.push(api.get<any,Envelope<any>>('/evaluation/monthly/detail',{params:{employeeId:selected.value,month:selectedMonth.value}}));
    const result=await Promise.all(jobs);summaries.value=result[0].data;
    if(!isEmployee.value){detail.value=result[1].data;for(const c of detail.value.components){inputFor(c.code).score=Number(c.effectiveScore??80);inputFor(c.code).comment=c.comment||''}}
  }finally{loading.value=false}
}
async function submitComponent(c:ScoreComponent){
  await api.put(`/evaluation/monthly/components/${c.code}`,{employeeId:selected.value,month:selectedMonth.value,...inputs[c.code]});ElMessage.success(`${labels[c.code]}已保存`);await loadSelected();
}
async function overrideScore(c:ScoreComponent){
  const score=Number((await ElMessageBox.prompt(`原始成绩：${scoreText(c.sourceScore)}`,'覆盖成绩',{inputPattern:/^(100(?:\.0{1,2})?|\d{1,2}(?:\.\d{1,2})?)$/,inputErrorMessage:'请输入0至100的成绩'})).value);
  const reason=(await ElMessageBox.prompt('请输入覆盖原因','覆盖成绩')).value;
  await api.put(`/evaluation/monthly/overrides/${c.code}`,{employeeId:selected.value,month:selectedMonth.value,score,reason});ElMessage.success('覆盖成绩已保存');await loadSelected();
}
async function removeOverride(c:ScoreComponent){await ElMessageBox.confirm('确认撤销该人工覆盖并恢复自动成绩？','撤销覆盖');await api.delete(`/evaluation/monthly/overrides/${c.code}`,{params:{employeeId:selected.value,month:selectedMonth.value}});await loadSelected()}
async function addAdjustment(){await api.post('/evaluation/adjustments',{employeeId:selected.value,month:selectedMonth.value,...adjustment});ElMessage.success('加扣分已登记');adjustment.reason='';await loadSelected()}
async function saveScheme(){if(schemeWeightTotal.value!==100||quarterWeightTotal.value!==100)return ElMessage.warning('月度及季度权重都必须合计100%');if(editingSchemeId.value)await api.put(`/evaluation/schemes/${editingSchemeId.value}`,scheme);else await api.post('/evaluation/schemes',scheme);ElMessage.success(editingSchemeId.value?'评分方案已修改':'评分方案已创建');editingSchemeId.value=undefined;await load()}
async function publishScheme(id:number){await api.post(`/evaluation/schemes/${id}/publish`);ElMessage.success('评分方案已发布');await load()}
async function revisePublished(row:any){const result=await api.post<any,Envelope<number>>(`/evaluation/schemes/${row.id}/draft`);await load();const draft=schemes.value.find(x=>x.id===result.data);if(draft)editScheme(draft);ElMessage.success('已生成新版本草稿，请修改后保存并发布')}
async function deleteScheme(row:any){await ElMessageBox.confirm(row.status==='DRAFT'?'确认删除该方案草稿？':'确认删除该已发布方案？历史汇总快照不会被删除。','删除评分方案');await api.delete(`/evaluation/schemes/${row.id}`);ElMessage.success('评分方案已删除');if(editingSchemeId.value===row.id)cancelEdit();await load()}
async function generateMonth(){await api.post('/evaluation/summaries/generate-month',null,{params:{month:selectedMonth.value}});ElMessage.success('月度草稿已生成或更新');await loadSelected()}
async function generateQuarter(){const year=Number((await ElMessageBox.prompt('年份','生成季度汇总',{inputValue:String(new Date().getFullYear())})).value);const quarter=Number((await ElMessageBox.prompt('季度（1-4）','生成季度汇总')).value);await api.post('/evaluation/summaries/generate-quarter',null,{params:{year,quarter}});ElMessage.success('季度草稿已生成或更新');await loadSelected()}
async function publish(row:any){let waiverReason:string|undefined,overrideScore:number|undefined;if(row.missing_items){if(!isAdmin.value)return ElMessage.warning('缺失评分仅管理员可豁免');waiverReason=(await ElMessageBox.prompt('请输入豁免原因','发布缺失汇总')).value;overrideScore=Number((await ElMessageBox.prompt('请输入人工核定总分','发布缺失汇总')).value)}await api.post(`/evaluation/summaries/${row.id}/publish`,{waiverReason,overrideScore});ElMessage.success('汇总已发布');await loadSelected()}
async function reopen(row:any){const reason=(await ElMessageBox.prompt('请输入重开原因','重开月度汇总')).value;await api.post(`/evaluation/summaries/${row.id}/reopen`,{reason});ElMessage.success('已创建新的月度草稿版本');await loadSelected()}

watch([selected,selectedMonth],loadSelected);onMounted(load);
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head"><h2>综合评价</h2><div v-if="canManage"><el-button @click="generateMonth">生成月度汇总</el-button><el-button @click="generateQuarter">生成季度汇总</el-button></div></div>
    <div class="toolbar"><el-select v-model="selected" filterable placeholder="选择员工"><el-option v-for="x in employees" :key="x.id" :label="`${x.name} (${x.employee_no})`" :value="x.id"/></el-select><el-date-picker v-if="!isEmployee" v-model="selectedMonth" type="month" value-format="YYYY-MM"/></div>

    <template v-if="detail&&!isEmployee">
      <el-alert v-if="detail.locked" title="该月评价已经发布并锁定；如需修改，请由管理员重开月度汇总。" type="warning" :closable="false"/>
      <div class="component-grid">
        <el-card v-for="c in detail.components" :key="c.code" :class="{disabled:!c.enabled}">
          <template #header><div class="card-head"><strong>{{labels[c.code]}}</strong><el-tag :type="c.status==='PENDING'?'warning':c.status==='DISABLED'?'info':'success'">{{statusLabels[c.status]}}</el-tag></div></template>
          <div class="score-line"><span>有效得分</span><b>{{scoreText(c.effectiveScore)}}</b></div>
          <div class="meta"><span>权重 {{c.weight}}%</span><span>加权贡献 {{scoreText(c.weightedScore)}}</span></div>
          <template v-if="c.sourceType==='AUTO'&&c.enabled"><p class="muted">系统原始分：{{scoreText(c.sourceScore)}}</p></template>
          <template v-else-if="componentEditable(c.code)"><el-input-number v-model="inputFor(c.code).score" :min="0" :max="100" :precision="2"/><el-input v-model="inputFor(c.code).comment" type="textarea" placeholder="评价意见"/><el-button type="primary" size="small" @click="submitComponent(c)">保存本项评价</el-button></template>
          <template v-else-if="c.sourceType==='MANUAL'&&c.enabled"><p class="muted">评价人：{{c.evaluatorName||'尚未提交'}}</p><p v-if="c.comment">评价意见：{{c.comment}}</p></template>
          <p v-if="c.overrideScore!==null" class="override">覆盖原因：{{c.overrideReason}}（{{c.overrideBy}}）</p><div v-if="isAdmin&&c.enabled&&!detail.locked"><el-button size="small" @click="overrideScore(c)">{{c.overrideScore===null?'覆盖成绩':'修改覆盖'}}</el-button><el-button v-if="c.overrideScore!==null" size="small" @click="removeOverride(c)">撤销覆盖</el-button></div>
        </el-card>
      </div>
      <el-card class="total-card"><div class="total"><span>加分 {{scoreText(detail.bonus)}}　扣分 {{scoreText(detail.deduction)}}</span><strong>月度预览：{{scoreText(detail.finalScore)}}</strong></div><div v-if="detail.missingItems.length" class="missing">缺失项：{{detail.missingItems.map((x:string)=>labels[x]).join('、')}}</div></el-card>
      <el-card v-if="canManage" class="section"><template #header>加扣分</template><div class="toolbar"><el-select v-model="adjustment.type"><el-option label="加分" value="BONUS"/><el-option label="扣分" value="DEDUCTION"/></el-select><el-input-number v-model="adjustment.points" :min="0.01" :precision="2"/><el-input v-model="adjustment.reason" placeholder="原因"/><el-button :disabled="detail.locked||!adjustment.reason" @click="addAdjustment">登记</el-button></div></el-card>
    </template>

    <el-card class="section"><template #header>月度 / 季度汇总</template><el-table :data="summaries" empty-text="暂无汇总"><el-table-column type="expand"><template #default="s"><div v-if="snapshot(s.row)" class="snapshot"><div v-for="item in snapshot(s.row)" :key="item.code||item.month"><b>{{item.code?labels[item.code]:item.month}}</b><span>得分 {{scoreText(item.effectiveScore??item.score)}}</span><span>权重 {{item.weight}}%</span><span>贡献 {{scoreText(item.weightedScore??item.contribution)}}</span></div></div><span v-else>历史汇总暂无分项快照</span></template></el-table-column><el-table-column prop="period_key" label="期间"/><el-table-column prop="summary_type" label="类型"><template #default="s">{{s.row.summary_type==='MONTH'?'月度':'季度'}}</template></el-table-column><el-table-column prop="version" label="版本"/><el-table-column label="综合分"><template #default="s">{{scoreText(s.row.final_score)}}</template></el-table-column><el-table-column prop="status" label="状态"/><el-table-column prop="missing_items" label="缺失项"/><el-table-column v-if="canManage" label="操作" width="150"><template #default="s"><el-button v-if="s.row.status==='DRAFT'" link @click="publish(s.row)">发布</el-button><el-button v-if="isAdmin&&s.row.status==='PUBLISHED'&&s.row.summary_type==='MONTH'" link @click="reopen(s.row)">重开</el-button></template></el-table-column></el-table></el-card>

    <el-card v-if="canManage" class="section"><template #header>评分方案</template><div class="scheme-form"><el-select v-model="scheme.batchId" :disabled="!!editingSchemeId" placeholder="培养批次"><el-option v-for="x in batches" :key="x.id" :label="x.name" :value="x.id"/></el-select><el-date-picker v-model="scheme.effectiveMonth" type="month" value-format="YYYY-MM" placeholder="生效月份"/><div v-for="item in schemeComponents" :key="item.code" class="weight-item"><el-switch v-model="scheme[item.enabled]" @change="toggleScheme(item)"/><span>{{item.label}}</span><el-input-number v-model="scheme[item.weight]" :disabled="!scheme[item.enabled]" :min="0" :max="100" :precision="2"/></div><div>月度合计：<b :class="{bad:schemeWeightTotal!==100}">{{schemeWeightTotal.toFixed(2)}}%</b></div><div class="quarter"><span>季度第1月</span><el-input-number v-model="scheme.quarterMonth1Weight" :precision="2"/><span>第2月</span><el-input-number v-model="scheme.quarterMonth2Weight" :precision="2"/><span>第3月</span><el-input-number v-model="scheme.quarterMonth3Weight" :precision="2"/><span>合计 <b :class="{bad:quarterWeightTotal!==100}">{{quarterWeightTotal.toFixed(2)}}%</b></span></div><div><span>加分上限</span><el-input-number v-model="scheme.bonusCap" :min="0"/><span> 扣分上限</span><el-input-number v-model="scheme.deductionCap" :min="0"/></div><el-button type="primary" :disabled="!scheme.batchId||schemeWeightTotal!==100||quarterWeightTotal!==100" @click="saveScheme">{{editingSchemeId?'保存修改':'创建方案草稿'}}</el-button><el-button v-if="editingSchemeId" @click="cancelEdit">取消修改</el-button></div>
      <h3>未发布方案</h3><el-table :data="draftSchemes" empty-text="暂无未发布方案"><el-table-column prop="batch_id" label="批次ID"/><el-table-column prop="version" label="版本"/><el-table-column prop="effective_month" label="生效月份"/><el-table-column label="操作" width="220"><template #default="s"><el-button link @click="editScheme(s.row)">修改</el-button><el-button link @click="publishScheme(s.row.id)">发布</el-button><el-button link type="danger" @click="deleteScheme(s.row)">删除</el-button></template></el-table-column></el-table>
      <h3 class="published-title">已发布方案</h3><el-table :data="publishedSchemes" empty-text="暂无已发布方案"><el-table-column prop="batch_id" label="批次ID"/><el-table-column prop="version" label="版本"/><el-table-column prop="effective_month" label="生效月份"/><el-table-column prop="status" label="状态"><template #default="s">{{s.row.status==='PUBLISHED'?'当前发布':'历史版本'}}</template></el-table-column><el-table-column label="操作" width="180"><template #default="s"><el-button link @click="revisePublished(s.row)">修改</el-button><el-button link type="danger" @click="deleteScheme(s.row)">删除</el-button></template></el-table-column></el-table>
    </el-card>
  </div>
</template>

<style scoped>
.component-grid{display:grid;grid-template-columns:repeat(5,minmax(180px,1fr));gap:12px;margin:16px 0}.component-grid .disabled{opacity:.55}.card-head,.total,.score-line,.meta{display:flex;align-items:center;justify-content:space-between}.score-line b{font-size:28px;color:#1769aa}.meta{font-size:13px;color:#667085;margin:10px 0}.component-grid :deep(.el-textarea),.component-grid :deep(.el-input-number){margin-bottom:8px;width:100%}.override,.missing,.bad{color:#d97706}.total-card,.section{margin-top:16px}.total strong{font-size:22px}.snapshot{display:grid;gap:8px;padding:8px 24px}.snapshot>div{display:grid;grid-template-columns:140px repeat(3,120px)}.scheme-form{display:flex;flex-wrap:wrap;align-items:center;gap:12px;margin-bottom:16px}.weight-item{display:flex;align-items:center;gap:6px}.weight-item :deep(.el-input-number){width:110px}.quarter{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.quarter :deep(.el-input-number){width:110px}@media(max-width:1200px){.component-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:700px){.component-grid{grid-template-columns:1fr}.snapshot>div{grid-template-columns:1fr 1fr}}
</style>
