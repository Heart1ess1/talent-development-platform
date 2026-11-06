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
  ...(canManage.value?[{title:'当月答辩成绩待完善',description:'已启用答辩的月评仍缺少正式成绩',count:overview.value.pendingDefenseScores||0,to:'/evaluation/defenses?month='+month.value,icon:Document},{title:'未分配评分人的任务',description:'先明确导师、站点或培训评分人，再开始本月评价',count:overview.value.unassignedRatingTasks,to:'/evaluation/assignments',icon:Setting}]:[]),
  {title:'待提交人工评分',description:'导师、站点或培训方评分尚未完成',count:overview.value.pendingManualScores,to:'/evaluation/monthly',icon:EditPen},
  {title:'待评分任务成果',description:'完成分配给你的任务评分，系统会自动进入月评',count:overview.value.pendingTaskReviews,to:'/task-scoring?status=MY_PENDING',icon:Document},
  {title:'遗留主观题阅卷',description:'历史主观题完成阅卷后等待考试结束自动下发',count:overview.value.pendingExamReviews,to:'/exams/results?focus=review',icon:List}
])

async function load(){loading.value=true;try{overview.value=(await api.get<any,Envelope<any>>('/evaluation/overview',{params:{month:month.value}})).data}finally{loading.value=false}}
function go(path:string){router.push(path)}
watch(month,load);onMounted(load)
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><h1>评价工作台</h1></div>
      <div class="evaluation-head-actions"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false"/><el-button :icon="Calendar" @click="load">刷新本月</el-button></div>
    </header>

    <section class="evaluation-summary-grid">
      <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Connection/></el-icon></span><div><small>方案覆盖</small><strong>{{coverage}}%</strong></div></article>
      <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>汇总草稿</small><strong>{{overview.draftSummaries}}</strong></div></article>
      <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><Histogram/></el-icon></span><div><small>发布进度</small><strong>{{completion}}%</strong></div></article>
      <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><Document/></el-icon></span><div><small>存在缺失项</small><strong>{{overview.missingSummaries}}</strong></div></article>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>月度评价流程</h2></div></div>
      <div class="evaluation-flow-grid">
        <article class="evaluation-flow-card" @click="go(canManage?'/evaluation/templates':'/evaluation/monthly')"><span class="step">1</span><strong>选择评价模板</strong><a>进入模板与方案 →</a></article>
        <article class="evaluation-flow-card" @click="go(canManage?'/evaluation/assignments':'/evaluation/my-tasks')"><span class="step">2</span><strong>生成并分配评分任务</strong><a>{{canManage?'进入评分任务':'查看我的任务'}} →</a></article>
        <article class="evaluation-flow-card" @click="go('/exams/results')"><span class="step">3</span><strong>完成来源评分</strong><a>核对考试与任务 →</a></article>
        <article class="evaluation-flow-card" @click="go('/evaluation/monthly')"><span class="step">4</span><strong>补齐人工评价</strong><a>进入月度评分 →</a></article>
        <article class="evaluation-flow-card" @click="go('/evaluation/results')"><span class="step">5</span><strong>生成并发布结果</strong><a>进入结果中心 →</a></article>
      </div>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>待办与快捷入口</h2></div><el-button v-if="canManage" :icon="Setting" @click="go('/evaluation/templates')">管理模板</el-button></div>
      <div class="evaluation-action-list">
        <article v-for="item in actions" :key="item.title" class="evaluation-action"><div class="copy"><span class="badge"><el-icon><component :is="item.icon"/></el-icon></span><div><strong>{{item.title}}</strong></div></div><div class="evaluation-head-actions"><el-tag :type="item.count?'warning':'success'">{{item.count}} 项</el-tag><el-button link type="primary" @click="go(item.to)">去处理</el-button></div></article>
      </div>
    </section>


  </div>
</template>
