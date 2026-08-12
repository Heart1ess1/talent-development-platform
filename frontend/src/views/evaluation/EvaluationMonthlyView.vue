<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {CircleCheck,Document,EditPen,Refresh,Right} from '@element-plus/icons-vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {componentLabels,componentStatusLabels,scoreText,type ComponentCode,type ScoreComponent} from '@/evaluation/model'
import '@/styles/evaluation-center.css'

const auth=useAuthStore(),route=useRoute(),router=useRouter()
const canManage=computed(()=>auth.can('evaluation:manage'))
const canSubmit=computed(()=>auth.can('evaluation:submit'))
const isAdmin=computed(()=>['ADMIN','SUPER_ADMIN'].includes(auth.user?.role||''))
const employees=ref<any[]>([]),selected=ref<number>(),month=ref(new Date().toISOString().slice(0,7)),detail=ref<any>(),loading=ref(false),loadError=ref('')
const inputs=reactive<Record<ComponentCode,{score:number;comment:string}>>({EXAM:{score:0,comment:''},TASK:{score:0,comment:''},MENTOR:{score:0,comment:''},STATION:{score:0,comment:''},TRAINING:{score:0,comment:''}})
const adjustment=reactive({type:'BONUS',points:1,reason:''})
const roleComponent=computed<ComponentCode|undefined>(()=>({MENTOR:'MENTOR',STATION_MANAGER:'STATION',TRAINING_ADMIN:'TRAINING'} as Record<string,ComponentCode>)[auth.user?.role||''])
const completedCount=computed(()=>detail.value?.components?.filter((x:ScoreComponent)=>x.enabled&&x.status!=='PENDING').length||0)
const enabledCount=computed(()=>detail.value?.components?.filter((x:ScoreComponent)=>x.enabled).length||0)

function inputFor(code:ComponentCode){return inputs[code]}
function editable(component:ScoreComponent){return canSubmit.value&&roleComponent.value===component.code&&!detail.value?.locked}
function jump(component:ScoreComponent){router.push(component.code==='EXAM'?'/exams/results':component.code==='TASK'?'/training-plans/tracking?focus=pending-review':'/evaluation/monthly')}
async function loadEmployees(){const response=await api.get<any,Envelope<any>>('/employees',{params:{size:200}});employees.value=response.data.records;const requested=Number(route.query.employeeId);if(requested&&employees.value.some(item=>Number(item.id)===requested))selected.value=requested;else if(!selected.value&&employees.value.length)selected.value=employees.value[0].id}
async function loadDetail(){if(!selected.value)return;loading.value=true;loadError.value='';detail.value=undefined;try{detail.value=(await api.get<any,Envelope<any>>('/evaluation/monthly/detail',{params:{employeeId:selected.value,month:month.value}})).data;for(const component of detail.value.components){const input=inputFor(component.code);input.score=Number(component.effectiveScore??0);input.comment=component.comment||''}}catch(error:any){loadError.value=error?.response?.data?.message||'当前月份尚未配置可用评价方案'}finally{loading.value=false}}
async function submit(component:ScoreComponent){await api.put(`/evaluation/monthly/components/${component.code}`,{employeeId:selected.value,month:month.value,...inputFor(component.code)});ElMessage.success(`${componentLabels[component.code]}已保存`);await loadDetail()}
async function override(component:ScoreComponent){const max=Number(component.fullScore||100);const value=Number((await ElMessageBox.prompt(`请输入 0 至 ${max} 分；系统原始分为 ${scoreText(component.sourceScore)}`,'人工核定',{inputPattern:new RegExp(`^(?:${max}(?:\\.0{1,2})?|(?:\\d|[1-9]\\d)(?:\\.\\d{1,2})?)$`),inputErrorMessage:`请输入不超过 ${max} 的分数`})).value);if(value>max)return ElMessage.warning(`评分不能超过 ${max}`);const reason=(await ElMessageBox.prompt('说明为什么需要人工核定','核定原因')).value;await api.put(`/evaluation/monthly/overrides/${component.code}`,{employeeId:selected.value,month:month.value,score:value,reason});ElMessage.success('人工核定已保存');await loadDetail()}
async function removeOverride(component:ScoreComponent){await ElMessageBox.confirm('撤销后将恢复自动或原始人工评分，确认继续吗？','撤销人工核定');await api.delete(`/evaluation/monthly/overrides/${component.code}`,{params:{employeeId:selected.value,month:month.value}});await loadDetail()}
async function addAdjustment(){await api.post('/evaluation/adjustments',{employeeId:selected.value,month:month.value,...adjustment});ElMessage.success('加扣分已登记');adjustment.reason='';await loadDetail()}
async function generate(){const count=(await api.post<any,Envelope<number>>('/evaluation/summaries/generate-month',null,{params:{month:month.value}})).data;ElMessage.success(`已生成或刷新 ${count} 份月度汇总草稿`);await loadDetail()}
watch([selected,month],loadDetail);onMounted(async()=>{await loadEmployees();await loadDetail()})
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · 月度评分</span><h1>月度评分</h1><p>按员工核对自动来源分，完成职责内人工评价，并处理必要的人工核定与加扣分。</p></div>
      <div class="evaluation-head-actions"><el-button :icon="Refresh" @click="loadDetail">刷新评分</el-button><el-button v-if="canManage" type="primary" @click="generate">生成本月汇总</el-button></div>
    </header>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>评分对象</h2><p>切换员工或月份会自动读取该批次当月生效的评价方案。</p></div><div class="evaluation-filters"><el-select v-model="selected" filterable placeholder="选择员工"><el-option v-for="employee in employees" :key="employee.id" :value="employee.id" :label="`${employee.name}（${employee.employee_no}）`"/></el-select><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false"/></div></div>
      <el-alert v-if="loadError" :title="loadError" description="请先在“评价模板”中把模板应用到该员工所属批次和月份，并发布方案。" type="warning" :closable="false" show-icon/>
      <template v-else-if="detail">
        <el-alert v-if="detail.locked" title="该月结果已经发布并锁定" description="如确需修改，请由管理员在结果中心重开，系统会保留旧版本。" type="warning" :closable="false" show-icon/>
        <div class="evaluation-summary-grid" style="margin-top:16px">
          <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Document/></el-icon></span><div><small>当前模板</small><strong style="font-size:17px">{{detail.templateName||`方案 V${detail.schemeVersion}`}}</strong><span>方案版本 V{{detail.schemeVersion}}</span></div></article>
          <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><CircleCheck/></el-icon></span><div><small>已完成评分项</small><strong>{{completedCount}} / {{enabledCount}}</strong><span>自动与人工评分合计</span></div></article>
          <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>加分 / 扣分</small><strong>{{scoreText(detail.bonus)}} / {{scoreText(detail.deduction)}}</strong><span>受模板上限约束</span></div></article>
          <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><Right/></el-icon></span><div><small>月度预览</small><strong>{{scoreText(detail.finalScore)}}</strong><span>{{detail.missingItems.length?`仍缺 ${detail.missingItems.length} 项`:'评分项已齐全'}}</span></div></article>
        </div>

        <div class="evaluation-component-grid">
          <article v-for="component in detail.components" :key="component.code" class="evaluation-component-card" :class="{disabled:!component.enabled}">
            <div class="evaluation-component-head"><strong>{{componentLabels[component.code as ComponentCode]}}</strong><el-tag :type="component.status==='PENDING'?'warning':component.status==='DISABLED'?'info':'success'" effect="plain">{{componentStatusLabels[component.status]}}</el-tag></div>
            <div class="evaluation-score-line"><span>有效得分</span><b>{{scoreText(component.effectiveScore)}}<small> / {{scoreText(component.fullScore)}}</small></b></div>
            <div class="evaluation-score-meta"><span>权重 {{component.weight}}%</span><span>综合贡献 {{scoreText(component.weightedScore)}}</span></div>
            <template v-if="component.enabled&&component.sourceType==='AUTO'">
              <p class="evaluation-muted">系统来源分：{{scoreText(component.sourceScore)}} / {{scoreText(component.fullScore)}}</p>
              <el-button link type="primary" @click="jump(component)">前往{{component.code==='EXAM'?'考试成绩':'任务审核'}} <el-icon><Right/></el-icon></el-button>
            </template>
            <template v-else-if="editable(component)">
              <el-input-number v-model="inputFor(component.code).score" :min="0" :max="Number(component.fullScore||100)" :precision="2" controls-position="right"/>
              <el-input v-model="inputFor(component.code).comment" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="请写明评价事实、表现与改进建议"/>
              <el-button type="primary" :disabled="!inputFor(component.code).comment.trim()" @click="submit(component)">保存本项评价</el-button>
            </template>
            <template v-else-if="component.enabled&&component.sourceType==='MANUAL'"><p class="evaluation-muted">评价人：{{component.evaluatorName||'尚未提交'}}</p><p v-if="component.comment" class="evaluation-muted">意见：{{component.comment}}</p></template>
            <p v-if="component.overrideScore!==null" class="evaluation-warning">已人工核定：{{component.overrideReason}}（{{component.overrideBy}}）</p>
            <div v-if="isAdmin&&component.enabled&&!detail.locked" class="evaluation-head-actions"><el-button size="small" @click="override(component)">{{component.overrideScore===null?'人工核定':'修改核定'}}</el-button><el-button v-if="component.overrideScore!==null" size="small" @click="removeOverride(component)">撤销</el-button></div>
          </article>
        </div>

        <div class="evaluation-total-card"><div><span>当前月度综合分</span><strong>{{scoreText(detail.finalScore)}}</strong></div><div v-if="detail.missingItems.length" class="evaluation-missing">待补齐：{{detail.missingItems.map((x:ComponentCode)=>componentLabels[x]).join('、')}}</div><div v-else>所有启用项已齐全，可以生成并发布汇总。</div></div>
      </template>
    </section>

    <section v-if="canManage&&detail" class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>加扣分登记</h2><p>仅记录有明确事实依据的额外表现；最终值受模板设置的加分、扣分上限约束。</p></div></div>
      <div class="evaluation-filters"><el-select v-model="adjustment.type"><el-option label="加分" value="BONUS"/><el-option label="扣分" value="DEDUCTION"/></el-select><el-input-number v-model="adjustment.points" :min="0.01" :precision="2" controls-position="right"/><el-input v-model="adjustment.reason" maxlength="1000" placeholder="填写事实依据和原因"/><el-button type="primary" :disabled="detail.locked||!adjustment.reason.trim()" @click="addAdjustment">登记</el-button></div>
    </section>
  </div>
</template>

<style scoped>
.evaluation-filters>.el-select{width:250px}.evaluation-filters>.el-date-editor{width:150px}.evaluation-filters>.el-input{min-width:280px;flex:1}.evaluation-score-line small{font-size:12px;font-weight:500;color:#8994a5}
</style>
