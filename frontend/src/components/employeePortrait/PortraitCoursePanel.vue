<script setup lang="ts">
import {onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {api} from '@/api'
import {dateTime,statusLabel} from './usePortraitPage'

const props=defineProps<{employeeId:number}>()
const type=ref<'SESSIONS'|'MATERIALS'>('SESSIONS')
const dateRange=ref<string[]>([])
const records=ref<any[]>([])
const total=ref(0)
const page=ref(1)
const size=ref(20)
const loading=ref(false)
const error=ref('')
let sequence=0
let controller:AbortController|undefined

async function load(nextPage=page.value){
  const current=++sequence
  controller?.abort()
  controller=new AbortController()
  loading.value=true
  error.value=''
  records.value=[]
  try{
    const response:any=await api.get(`/employees/${props.employeeId}/portrait/courses`,{params:{type:type.value,page:nextPage,size:size.value,dateFrom:dateRange.value[0],dateTo:dateRange.value[1]},signal:controller.signal,silentError:true} as any)
    if(current!==sequence)return
    records.value=response.data.page.records
    total.value=response.data.page.total
    page.value=response.data.page.page
  }catch(e:any){
    if(e?.code!=='ERR_CANCELED'&&current===sequence)error.value=e?.response?.data?.message||'课程数据加载失败'
  }finally{if(current===sequence)loading.value=false}
}
watch([type,dateRange],()=>load(1),{deep:true})
onMounted(()=>load())
onBeforeUnmount(()=>{sequence++;controller?.abort()})
</script>

<template>
  <section class="panel">
    <header><div class="filters"><el-segmented v-model="type" :options="[{label:'参训场次',value:'SESSIONS'},{label:'课件阅读',value:'MATERIALS'}]"/><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" :start-placeholder="type==='SESSIONS'?'场次日期起':'阅读日期起'" :end-placeholder="type==='SESSIONS'?'场次日期止':'阅读日期止'" range-separator="至"/></div><span>{{type==='SESSIONS'?'按场次开始时间筛选':'按最近阅读时间筛选；课件按 ID 去重'}}</span></header>
    <el-alert v-if="error" type="error" :title="error" show-icon><template #default><el-button link type="primary" @click="load(page)">重试</el-button></template></el-alert>
    <el-table v-else-if="type==='SESSIONS'" :data="records" v-loading="loading" height="100%" stripe empty-text="暂无参训场次">
      <el-table-column prop="courseName" label="课程" min-width="150"/>
      <el-table-column prop="sessionTitle" label="场次" min-width="150"/>
      <el-table-column label="时间" min-width="155"><template #default="s">{{dateTime(s.row.startsAt)}}<br>{{dateTime(s.row.endsAt)}}</template></el-table-column>
      <el-table-column prop="location" label="地点" min-width="110"><template #default="s">{{s.row.location||'-'}}</template></el-table-column>
      <el-table-column label="参与状态" width="105"><template #default="s">{{statusLabel(s.row.participationStatus)}}</template></el-table-column>
      <el-table-column label="签到状态" width="105"><template #default="s">{{statusLabel(s.row.attendanceStatus)}}</template></el-table-column>
      <el-table-column label="签到时间 / 方式" min-width="160"><template #default="s">{{dateTime(s.row.checkedAt)}} / {{statusLabel(s.row.attendanceSource)}}</template></el-table-column>
    </el-table>
    <el-table v-else :data="records" v-loading="loading" height="100%" stripe empty-text="暂无关联课件">
      <el-table-column prop="courseName" label="课程" min-width="150"/>
      <el-table-column prop="materialName" label="课件" min-width="220"/>
      <el-table-column label="阅读状态" width="100"><template #default="s"><el-tag :type="s.row.read?'success':'info'">{{s.row.read?'已阅读':'未阅读'}}</el-tag></template></el-table-column>
      <el-table-column prop="viewCount" label="阅读次数" width="100"/>
      <el-table-column label="累计记录时长" width="125"><template #default="s">{{Math.round(s.row.durationSeconds/60)}} 分钟</template></el-table-column>
      <el-table-column label="最近阅读" min-width="150"><template #default="s">{{dateTime(s.row.lastViewedAt)}}</template></el-table-column>
    </el-table>
    <footer><el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" @current-change="load"/></footer>
  </section>
</template>

<style scoped>.panel{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:10px}.panel header,.filters{display:flex;align-items:center;justify-content:space-between;gap:8px}.panel header span{color:#7b8798;font-size:12px}.panel footer{display:flex;justify-content:flex-end}@media(max-width:760px){.panel header,.filters{align-items:flex-start;flex-direction:column}}</style>
