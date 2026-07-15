<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {api, type Envelope} from '@/api'
import VChart from 'vue-echarts'
import {use} from 'echarts/core'
import {CanvasRenderer} from 'echarts/renderers'
import {BarChart} from 'echarts/charts'
import {GridComponent, TooltipComponent} from 'echarts/components'
import {useAuthStore} from '@/stores/auth'

use([CanvasRenderer, BarChart, GridComponent, TooltipComponent])

const auth = useAuthStore()
const employee = computed(() => auth.user?.role === 'EMPLOYEE')
const data = ref<any>({})

onMounted(async () => data.value = (await api.get<any, Envelope<any>>('/dashboard')).data)

const option = computed(() => ({
  tooltip: {},
  xAxis: {type: 'category', data: (data.value.scoreDistribution || []).map((item: any) => item.score_range)},
  yAxis: {type: 'value', minInterval: 1},
  series: [{type: 'bar', data: (data.value.scoreDistribution || []).map((item: any) => item.count), itemStyle: {color: '#1769aa'}}]
}))
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>进度概览</h2></div>
    <div class="cards" :class="{ 'employee-cards': employee }">
      <el-card v-if="!employee" class="metric"><span>人员数</span><div class="value">{{ data.employeeCount || 0 }}</div></el-card>
      <el-card class="metric"><span>任务总数</span><div class="value">{{ data.taskTotal || 0 }}</div></el-card>
      <el-card class="metric"><span>已完成任务</span><div class="value">{{ data.taskCompleted || 0 }}</div></el-card>
      <el-card class="metric"><span>平均评分</span><div class="value">{{ data.averageScore || 0 }}</div></el-card>
    </div>
    <el-card class="chart"><template #header>任务评分分布</template><v-chart :option="option" autoresize style="height:270px" /></el-card>
  </div>
</template>

<style scoped>
.employee-cards { grid-template-columns: repeat(3, 1fr); }
@media (max-width: 800px) { .employee-cards { grid-template-columns: 1fr 1fr; } }
</style>
