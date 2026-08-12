<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {Calendar,Connection,Document,EditPen,Histogram,List,Setting} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import '@/styles/evaluation-center.css'

const auth=useAuthStore(),router=useRouter()
const month=ref(new Date().toISOString().slice(0,7)),loading=ref(false)
const overview=ref<any>({totalEmployees:0,schemeCoveredEmployees:0,draftSummaries:0,publishedSummaries:0,missingSummaries:0,pendingManualScores:0,pendingTaskReviews:0,pendingExamReviews:0,unpublishedExamResults:0,unassignedRatingTasks:0})
const canManage=computed(()=>auth.can('evaluation:manage'))
const coverage=computed(()=>overview.value.totalEmployees?Math.round(overview.value.schemeCoveredEmployees/overview.value.totalEmployees*100):0)
const completion=computed(()=>overview.value.totalEmployees?Math.round(overview.value.publishedSummaries/overview.value.totalEmployees*100):0)
const actions=computed(()=>[
  ...(canManage.value?[{title:'未分配评分人的任务',description:'先明确导师、站点或培训评分人，再开始本月评价',count:overview.value.unassignedRatingTasks,to:'/evaluation/assignments',icon:Setting}]:[]),
  {title:'待提交人工评分',description:'导师、站点或培训方评分尚未完成',count:overview.value.pendingManualScores,to:'/evaluation/monthly',icon:EditPen},
  {title:'待审核任务成果',description:'先审核任务并给分，系统会自动进入月评',count:overview.value.pendingTaskReviews,to:'/training-plans/tracking?focus=pending-review',icon:Document},
  {title:'遗留主观题阅卷',description:'历史主观题完成阅卷后等待考试结束自动下发',count:overview.value.pendingExamReviews,to:'/exams/results?focus=review',icon:List}
])

async function load(){loading.value=true;try{overview.value=(await api.get<any,Envelope<any>>('/evaluation/overview',{params:{month:month.value}})).data}finally{loading.value=false}}
function go(path:string){router.push(path)}
watch(month,load);onMounted(load)
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · 评价工作台</span><h1>评价工作台</h1><p>以月份为主线，把模板启用、来源评分、人工评价和结果发布串成一条可追踪的工作流。</p></div>
      <div class="evaluation-head-actions"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false"/><el-button :icon="Calendar" @click="load">刷新本月</el-button></div>
    </header>

    <section class="evaluation-summary-grid">
      <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Connection/></el-icon></span><div><small>方案覆盖</small><strong>{{coverage}}%</strong><span>{{overview.schemeCoveredEmployees}} / {{overview.totalEmployees}} 人已有规则</span></div></article>
      <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>汇总草稿</small><strong>{{overview.draftSummaries}}</strong><span>等待补齐或发布</span></div></article>
      <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><Histogram/></el-icon></span><div><small>发布进度</small><strong>{{completion}}%</strong><span>{{overview.publishedSummaries}} 人已发布</span></div></article>
      <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><Document/></el-icon></span><div><small>存在缺失项</small><strong>{{overview.missingSummaries}}</strong><span>需要补评分或核定</span></div></article>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>月度评价流程</h2><p>模板定义规则，评分来源各自完成，最终汇总只负责核对和发布。</p></div></div>
      <div class="evaluation-flow-grid">
        <article class="evaluation-flow-card" @click="go(canManage?'/evaluation/templates':'/evaluation/monthly')"><span class="step">1</span><strong>选择评价模板</strong><p>设置评分项、每项满分、权重和加扣分边界，并应用到批次月份。</p><a>进入模板与方案 →</a></article>
        <article class="evaluation-flow-card" @click="go(canManage?'/evaluation/assignments':'/evaluation/my-tasks')"><span class="step">2</span><strong>生成并分配评分任务</strong><p>按员工和人工评分项生成任务，指定一名或多名评分人及截止时间。</p><a>{{canManage?'进入评分任务':'查看我的任务'}} →</a></article>
        <article class="evaluation-flow-card" @click="go('/exams/results')"><span class="step">3</span><strong>完成来源评分</strong><p>客观题自动评分并在考试结束后下发，任务审核仍在任务模块完成。</p><a>核对考试与任务 →</a></article>
        <article class="evaluation-flow-card" @click="go('/evaluation/monthly')"><span class="step">4</span><strong>补齐人工评价</strong><p>评分人分别提交，全部完成后系统自动取平均分。</p><a>进入月度评分 →</a></article>
        <article class="evaluation-flow-card" @click="go('/evaluation/results')"><span class="step">5</span><strong>生成并发布结果</strong><p>核对缺失项、加扣分和分项快照，发布后锁定历史结果。</p><a>进入结果中心 →</a></article>
      </div>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>待办与快捷入口</h2><p>评分动作仍在原业务模块完成，这里提供统一入口和待办数量。</p></div><el-button v-if="canManage" :icon="Setting" @click="go('/evaluation/templates')">管理模板</el-button></div>
      <div class="evaluation-action-list">
        <article v-for="item in actions" :key="item.title" class="evaluation-action"><div class="copy"><span class="badge"><el-icon><component :is="item.icon"/></el-icon></span><div><strong>{{item.title}}</strong><span>{{item.description}}</span></div></div><div class="evaluation-head-actions"><el-tag :type="item.count?'warning':'success'">{{item.count}} 项</el-tag><el-button link type="primary" @click="go(item.to)">去处理</el-button></div></article>
      </div>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>计分口径</h2><p>避免重复录入：来源数据先在专业模块完成，再由综合评价统一归一化和加权。</p></div></div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="考试成绩">按当月已发布成绩换算为百分制，再折算到模板设置的考试满分。</el-descriptions-item>
        <el-descriptions-item label="任务成果">按当月已审核任务的最终得分平均，逾期任务按现有业务规则计入。</el-descriptions-item>
        <el-descriptions-item label="三方评价">导师、站点负责人、培训管理员分别提交，不允许跨角色代填。</el-descriptions-item>
        <el-descriptions-item label="结果锁定">月度结果发布后冻结；管理员重开会形成新版本，旧结果保留。</el-descriptions-item>
      </el-descriptions>
    </section>
  </div>
</template>
