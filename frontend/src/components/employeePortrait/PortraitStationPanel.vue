<script setup lang="ts">
import {onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {api,type Envelope} from '@/api'
import {dateTime,statusLabel} from './usePortraitPage'

const props=defineProps<{employeeId:number}>()
const tab=ref('stations')
const station=ref<any>(null)
const stationLoading=ref(false)
const stationError=ref('')
const latest=ref<any>(null)
const records=ref<any[]>([])
const locationLoading=ref(false)
const locationError=ref('')
const page=ref(1)
const size=ref(20)
const total=ref(0)
const dateRange=ref<string[]>([])
let stationController:AbortController|undefined
let locationController:AbortController|undefined
let stationSequence=0
let locationSequence=0

async function loadStations(){
  const current=++stationSequence
  stationController?.abort()
  stationController=new AbortController()
  stationLoading.value=true
  stationError.value=''
  station.value=null
  try{
    const response=await api.get<any,Envelope<any>>(`/employees/${props.employeeId}/portrait/stations`,{signal:stationController.signal,silentError:true} as any)
    if(current===stationSequence)station.value=response.data
  }catch(e:any){if(e?.code!=='ERR_CANCELED'&&current===stationSequence)stationError.value=e?.response?.data?.message||'服务站经历加载失败'}
  finally{if(current===stationSequence)stationLoading.value=false}
}
async function loadLocations(nextPage=page.value){
  const current=++locationSequence
  locationController?.abort()
  locationController=new AbortController()
  locationLoading.value=true
  locationError.value=''
  records.value=[]
  try{
    const response:any=await api.get(`/employees/${props.employeeId}/portrait/locations`,{params:{page:nextPage,size:size.value,dateFrom:dateRange.value[0],dateTo:dateRange.value[1]},signal:locationController.signal,silentError:true} as any)
    if(current!==locationSequence)return
    latest.value=response.data.latest
    records.value=response.data.page.records
    total.value=response.data.page.total
    page.value=response.data.page.page
  }catch(e:any){if(e?.code!=='ERR_CANCELED'&&current===locationSequence)locationError.value=e?.response?.data?.message||'位置报备加载失败'}
  finally{if(current===locationSequence)locationLoading.value=false}
}
onMounted(()=>{loadStations();loadLocations()})
watch(dateRange,()=>loadLocations(1),{deep:true})
onBeforeUnmount(()=>{stationSequence++;locationSequence++;stationController?.abort();locationController?.abort()})
</script>

<template>
  <section class="station-panel">
    <div class="current">
      <div><span>当前归属服务站</span><strong>{{station?.employee?.stationName||'未分配'}}</strong></div>
      <div><span>最新位置报备</span><strong>{{latest?.location||'暂无报备'}}</strong><small v-if="latest">实际变动 {{dateTime(latest.occurredAt)}} · 提交 {{dateTime(latest.reportedAt)}}</small></div>
    </div>
    <el-tabs v-model="tab" class="inner-tabs">
      <el-tab-pane label="调站经历" name="stations">
        <el-alert v-if="stationError" type="error" :title="stationError" show-icon><template #default><el-button link type="primary" @click="loadStations">重试</el-button></template></el-alert>
        <el-table v-else :data="station?.history||[]" v-loading="stationLoading" height="100%" stripe empty-text="暂无已生效调站记录">
          <el-table-column prop="fromStation" label="调出服务站" min-width="150"><template #default="s">{{s.row.fromStation||'起始站未记录'}}</template></el-table-column>
          <el-table-column prop="toStation" label="调入服务站" min-width="150"/>
          <el-table-column label="生效时间" min-width="160"><template #default="s">{{dateTime(s.row.effectiveAt)}}</template></el-table-column>
          <el-table-column label="来源" width="140"><template #default="s">{{statusLabel(s.row.source)}}</template></el-table-column>
          <el-table-column prop="comment" label="审批意见" min-width="180"><template #default="s">{{s.row.comment||'-'}}</template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="位置报备历史" name="locations">
        <el-alert v-if="locationError" type="error" :title="locationError" show-icon><template #default><el-button link type="primary" @click="loadLocations(page)">重试</el-button></template></el-alert>
        <div v-else class="location-page">
          <div class="location-filter"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="变动日期起" end-placeholder="变动日期止" range-separator="至"/><span>按实际变动时间筛选</span></div>
          <el-table :data="records" v-loading="locationLoading" height="100%" stripe empty-text="暂无位置报备记录">
            <el-table-column prop="fromLocation" label="原位置" min-width="140"/>
            <el-table-column prop="toLocation" label="报备位置" min-width="140"/>
            <el-table-column prop="reason" label="原因" min-width="180"/>
            <el-table-column label="实际变动 / 提交时间" min-width="200"><template #default="s">{{dateTime(s.row.occurredAt)}}<br>{{dateTime(s.row.reportedAt)}}</template></el-table-column>
            <el-table-column label="预计返回" min-width="150"><template #default="s">{{dateTime(s.row.expectedReturnAt)}}</template></el-table-column>
            <el-table-column label="来源" width="100"><template #default="s">{{statusLabel(s.row.source)}}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" @current-change="loadLocations"/>
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>.station-panel{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr);gap:10px}.current{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.current>div{padding:12px 15px;border:1px solid #e4e9f0;border-radius:6px}.current span,.current small{display:block;color:#7b8798;font-size:12px}.current strong{display:block;margin:5px 0;color:#344054}.inner-tabs{min-height:0;display:grid;grid-template-rows:auto minmax(0,1fr)}.inner-tabs :deep(.el-tabs__header){margin-bottom:10px}.inner-tabs :deep(.el-tabs__content),.inner-tabs :deep(.el-tab-pane){min-height:0;height:100%}.location-page{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:10px}.location-filter{display:flex;justify-content:space-between;align-items:center}.location-filter span{color:#7b8798;font-size:12px}.location-page>.el-pagination{justify-self:end}@media(max-width:760px){.current{grid-template-columns:1fr}.location-filter{align-items:flex-start;gap:8px;flex-direction:column}}</style>
