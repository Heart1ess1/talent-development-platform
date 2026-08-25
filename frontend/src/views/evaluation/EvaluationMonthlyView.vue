<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {CircleCheck,Document,EditPen,Refresh,Right,Search,User} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {loadDictionaryValues,type DictionaryOption} from '@/utils/masterData'
import {componentLabels,componentStatusLabels,scoreText,type ComponentCode,type ScoreComponent} from '@/evaluation/model'
import '@/styles/evaluation-center.css'

type CandidateStatus='MY_PENDING'|'IN_PROGRESS'|'READY'|'PUBLISHED'|'NO_SCHEME'
interface Candidate{id:number;employee_no:string;name:string;class_id?:number;class_name?:string;class_position_id?:number;class_position_name?:string;batch_name?:string;station_name?:string;status:CandidateStatus;completedCount:number;enabledCount:number;finalScore:number|null;missingItems:ComponentCode[]}
interface ManualInput{score:number;comment:string}

const auth=useAuthStore(),route=useRoute(),router=useRouter()
const canManage=computed(()=>auth.can('evaluation:manage'))
const canSubmit=computed(()=>auth.can('evaluation:submit'))
const isAdmin=computed(()=>['ADMIN','SUPER_ADMIN'].includes(auth.user?.role||''))
const month=ref(new Date().toISOString().slice(0,7)),candidates=ref<Candidate[]>([]),selected=ref<number>(),detail=ref<any>()
const loading=ref(false),queueLoading=ref(false),loadError=ref(''),keyword=ref(''),statusFilter=ref('')
const classId=ref<number|null>(null),classOptions=ref<DictionaryOption[]>([])
const classPositionId=ref<number|null>(null),classPositionOptions=ref<DictionaryOption[]>([])
const manualInputs=reactive<Record<string,ManualInput>>({})
const adjustment=reactive({type:'BONUS',points:1,reason:''})
const stationWeightOpen=ref(false),stationWeights=ref<any[]>([])
const roleComponent=computed<ComponentCode|undefined>(()=>({MENTOR:'MENTOR',STATION_MANAGER:'STATION',TRAINING_ADMIN:'TRAINING'} as Record<string,ComponentCode>)[auth.user?.role||''])
const completedCount=computed(()=>detail.value?.components?.filter((x:ScoreComponent)=>x.enabled&&x.status!=='PENDING').length||0)
const enabledCount=computed(()=>detail.value?.components?.filter((x:ScoreComponent)=>x.enabled).length||0)
const filteredCandidates=computed(()=>{const q=keyword.value.trim().toLowerCase();return candidates.value.filter(x=>(!classId.value||x.class_id===classId.value)&&(!classPositionId.value||x.class_position_id===classPositionId.value)&&(!statusFilter.value||x.status===statusFilter.value)&&(!q||`${x.name} ${x.employee_no} ${x.class_name||''} ${x.class_position_name||''} ${x.batch_name||''} ${x.station_name||''}`.toLowerCase().includes(q)))})
const queueCounts=computed(()=>Object.fromEntries(['MY_PENDING','IN_PROGRESS','READY','PUBLISHED','NO_SCHEME'].map(status=>[status,candidates.value.filter(x=>x.status===status).length])))
const selectedCandidate=computed(()=>candidates.value.find(x=>x.id===selected.value))
const stationComponent=computed<any>(()=>detail.value?.components?.find((x:any)=>x.code==='STATION'))
const stationWeightTotal=computed(()=>Number(stationWeights.value.reduce((sum,row)=>sum+Number(row.weight||0),0).toFixed(2)))

const candidateStatusLabels:Record<CandidateStatus,string>={MY_PENDING:'待我评价',IN_PROGRESS:'评价进行中',READY:'评分已齐全',PUBLISHED:'结果已发布',NO_SCHEME:'未配置方案'}
const candidateStatusTypes:Record<CandidateStatus,''|'success'|'warning'|'info'|'danger'>={MY_PENDING:'warning',IN_PROGRESS:'info',READY:'success',PUBLISHED:'',NO_SCHEME:'danger'}
const stationModeLabels:Record<string,string>={AUTO_BY_DAYS:'按在站天数自动加权',PRIMARY_STATION:'仅采用在站最久站点',MANUAL:'管理员手动设置权重'}

function manualKey(code:ComponentCode,scopeId?:number|null){return `${code}:${scopeId||0}`}
function inputFor(code:ComponentCode,scopeId?:number|null){const key=manualKey(code,scopeId);return manualInputs[key]||(manualInputs[key]={score:0,comment:''})}
function editable(component:ScoreComponent){
  if(!canSubmit.value||roleComponent.value!==component.code||detail.value?.locked)return false
  if(component.code!=='TRAINING')return true
  return component.canEvaluate===true||(component.requiredCount===1&&!component.breakdown?.length)
}
function jump(component:ScoreComponent){router.push(component.code==='EXAM'?'/exams/results':'/training-plans/tracking?focus=pending-review')}
function candidateProgress(row:Candidate){return row.enabledCount?Math.round(row.completedCount*100/row.enabledCount):0}
function chooseCandidate(id:number){selected.value=id}

async function loadCandidates(){queueLoading.value=true;try{candidates.value=(await api.get<any,Envelope<Candidate[]>>('/evaluation/monthly/candidates',{params:{month:month.value,classId:classId.value||undefined,classPositionId:classPositionId.value||undefined}})).data;const requested=Number(route.query.employeeId);if(requested&&candidates.value.some(x=>x.id===requested))selected.value=requested;else if(!selected.value||!candidates.value.some(x=>x.id===selected.value)){selected.value=(candidates.value.find(x=>x.status==='MY_PENDING')||candidates.value.find(x=>x.status==='IN_PROGRESS')||candidates.value[0])?.id}}finally{queueLoading.value=false}}
function hydrateInputs(){for(const key of Object.keys(manualInputs))delete manualInputs[key];for(const component of detail.value?.components||[]){if(component.code==='MENTOR'){for(const row of component.breakdown||[])if(Number(row.evaluatorId)===Number(auth.user?.id))Object.assign(inputFor('MENTOR'),{score:Number(row.score||0),comment:row.comment||''})}else if(component.code==='STATION'){for(const row of component.breakdown||[]){const own=(row.evaluations||[]).find((x:any)=>Number(x.evaluatorId)===Number(auth.user?.id));if(row.canEvaluate)Object.assign(inputFor('STATION',row.stationId),{score:Number(own?.score||0),comment:own?.comment||''})}}else if(component.code==='TRAINING'){const row=(component.breakdown||[]).find((x:any)=>Number(x.evaluatorId)===Number(auth.user?.id))||component.breakdown?.[0];Object.assign(inputFor('TRAINING'),{score:Number(row?.score||0),comment:row?.comment||''})}}}
async function loadDetail(){if(!selected.value){detail.value=undefined;return}loading.value=true;loadError.value='';detail.value=undefined;try{detail.value=(await api.get<any,Envelope<any>>('/evaluation/monthly/detail',{params:{employeeId:selected.value,month:month.value}})).data;hydrateInputs()}catch(error:any){loadError.value=error?.response?.data?.message||'当前月份尚未配置可用评价方案'}finally{loading.value=false}}
async function refreshAll(){await loadCandidates();await loadDetail()}
async function submit(component:ScoreComponent,scopeId?:number){const input=inputFor(component.code,scopeId);await api.put(`/evaluation/monthly/components/${component.code}`,{employeeId:selected.value,month:month.value,scopeId,...input});ElMessage.success(`${componentLabels[component.code]}已保存`);await refreshAll()}
async function override(component:ScoreComponent){const max=Number(component.fullScore||100);const value=Number((await ElMessageBox.prompt(`请输入 0 至 ${max} 分；系统原始分为 ${scoreText(component.sourceScore)}`,'人工核定',{inputPattern:new RegExp(`^(?:${max}(?:\\.0{1,2})?|(?:\\d|[1-9]\\d)(?:\\.\\d{1,2})?)$`),inputErrorMessage:`请输入不超过 ${max} 的分数`})).value);if(value>max)return ElMessage.warning(`评分不能超过 ${max}`);const reason=(await ElMessageBox.prompt('说明为什么需要人工核定','核定原因')).value;await api.put(`/evaluation/monthly/overrides/${component.code}`,{employeeId:selected.value,month:month.value,score:value,reason});ElMessage.success('人工核定已保存');await refreshAll()}
async function removeOverride(component:ScoreComponent){await ElMessageBox.confirm('撤销后将恢复自动或原始人工评分，确认继续吗？','撤销人工核定');await api.delete(`/evaluation/monthly/overrides/${component.code}`,{params:{employeeId:selected.value,month:month.value}});await refreshAll()}
async function addAdjustment(){await api.post('/evaluation/adjustments',{employeeId:selected.value,month:month.value,...adjustment});ElMessage.success('加扣分已登记');adjustment.reason='';await refreshAll()}
async function generate(){const count=(await api.post<any,Envelope<number>>('/evaluation/summaries/generate-month',null,{params:{month:month.value}})).data;ElMessage.success(`已生成或刷新 ${count} 份月度汇总草稿`);await refreshAll()}
function openStationWeights(){stationWeights.value=(stationComponent.value?.breakdown||[]).map((x:any)=>({stationId:x.stationId,stationName:x.stationName,days:x.days,weight:Number(x.weight)}));stationWeightOpen.value=true}
async function saveStationWeights(){if(stationWeightTotal.value!==100)return ElMessage.warning('站点权重合计必须为 100%');await api.put('/evaluation/monthly/station-weights',{employeeId:selected.value,month:month.value,stations:stationWeights.value.map(x=>({stationId:x.stationId,weight:x.weight}))});ElMessage.success('站点权重已按本员工本月单独保存');stationWeightOpen.value=false;await refreshAll()}
async function resetStationWeights(){await ElMessageBox.confirm('确认恢复模板设定的自动站点权重吗？','恢复自动权重');await api.delete('/evaluation/monthly/station-weights',{params:{employeeId:selected.value,month:month.value}});stationWeightOpen.value=false;await refreshAll()}

watch(month,async()=>{selected.value=undefined;await loadCandidates();await loadDetail()})
watch(classId,async()=>{selected.value=undefined;await loadCandidates();await loadDetail()})
watch(classPositionId,async()=>{selected.value=undefined;await loadCandidates();await loadDetail()})
watch(selected,async value=>{if(value){if(Number(route.query.employeeId)!==value)await router.replace({query:{...route.query,employeeId:String(value)}});await loadDetail()}})
onMounted(async()=>{[classOptions.value,classPositionOptions.value]=await Promise.all([loadDictionaryValues('CLASS'),loadDictionaryValues('CLASS_POSITION')]);await loadCandidates();await loadDetail()})
</script>

<template>
  <div class="evaluation-module-page">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · 月度评分</span><h1>月度评价工作台</h1><p>按待办队列逐人完成评价。考试、任务自动取数；多位导师分别提交后取平均；跨站月份按实际在站天数汇总。</p></div>
      <div class="evaluation-head-actions"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false"/><el-button :icon="Refresh" @click="refreshAll">刷新</el-button><el-button v-if="canManage" type="primary" @click="generate">生成本月汇总</el-button></div>
    </header>

    <div class="monthly-review-layout">
      <aside class="employee-review-queue" v-loading="queueLoading">
        <div class="queue-title"><div><span class="eyebrow">评价对象</span><h2>员工队列</h2></div><el-tag effect="plain">{{filteredCandidates.length}} 人</el-tag></div>
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索姓名、工号、班级、批次或站点"/>
        <el-select v-model="classId" clearable filterable placeholder="全部班级" style="width:100%;margin-top:10px"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
        <el-select v-model="classPositionId" clearable filterable placeholder="全部班级职务" style="width:100%;margin-top:10px"><el-option v-for="item in classPositionOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
        <div class="queue-filters">
          <button :class="{active:!statusFilter}" @click="statusFilter=''">全部 <b>{{candidates.length}}</b></button>
          <button :class="{active:statusFilter==='MY_PENDING'}" @click="statusFilter='MY_PENDING'">待我评价 <b>{{queueCounts.MY_PENDING}}</b></button>
          <button :class="{active:statusFilter==='IN_PROGRESS'}" @click="statusFilter='IN_PROGRESS'">进行中 <b>{{queueCounts.IN_PROGRESS}}</b></button>
          <button :class="{active:statusFilter==='READY'}" @click="statusFilter='READY'">已齐全 <b>{{queueCounts.READY}}</b></button>
        </div>
        <div class="queue-list">
          <button v-for="row in filteredCandidates" :key="row.id" class="queue-person" :class="{selected:row.id===selected}" @click="chooseCandidate(row.id)">
            <span class="person-avatar">{{row.name.slice(0,1)}}</span>
            <span class="person-main"><strong>{{row.name}} <small>{{row.employee_no}}</small></strong><em>{{row.class_name||'未分配班级'}} · {{row.batch_name||'未分配批次'}} · {{row.station_name||'未分配站点'}}</em><span class="person-progress"><i :style="{width:`${candidateProgress(row)}%`}"></i></span></span>
            <span class="person-state"><el-tag size="small" :type="candidateStatusTypes[row.status]" effect="plain">{{candidateStatusLabels[row.status]}}</el-tag><b>{{row.completedCount}}/{{row.enabledCount}}</b></span>
          </button>
          <el-empty v-if="!filteredCandidates.length" :image-size="70" description="当前筛选条件下没有员工"/>
        </div>
      </aside>

      <main class="monthly-review-detail" v-loading="loading">
        <div v-if="!selectedCandidate" class="detail-empty"><el-icon><User/></el-icon><h3>请选择一名员工</h3><p>左侧会优先显示需要你评价的员工。</p></div>
        <template v-else>
          <div class="detail-person-head"><div><span class="eyebrow">当前评价对象</span><h2>{{selectedCandidate.name}} <small>{{selectedCandidate.employee_no}}</small></h2><p>{{selectedCandidate.batch_name||'未分配批次'}} · {{selectedCandidate.station_name||'未分配站点'}} · {{month}}</p></div><el-tag :type="candidateStatusTypes[selectedCandidate.status]" effect="plain" size="large">{{candidateStatusLabels[selectedCandidate.status]}}</el-tag></div>
          <el-alert v-if="loadError" :title="loadError" description="请先在“评价模板”中把模板应用到该员工所属批次和月份，并发布方案。" type="warning" :closable="false" show-icon/>
          <template v-else-if="detail">
            <el-alert v-if="detail.locked" title="该月结果已经发布并锁定" description="如确需修改，请由管理员在结果中心重开。" type="warning" :closable="false" show-icon/>
            <div class="evaluation-summary-grid compact">
              <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Document/></el-icon></span><div><small>当前模板</small><strong class="template-name">{{detail.templateName||`方案 V${detail.schemeVersion}`}}</strong><span>方案版本 V{{detail.schemeVersion}}</span></div></article>
              <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><CircleCheck/></el-icon></span><div><small>已完成评分项</small><strong>{{completedCount}} / {{enabledCount}}</strong><span>自动与人工评分合计</span></div></article>
              <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>加分 / 扣分</small><strong>{{scoreText(detail.bonus)}} / {{scoreText(detail.deduction)}}</strong><span>受模板上限约束</span></div></article>
              <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><Right/></el-icon></span><div><small>月度预览</small><strong>{{scoreText(detail.finalScore)}}</strong><span>{{detail.missingItems.length?`仍缺 ${detail.missingItems.length} 项`:'评分项已齐全'}}</span></div></article>
            </div>

            <div class="evaluation-component-grid monthly-components">
              <article v-for="component in detail.components" :key="component.code" class="evaluation-component-card" :class="{disabled:!component.enabled}">
                <div class="evaluation-component-head"><strong>{{componentLabels[component.code as ComponentCode]}}</strong><el-tag :type="component.status==='PENDING'?'warning':component.status==='DISABLED'?'info':'success'" effect="plain">{{componentStatusLabels[component.status]}}</el-tag></div>
                <div class="evaluation-score-line"><span>有效得分</span><b>{{scoreText(component.effectiveScore)}}<small> / {{scoreText(component.fullScore)}}</small></b></div>
                <div class="evaluation-score-meta"><span>综合权重 {{component.weight}}%</span><span>综合贡献 {{scoreText(component.weightedScore)}}</span></div>

                <template v-if="component.enabled&&component.sourceType==='AUTO'">
                  <div v-if="component.breakdown?.length" class="source-breakdown">
                    <div v-for="row in component.breakdown" :key="row.sourceId" class="source-breakdown-row"><span><strong>{{row.name}}</strong><small>{{row.status==='PENDING'?'等待成绩':'内部权重 '+row.weight+'%'}}</small></span><b>{{scoreText(row.scorePercent)}}</b><em>{{row.contribution===null?'—':`贡献 ${scoreText(row.contribution)}`}}</em></div>
                  </div>
                  <p v-else class="evaluation-muted">本月暂无已分配的{{component.code==='EXAM'?'考试':'任务'}}。</p>
                  <el-button link type="primary" @click="jump(component)">前往{{component.code==='EXAM'?'考试成绩':'任务审核'}} <el-icon><Right/></el-icon></el-button>
                </template>

                <template v-else-if="component.enabled&&component.code==='MENTOR'">
                  <div class="manual-progress">导师提交 {{component.submittedCount||0}} / {{component.requiredCount||0}}<span v-if="component.partialScore!==null&&component.partialScore!==undefined">当前平均 {{scoreText(component.partialScore)}}</span></div>
                  <div v-for="row in component.breakdown||[]" :key="row.evaluatorId" class="manual-review-row">
                    <div class="manual-review-head"><strong>{{row.evaluatorName}}</strong><el-tag size="small" :type="row.status==='SUBMITTED'?'success':'warning'" effect="plain">{{row.status==='SUBMITTED'?'已提交':'待提交'}}</el-tag></div>
                    <template v-if="row.canEvaluate&&!detail.locked"><el-input-number v-model="inputFor('MENTOR').score" :min="0" :max="Number(component.fullScore||100)" :precision="2" controls-position="right"/><el-input v-model="inputFor('MENTOR').comment" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="填写评价事实、表现与改进建议"/><el-button type="primary" :disabled="!inputFor('MENTOR').comment.trim()" @click="submit(component)">保存我的导师评价</el-button></template>
                    <template v-else><p class="evaluation-muted">评分：{{scoreText(row.score)}}<span v-if="row.comment"> · {{row.comment}}</span></p></template>
                  </div>
                  <el-alert v-if="component.requiredCount>1&&component.submittedCount<component.requiredCount" title="所有导师提交后才形成正式平均分" type="info" :closable="false"/>
                </template>

                <template v-else-if="component.enabled&&component.code==='STATION'">
                  <div class="manual-progress">{{stationModeLabels[component.aggregationMode]||component.aggregationMode}}<el-button v-if="isAdmin&&!detail.locked&&component.breakdown?.length" link type="primary" @click="openStationWeights">调整权重</el-button></div>
                  <div v-for="row in component.breakdown||[]" :key="row.stationId" class="station-review-row">
                    <div class="manual-review-head"><strong>{{row.stationName}}</strong><span>{{row.days}} 天 · 权重 {{row.weight}}%</span></div>
                    <p v-if="row.reviewerCount" class="evaluation-muted">评分进度：{{row.submittedCount||0}} / {{row.reviewerCount}}<span v-if="row.evaluations?.some((x:any)=>x.score!==null)"> · 已评：{{row.evaluations.filter((x:any)=>x.score!==null).map((x:any)=>`${x.evaluatorName} ${scoreText(x.score)}分`).join('、')}}</span></p>
                    <template v-if="row.canEvaluate&&row.status!=='IGNORED'&&!detail.locked"><el-input-number v-model="inputFor('STATION',row.stationId).score" :min="0" :max="Number(component.fullScore||100)" :precision="2" controls-position="right"/><el-input v-model="inputFor('STATION',row.stationId).comment" type="textarea" :rows="2" maxlength="1000" show-word-limit placeholder="填写该员工在本站期间的表现"/><el-button type="primary" :disabled="!inputFor('STATION',row.stationId).comment.trim()" @click="submit(component,row.stationId)">保存本站评价</el-button></template>
                    <el-tag v-else size="small" :type="row.status==='SUBMITTED'?'success':'info'" effect="plain">{{row.status==='IGNORED'?'本月不计分':row.status==='SUBMITTED'?`站点得分 ${scoreText(row.score)}`:'等待站点负责人评价'}}</el-tag>
                  </div>
                </template>

                <template v-else-if="component.enabled&&component.code==='TRAINING'">
                  <div v-if="component.requiredCount" class="manual-progress">培训评分人提交 {{component.submittedCount||0}} / {{component.requiredCount}}<span v-if="component.partialScore!==null&&component.partialScore!==undefined">当前平均 {{scoreText(component.partialScore)}}</span></div>
                  <div v-for="row in component.breakdown||[]" :key="row.evaluatorId" class="manual-review-row">
                    <div class="manual-review-head"><strong>{{row.evaluatorName}}</strong><el-tag size="small" :type="row.status==='SUBMITTED'?'success':'warning'" effect="plain">{{row.status==='SUBMITTED'?'已提交':'待提交'}}</el-tag></div>
                    <template v-if="row.canEvaluate&&!detail.locked"><el-input-number v-model="inputFor('TRAINING').score" :min="0" :max="Number(component.fullScore||100)" :precision="2" controls-position="right"/><el-input v-model="inputFor('TRAINING').comment" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="填写培训学习与综合表现"/><el-button type="primary" :disabled="!inputFor('TRAINING').comment.trim()" @click="submit(component)">保存我的培训评价</el-button></template>
                    <p v-else class="evaluation-muted">评分：{{scoreText(row.score)}}<span v-if="row.comment"> · {{row.comment}}</span></p>
                  </div>
                  <template v-if="!component.breakdown?.length&&editable(component)"><el-input-number v-model="inputFor('TRAINING').score" :min="0" :max="Number(component.fullScore||100)" :precision="2" controls-position="right"/><el-input v-model="inputFor('TRAINING').comment" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="填写培训学习与综合表现"/><el-button type="primary" :disabled="!inputFor('TRAINING').comment.trim()" @click="submit(component)">保存培训评价</el-button></template>
                  <el-alert v-if="component.requiredCount>1&&component.submittedCount<component.requiredCount" title="全部评分人提交后才形成正式平均分" type="info" :closable="false"/>
                </template>

                <p v-if="component.overrideScore!==null" class="evaluation-warning">已人工核定：{{component.overrideReason}}（{{component.overrideBy}}）</p>
                <div v-if="isAdmin&&component.enabled&&!detail.locked" class="evaluation-head-actions override-actions"><el-button size="small" @click="override(component)">{{component.overrideScore===null?'人工核定':'修改核定'}}</el-button><el-button v-if="component.overrideScore!==null" size="small" @click="removeOverride(component)">撤销</el-button></div>
              </article>
            </div>

            <div class="evaluation-total-card"><div><span>当前月度综合分</span><strong>{{scoreText(detail.finalScore)}}</strong></div><div v-if="detail.missingItems.length" class="evaluation-missing">待补齐：{{detail.missingItems.map((x:ComponentCode)=>componentLabels[x]).join('、')}}</div><div v-else>所有启用项已齐全，可以生成并发布汇总。</div></div>

            <section v-if="canManage" class="adjustment-panel"><div><h3>加扣分登记</h3><p>仅登记有明确事实依据的额外表现，最终值受模板上限约束。</p></div><div class="evaluation-filters"><el-select v-model="adjustment.type"><el-option label="加分" value="BONUS"/><el-option label="扣分" value="DEDUCTION"/></el-select><el-input-number v-model="adjustment.points" :min="0.01" :precision="2" controls-position="right"/><el-input v-model="adjustment.reason" maxlength="1000" placeholder="填写事实依据和原因"/><el-button type="primary" :disabled="detail.locked||!adjustment.reason.trim()" @click="addAdjustment">登记</el-button></div></section>
          </template>
        </template>
      </main>
    </div>

    <el-dialog v-model="stationWeightOpen" title="调整本员工本月站点权重" width="min(620px, 94vw)" :close-on-click-modal="false">
      <el-alert title="只影响当前员工当前月份" description="默认按实际在站天数计算。手动调整后会完整记录，恢复自动权重时将重新按模板计算。" type="info" :closable="false" show-icon/>
      <div class="station-weight-editor"><div v-for="row in stationWeights" :key="row.stationId"><span><strong>{{row.stationName}}</strong><small>实际在站 {{row.days}} 天</small></span><el-input-number v-model="row.weight" :min="0" :max="100" :precision="2" controls-position="right"/><b>%</b></div></div>
      <div class="template-total"><span>权重合计</span><strong :class="{bad:stationWeightTotal!==100}">{{stationWeightTotal.toFixed(2)}}%</strong></div>
      <template #footer><el-button v-if="stationComponent?.aggregationMode==='MANUAL'" @click="resetStationWeights">恢复自动权重</el-button><el-button @click="stationWeightOpen=false">取消</el-button><el-button type="primary" :disabled="stationWeightTotal!==100" @click="saveStationWeights">保存权重</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.monthly-review-layout{display:grid;grid-template-columns:330px minmax(0,1fr);gap:16px;align-items:start}.employee-review-queue,.monthly-review-detail{border:1px solid #e5eaf1;border-radius:12px;background:#fff;box-shadow:0 4px 16px rgba(31,45,61,.04)}.employee-review-queue{position:sticky;top:16px;overflow:hidden}.queue-title,.detail-person-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:19px 20px}.queue-title h2,.detail-person-head h2{margin:2px 0 0;color:#263247;font-size:18px}.queue-title .eyebrow,.detail-person-head .eyebrow{color:#3574c4;font-size:11px;font-weight:700}.employee-review-queue>.el-input{width:calc(100% - 40px);margin:0 20px 13px}.queue-filters{display:grid;grid-template-columns:repeat(2,1fr);gap:6px;padding:0 20px 14px;border-bottom:1px solid #edf0f5}.queue-filters button{display:flex;justify-content:space-between;padding:8px 9px;border:1px solid #e6eaf0;border-radius:7px;color:#69758a;background:#fff;cursor:pointer}.queue-filters button.active{border-color:#80ace5;color:#2868c7;background:#edf5ff}.queue-list{max-height:calc(100vh - 300px);overflow:auto}.queue-person{display:grid;width:100%;grid-template-columns:38px minmax(0,1fr) auto;gap:10px;align-items:center;padding:14px 16px;border:0;border-bottom:1px solid #eef1f5;text-align:left;background:#fff;cursor:pointer}.queue-person:hover,.queue-person.selected{background:#f4f8ff}.queue-person.selected{box-shadow:inset 3px 0 #3676c8}.person-avatar{display:flex;width:38px;height:38px;align-items:center;justify-content:center;border-radius:10px;color:#2868c7;background:#eaf2ff;font-weight:700}.person-main strong,.person-main em{display:block}.person-main strong{font-size:13px;color:#293548}.person-main strong small{font-weight:400;color:#8894a6}.person-main em{overflow:hidden;margin-top:4px;color:#8a96a8;font-size:11px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.person-progress{display:block;height:3px;margin-top:8px;border-radius:3px;background:#edf0f5}.person-progress i{display:block;height:100%;border-radius:3px;background:#4a8bd9}.person-state{text-align:right}.person-state>b{display:block;margin-top:5px;color:#8a96a8;font-size:11px}.monthly-review-detail{min-height:530px;padding:0 20px 20px}.detail-person-head{margin:0 -20px 16px;border-bottom:1px solid #edf0f5}.detail-person-head h2 small{margin-left:6px;color:#8a96a8;font-size:12px;font-weight:400}.detail-person-head p{margin:5px 0 0;color:#8a96a8;font-size:12px}.detail-empty{padding:120px 20px;text-align:center;color:#8a96a8}.detail-empty .el-icon{font-size:42px}.detail-empty h3{margin:12px 0 4px;color:#4a5568}.detail-empty p{margin:0}.evaluation-summary-grid.compact{grid-template-columns:repeat(4,minmax(150px,1fr));margin-top:16px}.evaluation-summary-grid.compact .evaluation-summary-card{min-height:82px;padding:13px}.evaluation-summary-grid.compact .summary-icon{width:36px;height:36px}.evaluation-summary-grid.compact strong{font-size:20px}.evaluation-summary-grid.compact .template-name{font-size:14px}.monthly-components{grid-template-columns:repeat(2,minmax(280px,1fr))}.source-breakdown{margin:10px 0;border-top:1px solid #edf0f5}.source-breakdown-row{display:grid;grid-template-columns:minmax(0,1fr) 52px 80px;gap:8px;align-items:center;padding:9px 0;border-bottom:1px solid #edf0f5;font-size:12px}.source-breakdown-row strong,.source-breakdown-row small{display:block}.source-breakdown-row small{margin-top:2px;color:#8a96a8}.source-breakdown-row>b{text-align:right}.source-breakdown-row>em{color:#6f7c90;font-style:normal;text-align:right}.manual-progress{display:flex;align-items:center;justify-content:space-between;margin:10px 0;padding:9px 11px;border-radius:7px;color:#536178;background:#f4f7fb;font-size:12px}.manual-review-row,.station-review-row{margin-top:9px;padding:11px;border:1px solid #e7ebf2;border-radius:8px}.manual-review-head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px;font-size:12px}.manual-review-head>span{color:#7d899a}.manual-review-row .el-input-number,.manual-review-row .el-textarea,.station-review-row .el-input-number,.station-review-row .el-textarea{width:100%;margin-bottom:8px}.override-actions{margin-top:10px}.adjustment-panel{margin-top:16px;padding:16px;border:1px solid #e5eaf1;border-radius:9px;background:#fbfcfe}.adjustment-panel h3{margin:0;font-size:14px}.adjustment-panel p{margin:5px 0 12px;color:#8a96a8;font-size:12px}.adjustment-panel .el-input{min-width:240px;flex:1}.station-weight-editor{margin-top:15px}.station-weight-editor>div{display:grid;grid-template-columns:minmax(0,1fr) 160px 20px;gap:9px;align-items:center;padding:10px 0;border-bottom:1px solid #edf0f5}.station-weight-editor span strong,.station-weight-editor span small{display:block}.station-weight-editor span small{margin-top:3px;color:#8a96a8}
@media(max-width:1180px){.monthly-review-layout{grid-template-columns:280px minmax(0,1fr)}.evaluation-summary-grid.compact{grid-template-columns:repeat(2,1fr)}.monthly-components{grid-template-columns:1fr}}
@media(max-width:760px){.monthly-review-layout{grid-template-columns:1fr}.employee-review-queue{position:static}.queue-list{max-height:420px}.evaluation-summary-grid.compact{grid-template-columns:1fr}.monthly-review-detail{padding:0 14px 14px}.detail-person-head{margin:0 -14px 14px}.station-weight-editor>div{grid-template-columns:1fr 130px 18px}}
</style>
