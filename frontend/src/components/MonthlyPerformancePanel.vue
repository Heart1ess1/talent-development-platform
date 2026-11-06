<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {componentLabels,scoreText,type ComponentCode} from '@/evaluation/model'
import {filterPerformanceMembers,validPerformanceRatios,type PerformanceGroup,type PerformanceMember,type PerformancePreview} from '@/evaluation/performance'

const props=defineProps<{month:string}>(),route=useRoute(),auth=useAuthStore()
const batches=ref<Array<{id:number;name:string}>>([]),batchId=ref<number>(),versions=ref<PerformanceGroup[]>([]),selectedId=ref<number>(),saved=ref<PerformanceGroup>(),preview=ref<PerformancePreview>()
const ratio=reactive<{a:number|undefined;b:number|undefined;c:number|undefined}>({a:undefined,b:undefined,c:undefined})
const busy=ref(false),loading=ref(false),error=ref(''),keyword=ref(''),grade=ref(''),page=ref(1),pageSize=20
const isAdmin=computed(()=>['ADMIN','SUPER_ADMIN'].includes(auth.user?.role||''))
const current=computed(()=>preview.value?.groups.find(g=>g.id===saved.value?.id)||saved.value)
const editable=computed(()=>!saved.value||saved.value.status==='DRAFT')
const ratioValid=computed(()=>validPerformanceRatios([ratio.a,ratio.b,ratio.c]))
const dirty=computed(()=>!saved.value||Number(saved.value.ratio_a)!==ratio.a||Number(saved.value.ratio_b)!==ratio.b||Number(saved.value.ratio_c)!==ratio.c)
const total=computed(()=>[ratio.a,ratio.b,ratio.c].reduce<number>((s,x)=>s+Math.round((x||0)*100),0)/100)
const rows=computed(()=>current.value?.members||[]),filtered=computed(()=>filterPerformanceMembers(rows.value,keyword.value,grade.value)),paged=computed(()=>filtered.value.slice((page.value-1)*pageSize,page.value*pageSize))
const included=computed(()=>rows.value.filter(r=>r.participating).length)
const distribution=computed(()=>{
  const g=current.value;if(!g)return undefined
  if(g.targets)return {targets:g.targets,actual:g.actual,tie_expanded:g.tie_expanded}
  if(typeof g.distribution_snapshot==='string'){try{return JSON.parse(g.distribution_snapshot)}catch{return undefined}}
  return g.distribution_snapshot
})
const related=computed(()=>preview.value?.groups.filter(g=>g.id!==saved.value?.id)||[])
const missingLabels=(items?:string)=>items?.split(',').map(x=>componentLabels[x as ComponentCode]||x).join('、')
let loadGeneration=0
function hydrate(g?:PerformanceGroup){saved.value=g;preview.value=undefined;Object.assign(ratio,{a:g?Number(g.ratio_a):undefined,b:g?Number(g.ratio_b):undefined,c:g?Number(g.ratio_c):undefined});page.value=1}
async function load(prefer?:number){
  const generation=++loadGeneration;loading.value=true;error.value='';hydrate();versions.value=[]
  try{if(!batchId.value||!props.month)return
    const list=(await api.get<any,Envelope<PerformanceGroup[]>>('/evaluation/performance',{params:{batchId:batchId.value,month:props.month}})).data
    if(generation!==loadGeneration)return
    versions.value=list;const target=list.find(x=>x.id===prefer)||list[0];selectedId.value=target?.id
    if(target){const detail=(await api.get<any,Envelope<PerformanceGroup>>(`/evaluation/performance/${target.id}`)).data;if(generation===loadGeneration)hydrate(detail)}
  }catch(e:any){if(generation===loadGeneration)error.value=e.response?.data?.message||'绩效方案加载失败'}finally{if(generation===loadGeneration)loading.value=false}
}
async function action(work:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await work()}catch(e:any){if(e==='cancel'||e==='close')return;preview.value=undefined;error.value=e.response?.data?.message||'操作失败，请刷新后重试'}finally{busy.value=false}}
async function save(){await action(async()=>{if(!batchId.value||!ratioValid.value)return;const result=(await api.put<any,Envelope<PerformanceGroup>>('/evaluation/performance',{batchId:batchId.value,month:props.month,ratioA:ratio.a,ratioB:ratio.b,ratioC:ratio.c})).data;await load(result.id);ElMessage.success('当月绩效比例已保存，请核对名单并预览')})}
async function inspect(){await action(async()=>{if(!saved.value||dirty.value)return;preview.value=(await api.post<any,Envelope<PerformancePreview>>(`/evaluation/performance/${saved.value.id}/preview`)).data})}
async function refreshRoster(){await action(async()=>{if(!saved.value)return;await ElMessageBox.confirm('按该批次当前在培人员更新名单；保留已填写的排除原因。确认刷新？','刷新参评名单');await api.post(`/evaluation/performance/${saved.value.id}/roster/refresh`);await load(saved.value.id)})}
async function participation(row:PerformanceMember){await action(async()=>{if(!saved.value)return;let reason:string|undefined;if(row.participating)reason=(await ElMessageBox.prompt('填写本月不参评原因（必填）','设置不参评',{inputType:'textarea',inputValidator:v=>!!v?.trim()&&v.length<=1000||'请填写不超过1000字的原因'})).value;await api.put(`/evaluation/performance/${saved.value.id}/members/${row.employee_id}`,{participating:!row.participating,reason});await load(saved.value.id)})}
async function waive(row:PerformanceMember,groupId:number){await action(async()=>{const reason=(await ElMessageBox.prompt('说明允许缺项发布的原因','管理员核定',{inputType:'textarea',inputValidator:v=>!!v?.trim()&&v.length<=1000||'请填写不超过1000字的原因'})).value;const score=Number((await ElMessageBox.prompt('输入0至100的综合分，最多两位小数','核定分数',{inputPattern:/^(100(?:\.0{1,2})?|\d{1,2}(?:\.\d{1,2})?)$/,inputErrorMessage:'请输入0至100，最多两位小数'})).value);await api.post(`/evaluation/performance/${groupId}/waivers`,{employeeId:row.employee_id,reason,score});preview.value=(await api.post<any,Envelope<PerformancePreview>>(`/evaluation/performance/${saved.value!.id}/preview`)).data})}
async function publish(){await action(async()=>{if(!saved.value||!preview.value?.canPublish||dirty.value)return;const count=preview.value.groups.reduce((s,g)=>s+g.participant_count,0);await ElMessageBox.confirm(`将统一发布 ${preview.value.groups.length} 个批次月份、${count} 名参评人员的成绩与ABC等级${preview.value.revisionMembers.length?`，以及 ${preview.value.revisionMembers.length} 份关联月评`:''}。原正式版本保留，确认发布？`,'统一发布月度绩效',{confirmButtonText:'确认统一发布',type:'warning'});await api.post(`/evaluation/performance/${saved.value.id}/publish`,{reviewToken:preview.value.reviewToken});ElMessage.success('成绩与绩效等级已统一发布');await load(saved.value.id)})}
async function reopen(){await action(async()=>{if(!saved.value)return;const reason=(await ElMessageBox.prompt('重开将保留原正式结果；修订后重新划分全组等级。请填写原因。','重开月度绩效',{inputType:'textarea',inputValidator:v=>!!v?.trim()&&v.length<=1000||'请填写不超过1000字的原因'})).value;const result=(await api.post<any,Envelope<PerformanceGroup>>(`/evaluation/performance/${saved.value.id}/reopen`,{reason})).data;await load(result.id);ElMessage.success('已创建绩效修订草稿')})}
async function download(){await action(async()=>{if(!saved.value)return;const blob=await api.get<any,Blob>(`/evaluation/performance/${saved.value.id}/export`,{responseType:'blob'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`月度绩效-${current.value?.batch_name}-${props.month}-V${saved.value.version}.xlsx`;a.click();URL.revokeObjectURL(url)})}
watch([keyword,grade],()=>page.value=1)
watch([()=>ratio.a,()=>ratio.b,()=>ratio.c],()=>{preview.value=undefined})
watch([batchId,()=>props.month],()=>{void load()})
onMounted(async()=>{try{batches.value=(await api.get<any,Envelope<Array<{id:number;name:string}>>>('/batches')).data;const requested=Number(route.query.batchId);batchId.value=batches.value.some(b=>b.id===requested)?requested:batches.value[0]?.id}catch{error.value='培养批次加载失败，请刷新页面重试'}})
</script>

<template>
  <section class="performance-panel" v-loading="loading||busy">
    <div class="performance-toolbar">
      <el-select v-model="batchId" filterable placeholder="选择培养批次" aria-label="绩效培养批次"><el-option v-for="b in batches" :key="b.id" :label="b.name" :value="b.id"/></el-select>
      <el-select v-if="versions.length" v-model="selectedId" filterable aria-label="绩效版本" @change="(id:number)=>load(id)"><el-option v-for="g in versions" :key="g.id" :value="g.id" :label="`V${g.version} · ${{DRAFT:'草稿',PUBLISHED:'已发布',SUPERSEDED:'历史版本'}[g.status]||g.status}`"/></el-select>
      <el-button :disabled="!batchId" @click="load(selectedId)">刷新方案</el-button>
      <el-button v-if="saved" :disabled="dirty" @click="download">导出本组结果</el-button>
      <el-button v-if="isAdmin&&saved?.status==='PUBLISHED'" @click="reopen">重开修订</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert v-if="saved?.version&&saved.version>1&&saved.status==='DRAFT'" title="正在修订：原正式成绩和等级继续有效，发布后统一切换。参评名单保持发布时的快照。" type="warning" :closable="false"/>
    <section class="performance-card">
      <h2>当月绩效比例 <small>{{month}}</small></h2>
      <p>同培养批次按综合分排名；同分同档并进入较高档，实际比例可能偏离目标。</p>
      <div class="ratio-form"><label v-for="code in (['a','b','c'] as const)" :key="code"><span>{{code.toUpperCase()}} 档比例</span><el-input-number :key="`${code}-${!!batchId&&editable}`" v-model="ratio[code]" :min="0" :max="100" :precision="2" :step="1" :disabled="!editable||!batchId" :aria-label="`${code.toUpperCase()}档比例`" controls-position="right"/><b>%</b></label><strong :class="{invalid:!ratioValid}">合计 {{total.toFixed(2)}}%</strong><el-button v-if="editable" type="primary" :disabled="!batchId||!ratioValid||!dirty" @click="save">保存当月比例</el-button></div>
      <p v-if="!saved" class="muted">每月单独设置；保存后载入参评名单。已有正式成绩的月份不支持补划历史绩效。</p>
      <p v-else-if="dirty" class="invalid">比例尚未保存，请保存后重新预览。</p>
    </section>
    <template v-if="saved">
      <section class="performance-card">
        <div class="section-head"><h2>参评名单 <small>参评 {{included}} 人 / 不参评 {{rows.length-included}} 人</small></h2><el-button v-if="editable&&saved.version===1" :disabled="dirty" @click="refreshRoster">刷新参评名单</el-button></div>
        <div class="performance-toolbar"><el-input v-model="keyword" clearable placeholder="搜索姓名、工号" aria-label="搜索绩效人员"/><el-select v-model="grade" filterable clearable placeholder="全部等级" aria-label="绩效等级筛选"><el-option v-for="g in ['A','B','C']" :key="g" :label="`${g}档`" :value="g"/><el-option label="不参评" value="EXCLUDED"/></el-select><span class="muted">筛选仅影响展示，全组参与计算。</span></div>
        <el-table :data="paged" empty-text="暂无匹配人员" row-key="employee_id">
          <el-table-column prop="employee_no" label="工号" min-width="100"/><el-table-column prop="employee_name" label="姓名" min-width="100"/>
          <el-table-column label="综合分" width="100"><template #default="s">{{scoreText(s.row.final_score)}}</template></el-table-column>
          <el-table-column prop="rank_no" label="并列名次" width="95"/>
          <el-table-column label="绩效等级" width="110"><template #default="s"><el-tag v-if="s.row.grade" :type="s.row.grade==='A'?'success':s.row.grade==='C'?'warning':'info'">{{s.row.grade}}</el-tag><span v-else>{{s.row.participating?'待预览':'不参评'}}</span></template></el-table-column>
          <el-table-column v-if="saved.version>1" label="原分 / 原等级" min-width="120"><template #default="s">{{scoreText(s.row.old_score)}} / {{s.row.old_grade||'—'}}</template></el-table-column>
          <el-table-column label="评分情况 / 不参评原因" min-width="220"><template #default="s"><span v-if="!s.row.participating">{{s.row.exclusion_reason}}</span><template v-else><span :class="{invalid:s.row.error}">{{s.row.error?(missingLabels(s.row.missing_items)||s.row.error):s.row.revision_override_score!=null?'已核定：'+s.row.waiver_reason:s.row.summary_id?'完整':'请预览核对'}}</span><el-button v-if="isAdmin&&editable&&s.row.missing_items" link type="primary" @click="waive(s.row,saved!.id)">管理员核定</el-button></template></template></el-table-column>
          <el-table-column v-if="editable&&saved.version===1" label="操作" width="110"><template #default="s"><el-button link type="primary" :disabled="dirty" @click="participation(s.row)">{{s.row.participating?'设为不参评':'恢复参评'}}</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" :page-size="pageSize" :total="filtered.length" layout="total, prev, pager, next"/>
      </section>
      <section class="performance-card">
        <div class="section-head"><h2>{{editable?'预览与统一发布':'正式分布'}}</h2><el-button v-if="editable" :disabled="dirty" @click="inspect">重新计算并预览</el-button></div>
        <div v-if="distribution" class="distribution"><article v-for="(g,i) in ['A','B','C']" :key="g"><b>{{g}} 档</b><span>目标 {{distribution.targets[i]}} 人</span><strong>{{distribution.actual?`${distribution.actual[i]} 人`:'待评分完整'}}</strong><small>实际 {{distribution.actual&&included?((distribution.actual[i]/included)*100).toFixed(2)+'%':'—'}}</small></article></div>
        <el-alert v-if="distribution?.tie_expanded" title="分界出现同分，已统一进入较高档；实际人数和比例与目标存在偏差。" type="warning" :closable="false"/>
        <el-alert v-for="message in preview?.blockingMessages||[]" :key="message" :title="message" type="error" :closable="false"/>
        <section v-for="g in related" :key="g.id" class="related-group"><h3>关联绩效：{{g.batch_name}} · {{String(g.period_month).slice(0,7)}} · V{{g.version}}</h3><p>目标 A/B/C：{{g.targets?.join(' / ')}} 人；实际：{{g.actual?.join(' / ')||'待评分完整'}} 人</p><el-table :data="g.members"><el-table-column prop="employee_name" label="姓名"/><el-table-column label="原分 → 新分"><template #default="s">{{scoreText(s.row.old_score)}} → {{scoreText(s.row.final_score)}}</template></el-table-column><el-table-column label="原等级 → 新等级"><template #default="s">{{s.row.old_grade||'—'}} → {{s.row.grade||'—'}}</template></el-table-column><el-table-column label="评分情况"><template #default="s">{{s.row.error||s.row.waiver_reason||'完整'}}<el-button v-if="isAdmin&&s.row.missing_items" link @click="waive(s.row,g.id)">管理员核定</el-button></template></el-table-column></el-table></section>
        <section v-if="preview?.revisionMembers.length" class="related-group"><h3>同时发布的关联月评</h3><el-table :data="preview.revisionMembers"><el-table-column prop="employee_name" label="姓名"/><el-table-column prop="period_key" label="月份"/><el-table-column label="综合分"><template #default="s">{{scoreText(s.row.final_score)}}</template></el-table-column><el-table-column label="缺项"><template #default="s">{{missingLabels(s.row.missing_items)||s.row.error||'无'}}</template></el-table-column></el-table></section>
        <el-button v-if="editable" type="primary" :disabled="!preview?.canPublish||dirty" @click="publish">核对完成，统一发布成绩与等级</el-button>
      </section>
    </template>
  </section>
</template>

<style scoped>
.performance-panel{display:grid;gap:16px}.performance-toolbar,.section-head,.ratio-form{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.performance-toolbar>.el-select,.performance-toolbar>.el-input{width:220px}.performance-card{padding:22px;background:var(--app-surface,#fff);border:1px solid var(--app-border,#e5e7eb);border-radius:12px;min-width:0}.performance-card h2{margin:0;font-size:18px}.performance-card h2 small{font-size:14px;font-weight:400;color:var(--app-text-secondary,#737373);margin-left:10px}.performance-card p,.muted{color:var(--app-text-secondary,#737373);font-size:14px;line-height:1.6}.ratio-form label{display:flex;align-items:center;gap:8px}.ratio-form .el-input-number{width:130px}.invalid{color:var(--el-color-danger)}.section-head{justify-content:space-between;margin-bottom:16px}.performance-card>.performance-toolbar{margin-bottom:14px}.distribution{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:16px 0}.distribution article{display:grid;gap:8px;padding:18px;border:1px solid var(--app-border,#e5e7eb);border-radius:9px}.distribution article b{font-size:18px}.distribution article strong{font-size:24px}.distribution article span,.distribution article small{color:var(--app-text-secondary,#737373)}.performance-card>.el-alert{margin:12px 0}.performance-card>.el-pagination{margin-top:16px;justify-content:flex-end}.related-group{margin:20px 0;padding-top:12px;border-top:1px solid var(--app-border,#e5e7eb)}.related-group h3{font-size:16px}@media(max-width:760px){.performance-card{padding:15px}.ratio-form{align-items:flex-start;flex-direction:column}.distribution{grid-template-columns:1fr}.performance-toolbar>.el-select,.performance-toolbar>.el-input{width:100%}}
</style>
