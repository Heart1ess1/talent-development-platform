<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api,type Envelope} from '@/api'
import {dateTimeParts,participationLabels,planPhaseLabels,parseJson} from './examUi'

type ProctorEventResult={violationCount:number;allowedViolations:number;autoSubmitted:boolean;status:string}
const plans=ref<any[]>([]),dialog=ref(false),attempt=ref<any>()
const answers=reactive<Record<number,any>>({})
const violationDialog=ref(false),violationCount=ref(0),allowedViolations=ref(3),isFullscreen=ref(false),restoringFullscreen=ref(false),violationReporting=ref(false),violationReportFailed=ref(false),autoSubmitting=ref(false)
let activeViolationEventId:string|undefined,lastViolation:{type:'BLUR'|'HIDDEN'|'EXIT_FULLSCREEN';detail:string}|undefined
const examCanAnswer=computed(()=>dialog.value&&isFullscreen.value&&!violationDialog.value&&!autoSubmitting.value)

async function load(){plans.value=(await api.get<any,Envelope<any[]>>('/exams/plans')).data}
function planPhase(row:any){return planPhaseLabels[row.plan_phase]??{label:row.status,type:'info'}}
function participation(row:any){return participationLabels[row.participation_status]??{label:'--',type:'info'}}
function canStart(row:any){return ['READY','IN_PROGRESS'].includes(row.participation_status)}
async function enterFullscreen(){
  if(document.fullscreenElement){isFullscreen.value=true;return true}
  if(!document.documentElement.requestFullscreen){isFullscreen.value=false;return false}
  try{await document.documentElement.requestFullscreen();isFullscreen.value=Boolean(document.fullscreenElement);return isFullscreen.value}catch{isFullscreen.value=false;return false}
}
async function start(row:any){
  const entered=await enterFullscreen()
  try{
    attempt.value=(await api.post<any,Envelope<any>>(`/exams/plans/${row.id}/attempts`)).data
    attempt.value.questions.forEach((q:any)=>answers[q.id]=q.saved_answer??(q.question_type==='MULTIPLE'?[]:q.question_type==='SHORT'?'':null))
    violationCount.value=Number(attempt.value.violation_count||0);allowedViolations.value=Number(attempt.value.allowed_violations||3)
    isFullscreen.value=Boolean(document.fullscreenElement);activeViolationEventId=undefined;lastViolation=undefined;violationReportFailed.value=false;autoSubmitting.value=false;dialog.value=true
    document.addEventListener('visibilitychange',visibility);document.addEventListener('fullscreenchange',fullscreen);window.addEventListener('blur',blur)
    if(!entered||!isFullscreen.value)violationDialog.value=true
  }catch(e){closeExam(true);throw e}
}
async function save(q:any){if(!examCanAnswer.value)return;await api.put(`/exams/attempts/${attempt.value.id}/answers`,{questionId:q.id,answer:answers[q.id]})}
async function submit(){if(!examCanAnswer.value)return ElMessage.warning('请保持全屏模式后再提交');await api.post(`/exams/attempts/${attempt.value.id}/submit`);closeExam();ElMessage.success('试卷已提交');await load()}
function closeExam(forceExit=false){const wasExamOpen=dialog.value||Boolean(attempt.value);dialog.value=false;violationDialog.value=false;document.removeEventListener('visibilitychange',visibility);document.removeEventListener('fullscreenchange',fullscreen);window.removeEventListener('blur',blur);attempt.value=undefined;activeViolationEventId=undefined;lastViolation=undefined;violationReportFailed.value=false;isFullscreen.value=false;if((wasExamOpen||forceExit)&&document.fullscreenElement)document.exitFullscreen().catch(()=>{})}
async function registerViolation(type:'BLUR'|'HIDDEN'|'EXIT_FULLSCREEN',detail:string){
  if(!dialog.value||!attempt.value||autoSubmitting.value||restoringFullscreen.value)return
  isFullscreen.value=Boolean(document.fullscreenElement);violationDialog.value=true;lastViolation={type,detail};activeViolationEventId??=crypto.randomUUID()
  if(violationReporting.value)return
  violationReporting.value=true;violationReportFailed.value=false
  try{
    const result=(await api.post<any,Envelope<ProctorEventResult>>(`/exams/attempts/${attempt.value.id}/events`,{type,eventId:activeViolationEventId,detail})).data
    violationCount.value=result.violationCount;allowedViolations.value=result.allowedViolations
    if(result.autoSubmitted){autoSubmitting.value=true;closeExam();ElMessage.error(`异常操作已达 ${result.violationCount} 次，系统已自动交卷`);await load()}
  }catch{violationReportFailed.value=true;violationDialog.value=true}finally{violationReporting.value=false}
}
function visibility(){if(document.hidden)void registerViolation('HIDDEN','考试页面进入后台')}
function fullscreen(){isFullscreen.value=Boolean(document.fullscreenElement);if(!isFullscreen.value&&dialog.value)void registerViolation('EXIT_FULLSCREEN','退出全屏模式')}
function blur(){if(dialog.value)void registerViolation('BLUR','考试窗口失去焦点')}
async function restoreFullscreen(){
  if(violationReporting.value)return ElMessage.warning('正在记录异常行为，请稍候')
  if(violationReportFailed.value&&lastViolation){await registerViolation(lastViolation.type,lastViolation.detail);if(violationReportFailed.value)return ElMessage.error('异常记录失败，请检查网络后重试')}
  restoringFullscreen.value=true;const restored=await enterFullscreen()
  if(restored){violationDialog.value=false;activeViolationEventId=undefined;lastViolation=undefined}else ElMessage.error('未能进入全屏模式，请允许浏览器全屏后重试')
  restoringFullscreen.value=false
}
function options(q:any){const value=parseJson(q.options_json);return Array.isArray(value)?value:[]}
function optionLabel(o:any){return o===true?'正确':o===false?'错误':String(o)}
onBeforeUnmount(()=>closeExam());onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><div><h2>我的考试</h2><p class="muted">查看考试安排，并在开放时间内进入或继续考试</p></div></div>
    <el-card>
      <el-table :data="plans" empty-text="暂无考试安排" class="plan-table">
        <el-table-column prop="name" label="考试" min-width="110" show-overflow-tooltip/><el-table-column prop="paper_name" label="试卷" min-width="100" show-overflow-tooltip/>
        <el-table-column label="开始时间" width="112"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.starts_at).date}}</span><span>{{dateTimeParts(s.row.starts_at).time}}</span></span></template></el-table-column>
        <el-table-column label="结束时间" width="112"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.ends_at).date}}</span><span>{{dateTimeParts(s.row.ends_at).time}}</span></span></template></el-table-column>
        <el-table-column prop="duration_minutes" label="时长(分钟)" width="90"/><el-table-column label="计划状态" width="94"><template #default="s"><el-tag :type="planPhase(s.row).type" effect="plain">{{planPhase(s.row).label}}</el-tag></template></el-table-column><el-table-column label="我的状态" width="112"><template #default="s"><el-tag :type="participation(s.row).type" effect="plain">{{participation(s.row).label}}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="94"><template #default="s"><el-button v-if="canStart(s.row)" link type="primary" @click="start(s.row)">{{s.row.participation_status==='IN_PROGRESS'?'继续考试':'进入考试'}}</el-button><span v-else class="muted">--</span></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="attempt?.exam_name" width="760px" :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false">
      <div class="exam-security-bar"><el-tag :type="isFullscreen?'success':'danger'">{{isFullscreen?'全屏答题中':'答题已锁定'}}</el-tag><span>异常操作：<strong>{{violationCount}}</strong> 次（超过 {{allowedViolations}} 次自动交卷）</span></div>
      <div v-for="(q,i) in attempt?.questions" :key="q.id" class="exam-question"><h4>{{i+1}}. {{q.stem}}（{{q.score}}分）</h4><el-radio-group v-if="['SINGLE','TRUE_FALSE'].includes(q.question_type)" v-model="answers[q.id]" :disabled="!examCanAnswer" @change="save(q)"><el-radio v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-radio></el-radio-group><el-checkbox-group v-else-if="q.question_type==='MULTIPLE'" v-model="answers[q.id]" :disabled="!examCanAnswer" @change="save(q)"><el-checkbox v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-checkbox></el-checkbox-group><el-input v-else v-model="answers[q.id]" type="textarea" :rows="5" maxlength="3000" show-word-limit :disabled="!examCanAnswer" placeholder="请输入答案" @blur="save(q)"/></div>
      <template #footer><el-button type="primary" :disabled="!examCanAnswer" @click="submit">提交试卷</el-button></template>
    </el-dialog>
    <el-dialog v-model="violationDialog" class="anti-cheat-dialog" width="520px" append-to-body :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <template #header><div class="anti-cheat-title">考试异常警告</div></template>
      <div class="anti-cheat-content"><div class="anti-cheat-icon">!</div><p>请在全屏模式下作答！</p><p>退出全屏、切换页面或切换其他窗口均视为异常行为。</p><p>超出 {{allowedViolations}} 次将自动交卷，当前 <strong>{{violationCount}}</strong> 次！</p></div>
      <template #footer><el-button type="danger" :loading="restoringFullscreen||violationReporting" @click="restoreFullscreen">返回全屏继续作答</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.plan-table :deep(.el-table__cell){padding:10px 0}.datetime-cell{display:inline-flex;flex-direction:column;line-height:1.35;white-space:nowrap}.datetime-cell span:last-child{color:#606266}.exam-security-bar{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;margin-bottom:12px;background:#f8fafc;border-radius:6px;color:#475569}.exam-security-bar strong{color:#dc2626}.exam-question{padding:8px 0 16px;border-bottom:1px solid #ebeef5}.anti-cheat-title{color:#b91c1c;font-size:20px;font-weight:700}.anti-cheat-content{text-align:center;color:#b91c1c;font-size:17px;font-weight:600;line-height:1.8}.anti-cheat-content p{margin:8px 0}.anti-cheat-content strong{font-size:24px}.anti-cheat-icon{display:flex;align-items:center;justify-content:center;width:56px;height:56px;margin:0 auto 14px;border:3px solid #dc2626;border-radius:50%;font-size:36px;font-weight:800}:global(.anti-cheat-dialog){border:3px solid #dc2626;border-radius:10px}:global(.anti-cheat-dialog .el-dialog__header){border-bottom:1px solid #fecaca}:global(.anti-cheat-dialog .el-dialog__footer){text-align:center;border-top:1px solid #fecaca}
</style>
