<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ArrowLeft,EditPen,Right,UserFilled} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {componentLabels,scoreText,type ComponentCode} from '@/evaluation/model'
import '@/styles/evaluation-center.css'

const route=useRoute(),router=useRouter(),loading=ref(false),detail=ref<any>()
const statusLabels:Record<string,string>={UNASSIGNED:'未分配',PENDING:'待评分',IN_PROGRESS:'评分中',OVERDUE:'已逾期',COMPLETED:'已完成',CLOSED:'已锁定'}
const progress=computed(()=>detail.value?.reviewerCount?Math.round(detail.value.submittedCount*100/detail.value.reviewerCount):0)
const assignedByScope=computed(()=>detail.value?.reviewers?.some((row:any)=>row.assignmentSource==='SCOPE_RULE'))
async function load(){loading.value=true;try{detail.value=(await api.get<any,Envelope<any>>(`/evaluation/assignments/${route.params.id}`)).data}finally{loading.value=false}}
function goScore(){router.push({path:'/evaluation/monthly',query:{employeeId:String(detail.value.employee_id),month:String(detail.value.period_month).slice(0,7)}})}
function timeText(value?:string){return value?String(value).replace('T',' ').slice(0,16):'—'}
onMounted(load)
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><el-button link :icon="ArrowLeft" @click="router.push('/evaluation/assignments')">返回评分任务</el-button><span class="eyebrow">综合评价 · 任务详情</span><h1>{{detail?.employee_name||'评分任务'}}</h1><p v-if="detail">{{detail.employee_no}} · {{componentLabels[detail.component_type as ComponentCode]}}<template v-if="detail.scope_name"> · {{detail.scope_name}}</template> · {{String(detail.period_month).slice(0,7)}}</p></div>
      <div v-if="detail" class="evaluation-head-actions"><el-tag effect="plain" size="large">{{statusLabels[detail.status]}}</el-tag><el-button type="primary" :icon="Right" @click="goScore">进入月度评分</el-button></div>
    </header>

    <template v-if="detail">
      <section class="evaluation-summary-grid">
        <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>评分人数</small><strong>{{detail.reviewerCount}}</strong><span>当前有效评分人</span></div></article>
        <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>提交进度</small><strong>{{detail.submittedCount}} / {{detail.reviewerCount}}</strong><span>{{progress}}% 已完成</span></div></article>
        <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>当前平均</small><strong>{{scoreText(detail.averageScore)}}</strong><span>{{detail.finalAverageScore===null?'仅供过程查看':'已形成正式平均分'}}</span></div></article>
        <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><UserFilled/></el-icon></span><div><small>截止时间</small><strong class="detail-date">{{timeText(detail.due_at)}}</strong><span>{{detail.locked?'结果已经发布锁定':'仍可调整任务安排'}}</span></div></article>
      </section>

      <div class="assignment-detail-grid">
        <section class="evaluation-workspace">
          <div class="evaluation-workspace-head"><div><h2>评分人及提交情况</h2><p>全部当前评分人提交后，系统使用算术平均作为本任务最终得分。</p></div></div>
          <div v-if="detail.reviewers.length" class="reviewer-detail-list">
            <article v-for="row in detail.reviewers" :key="row.reviewerId">
              <div class="reviewer-avatar">{{row.reviewerName.slice(0,1)}}</div>
              <div><strong>{{row.reviewerName}}</strong><span>{{row.username}} · {{row.role}}</span><p v-if="row.comment">{{row.comment}}</p><small>分配：{{timeText(row.assignedAt)}}<template v-if="row.submittedAt"> · 提交：{{timeText(row.submittedAt)}}</template></small></div>
              <div class="reviewer-score"><el-tag :type="row.score===null?'warning':'success'" effect="plain">{{row.score===null?'待提交':'已提交'}}</el-tag><b>{{scoreText(row.score)}}</b></div>
            </article>
          </div>
          <div v-else class="evaluation-empty">尚未匹配评分人，请返回评分任务页配置全员、批次或板块范围。</div>
        </section>

        <section class="evaluation-workspace assignment-source">
          <div class="evaluation-workspace-head"><div><h2>分配依据</h2><p>员工任务由评分任务页按人员范围统一展开，此页仅用于查看结果。</p></div></div>
          <el-alert v-if="detail.locked" title="评价结果已发布，当前任务已锁定" type="warning" :closable="false" show-icon/>
          <el-alert v-else-if="assignedByScope" title="当前评分人来自全员、批次或板块范围配置" type="success" :closable="false" show-icon/>
          <el-alert v-else title="当前评分人为历史或单独分配记录" type="info" :closable="false" show-icon/>
          <dl>
            <div><dt>所属批次</dt><dd>{{detail.batch_name||'—'}}</dd></div>
            <div><dt>所属板块</dt><dd>{{detail.business_unit_name||'—'}}</dd></div>
            <div><dt>截止时间</dt><dd>{{timeText(detail.due_at)}}</dd></div>
            <div><dt>任务说明</dt><dd>{{detail.note||'—'}}</dd></div>
          </dl>
          <el-button type="primary" plain style="width:100%" @click="router.push('/evaluation/assignments')">返回统一配置评分人</el-button>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.detail-date{font-size:16px!important}.assignment-detail-grid{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(320px,.7fr);gap:16px}.reviewer-detail-list article{display:grid;grid-template-columns:44px minmax(0,1fr) auto;gap:12px;align-items:start;padding:16px 0;border-bottom:1px solid #edf0f5}.reviewer-avatar{display:flex;width:44px;height:44px;align-items:center;justify-content:center;border-radius:12px;color:#2868c7;background:#eaf2ff;font-weight:700}.reviewer-detail-list strong,.reviewer-detail-list span,.reviewer-detail-list small{display:block}.reviewer-detail-list span,.reviewer-detail-list small{margin-top:4px;color:#8a96a8;font-size:12px}.reviewer-detail-list p{margin:9px 0;color:#536178;font-size:13px}.reviewer-score{text-align:right}.reviewer-score b{display:block;margin-top:8px;font-size:20px}.assignment-source dl{margin:20px 0}.assignment-source dl div{padding:12px 0;border-bottom:1px solid #edf0f5}.assignment-source dt{color:#8a96a8;font-size:12px}.assignment-source dd{margin:5px 0 0;color:#344258;white-space:pre-wrap}
@media(max-width:900px){.assignment-detail-grid{grid-template-columns:1fr}}
</style>
