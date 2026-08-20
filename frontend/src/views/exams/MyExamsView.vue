<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {api,type Envelope} from '@/api'
import {dateTimeParts,participationLabels,planPhaseLabels,parseJson,resultStatusLabels,scoreMonth} from './examUi'
import {createProctorEventId,examDeadlineMillis,formatExamCountdown,postProctorEvent,recoverProctorViolation,remainingExamSeconds,remainingViolationSeconds,serverClockOffsetMillis,type ProctorEvent,type ProctorEventType} from './examProctor'
import {detectExamClientProfile,examFullscreenElement,exitExamFullscreen,proctorModeForFullscreenResult,requestExamFullscreen,type ExamProctorMode} from './examDisplayMode'

const route=useRoute()
const plans=ref<any[]>([]),results=ref<any[]>([]),activeSection=ref(route.query.section==='results'?'results':'plans'),dialog=ref(false),attempt=ref<any>()
const integrityDialog=ref(false),integrityAccepted=ref(false),pendingPlan=ref<any>(),starting=ref(false)
const answers=reactive<Record<number,any>>({})
const violationDialog=ref(false),violationCount=ref(0),violationLimit=ref(4),violationGraceSeconds=ref(15),violationRemainingSeconds=ref(0),isFullscreen=ref(false),restoringFullscreen=ref(false),violationReporting=ref(false),violationReportFailed=ref(false),violationSyncPending=ref(false),autoSubmitting=ref(false)
const remainingSeconds=ref(0),deadlineSubmitting=ref(false),deadlineSubmitFailed=ref(false),statusSyncing=ref(false)
const clientProfile=detectExamClientProfile(),proctorMode=ref<ExamProctorMode>(clientProfile.preferredMode)
let activeViolation:ProctorEvent|undefined,countdownTimer:ReturnType<typeof setInterval>|undefined,statusTimer:ReturnType<typeof setInterval>|undefined,deadlineMillis:number|undefined,violationDeadlineMillis:number|undefined,serverOffsetMillis=0,lastDeadlineRetry=0
const compatibleMode=computed(()=>proctorMode.value==='MOBILE_COMPATIBLE')
const examCanAnswer=computed(()=>dialog.value&&(compatibleMode.value||isFullscreen.value)&&!violationDialog.value&&!autoSubmitting.value&&remainingSeconds.value>0)
const countdownText=computed(()=>formatExamCountdown(remainingSeconds.value))
const violationCountdownText=computed(()=>`${Math.max(0,violationRemainingSeconds.value)} 秒`)
const proctorStatus=computed(()=>compatibleMode.value
  ?{label:'移动端兼容答题',type:'warning' as const}
  :isFullscreen.value?{label:'全屏答题中',type:'success' as const}:{label:'答题已锁定',type:'danger' as const})

async function load(){
  const [planResponse,resultResponse]=await Promise.all([
    api.get<any,Envelope<any[]>>('/exams/plans'),
    api.get<any,Envelope<any[]>>('/exams/results')
  ])
  plans.value=planResponse.data;results.value=resultResponse.data
}
function planPhase(row:any){return planPhaseLabels[row.plan_phase]??{label:row.status,type:'info'}}
function participation(row:any){return participationLabels[row.participation_status]??{label:'--',type:'info'}}
function resultStatus(row:any){return resultStatusLabels[row.result_status]??{label:'--',type:'success'}}
function canStart(row:any){return ['READY','IN_PROGRESS'].includes(row.participation_status)}
async function enterFullscreen(){
  if(examFullscreenElement(document)){isFullscreen.value=true;return true}
  const entered=await requestExamFullscreen(document)
  isFullscreen.value=Boolean(examFullscreenElement(document))
  return entered&&isFullscreen.value
}
function requestStart(row:any){pendingPlan.value=row;syncViolationRule(row);integrityAccepted.value=false;integrityDialog.value=true}
function cancelStart(){integrityDialog.value=false;integrityAccepted.value=false;pendingPlan.value=undefined}
async function confirmStart(){
  if(!integrityAccepted.value)return ElMessage.warning('请先阅读并确认诚信考试承诺')
  if(!pendingPlan.value||starting.value)return
  starting.value=true
  const entered=clientProfile.fullscreenCapable?await enterFullscreen():false
  const requestedMode=proctorModeForFullscreenResult(entered)
  const row=pendingPlan.value
  integrityDialog.value=false
  try{
    attempt.value=(await api.post<any,Envelope<any>>(`/exams/plans/${row.id}/attempts`,{
      proctorMode:requestedMode,
      fullscreenCapable:clientProfile.fullscreenCapable,
      clientContext:clientProfile.clientContext
    })).data
    Object.keys(answers).forEach(key=>delete answers[Number(key)])
    attempt.value.questions.forEach((q:any)=>answers[q.id]=q.saved_answer??(q.question_type==='MULTIPLE'?[]:q.question_type==='SHORT'?'':null))
    violationCount.value=Number(attempt.value.violation_count||0);syncViolationRule(attempt.value)
    proctorMode.value=attempt.value.proctor_mode==='MOBILE_COMPATIBLE'?'MOBILE_COMPATIBLE':'FULLSCREEN_STRICT'
    isFullscreen.value=Boolean(examFullscreenElement(document));activeViolation=attempt.value.active_violation_key?{type:'HIDDEN',detail:'待确认的异常离场',eventId:String(attempt.value.active_violation_key)}:undefined;violationReportFailed.value=false;violationSyncPending.value=false;autoSubmitting.value=false;deadlineSubmitFailed.value=false;dialog.value=true
    document.addEventListener('visibilitychange',visibility);window.addEventListener('pagehide',pagehide)
    if(!compatibleMode.value){document.addEventListener('fullscreenchange',fullscreen);document.addEventListener('webkitfullscreenchange',fullscreen as EventListener);window.addEventListener('blur',blur)}
    startCountdown(attempt.value)
    startStatusPolling()
    if(activeViolation)violationDialog.value=true
    else if(!compatibleMode.value&&(!entered||!isFullscreen.value))violationDialog.value=true
    if(compatibleMode.value&&clientProfile.fullscreenCapable&&!entered)ElMessage.warning('浏览器未能进入全屏，已切换为移动端兼容答题模式')
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
function stopCountdown(){if(countdownTimer)clearInterval(countdownTimer);countdownTimer=undefined;deadlineMillis=undefined;violationDeadlineMillis=undefined;violationRemainingSeconds.value=0;serverOffsetMillis=0}
function syncViolationRule(payload:any){
  violationLimit.value=Number(payload?.violation_limit??payload?.violationLimit??violationLimit.value)
  violationGraceSeconds.value=Number(payload?.violation_grace_seconds??payload?.violationGraceSeconds??violationGraceSeconds.value)
  const raw=payload?.violation_deadline_epoch_ms??payload?.violationDeadlineEpochMillis
  const parsed=Number(raw);violationDeadlineMillis=raw!==null&&raw!==undefined&&Number.isFinite(parsed)?parsed:undefined
}
function syncServerClock(payload:any){
  const epoch=Number(payload?.deadline_epoch_ms??payload?.deadlineEpochMillis)
  deadlineMillis=Number.isFinite(epoch)?epoch:examDeadlineMillis(payload?.deadline_at)??undefined
  serverOffsetMillis=serverClockOffsetMillis(payload?.server_now_epoch_ms??payload?.serverNowEpochMillis)
  syncViolationRule(payload)
}
function startCountdown(payload:any){stopCountdown();syncServerClock(payload);if(deadlineMillis===undefined){remainingSeconds.value=0;return}syncCountdown();countdownTimer=setInterval(syncCountdown,1000)}
function syncCountdown(){
  if(deadlineMillis!==undefined){remainingSeconds.value=remainingExamSeconds(deadlineMillis,Date.now(),serverOffsetMillis);if(remainingSeconds.value===0)void submitExpiredAttempt()}
  violationRemainingSeconds.value=remainingViolationSeconds(violationDeadlineMillis,Date.now(),serverOffsetMillis)
  if(violationDeadlineMillis!==undefined&&violationRemainingSeconds.value===0)void syncAttemptStatus()
}
function stopStatusPolling(){if(statusTimer)clearInterval(statusTimer);statusTimer=undefined}
function startStatusPolling(){stopStatusPolling();statusTimer=setInterval(()=>void syncAttemptStatus(),3000)}
async function syncAttemptStatus(){
  const currentId=Number(attempt.value?.id)
  if(!dialog.value||!currentId||statusSyncing.value)return
  statusSyncing.value=true
  try{
    const current=(await api.get<any,Envelope<any>>(`/exams/attempts/${currentId}/status`,{silentError:true} as any)).data
    if(Number(attempt.value?.id)!==currentId)return
    const violationWasCountingDown=violationDeadlineMillis!==undefined
    violationCount.value=Number(current.violationCount??violationCount.value)
    syncServerClock(current);syncCountdown()
    if(current.status!=='IN_PROGRESS'){const examTimedOut=remainingSeconds.value===0,violationTimedOut=violationWasCountingDown&&!examTimedOut;closeExam();ElMessage[examTimedOut?'warning':violationTimedOut?'error':'success'](examTimedOut?'考试时间已结束，系统已自动交卷':violationTimedOut?'异常离场倒计时结束，系统已自动交卷':'答卷已在其他页面提交');await load()}
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
function closeExam(forceExit=false){const wasExamOpen=dialog.value||Boolean(attempt.value);dialog.value=false;violationDialog.value=false;document.removeEventListener('visibilitychange',visibility);document.removeEventListener('fullscreenchange',fullscreen);document.removeEventListener('webkitfullscreenchange',fullscreen as EventListener);window.removeEventListener('blur',blur);window.removeEventListener('pagehide',pagehide);stopCountdown();stopStatusPolling();attempt.value=undefined;activeViolation=undefined;violationReportFailed.value=false;violationSyncPending.value=false;isFullscreen.value=false;remainingSeconds.value=0;if((wasExamOpen||forceExit)&&examFullscreenElement(document))void exitExamFullscreen(document)}
async function registerViolation(type:ProctorEventType,detail:string){
  if(!dialog.value||!attempt.value||autoSubmitting.value||restoringFullscreen.value)return
  isFullscreen.value=Boolean(examFullscreenElement(document));violationDialog.value=true
  if(!activeViolation){activeViolation={type,detail,eventId:createProctorEventId()};violationCount.value+=1;violationDeadlineMillis=Date.now()+serverOffsetMillis+violationGraceSeconds.value*1000;violationRemainingSeconds.value=violationGraceSeconds.value}
  if(violationReporting.value)return
  violationReporting.value=true;violationReportFailed.value=false;violationSyncPending.value=true
  try{
    const result=await postProctorEvent(Number(attempt.value.id),activeViolation)
    violationCount.value=result.violationCount;syncViolationRule(result);syncCountdown()
    if(result.autoSubmitted){const violationTriggered=result.violationCount>=result.violationLimit;autoSubmitting.value=true;closeExam();ElMessage[violationTriggered?'error':'warning'](violationTriggered?`异常行为达到 ${result.violationLimit} 次，系统已自动交卷`:'考试时间已结束或异常离场超时，系统已自动交卷');await load()}
  }catch{violationReportFailed.value=true;violationDialog.value=true}finally{violationReporting.value=false;violationSyncPending.value=false}
}
function visibility(){if(document.hidden)void registerViolation('HIDDEN','考试页面进入后台');else{syncCountdown();void syncAttemptStatus()}}
function fullscreen(){if(compatibleMode.value)return;isFullscreen.value=Boolean(examFullscreenElement(document));if(!isFullscreen.value&&dialog.value)void registerViolation('EXIT_FULLSCREEN','退出全屏模式')}
function blur(){if(dialog.value&&!compatibleMode.value)void registerViolation('BLUR','考试窗口失去焦点')}
function pagehide(){if(dialog.value)void registerViolation('HIDDEN','离开或关闭考试页面')}
async function restoreFullscreen(){
  if(violationReporting.value)return ElMessage.warning('正在记录异常行为，请稍候')
  if(violationReportFailed.value&&activeViolation){await registerViolation(activeViolation.type,activeViolation.detail);if(violationReportFailed.value)return ElMessage.error('异常记录失败，请检查网络后重试')}
  restoringFullscreen.value=true;const restored=await enterFullscreen()
  if(restored)await confirmViolationRecovery();else ElMessage.error('未能进入全屏模式，请允许浏览器全屏后重试')
  restoringFullscreen.value=false
}
async function continueAfterViolation(){
  if(!compatibleMode.value)return restoreFullscreen()
  if(violationReporting.value)return ElMessage.warning('正在记录异常行为，请稍候')
  if(violationReportFailed.value&&activeViolation){await registerViolation(activeViolation.type,activeViolation.detail);if(violationReportFailed.value)return ElMessage.error('异常记录失败，请检查网络后重试')}
  await confirmViolationRecovery()
}
async function confirmViolationRecovery(){
  if(!dialog.value)return
  if(!activeViolation){violationDialog.value=false;violationDeadlineMillis=undefined;violationRemainingSeconds.value=0;return}
  try{
    const current=await recoverProctorViolation(Number(attempt.value.id),activeViolation.eventId)
    violationCount.value=Number(current.violationCount??violationCount.value);syncViolationRule(current);syncCountdown()
    if(current.status!=='IN_PROGRESS'){autoSubmitting.value=true;closeExam();ElMessage.error('异常离场倒计时结束，系统已自动交卷');await load();return}
    violationDialog.value=false;activeViolation=undefined;violationDeadlineMillis=undefined;violationRemainingSeconds.value=0
  }catch{ElMessage.error('恢复确认失败，请检查网络后重试')}
}
function options(q:any){const value=parseJson(q.options_json);return Array.isArray(value)?value:[]}
function optionLabel(o:any){return o===true?'正确':o===false?'错误':String(o)}
onBeforeUnmount(()=>closeExam());onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><div><h2>我的考试</h2><p class="muted">统一查看考试安排、参加考试和查询已发布成绩</p></div></div>
    <el-card class="exam-overview-card">
      <el-tabs v-model="activeSection" class="exam-tabs">
        <el-tab-pane name="plans">
          <template #label><span>考试安排 <el-tag size="small" effect="plain">{{plans.length}}</el-tag></span></template>
          <el-table :data="plans" empty-text="暂无考试安排" class="plan-table">
            <el-table-column prop="name" label="考试" min-width="110" show-overflow-tooltip/><el-table-column prop="paper_name" label="试卷" min-width="100" show-overflow-tooltip/>
            <el-table-column label="开始时间" width="112"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.starts_at).date}}</span><span>{{dateTimeParts(s.row.starts_at).time}}</span></span></template></el-table-column>
            <el-table-column label="结束时间" width="112"><template #default="s"><span class="datetime-cell"><span>{{dateTimeParts(s.row.ends_at).date}}</span><span>{{dateTimeParts(s.row.ends_at).time}}</span></span></template></el-table-column>
            <el-table-column prop="duration_minutes" label="时长(分钟)" width="90"/><el-table-column label="计划状态" width="94"><template #default="s"><el-tag :type="planPhase(s.row).type" effect="plain">{{planPhase(s.row).label}}</el-tag></template></el-table-column><el-table-column label="我的状态" width="112"><template #default="s"><el-tag :type="participation(s.row).type" effect="plain">{{participation(s.row).label}}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="94"><template #default="s"><el-button v-if="canStart(s.row)" link type="primary" @click="requestStart(s.row)">{{s.row.participation_status==='IN_PROGRESS'?'继续考试':'进入考试'}}</el-button><span v-else class="muted">--</span></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane name="results">
          <template #label><span>考试成绩 <el-tag size="small" type="success" effect="plain">{{results.length}}</el-tag></span></template>
          <el-alert title="成绩在整场考试结束后自动发布；考试进行期间不会提前显示分数。" type="info" :closable="false" show-icon class="result-notice"/>
          <el-table :data="results" empty-text="暂无已发布成绩" class="result-table">
            <el-table-column prop="exam_name" label="考试" min-width="160"/>
            <el-table-column prop="total_score" label="成绩" width="100"><template #default="s"><strong class="result-score">{{s.row.total_score}}</strong></template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="s"><el-tag :type="resultStatus(s.row).type" effect="plain">{{resultStatus(s.row).label}}</el-tag></template></el-table-column>
            <el-table-column label="计分月份" width="130"><template #default="s">{{scoreMonth(s.row.score_month)}}</template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="integrityDialog" title="诚信考试确认" width="600px" :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false">
      <div class="integrity-summary">
        <h3>{{pendingPlan?.name}}</h3>
        <div class="integrity-meta"><span>考试时长：{{pendingPlan?.duration_minutes}} 分钟</span><span>结束时间：{{dateTimeParts(pendingPlan?.ends_at).date}} {{dateTimeParts(pendingPlan?.ends_at).time}}</span></div>
      </div>
      <el-alert title="请在安静、网络稳定的环境中独立完成考试" type="warning" :closable="false" show-icon/>
      <el-alert v-if="clientProfile.preferredMode==='MOBILE_COMPATIBLE'" :title="clientProfile.wechat?'当前为 iPhone 微信环境，将使用移动端兼容答题模式':'当前浏览器不支持网页全屏，将使用移动端兼容答题模式'" :description="clientProfile.wechat?'可直接答题；如遇页面显示异常，建议从右上角菜单使用 Safari 打开。':'兼容模式不会因无法全屏而锁定答题，但进入后台或离开页面仍会记录异常。'" type="info" :closable="false" show-icon class="compatibility-notice"/>
      <ol class="integrity-rules">
        <li v-if="clientProfile.preferredMode==='FULLSCREEN_STRICT'">点击确认后系统将创建或继续答卷并尝试进入全屏；若浏览器拒绝全屏，将自动切换为移动端兼容答题。</li>
        <li v-else>点击确认后系统将创建或继续答卷，并进入覆盖整个页面的移动端兼容答题界面。</li>
        <li>{{clientProfile.preferredMode==='FULLSCREEN_STRICT'?'切换标签页、切换窗口、退出全屏或离开考试页面会被记录为异常行为。':'切换应用、锁屏、进入后台或离开考试页面会被记录为异常行为。'}}</li>
        <li>异常行为累计达到 {{violationLimit}} 次时系统立即自动交卷；同一次切屏触发的关联事件只记一次。</li>
        <li>每次异常离场后须在 {{violationGraceSeconds}} 秒内返回考试并确认，倒计时结束仍未恢复将自动交卷。</li>
        <li>顶部倒计时以服务器时间为准；时间归零后系统自动交卷，无需再次点击提交。</li>
        <li>答案会在选择或离开输入框时保存。请勿刷新、关闭页面或断开网络。</li>
      </ol>
      <el-checkbox v-model="integrityAccepted" class="integrity-check">我已阅读以上规则，并承诺独立、诚信完成考试</el-checkbox>
      <template #footer><el-button :disabled="starting" @click="cancelStart">取消</el-button><el-button type="primary" :disabled="!integrityAccepted" :loading="starting" @click="confirmStart">{{clientProfile.preferredMode==='MOBILE_COMPATIBLE'?'确认并开始兼容答题':'确认并进入全屏考试'}}</el-button></template>
    </el-dialog>

    <el-dialog v-model="dialog" :title="attempt?.exam_name" width="760px" :fullscreen="compatibleMode" :class="{'mobile-compatible-exam':compatibleMode}" append-to-body :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false">
      <div class="exam-security-bar"><el-tag :type="proctorStatus.type">{{proctorStatus.label}}</el-tag><span class="exam-countdown" :class="{danger:remainingSeconds<=300}">剩余时间：<strong>{{countdownText}}</strong><small> · 服务器校时</small></span><span>异常操作：<strong>{{violationCount}}</strong> / {{violationLimit}} 次<small v-if="violationSyncPending"> · 同步中</small></span></div>
      <el-alert v-if="compatibleMode" title="当前设备使用移动端兼容答题模式" description="无法使用浏览器全屏不会影响作答；切换应用、锁屏或离开页面仍会记录异常。" type="warning" :closable="false" show-icon class="compatibility-mode-bar"/>
      <el-alert v-if="deadlineSubmitFailed" title="考试时间已结束，系统正在重试自动交卷，请勿关闭页面" type="error" :closable="false" show-icon class="deadline-alert"/>
      <div v-for="(q,i) in attempt?.questions" :key="q.id" class="exam-question"><h4>{{i+1}}. {{q.stem}}（{{q.score}}分）</h4><el-radio-group v-if="['SINGLE','TRUE_FALSE'].includes(q.question_type)" v-model="answers[q.id]" :disabled="!examCanAnswer" @change="save(q)"><el-radio v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-radio></el-radio-group><el-checkbox-group v-else-if="q.question_type==='MULTIPLE'" v-model="answers[q.id]" :disabled="!examCanAnswer" @change="save(q)"><el-checkbox v-for="o in options(q)" :key="String(o)" :value="o">{{optionLabel(o)}}</el-checkbox></el-checkbox-group><el-input v-else v-model="answers[q.id]" type="textarea" :rows="5" maxlength="3000" show-word-limit :disabled="!examCanAnswer" placeholder="请输入答案" @blur="save(q)"/></div>
      <template #footer><span class="submit-hint">时间归零将自动交卷</span><el-button type="primary" :disabled="!examCanAnswer" @click="submit">提交试卷</el-button></template>
    </el-dialog>
    <el-dialog v-model="violationDialog" class="anti-cheat-dialog" width="520px" append-to-body :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <template #header><div class="anti-cheat-title">考试异常警告</div></template>
      <div class="anti-cheat-content"><div class="anti-cheat-icon">!</div><p>{{compatibleMode?'检测到考试页面进入后台或被离开！':'请在全屏模式下作答！'}}</p><div class="violation-countdown"><span>恢复作答剩余时间</span><strong>{{violationCountdownText}}</strong><small>倒计时结束将自动交卷</small></div><p>{{compatibleMode?'请立即返回考试并确认继续作答。':'请立即恢复全屏；切换页面或窗口均视为异常行为。'}}</p><p>异常行为达到 {{violationLimit}} 次自动交卷，当前 <strong>{{violationCount}}</strong> 次。</p></div>
      <template #footer><el-button type="danger" :loading="restoringFullscreen||violationReporting" @click="continueAfterViolation">{{compatibleMode?'确认并继续答题':'返回全屏继续作答'}}</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head p{margin:6px 0 0}.exam-overview-card :deep(.el-card__body){padding-top:8px}.exam-tabs :deep(.el-tabs__header){margin-bottom:18px}.exam-tabs :deep(.el-tabs__item>span){display:flex;align-items:center;gap:8px}.plan-table :deep(.el-table__cell),.result-table :deep(.el-table__cell){padding:10px 0}.result-notice{margin-bottom:14px}.result-score{color:#1769aa;font-size:18px}.datetime-cell{display:inline-flex;flex-direction:column;line-height:1.35;white-space:nowrap}.datetime-cell span:last-child{color:#606266}.integrity-summary h3{margin:0 0 8px;color:#172554}.integrity-meta{display:flex;flex-wrap:wrap;gap:8px 24px;margin-bottom:16px;color:#64748b}.compatibility-notice{margin-top:12px}.integrity-rules{margin:16px 0;padding-left:22px;color:#334155;line-height:1.8}.integrity-check{height:auto;font-weight:600}.exam-security-bar{position:sticky;top:-16px;z-index:5;display:flex;align-items:center;justify-content:space-between;gap:14px;padding:12px;margin-bottom:12px;background:#f8fafc;border:1px solid #dbeafe;border-radius:6px;box-shadow:0 3px 10px rgb(15 23 42 / 8%);color:#475569}.exam-security-bar strong{color:#dc2626}.exam-security-bar small{color:#d97706}.exam-countdown{margin-left:auto;font-variant-numeric:tabular-nums;white-space:nowrap}.exam-countdown strong{color:#176b58;font-size:20px}.exam-countdown.danger strong{color:#dc2626}.compatibility-mode-bar,.deadline-alert{margin-bottom:12px}.exam-question{padding:8px 0 16px;border-bottom:1px solid #ebeef5}.submit-hint{margin-right:12px;color:#64748b}.anti-cheat-title{color:#b91c1c;font-size:20px;font-weight:700}.anti-cheat-content{text-align:center;color:#b91c1c;font-size:17px;font-weight:600;line-height:1.8}.anti-cheat-content p{margin:8px 0}.anti-cheat-content strong{font-size:24px}.anti-cheat-icon{display:flex;align-items:center;justify-content:center;width:56px;height:56px;margin:0 auto 14px;border:3px solid #dc2626;border-radius:50%;font-size:36px;font-weight:800}.violation-countdown{display:flex;margin:18px auto;padding:15px 18px;flex-direction:column;align-items:center;border:2px solid #ef4444;border-radius:10px;background:#fff1f2;box-shadow:0 0 0 5px rgb(239 68 68 / 10%);line-height:1.25}.violation-countdown span{font-size:14px}.violation-countdown strong{margin:5px 0;color:#dc2626;font-size:42px;font-variant-numeric:tabular-nums}.violation-countdown small{color:#991b1b;font-size:13px}:global(.anti-cheat-dialog){border:3px solid #dc2626;border-radius:10px}:global(.anti-cheat-dialog .el-dialog__header){border-bottom:1px solid #fecaca}:global(.anti-cheat-dialog .el-dialog__footer){text-align:center;border-top:1px solid #fecaca}:global(.mobile-compatible-exam.el-dialog){display:flex;width:100%;height:100dvh;max-height:none;margin:0;flex-direction:column;border-radius:0;padding-top:env(safe-area-inset-top);padding-right:env(safe-area-inset-right);padding-bottom:env(safe-area-inset-bottom);padding-left:env(safe-area-inset-left)}:global(.mobile-compatible-exam .el-dialog__header){flex:none;padding:12px 16px}:global(.mobile-compatible-exam .el-dialog__body){min-height:0;flex:1;overflow-y:auto;padding:12px 16px}:global(.mobile-compatible-exam .el-dialog__footer){flex:none;padding:10px 16px calc(10px + env(safe-area-inset-bottom))}@media(max-width:700px){.integrity-meta,.exam-security-bar{align-items:flex-start;flex-direction:column}.exam-countdown{margin-left:0}:global(.mobile-compatible-exam .exam-security-bar){top:-12px}}
</style>
