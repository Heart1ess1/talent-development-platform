<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {usePortraitPage,dateTime,statusLabel} from './usePortraitPage'

const props=defineProps<{employeeId:number}>()
const dateRange=ref<string[]>([])
const q=usePortraitPage<any>(()=>props.employeeId,'exams',()=>({dateFrom:dateRange.value[0],dateTo:dateRange.value[1]}))
const attemptScore=(row:any)=>row.score!==null&&row.score!==undefined?String(row.score):row.status==='GRADED'?'未发布':row.status==='PENDING_REVIEW'?'待阅卷':'-'
watch(dateRange,()=>q.load(1),{deep:true})
onMounted(()=>q.load())
</script>

<template>
  <section class="panel">
    <header><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="考试日期起" end-placeholder="考试日期止" range-separator="至"/><span>按考试开始时间筛选</span></header>
    <el-alert v-if="q.error.value" type="error" :title="q.error.value" show-icon>
      <template #default><el-button link type="primary" @click="q.load(q.page.value)">重试</el-button></template>
    </el-alert>
    <el-table v-else :data="q.records.value" v-loading="q.loading.value" height="100%" stripe empty-text="暂无考试安排">
      <el-table-column type="expand">
        <template #default="s">
          <el-table :data="s.row.attempts" size="small" empty-text="暂无作答记录">
            <el-table-column prop="attemptNo" label="次数" width="82"><template #default="a">第 {{a.row.attemptNo}} 次</template></el-table-column>
            <el-table-column label="阅卷状态" width="110"><template #default="a">{{statusLabel(a.row.status)}}</template></el-table-column>
            <el-table-column label="开始 / 交卷" min-width="220"><template #default="a">{{dateTime(a.row.startedAt)}} / {{dateTime(a.row.submittedAt)}}</template></el-table-column>
            <el-table-column label="成绩" width="120"><template #default="a">{{attemptScore(a.row)}}<span v-if="a.row.score!==null&&a.row.score!==undefined&&a.row.maxScore!==null"> / {{a.row.maxScore}}</span></template></el-table-column>
            <el-table-column label="发布状态" width="100"><template #default="a"><el-tag :type="a.row.published?'success':'warning'">{{a.row.published?'已发布':'未发布'}}</el-tag></template></el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column prop="examName" label="考试" min-width="200"/>
      <el-table-column label="考试时间" min-width="190"><template #default="s">{{dateTime(s.row.startsAt)}}<br>{{dateTime(s.row.endsAt)}}</template></el-table-column>
      <el-table-column label="计划状态" width="100"><template #default="s">{{statusLabel(s.row.planPhase)}}</template></el-table-column>
      <el-table-column label="参与状态" width="110"><template #default="s"><el-tag :type="s.row.participationStatus==='ABSENT'?'danger':'info'">{{statusLabel(s.row.participationStatus)}}</el-tag></template></el-table-column>
      <el-table-column label="作答次数" width="100"><template #default="s">{{s.row.attempts.length}} / {{s.row.maxAttempts}}</template></el-table-column>
    </el-table>
    <footer><span>按考试开始时间排列</span><el-pagination v-model:current-page="q.page.value" :page-size="q.size.value" :total="q.total.value" layout="total, prev, pager, next" @current-change="q.load"/></footer>
  </section>
</template>

<style scoped>.panel{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:10px}.panel header,.panel footer{display:flex;align-items:center;justify-content:space-between}.panel header span,.panel footer span{font-size:12px;color:#7b8798}@media(max-width:760px){.panel header{align-items:flex-start;gap:8px;flex-direction:column}}</style>
