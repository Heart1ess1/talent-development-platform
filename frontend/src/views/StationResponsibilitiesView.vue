<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {api,type Envelope} from '@/api'

type Person={id:number;username:string;display_name:string;role:string;enabled:boolean}
type Station={id:number;name:string;enabled:boolean}
type Scope={user_id:number;station_id:number;level:string;display_name:string;station_name:string}
type Assignment={stationId:number;level:string;userIds:number[]}
type Impact={key:string;level:string;stationId:number;stationName:string;fromUserId:number;fromName:string;count:number;candidateIds:number[]}
const router=useRouter()
const context=ref<{stations:Station[];users:Person[];scopes:Scope[]}>({stations:[],users:[],scopes:[]})
const tab=ref('服务站维护'),keyword=ref(''),page=ref(1),loading=ref(false),busy=ref(false),dialog=ref(false)
const editing=ref<'station'|'person'>('station'),selectedStation=ref<Station|null>(null),selectedPerson=ref<Person|null>(null)
const form=reactive({stationManagers:[] as number[],areaManagers:[] as number[],stationIds:[] as number[],replacementId:null as number|null,reason:''})
const preview=ref<{baselineHash:string;impacts:Impact[];affectedVersions:number}|null>(null)
const pendingAssignments=ref<Assignment[]>([])
const transfers=reactive<Record<string,number|null>>({})
const labels:Record<string,string>={STATION_MANAGER:'站长',AREA_MANAGER:'区域经理'}
const role=computed(()=>tab.value==='站长管理'?'STATION_MANAGER':'AREA_MANAGER')
const people=(level:string)=>context.value.users.filter(x=>x.role===level)
const selectedNames=(station:number,level:string)=>context.value.scopes.filter(x=>x.station_id===station&&x.level===level).map(x=>x.display_name).join('、')||'未配置'
const stationNames=(user:number)=>context.value.scopes.filter(x=>x.user_id===user).map(x=>x.station_name).join('、')||'未分配'
const personName=(id:number)=>context.value.users.find(x=>x.id===id)?.display_name||String(id)
const scopedIds=(station:number,level:string)=>context.value.scopes.filter(x=>x.station_id===station&&x.level===level).map(x=>x.user_id)
const stationOptions=computed(()=>context.value.stations.map(x=>({key:x.id,label:x.name+(x.enabled?'':'（停用）')})))
const stationRows=computed(()=>context.value.stations.filter(x=>!keyword.value||`${x.name} ${selectedNames(x.id,'STATION_MANAGER')} ${selectedNames(x.id,'AREA_MANAGER')}`.includes(keyword.value.trim())))
const personRows=computed(()=>people(role.value).filter(x=>!keyword.value||`${x.display_name} ${x.username} ${stationNames(x.id)}`.includes(keyword.value.trim())))
const total=computed(()=>tab.value==='服务站维护'?stationRows.value.length:personRows.value.length)
const visibleStations=computed(()=>stationRows.value.slice((page.value-1)*20,page.value*20))
const visiblePeople=computed(()=>personRows.value.slice((page.value-1)*20,page.value*20))
const unconfigured=computed(()=>context.value.stations.filter(x=>x.enabled&&(!scopedIds(x.id,'STATION_MANAGER').length||!scopedIds(x.id,'AREA_MANAGER').length)).length)
watch([tab,keyword],()=>{page.value=1})
async function load(){loading.value=true;try{const response=await api.get<any,Envelope<typeof context.value>>('/station-responsibilities');context.value=response.data}finally{loading.value=false}}
function reset(){preview.value=null;pendingAssignments.value=[];Object.keys(transfers).forEach(key=>delete transfers[key]);Object.assign(form,{stationManagers:[],areaManagers:[],stationIds:[],replacementId:null,reason:''})}
function editStation(station:Station){reset();editing.value='station';selectedStation.value=station;selectedPerson.value=null;form.stationManagers=scopedIds(station.id,'STATION_MANAGER');form.areaManagers=scopedIds(station.id,'AREA_MANAGER');dialog.value=true}
function editPerson(person:Person){reset();editing.value='person';selectedPerson.value=person;selectedStation.value=null;form.stationIds=context.value.scopes.filter(x=>x.user_id===person.id).map(x=>x.station_id);dialog.value=true}
function assignments():Assignment[]{
  let result:Assignment[]=[]
  if(editing.value==='station'&&selectedStation.value)result=[{stationId:selectedStation.value.id,level:'STATION_MANAGER',userIds:form.stationManagers},{stationId:selectedStation.value.id,level:'AREA_MANAGER',userIds:form.areaManagers}]
  if(editing.value==='person'&&selectedPerson.value){
    const person=selectedPerson.value
    const before=context.value.scopes.filter(x=>x.user_id===person.id).map(x=>x.station_id)
    result=[...new Set([...before,...form.stationIds])].map(stationId=>{
      let ids=scopedIds(stationId,person.role).filter(x=>x!==person.id)
      if(form.stationIds.includes(stationId))ids.push(person.id)
      else if(form.replacementId)ids.push(form.replacementId)
      return {stationId,level:person.role,userIds:[...new Set(ids)].sort((a,b)=>a-b)}
    })
  }
  return result.filter(x=>JSON.stringify([...new Set(x.userIds)].sort((a,b)=>a-b))!==JSON.stringify(scopedIds(x.stationId,x.level).sort((a,b)=>a-b)))
}
async function inspect(){
  pendingAssignments.value=assignments()
  if(!pendingAssignments.value.length)return ElMessage.info('负责范围尚未变化')
  if(!form.reason.trim())return ElMessage.warning('请填写调整原因')
  busy.value=true
  try{
    const response=await api.post<any,Envelope<NonNullable<typeof preview.value>>>('/station-responsibilities/preview',{assignments:pendingAssignments.value})
    preview.value=response.data
    for(const impact of response.data.impacts)transfers[impact.key]=impact.candidateIds.length===1?impact.candidateIds[0]!:null
  }finally{busy.value=false}
}
async function save(){
  if(!preview.value)return
  if(preview.value.impacts.some(x=>!transfers[x.key]))return ElMessage.warning('请为受影响的请假责任选择接任人；没有接任人时请返回修改负责范围')
  busy.value=true
  try{
    const response=await api.post<any,Envelope<{affectedVersions:number;context:typeof context.value}>>('/station-responsibilities',{assignments:pendingAssignments.value,transfers,reason:form.reason.trim(),baselineHash:preview.value.baselineHash})
    context.value=response.data.context;dialog.value=false;ElMessage.success(`负责范围已保存，转交 ${response.data.affectedVersions} 项请假责任`)
  }finally{busy.value=false}
}
onMounted(load)
</script>

<template>
  <div class="responsibility-page">
    <header class="page-head"><div><h1>站长与区域经理管理</h1><p>维护服务站负责人及其负责范围，人员变更时一并交接请假责任。</p></div><div><el-button @click="router.push('/users')">账号管理</el-button><el-button :loading="loading" @click="load">刷新</el-button></div></header>
    <div class="summary"><span>服务站 <strong>{{context.stations.length}}</strong></span><span>站长 <strong>{{people('STATION_MANAGER').length}}</strong></span><span>区域经理 <strong>{{people('AREA_MANAGER').length}}</strong></span><span>在用站点待完善 <strong>{{unconfigured}}</strong></span></div>
    <el-tabs v-model="tab"><el-tab-pane v-for="item in ['服务站维护','站长管理','区域经理管理']" :key="item" :name="item" :label="item"/></el-tabs>
    <div class="toolbar"><el-input v-model="keyword" clearable placeholder="搜索服务站、负责人或账号"/><span>共 {{total}} 项</span></div>
    <el-table v-if="tab==='服务站维护'" v-loading="loading" :data="visibleStations" row-key="id">
      <el-table-column prop="name" label="服务站" min-width="180"/>
      <el-table-column label="站长" min-width="180"><template #default="{row}">{{selectedNames(row.id,'STATION_MANAGER')}}</template></el-table-column>
      <el-table-column label="区域经理" min-width="180"><template #default="{row}">{{selectedNames(row.id,'AREA_MANAGER')}}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'在用':'停用'}}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="130" fixed="right"><template #default="{row}"><el-button link type="primary" @click="editStation(row)">调整负责人</el-button></template></el-table-column>
    </el-table>
    <el-table v-else v-loading="loading" :data="visiblePeople" row-key="id">
      <el-table-column prop="display_name" label="负责人" min-width="130"/><el-table-column prop="username" label="账号" min-width="140"/>
      <el-table-column label="当前负责服务站" min-width="300"><template #default="{row}">{{stationNames(row.id)}}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'启用':'停用'}}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="{row}"><el-button link type="primary" @click="editPerson(row)">调整负责服务站</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" class="pagination"/>
    <el-dialog v-model="dialog" :title="editing==='station'?`${selectedStation?.name} · 调整负责人`:`${selectedPerson?.display_name} · 调整负责服务站`" width="min(850px,96vw)" destroy-on-close>
      <el-form label-position="top" :disabled="Boolean(preview)">
        <template v-if="editing==='station'">
          <el-form-item label="站长"><el-select v-model="form.stationManagers" multiple filterable class="full-width" placeholder="选择该服务站的站长"><el-option v-for="person in people('STATION_MANAGER')" :key="person.id" :label="`${person.display_name}（${person.username}）`" :value="person.id" :disabled="!person.enabled"/></el-select></el-form-item>
          <el-form-item label="区域经理"><el-select v-model="form.areaManagers" multiple filterable class="full-width" placeholder="选择负责该服务站的区域经理"><el-option v-for="person in people('AREA_MANAGER')" :key="person.id" :label="`${person.display_name}（${person.username}）`" :value="person.id" :disabled="!person.enabled"/></el-select></el-form-item>
        </template>
        <template v-else>
          <el-transfer v-model="form.stationIds" :data="stationOptions" filterable filter-placeholder="搜索服务站" :titles="['可分配服务站','当前负责服务站']" :disabled="Boolean(preview)"/>
          <el-form-item label="移出站点的接任人（选填）" class="replacement"><el-select v-model="form.replacementId" filterable clearable class="full-width" placeholder="需要交接时选择同级接任人"><el-option v-for="person in people(selectedPerson?.role||'').filter(x=>x.id!==selectedPerson?.id&&x.enabled)" :key="person.id" :label="`${person.display_name}（${person.username}）`" :value="person.id"/></el-select><small>所选人员将接管移出的站点；没有未完成请假责任时，也可仅移出负责范围。</small></el-form-item>
        </template>
        <el-form-item label="调整原因" required><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="500" show-word-limit/></el-form-item>
      </el-form>
      <section v-if="preview" class="impact-preview">
        <el-alert :title="`预计交接 ${preview.affectedVersions} 项待审批或未销假责任`" type="info" :closable="false"/>
        <div v-for="impact in preview.impacts" :key="impact.key" class="impact-row"><div><strong>{{impact.stationName}} · {{labels[impact.level]}}</strong><span>{{impact.fromName}} 移出范围，{{impact.count}} 项请假责任待交接</span></div><el-select v-model="transfers[impact.key]" filterable placeholder="选择接任人"><el-option v-for="id in impact.candidateIds" :key="id" :label="personName(id)" :value="id"/></el-select><small v-if="!impact.candidateIds.length" class="warning">尚未配置接任人，请返回修改。</small></div>
        <p>保存后统一更新负责范围、请假责任和待办；历史审批记录继续保留。</p>
      </section>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button v-if="preview" @click="preview=null">返回修改</el-button><el-button v-if="!preview" type="primary" :loading="busy" @click="inspect">预览调整</el-button><el-button v-else type="primary" :loading="busy" @click="save">确认保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.responsibility-page{padding:24px;background:#f7f8fa;min-height:100%;color:#24313d}.page-head{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:20px}.page-head h1{font-size:25px;margin:0 0 8px}.page-head p{color:#637581;margin:0}.summary{display:flex;gap:28px;flex-wrap:wrap;padding:18px 20px;background:white;border:1px solid #e2e8ee;border-radius:8px;margin-bottom:18px}.summary strong{font-size:21px;margin-left:8px;color:#286c8f}.toolbar{display:flex;align-items:center;gap:20px;margin:12px 0}.toolbar .el-input{max-width:340px}.toolbar span{color:#6a7783;font-size:13px}.pagination{justify-content:flex-end;margin-top:16px}.full-width{width:100%}.replacement{margin-top:18px}.replacement small{color:#687986;margin-top:7px}.impact-preview{border-top:1px solid #e2e8ee;padding-top:16px}.impact-row{display:flex;align-items:center;gap:16px;padding:14px 0;flex-wrap:wrap}.impact-row>div:first-child{display:flex;flex-direction:column;gap:6px;flex:1}.impact-row span,.impact-preview p{color:#667783;font-size:13px}.impact-row .el-select{width:230px}.warning{color:#b4532c}.responsibility-page :deep(.el-transfer){display:flex;align-items:center;justify-content:center}.responsibility-page :deep(.el-transfer-panel){flex:1;max-width:310px}.responsibility-page :deep(.el-transfer__buttons){padding:0 18px}.responsibility-page :deep(.el-transfer-panel__body){height:260px}@media(max-width:650px){.responsibility-page{padding:14px}.page-head{align-items:flex-start;flex-direction:column}.summary{gap:14px}.responsibility-page :deep(.el-transfer){flex-wrap:wrap}.responsibility-page :deep(.el-transfer-panel){min-width:100%;max-width:none}.responsibility-page :deep(.el-transfer__buttons){display:flex;gap:12px;padding:12px;transform:none}.responsibility-page :deep(.el-transfer__buttons .el-icon){transform:rotate(90deg)}.responsibility-page :deep(.el-transfer__button+.el-transfer__button){margin-left:0}.responsibility-page :deep(.el-dialog__body){max-height:60vh;overflow-y:auto}.responsibility-page :deep(.el-transfer-panel__body){height:180px}.impact-row .el-select{width:100%}}
</style>
