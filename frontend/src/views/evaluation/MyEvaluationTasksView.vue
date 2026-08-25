<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {Calendar,Check,Clock,EditPen,Refresh} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {componentLabels,scoreText,type ComponentCode} from '@/evaluation/model'
import {useAuthStore} from '@/stores/auth'
import '@/styles/evaluation-center.css'

const router=useRouter(),auth=useAuthStore(),loading=ref(false),rows=ref<any[]>([]),month=ref(new Date().toISOString().slice(0,7)),status=ref(''),component=ref('')
const statusLabels:Record<string,string>={PENDING:'待评分',IN_PROGRESS:'评分中',OVERDUE:'已逾期',COMPLETED:'已完成',CLOSED:'已锁定'}
const statusTypes:Record<string,''|'success'|'warning'|'info'|'danger'>={PENDING:'warning',IN_PROGRESS:'info',OVERDUE:'danger',COMPLETED:'success',CLOSED:''}
const metrics=computed(()=>({pending:rows.value.filter(x=>['PENDING','IN_PROGRESS'].includes(x.status)).length,overdue:rows.value.filter(x=>x.status==='OVERDUE').length,completed:rows.value.filter(x=>['COMPLETED','CLOSED'].includes(x.status)).length}))
async function load(){loading.value=true;try{rows.value=(await api.get<any,Envelope<any[]>>('/evaluation/assignments/mine',{params:{month:month.value,status:status.value||undefined,component:component.value||undefined}})).data}finally{loading.value=false}}
function score(row:any){router.push({path:'/evaluation/monthly',query:{employeeId:String(row.employee_id),month:String(row.period_month).slice(0,7)}})}
function myScore(row:any){return row.reviewers.find((x:any)=>Number(x.reviewerId)===Number(auth.user?.id))?.score??null}
function dueText(value?:string){return value?String(value).replace('T',' ').slice(0,16):'未设置截止时间'}
watch([month,status,component],load);onMounted(load)
</script>

<template>
  <div class="evaluation-module-page" v-loading="loading">
    <header class="evaluation-page-head">
      <div><span class="eyebrow">综合评价 · 我的任务</span><h1>我的评分任务</h1><p>这里只显示明确分配给你的评分任务。进入月度评分后提交个人分数，全部评分人完成后系统自动计算平均分。</p></div>
      <div class="evaluation-head-actions"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false"/><el-button :icon="Refresh" @click="load">刷新</el-button></div>
    </header>

    <section class="evaluation-summary-grid">
      <article class="evaluation-summary-card blue"><span class="summary-icon"><el-icon><Calendar/></el-icon></span><div><small>分配给我</small><strong>{{rows.length}}</strong><span>{{month}} 评分任务</span></div></article>
      <article class="evaluation-summary-card amber"><span class="summary-icon"><el-icon><Clock/></el-icon></span><div><small>待我处理</small><strong>{{metrics.pending}}</strong><span>尚未提交个人评分</span></div></article>
      <article class="evaluation-summary-card violet"><span class="summary-icon"><el-icon><EditPen/></el-icon></span><div><small>已逾期</small><strong>{{metrics.overdue}}</strong><span>请优先完成</span></div></article>
      <article class="evaluation-summary-card green"><span class="summary-icon"><el-icon><Check/></el-icon></span><div><small>已完成 / 锁定</small><strong>{{metrics.completed}}</strong><span>个人评分已纳入平均</span></div></article>
    </section>

    <section class="evaluation-workspace">
      <div class="evaluation-workspace-head"><div><h2>任务清单</h2><p>“当前平均”包含已提交评分，仅供过程查看；所有评分人提交后才成为正式得分。</p></div></div>
      <div class="my-task-filters"><el-select v-model="component" clearable filterable placeholder="全部评分项"><el-option label="导师评价" value="MENTOR"/><el-option label="站点评价" value="STATION"/><el-option label="培训评价" value="TRAINING"/></el-select><el-select v-model="status" clearable filterable placeholder="全部状态"><el-option v-for="(label,key) in statusLabels" :key="key" :label="label" :value="key"/></el-select></div>
      <div v-if="rows.length" class="my-task-list">
        <article v-for="row in rows" :key="row.id" :class="{overdue:row.status==='OVERDUE'}">
          <div class="task-person"><span>{{row.employee_name.slice(0,1)}}</span><div><strong>{{row.employee_name}} <small>{{row.employee_no}}</small></strong><p>{{row.batch_name||'未分配批次'}} · {{row.station_name||'未分配站点'}}</p></div></div>
          <div class="task-scope"><strong>{{componentLabels[row.component_type as ComponentCode]}}</strong><span v-if="row.scope_name">{{row.scope_name}}</span><p v-if="row.note">{{row.note}}</p></div>
          <div class="task-progress"><span>团队进度 {{row.submittedCount}} / {{row.reviewerCount}}</span><el-progress :percentage="row.reviewerCount?Math.round(row.submittedCount*100/row.reviewerCount):0" :show-text="false"/><small>当前平均 {{scoreText(row.averageScore)}} · 我的评分 {{scoreText(myScore(row))}}</small></div>
          <div class="task-action"><el-tag :type="statusTypes[row.status]" effect="plain">{{statusLabels[row.status]}}</el-tag><span>{{dueText(row.due_at)}}</span><el-button v-if="!row.locked" type="primary" @click="score(row)">{{myScore(row)===null?'开始评分':'修改评分'}}</el-button><el-button v-else @click="score(row)">查看结果</el-button></div>
        </article>
      </div>
      <el-empty v-else description="当前筛选条件下没有分配给你的评分任务"/>
    </section>
  </div>
</template>

<style scoped>
.my-task-filters{display:flex;gap:10px;margin-bottom:14px}.my-task-filters .el-select{width:180px}.my-task-list article{display:grid;grid-template-columns:minmax(210px,1.1fr) minmax(170px,.8fr) minmax(220px,1fr) 150px;gap:18px;align-items:center;padding:17px 0;border-bottom:1px solid #edf0f5}.my-task-list article.overdue{margin:0 -12px;padding:17px 12px;border-radius:8px;background:#fff7f5}.task-person{display:flex;gap:10px;align-items:center}.task-person>span{display:flex;width:40px;height:40px;align-items:center;justify-content:center;border-radius:11px;color:#2868c7;background:#eaf2ff;font-weight:700}.task-person strong,.task-person p,.task-scope strong,.task-scope span,.task-scope p,.task-progress span,.task-progress small,.task-action>span{display:block}.task-person small{color:#8a96a8;font-weight:400}.task-person p,.task-scope span,.task-scope p,.task-progress small,.task-action>span{margin:4px 0 0;color:#8995a7;font-size:11px}.task-scope p{overflow:hidden;max-width:260px;text-overflow:ellipsis;white-space:nowrap}.task-progress .el-progress{margin:7px 0}.task-action{text-align:right}.task-action .el-button{width:100%;margin-top:9px}
@media(max-width:1050px){.my-task-list article{grid-template-columns:1fr 1fr}.task-action{text-align:left}}@media(max-width:650px){.my-task-filters{display:grid}.my-task-filters .el-select{width:100%}.my-task-list article{grid-template-columns:1fr}}
</style>
