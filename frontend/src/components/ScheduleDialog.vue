<script setup lang="ts">
import {reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,type Envelope} from '@/api'
import TimeWindowEditor from './TimeWindowEditor.vue'
import {localDateTime,windowPayload} from '@/utils/timeWindow'
import {componentLabels,type ComponentCode} from '@/evaluation/model'
const emit=defineEmits<{changed:[]}>()
const visible=ref(false),loading=ref(false),resourceType=ref('TASK'),resourceId=ref<number>(),detail=ref<any>(),preview=ref<any>(),personal=ref(false)
const form=reactive<any>({scheduleMode:'IMMEDIATE',startsAt:'',endsAt:'',baseDate:'',offsetDays:7,version:0,reason:'',participantId:null})
function path(){return `/schedules/${resourceType.value}/${resourceId.value}`}
async function open(type:string,id:number,participantId?:number){resourceType.value=type;resourceId.value=id;loading.value=true;visible.value=true;try{detail.value=(await api.get<any,Envelope<any>>(path())).data;personal.value=Boolean(participantId);const end=detail.value.endsAt;const ongoing=end&&new Date(end)>new Date();const next=new Date();next.setDate(next.getDate()+7);Object.assign(form,{scheduleMode:personal.value||!ongoing?'IMMEDIATE':detail.value.schedule_mode||'IMMEDIATE',startsAt:!personal.value&&ongoing&&detail.value.starts_at?String(detail.value.starts_at).replace(' ','T'):localDateTime(),endsAt:ongoing?String(end).replace(' ','T'):localDateTime(next).slice(0,10)+'T23:59:59',baseDate:detail.value.base_date?String(detail.value.base_date).slice(0,10):localDateTime().slice(0,10),offsetDays:detail.value.offset_days??7,version:detail.value.schedule_version,reason:'',participantId:participantId||null});preview.value=null}finally{loading.value=false}}
function payload(){return {...windowPayload(form),version:form.version,reason:form.reason,participantId:personal.value?form.participantId:null}}
async function inspect(){if(!form.reason.trim())return ElMessage.warning('请填写调整原因');if(personal.value&&!form.participantId)return ElMessage.warning('请选择个人办理对象');preview.value=(await api.post<any,Envelope<any>>(`${path()}/preview`,payload())).data}
async function save(){loading.value=true;try{if(personal.value)await api.post(`${path()}/opportunities`,payload());else await api.put(path(),payload());ElMessage.success('已更新办理窗口；涉及已发布结果的调整已生成关联修订稿');visible.value=false;emit('changed')}finally{loading.value=false}}
async function revoke(row:any){await ElMessageBox.confirm('撤销后恢复整体时限及计分月份，确认撤销此个人机会？','撤销个人机会');await api.delete(`${path()}/opportunities/${row.id}`);ElMessage.success('已撤销');await open(resourceType.value,resourceId.value!);emit('changed')}
function score(value:any){return value==null?'—':Number(value).toFixed(2)}
function issue(row:any){return row.error||((row.missingItems||[]).length?`待完善：${row.missingItems.map((key:string)=>componentLabels[key as ComponentCode]||key).join('、')}`:'按当前有效成绩预估')}
watch([form,personal],()=>preview.value=null,{deep:true})
defineExpose({open})
</script>
<template>
  <el-dialog v-model="visible" title="调整时限与重新开放" width="min(900px,96vw)" :close-on-click-modal="false">
    <div v-loading="loading"><el-alert title="保留原提交、答卷及评分记录" description="整体调整覆盖全部人员的截止时间；个人补交只影响指定人员，并提供一次额外机会。跨月时同步调整计分归属。" type="info" :closable="false"/>
      <el-form label-position="top" style="margin-top:18px"><el-form-item label="调整范围"><el-radio-group v-model="personal"><el-radio-button :value="false">整体调整／重新开放</el-radio-button><el-radio-button :value="true">个人补交／补考一次</el-radio-button></el-radio-group></el-form-item>
        <el-form-item v-if="personal" label="办理对象"><el-select v-model="form.participantId" filterable placeholder="选择人员"><el-option v-for="p in detail?.participants||[]" :key="p.participant_id" :value="p.participant_id" :label="`${p.name}（${p.employee_no}）`"/></el-select></el-form-item>
        <TimeWindowEditor :model-value="form" @update:model-value="Object.assign(form,$event)"/><el-form-item label="调整原因" required><el-input v-model="form.reason" type="textarea" maxlength="1000" show-word-limit/></el-form-item>
      </el-form>
      <template v-if="preview"><el-divider>影响预览 · {{preview.participants.length}} 人</el-divider><p>新窗口：{{preview.window.startsAt.replace('T',' ')}} 至 {{preview.window.endsAt.replace('T',' ')}}；已有个人机会 {{preview.activePersonalCount}} 个，整体调整时次数保留、窗口同步更新。</p><el-table :data="preview.participants" max-height="230"><el-table-column prop="name" label="姓名"/><el-table-column prop="employee_no" label="工号"/><el-table-column prop="oldMonth" label="原计分月份"/><el-table-column prop="newMonth" label="新计分月份"/></el-table><el-alert v-if="preview.publishedSummaries.length" :title="`影响 ${preview.publishedSummaries.length} 份已发布结果，将生成关联修订稿`" type="warning" :closable="false"/>
        <p>以下按现有成绩预估迁移影响；补交、补考产生的新成绩须完成评分后再核定。</p><el-table :data="preview.scoreImpacts||[]" max-height="230"><el-table-column prop="name" label="姓名"/><el-table-column prop="periodKey" label="月份／季度"/><el-table-column label="原正式分"><template #default="s">{{score(s.row.oldScore)}}</template></el-table-column><el-table-column label="调整后预估"><template #default="s">{{score(s.row.newScore)}}</template></el-table-column><el-table-column label="说明" min-width="180"><template #default="s">{{issue(s.row)}}</template></el-table-column></el-table>
      </template>
      <template v-if="detail?.opportunities?.length"><el-divider>个人机会记录</el-divider><el-table :data="detail.opportunities" max-height="180"><el-table-column prop="participant_id" label="人员编号"/><el-table-column prop="ends_at" label="个人截止时间"/><el-table-column prop="status" label="状态"><template #default="s">{{({ACTIVE:'未使用',CONSUMED:'已使用',REVOKED:'已撤销',ARCHIVED:'已使用（窗口已调整）'} as any)[s.row.status]}}</template></el-table-column><el-table-column prop="reason" label="原因"/><el-table-column label="操作"><template #default="s"><el-button v-if="s.row.status==='ACTIVE'" link @click="revoke(s.row)">撤销</el-button></template></el-table-column></el-table></template>
    </div><template #footer><el-button @click="visible=false">取消</el-button><el-button :disabled="loading" @click="inspect">预览影响</el-button><el-button type="primary" :disabled="!preview||loading" @click="save">确认调整</el-button></template>
  </el-dialog>
</template>
