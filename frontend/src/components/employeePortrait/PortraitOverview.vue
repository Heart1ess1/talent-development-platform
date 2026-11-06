<script setup lang="ts">
import {computed} from 'vue'
import VChart from 'vue-echarts'
import {use} from 'echarts/core'
import {CanvasRenderer} from 'echarts/renderers'
import {PieChart,BarChart,LineChart} from 'echarts/charts'
import {GridComponent,LegendComponent,TooltipComponent} from 'echarts/components'
import type {Overview} from './types'
import PortraitMetrics from './PortraitMetrics.vue'
use([CanvasRenderer,PieChart,BarChart,LineChart,GridComponent,LegendComponent,TooltipComponent])
const props=defineProps<{overview:Overview}>()
const primaryColor=getComputedStyle(document.documentElement).getPropertyValue('--app-primary').trim()
const palette=[primaryColor,'#666666','#b6b6b6','#ed6b7b','#8e8e8e']
const taskMain=computed(()=>props.overview.taskStatus.slice(0,4).filter(x=>x.value>0))
const overdue=computed(()=>props.overview.taskStatus.find(x=>x.key==='OVERDUE')?.value||0)
const pie=computed(()=>({tooltip:{trigger:'item'},legend:{bottom:0},series:[{type:'pie',radius:['43%','67%'],label:{show:false},data:taskMain.value.map((x,i)=>({name:x.label,value:x.value,itemStyle:{color:palette[i]}}))}]}))
const course=computed(()=>({tooltip:{trigger:'axis'},grid:{left:45,right:18,top:20,bottom:40},xAxis:{type:'category',data:props.overview.courseParticipation.map(x=>x.label)},yAxis:{type:'value',minInterval:1},series:[{type:'bar',data:props.overview.courseParticipation.map((x,i)=>({value:x.value,itemStyle:{color:palette[i%palette.length]}})),barMaxWidth:42}]}))
const exam=computed(()=>({tooltip:{trigger:'axis',formatter:(items:any[])=>{const p=items[0];const x=props.overview.examTrend[p.dataIndex];return x?`${x.label}<br/>${x.value} / ${x.maxValue??'-'}<br/>${x.meta||''}`:''}},grid:{left:45,right:18,top:20,bottom:50},xAxis:{type:'category',data:props.overview.examTrend.map(x=>x.label),axisLabel:{rotate:20}},yAxis:{type:'value'},series:[{type:'line',smooth:true,data:props.overview.examTrend.map(x=>x.value),itemStyle:{color:primaryColor},areaStyle:{color:'rgba(217,0,27,.08)'}}]}))
const evaluation=computed(()=>({tooltip:{trigger:'axis'},grid:{left:45,right:18,top:20,bottom:45},xAxis:{type:'category',data:props.overview.monthlyEvaluationTrend.map(x=>x.label)},yAxis:{type:'value'},series:[{type:'line',data:props.overview.monthlyEvaluationTrend.map(x=>x.value),itemStyle:{color:'#666666'}}]}))
</script>
<template><div class="overview"><PortraitMetrics :metrics="overview.metrics"/><div class="charts"><section><header><h3>任务状态分布</h3><el-tag v-if="overdue" type="danger" size="small">逾期 {{overdue}} 项</el-tag></header><VChart v-if="taskMain.length" :option="pie" autoresize/><el-empty v-else :image-size="62" description="暂无任务"/></section><section><h3>课程参与情况</h3><VChart v-if="overview.courseParticipation.length" :option="course" autoresize/><el-empty v-else :image-size="62" description="暂无课程安排"/></section><section><h3>考试成绩趋势</h3><VChart v-if="overview.examTrend.length" :option="exam" autoresize/><el-empty v-else :image-size="62" description="暂无已发布成绩"/></section><section><header><h3>月度评价趋势</h3><span>月度</span></header><VChart v-if="overview.monthlyEvaluationTrend.length" :option="evaluation" autoresize/><el-empty v-else :image-size="62" description="暂无已发布评价"/></section></div></div></template>
<style scoped>.overview{display:flex;flex-direction:column;gap:14px}.charts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.charts section{min-width:0;height:275px;border:1px solid #e8e8e8;border-radius:6px;padding:12px;background:#fff}.charts h3{margin:0;font-size:16px}.charts header{display:flex;justify-content:space-between;align-items:center}.charts .echarts{height:225px}.charts :deep(.el-empty){height:220px;padding:0}@media(max-width:800px){.charts{grid-template-columns:minmax(0,1fr)}}</style>
