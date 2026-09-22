<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {usePortraitPage,dateTime,score} from './usePortraitPage'

const props=defineProps<{employeeId:number}>()
const type=ref<'MONTH'|'QUARTER'>('MONTH')
const dateRange=ref<string[]>([])
const q=usePortraitPage<any>(()=>props.employeeId,'evaluations',()=>({type:type.value,dateFrom:dateRange.value[0],dateTo:dateRange.value[1]}))
watch([type,dateRange],()=>q.load(1),{deep:true})
onMounted(()=>q.load())
</script>

<template>
  <section class="panel">
    <header><div class="filters"><el-segmented v-model="type" :options="[{label:'月度评价',value:'MONTH'},{label:'季度评价',value:'QUARTER'}]"/><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="生成日期起" end-placeholder="生成日期止" range-separator="至"/></div><span>按生成时间筛选；暂定结果不覆盖已发布结果</span></header>
    <el-alert v-if="q.error.value" type="error" :title="q.error.value" show-icon>
      <template #default><el-button link type="primary" @click="q.load(q.page.value)">重试</el-button></template>
    </el-alert>
    <el-table v-else :data="q.records.value" v-loading="q.loading.value" height="100%" stripe empty-text="暂无评价记录">
      <el-table-column type="expand"><template #default="s"><div class="evaluation-detail" v-if="type==='MONTH'"><span>考试：{{score(s.row.examScore)}}</span><span>任务：{{score(s.row.taskScore)}}</span><span>导师：{{score(s.row.mentorScore)}}</span><span>服务站：{{score(s.row.stationScore)}}</span><span>培训：{{score(s.row.trainingScore)}}</span><span>加分：{{s.row.bonus??0}}</span><span>扣分：{{s.row.deduction??0}}</span></div><div v-else class="evaluation-detail"><span>季度组成快照：{{s.row.quarterSnapshot||'暂无快照'}}</span></div></template></el-table-column>
      <el-table-column prop="period" label="周期" min-width="120"/>
      <el-table-column prop="version" label="版本" width="80"/>
      <el-table-column label="状态" width="110"><template #default="s"><el-tag :type="s.row.provisional?'warning':'success'">{{s.row.provisional?'暂定':'已发布'}}</el-tag></template></el-table-column>
      <el-table-column label="最终得分" width="110"><template #default="s"><strong>{{score(s.row.finalScore)}}</strong></template></el-table-column>
      <el-table-column prop="missingItems" label="缺项" min-width="180"><template #default="s">{{s.row.missingItems||'-'}}</template></el-table-column>
      <el-table-column label="生成 / 发布时间" min-width="190"><template #default="s">{{dateTime(s.row.generatedAt)}}<br>{{dateTime(s.row.publishedAt)}}</template></el-table-column>
    </el-table>
    <footer><el-pagination v-model:current-page="q.page.value" :page-size="q.size.value" :total="q.total.value" layout="total, prev, pager, next" @current-change="q.load"/></footer>
  </section>
</template>

<style scoped>.panel{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:10px}.panel header,.filters{display:flex;justify-content:space-between;align-items:center;gap:8px}.panel header span{font-size:12px;color:#7b8798}.panel footer{display:flex;justify-content:flex-end}.evaluation-detail{display:flex;flex-wrap:wrap;gap:12px 28px;margin:4px 44px;color:#475467}@media(max-width:760px){.panel header,.filters{align-items:flex-start;flex-direction:column}}</style>
