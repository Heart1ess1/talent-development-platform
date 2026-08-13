<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api,type Envelope} from '@/api'
import {dateTimeParts,participationLabels,planPhaseLabels,parseJson} from './examUi'
import {createProctorEventId,examDeadlineMillis,formatExamCountdown,postProctorEvent,remainingExamSeconds,serverClockOffsetMillis,type ProctorEvent,type ProctorEventType} from './examProctor'

const plans=ref<any[]>([]),dialog=ref(false),attempt=ref<any>()
const integrityDialog=ref(false),integrityAccepted=ref(false),pendingPlan=ref<any>(),starting=ref(false)
const answers=reactive<Record<number,any>>({})
const violationDialog=ref(false),violationCount=ref(0),allowedViolations=ref(3),isFullscreen=ref(false),restoringFullscreen=ref(false),violationReporting=ref(false),violationReportFailed=ref(false),violationSyncPending=ref(false),autoSubmitting=ref(false)
const remainingSeconds=ref(0),deadlineSubmitting=ref(false),deadlineSubmitFailed=ref(false),statusSyncing=ref(false)
let activeViolation:ProctorEvent|undefined,countdownTimer:ReturnType<typeof setInterval>|undefined,statusTimer:ReturnType<typeof setInterval>|undefined,deadlineMillis:number|undefined,serverOffsetMillis=0,lastDeadlineRetry=0
const examCanAnswer=computed(()=>dialog.value&&isFullscreen.value&&!violationDialog.value&&!autoSubmitting.value&&remainingSeconds.value>0)
const countdownText=computed(()=>formatExamCountdown(remainingSeconds.value))

async function load(){plans.value=(await api.get<any,Envelope<any[]>>('/exams/plans')).data}
function planPhase(row:any){return planPhaseLabels[row.plan_phase]??{label:row.status,type:'info'}}
function participation(row:any){return participationLabels[row.participation_status]??{label:'--',type:'info'}}
function canStart(row:any){return ['READY','IN_PROGRESS'].includes(row.participation_status)}
async function enterFullscreen(){
  if(document.fullscreenElement){isFullscreen.value=true;return true}
  if(!document.documentElement.requestFullscreen){isFullscreen.value=false;return false}
  try{await document.documentElement.requestFullscreen();isFullscreen.value=Boolean(document.fullscreenElement);return isFullscreen.value}catch{isFullscreen.value=false;return false}
}
function requestStart(row:any){pendingPlan.value=row;integrityAccepted.value=false;integrityDialog.value=true}
function cancelStart(){integrityDialog.value=false;integrityAccepted.value=false;pendingPlan.value=undefined}
async function confirmStart(){
  if(!integrityAccepted.value)return ElMessage.warning('请先阅读并确认诚信考试承诺')
  if(!pendingPlan.value||starting.value)return
  starting.value=true
  const entered=await enterFullscreen()
  const row=pendingPlan.value
  integrityDialog.value=false
  try{
    attempt.value=(await api.post<any,Envelope<any>>(`/exams/plans/${row.id}/attempts`)).data
    Object.keys(answers).forEach(key=>delete answers[Number(key)])
    attempt.value.questions.forEach((q:any)=>answers[q.id]=q.saved_answer??(q.question_type==='MULTIPLE'?[]:q.question_type==='SHORT'?'':null))
    violationCount.value=Number(attempt.value.violation_count||0);allowedViolations.value=Number(attempt.value.allowed_violations||3)
    isFullscreen.value=Boolean(document.fullscreenElement);activeViolation=undefined;violationReportFailed.value=false;violationSyncPending.value=false;autoSubmitting.value=false;deadlineSubmitFailed.value=false;dialog.value=true
    document.addEventListener('visibilitychange',visibility);document.addEventListener('fullscreenchange',fullscreen);window.addEventListener('blur',blur);window.addEventListener('pagehide',pagehide)
    startCountdown(attempt.value)
    startStatusPolling()
    if(!entered||!isFullscreen.value)violationDialog.value=true
  }catch(e){closeExam(true);throw e}
  finally{starting.value=false;pendingPlan.value=undefined;integrityAccepted.value=false}
}
async function save(q:any){
  if(!examCanAnswer.value)return
  try{await api.put(`/exams/attempts/${attempt.value.id}/answers`,{questionId:q.id,answer:answers[q.id]},{silentError:true} as any)}
  catch{await syncAttemptStatus()}
}
async function submit(){
  if(remainingSeconds.value<=0)return void submitExpiredAttempt()
  if(!examCanAnswer.value)return ElMessage.warning('请保持全屏模式后再提交')
  try{
    const result=(await api.post<any,Envelope<any>>(`/exams/attempts/${attempt.value.id}/submit`,undefined,{silentError:true} as any)).data
    const timedOut=Boolean(result.autoSubmitted)
    closeExam();ElMessage[timedOut?'warning':'success'](timedOut?'考试时间已结束，系统已自动交卷':'试卷已提交');await load()
  }catch{await syncAttemptStatus();if(dialog.value)ElMessage.error('提交失败，请检查网络后重试')}
}
function stopCountdown(){if(countdownTimer)clearInterval(countdownTimer);countdownTimer=undefined;deadlineMillis=undefined;serverOffsetMillis=0}
function syncServerClock(payload:any){
  const epoch=Number(payload?.deadline_epoch_ms??payload?.deadlineEpochMillis)
  deadlineMillis=Number.isFinite(epoch)?epoch:examDeadlineMillis(payload?.deadline_at)??undefined
  serverOffsetMillis=serverClockOffsetMillis(payload?.server_now_epoch_ms??payload?.serverNowEpochMillis)
}
function startCountdown(payload:any){stopCountdown();syncServerClock(payload);if(deadlineMillis===undefined){remainingSeconds.value=0;return}syncCountdown();countdownTimer=setInterval(syncCountdown,1000)}
function syncCountdown(){if(deadlineMillis===undefined)return;remainingSeconds.value=remainingExamSeconds(deadlineMillis,Date.now(),serverOffsetMillis);if(remainingSeconds.value===0)void submitExpiredAttempt()}
function stopStatusPolling(){if(statusTimer)clearInterval(statusTimer);statusTimer=undefined}
function startStatusPolling(){stopStatusPolling();statusTimer=setInterval(()=>void syncAttemptStatus(),3000)}
async function syncAttemptStatus(){
  const currentId=Number(attempt.value?.id)
  if(!dialog.value||!currentId||statusSyncing.value)return
  statusSyncing.value=true
  try{
    const current=(await api.get<any,Envelope<any>>(`/exams/attempts/${currentId}/status`,{silentError:true} as any)).data
    if(Number(attempt.value?.id)!==currentId)return
    violationCount.value=Number(current.violationCount??violationCount.value);allowedViolations.value=Number(current.allowedViolations??allowedViolations.value)
    syncServerClock(current);syncCountdown()
    if(current.status!=='IN_PROGRESS'){const timedOut=remainingSeconds.value===0;closeExam();ElMessage[timedOut?'warning':'success'](timedOut?'考试时间已结束，系统已自动交卷':'答卷已在其他页面提交');await load()}
  }catch{/* 网络短暂中断时继续本地倒计时，并由超时提交重试兜底 */}
  finally{statusSyncing.value=false}
}
async function submitExpiredAttempt(){
  if(!dialog.value||!attempt.value||deadlineSubmitting.value)return
  const now=Date.now();if(now-lastDeadlineRetry<5000)return;lastDeadlineRetry=now;deadlineSubmitting.value=true
  try{await api.post(`/exams/attempts/${attempt.value.id}/timeout`,undefined,{silentError:true} as any);deadlineSubmitFailed.value=false;closeExam();ElMessage.warning('考试时间已结束，系统已自动交卷');await load()}
  catch{if(!deadlineSubmitFailed.value)ElMessage.error('考试时间已结束，正在重试自动交卷');deadlineSubmitFailed.value=true}
  finally{deadlineSubmitting.value=false}
}
function closeExam(forceExit=false){const wasExamOpen=dialog.value||Boolean(attempt.value);dialog.value=false;violationDialog.value=false;document.removeEventListener('visibilitychange',visibility);document.removeEventListener('fullscreenchange',fullscreen);window.removeEventListener('blur',blur);window.removeEventListener('pagehide',pagehide);stopCountdown();stopStatusPolling();attempt.value=undefined;activeViolation=undefined;violationReportFailed.value=false;violationSyncPending.value=false;isFullscreen.value=false;remainingSeconds.value=0;if((wasExamOpen||forceExit)&&document.fullscreenElement)document.exitFullscreen().catch(()=>{})}
async function registerViolation(type:ProctorEventType,detail:string){
  if(!dialog.value||!attempt.value||autoSubmitting.value||restoringFullscreen.value)return
  isFullscreen.value=Boolean(document.fullscreenElement);violationDialog.value=true
  if(!activeViolation){activeViolation={type,detail,eventId:createProctorEventId()};violationCount.value+=1}
  if(violationReporting.value)return
  violationReporting.value=true;violationReportFailed.value=false;violationSyncPending.value=true
  try{
    const result=await postProctorEvent(Number(attempt.value.id),activeViolation)
    violationCount.value=result.violationCount;allowedViolations.value=result.allowedViolations
    if(result.autoSubmitted){const violationTriggered=result.violationCount>result.allowedViolations;autoSubmitting.value=true;closeExam();ElMessage[violationTriggered?'error':'warning'](violationTriggered?`异常操作已达 ${result.violationCount} 次，系统已自动交卷`:'考试时间已结束或答卷已在其他页面提交');await load()}
  }catch{violationReportFailed.value=true;violationDialog.value=true}finally{violationReporting.value=false;violationSyncPending.value=false}
}
function visibility(){if(document.hidden)void registerViolation('HIDDEN','考试页面进入后台')}
function fullscreen(){isFullscreen.value=Boolean(document.fullscreenElement);if(!isFullscreen.value&&dialog.value)void registerViolation('EXIT_FULLSCREEN','退出全屏模式')}
function blur(){if(dialog.value)void registerViolation('BLUR','考试窗口失去焦点')}
function pagehide(){if(dialog.value)void registerViolation('HIDDEN','离开或关闭考试页面')}
async function restoreFullscreen(){
  if(violationReporting.value)return ElMessage.warning('正在记录异常行为，请稍候')
  if(violationReportFailed.value&&activeViolation){await registerViolation(activeViolation.type,activeViolation.detail);if(violationReportFailed.value)return ElMessage.error('异常记录失败，请检查网络后重试')}
  restoringFullscreen.value=true;const restored=await enterFullscreen()
  if(restored){violationDialog.value=false;activeViolation=undefined}else ElMessage.error('未能进入全屏模式，请允许浏览器全屏后重试')
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
        <el-table-column label="操作" width="94"><template #default="s"><el-button v-if="canStart(s.row)" link type="primary" @click="requestStart(s.row)">{{s.row.participation_status==='IN_PROGRESS'?'继续考试':'进入考试'}}</el-button><span v-else class="muted">--</span></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="integrityDialog" title="诚信考试确认" width="600px" :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false">
      <div class="integrity-summary">
        <h3>{{pendingPlan?.name}}</h3>
        <div class="integrity-meta"><span>考试时长：{{pendingPlan?.duration_minutes}} 分钟</span><span>结束时间：{{dateTimeParts(pendingPlan?.ends_at).date}} {{dateTimeParts(pendingPlan?.ends_at).time}}</span></div>
      </div>
      <el-alert title="请在安静、网络稳定的环境中独立完成考试" type="warning" :closable="false" show-icon/>
      <ol class="integrity-rules">
        <li>点击确认后系统将创建或继续答卷，并尝试进入全屏；未能全屏时答题会锁定，需按提示恢复全屏。</li>
        <li>切换标签页、切换窗口、退出全屏或离开考试页面会被记录为异常行为。</li>
        <li>异常行为超过 {{allowedViolations}} 次，系统将自动交卷；同一次切屏触发的关联事件只记一次。</li>
        <li>顶部倒计时以服务器时间为准；时间归零后系统自动交卷，无需再次点击提交。</li>
        <li>答案会在选择或离开输入框时保存。请勿刷新、关闭页面或断开网络。</li>
      </ol>
      <el-checkbox v-model="integrityAccepted" class="integrity-check">我已阅读以上规则，并承诺独立、诚信完成考试</el-checkbox>
      <template #footer><el-button :disabled="starting" @click="cancelStart">取消</el-button><el-button type="primary" :disabled="!integrityAccepted" :loading="starting" @click="confirmStart">确认并进入全屏考试</el-button></template>
    </el-dialog>

    <el-dialog v-model="dialog" :title="attempt?.exam_name" width="760px" :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false">
      <div class="exam-security-bar"><el-tag :type="isFullscreen?'success':'danger'">{{isFullscreen?'全屏答题中':'答题已锁定'}}</el-tag><span class="exam-countdown" :class="{danger:remainingSeconds<=300}">剩余时间：<strong>{{countdownText}}</strong><small> · 服务器校时</small></span><span>异常操作：<strong>{{violationCount}}</strong> 次（超过 {{allowedViolations}} 次自动交卷）<small v-if="violationSyncPending"> · 同步中</small></span></div>
      <el-alert v-if="deadlineSubmitFailed" title="考试时间已结束，系统正在重试自动交卷，请勿关闭页面" type="error" :closable="false" show-icon class="deadline-alert"/>
      <div v-for="(q,i) in attempt?.questions" :key="q.id" class="exam-question"><h4>{{i+1}}. {{q.stem}}（{{q.score}}分）</h4><el-radio-group v-if="['SINGLE','TRUE_FALSE'].includes(q.question_type)" v-model="answers[q.id]" :disabled="!examCanAnswer" @change="save(q)"><el-radio v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-radio></el-radio-group><el-checkbox-group v-else-if="q.question_type==='MULTIPLE'" v-model="answers[q.id]" :disabled="!examCanAnswer" @change="save(q)"><el-checkbox v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-checkbox></el-checkbox-group><el-input v-else v-model="answers[q.id]" type="textarea" :rows="5" maxlength="3000" show-word-limit :disabled="!examCanAnswer" placeholder="请输入答案" @blur="save(q)"/></div>
      <template #footer><span class="submit-hint">时间归零将自动交卷</span><el-button type="primary" :disabled="!examCanAnswer" @click="submit">提交试卷</el-button></template>
    </el-dialog>
    <el-dialog v-model="violationDialog" class="anti-cheat-dialog" width="520px" append-to-body :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <template #header><div class="anti-cheat-title">考试异常警告</div></template>
      <div class="anti-cheat-content"><div class="anti-cheat-icon">!</div><p>请在全屏模式下作答！</p><p>退出全屏、切换页面或切换其他窗口均视为异常行为。</p><p>超出 {{allowedViolations}} 次将自动交卷，当前 <strong>{{violationCount}}</strong> 次！</p></div>
      <template #footer><el-button type="danger" :loading="restoringFullscreen||violationReporting" @click="restoreFullscreen">返回全屏继续作答</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.plan-table :deep(.el-table__cell){padding:10px 0}.datetime-cell{display:inline-flex;flex-direction:column;line-height:1.35;white-space:nowrap}.datetime-cell span:last-child{color:#606266}.integrity-summary h3{margin:0 0 8px;color:#172554}.integrity-meta{display:flex;flex-wrap:wrap;gap:8px 24px;margin-bottom:16px;color:#64748b}.integrity-rules{margin:16px 0;padding-left:22px;color:#334155;line-height:1.8}.integrity-check{height:auto;font-weight:600}.exam-security-bar{position:sticky;top:-16px;z-index:5;display:flex;align-items:center;justify-content:space-between;gap:14px;padding:12px;margin-bottom:12px;background:#f8fafc;border:1px solid #dbeafe;border-radius:6px;box-shadow:0 3px 10px rgb(15 23 42 / 8%);color:#475569}.exam-security-bar strong{color:#dc2626}.exam-security-bar small{color:#d97706}.exam-countdown{margin-left:auto;font-variant-numeric:tabular-nums;white-space:nowrap}.exam-countdown strong{color:#176b58;font-size:20px}.exam-countdown.danger strong{color:#dc2626}.deadline-alert{margin-bottom:12px}.exam-question{padding:8px 0 16px;border-bottom:1px solid #ebeef5}.submit-hint{margin-right:12px;color:#64748b}.anti-cheat-title{color:#b91c1c;font-size:20px;font-weight:700}.anti-cheat-content{text-align:center;color:#b91c1c;font-size:17px;font-weight:600;line-height:1.8}.anti-cheat-content p{margin:8px 0}.anti-cheat-content strong{font-size:24px}.anti-cheat-icon{display:flex;align-items:center;justify-content:center;width:56px;height:56px;margin:0 auto 14px;border:3px solid #dc2626;border-radius:50%;font-size:36px;font-weight:800}:global(.anti-cheat-dialog){border:3px solid #dc2626;border-radius:10px}:global(.anti-cheat-dialog .el-dialog__header){border-bottom:1px solid #fecaca}:global(.anti-cheat-dialog .el-dialog__footer){text-align:center;border-top:1px solid #fecaca}@media(max-width:700px){.integrity-meta,.exam-security-bar{align-items:flex-start;flex-direction:column}.exam-countdown{margin-left:0}}
</style>
