<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {usePortraitPage,dateTime,eventTypeLabel,statusLabel} from './usePortraitPage'

const props=defineProps<{employeeId:number}>()
const type=ref('')
const dateRange=ref<string[]>([])
const q=usePortraitPage<any>(()=>props.employeeId,'timeline',()=>({type:type.value||undefined,dateFrom:dateRange.value[0],dateTo:dateRange.value[1]}))
watch([type,dateRange],()=>q.load(1),{deep:true})
onMounted(()=>q.load())
</script>

<template>
  <section class="timeline-panel">
    <header><div class="filters"><el-select v-model="type" clearable filterable placeholder="全部事件" style="width:160px"><el-option label="入职" value="ONBOARD"/><el-option label="课程签到" value="ATTENDANCE"/><el-option label="任务提交" value="TASK"/><el-option label="任务审核" value="TASK_REVIEW"/><el-option label="考试交卷" value="EXAM"/><el-option label="评价发布" value="EVALUATION"/><el-option label="服务站调整" value="STATION"/><el-option label="位置报备" value="LOCATION"/></el-select><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="发生日期起" end-placeholder="发生日期止" range-separator="至"/></div><span>按实际业务发生时间倒序排列</span></header>
    <el-alert v-if="q.error.value" type="error" :title="q.error.value" show-icon><template #default><el-button link type="primary" @click="q.load(q.page.value)">重试</el-button></template></el-alert>
    <div v-else v-loading="q.loading.value" class="timeline-scroll"><el-empty v-if="q.loaded.value&&!q.records.value.length" description="暂无成长记录"/><el-timeline v-else><el-timeline-item v-for="item in q.records.value" :key="item.id" :timestamp="item.timeMissing?'时间未记录':dateTime(item.occurredAt)" placement="top"><strong>{{item.title}}</strong><p>{{eventTypeLabel(item.type)}} · {{statusLabel(item.status)}} · 记录 #{{item.sourceId}}</p></el-timeline-item></el-timeline></div>
    <footer><el-pagination v-model:current-page="q.page.value" :page-size="q.size.value" :total="q.total.value" layout="total, prev, pager, next" @current-change="q.load"/></footer>
  </section>
</template>

<style scoped>.timeline-panel{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:10px}.timeline-panel header,.filters{display:flex;align-items:center;justify-content:space-between;gap:8px}.timeline-panel header span{font-size:12px;color:#7b8798}.timeline-scroll{overflow:auto;padding:10px 18px}.timeline-scroll p{margin:5px 0;color:#7b8798}.timeline-panel footer{display:flex;justify-content:flex-end}@media(max-width:760px){.timeline-panel header,.filters{align-items:flex-start;flex-direction:column}}</style>
