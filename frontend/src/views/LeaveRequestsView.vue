<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Plus,Refresh,Search} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {useRoute} from 'vue-router'

type Row=Record<string,any>
type Preview={halfDays:number;days:number;level:string;periods:string[];approvers:{id:number;display_name:string;role:string}[]}
const auth=useAuthStore()
const route=useRoute()
const employee=computed(()=>auth.user?.role==='EMPLOYEE')
const administrator=computed(()=>['ADMIN','SUPER_ADMIN'].includes(auth.user?.role||''))
const tabs=employee.value?['全部','待审批','待销假','已销假']:['待我审批','我已审批','逾期未销假',...(auth.can('leave:view')?['管理范围记录']:[])]
const tab=ref(tabs[0])
const rows=ref<Row[]>([]),total=ref(0),page=ref(1),loading=ref(false)
const keyword=ref(''),status=ref('')
const filters=reactive({stationId:null as number|null,scenario:'',approvalStatus:'',returnStatus:'',dates:[] as string[]})
const context=ref<{employee:Row;stations:{id:number;name:string}[]}>({employee:{},stations:[]})
const draftAvailable=ref(false)
const transferOpen=ref(false),transferRow=ref<Row|null>(null),transferPeople=ref<Row[]>([])
const transferForm=reactive({approverUserId:null as number|null,reason:''})
const formOpen=ref(false),noticeOpen=ref(false),detailOpen=ref(false),returnOpen=ref(false),busy=ref(false)
const changing=ref<number|null>(null),detail=ref<any>(null),preview=ref<Preview|null>(null),ack=ref(false)
const expectedVersion=ref<number|null>(null),requestKey=ref('')
let previewSequence=0
const form=reactive({scenario:'FIELD_ABSENCE',leaveType:'PERSONAL',startAt:'',endAt:'',destination:'',returnLocation:'',reason:'',supplementalReason:'',approverUserId:null as number|null})
const returnForm=reactive({actualReturnAt:'',actualReturnLocation:'',explanation:''})
const levelLabel:Record<string,string>={STATION_MANAGER:'站长',AREA_MANAGER:'区域经理',TRAINING_DIRECTOR:'培训负责人'}
const statusLabel:Record<string,string>={PENDING:'待审批',APPROVED:'已批准',REJECTED:'已驳回',WITHDRAWN:'已撤回',CANCELLED:'已取消',RETURNED:'已销假'}
const scenarioLabel:Record<string,string>={FIELD_ABSENCE:'现场临时不上班',RETURN_COMPANY:'返司',RETURN_HOME:'返家',OTHER:'其他'}
const actionLabel:Record<string,string>={SUBMIT:'提交申请',CHANGE_SUBMIT:'提交变更',APPROVED:'审批通过',REJECTED:'审批驳回',WITHDRAW:'撤回申请',CANCEL:'取消请假',RETURN:'完成销假',TRANSFER:'转交责任',CHANGE_CLOSED:'结束变更'}
const requestView=computed(()=>employee.value?(tab.value==='待审批'?'pending':tab.value==='待销假'?'active':tab.value==='已销假'?'returned':'mine'):tab.value==='待我审批'?'pending':tab.value==='我已审批'?'reviewed':tab.value==='逾期未销假'?'overdue':'all')
function dateTime(value:any){return value?String(value).replace('T',' ').slice(0,16):'-'}
function iso(value:string){return value.replace(' ','T')}
function truth(value:any){return value===true||value===1}
function canReview(row:Row){return row.status==='PENDING'&&Number(row.approver_user_id)===auth.user?.id}
function canCancel(row:Row){return row.request_status==='APPROVED'&&new Date(String(row.start_at).replace(' ','T')).getTime()>Date.now()}
async function load(){
  loading.value=true
  try{
    const response=await api.get<any,Envelope<{records:Row[];total:number}>>('/leave-requests',{params:{view:requestView.value,keyword:keyword.value||undefined,status:status.value||undefined,stationId:filters.stationId||undefined,scenario:filters.scenario||undefined,approvalStatus:filters.approvalStatus||undefined,returnStatus:filters.returnStatus||undefined,dateFrom:filters.dates[0]||undefined,dateTo:filters.dates[1]||undefined,page:page.value,size:20}})
    rows.value=response.data.records;total.value=response.data.total
  }finally{loading.value=false}
}
async function loadPreview(){
  const sequence=++previewSequence
  const selected=form.approverUserId
  preview.value=null
  if(!form.startAt||!form.endAt)return
  try{
    const response=await api.get<any,Envelope<Preview>>('/leave-requests/preview',{params:{startAt:iso(form.startAt),endAt:iso(form.endAt)}})
    if(sequence!==previewSequence)return
    preview.value=response.data
    form.approverUserId=response.data.approvers.some(x=>x.id===selected)?selected:null
  }catch{if(sequence===previewSequence){preview.value=null;form.approverUserId=null}}
}
watch(()=>[form.startAt,form.endAt],loadPreview)
watch(tab,()=>{page.value=1;status.value='';load()})
function openForm(row?:Row){
  changing.value=row?.request_status==='APPROVED'?Number(row.request_id):null
  expectedVersion.value=changing.value?Number(row?.current_version):null
  requestKey.value=crypto.randomUUID();ack.value=false
  Object.assign(form,{scenario:row?.scenario||'FIELD_ABSENCE',leaveType:row?.leave_type||'PERSONAL',startAt:row?dateTime(row.start_at):'',endAt:row?dateTime(row.end_at):'',destination:row?.destination||'',returnLocation:row?.return_location||'',reason:row?.reason||'',supplementalReason:'',approverUserId:null})
  if(!row){form.startAt='';form.endAt=''}
  preview.value=null;formOpen.value=true
  if(row)loadPreview()
}
async function saveDraft(){await api.put('/leave-requests/draft',{...form,changing:changing.value,expectedVersion:expectedVersion.value});draftAvailable.value=true;ElMessage.success('草稿已保存')}
async function resumeDraft(){const response=await api.get<any,Envelope<Row>>('/leave-requests/draft');openForm();Object.assign(form,response.data);changing.value=response.data.changing||null;expectedVersion.value=response.data.expectedVersion||null;await loadPreview()}
function validate(){
  if(!form.startAt||!form.endAt||!form.destination.trim()||!form.returnLocation.trim()||!form.reason.trim())return ElMessage.warning('请完整填写请假时间、地点和原因')
  if(!preview.value)return ElMessage.warning('请先确认有效的请假时段')
  if(!form.approverUserId)return ElMessage.warning('请选择对应层级的审批人')
  if(new Date(form.startAt.replace(' ','T')).getTime()<Date.now()&&!form.supplementalReason.trim())return ElMessage.warning('补提请假需填写补提说明')
  ack.value=false;noticeOpen.value=true
}
async function submit(){
  if(!ack.value)return ElMessage.warning('请确认已阅读请假须知')
  busy.value=true
  try{
    const body={...form,startAt:iso(form.startAt),endAt:iso(form.endAt),noticeAcknowledged:true,requestKey:requestKey.value,expectedVersion:expectedVersion.value}
    await api.post(changing.value?`/leave-requests/${changing.value}/changes`:'/leave-requests',body)
    noticeOpen.value=false;formOpen.value=false;ack.value=false;draftAvailable.value=false
    ElMessage.success('请假申请已提交');await load()
  }finally{busy.value=false}
}
async function openDetail(row:Row){
  const response=await api.get<any,Envelope<any>>(`/leave-requests/${row.request_id}`)
  detail.value=response.data;detailOpen.value=true
}
async function review(row:Row,action:'APPROVE'|'REJECT'){
  let comment=''
  if(action==='REJECT'){
    const response=await ElMessageBox.prompt('填写驳回原因','驳回请假',{inputValidator:value=>Boolean(value?.trim())||'驳回原因必填',confirmButtonText:'确认驳回',cancelButtonText:'取消'})
    comment=response.value
  }else await ElMessageBox.confirm('确认同意这份请假申请？','审批确认',{type:'warning',confirmButtonText:'确认同意',cancelButtonText:'取消'})
  await api.post(`/leave-requests/${row.request_id}/versions/${row.version_no}/review`,{action,comment})
  ElMessage.success('审批已完成');await load()
}
async function withdraw(row:Row){await ElMessageBox.confirm('确认撤回待审批申请？','撤回确认',{confirmButtonText:'确认撤回',cancelButtonText:'取消'});await api.post(`/leave-requests/${row.request_id}/versions/${row.version_no}/withdraw`);ElMessage.success('已撤回');await load()}
async function cancel(row:Row){await ElMessageBox.confirm('确认取消尚未开始的请假？','取消确认',{confirmButtonText:'确认取消',cancelButtonText:'返回'});await api.post(`/leave-requests/${row.request_id}/cancel`,null,{params:{version:row.current_version}});ElMessage.success('已取消');await load()}
function openReturn(row:Row){detail.value=row;Object.assign(returnForm,{actualReturnAt:'',actualReturnLocation:row.return_location||'',explanation:''});returnOpen.value=true}
async function submitReturn(){
  if(!returnForm.actualReturnAt||!returnForm.actualReturnLocation.trim())return ElMessage.warning('请填写实际返回时间和地点')
  busy.value=true
  try{await api.post(`/leave-requests/${detail.value.request_id}/return`,{...returnForm,actualReturnAt:iso(returnForm.actualReturnAt),expectedVersion:detail.value.current_version});returnOpen.value=false;ElMessage.success('销假已完成');await load()}finally{busy.value=false}
}
async function openTransfer(row:Row){const response=await api.get<any,Envelope<Row[]>>(`/leave-requests/${row.request_id}/versions/${row.version_no}/approvers`);transferPeople.value=response.data.filter(x=>x.id!==row.approver_user_id);transferRow.value=row;Object.assign(transferForm,{approverUserId:null,reason:''});transferOpen.value=true}
async function submitTransfer(){if(!transferRow.value||!transferForm.approverUserId||!transferForm.reason.trim())return ElMessage.warning('请选择接收人并填写转交原因');busy.value=true;try{await api.post(`/leave-requests/${transferRow.value.request_id}/versions/${transferRow.value.version_no}/transfer`,transferForm);transferOpen.value=false;ElMessage.success('责任已转交');await load()}finally{busy.value=false}}
onMounted(async()=>{const response=await api.get<any,Envelope<typeof context.value>>('/leave-requests/context');context.value=response.data;if(employee.value){const draft=await api.get<any,Envelope<Row>>('/leave-requests/draft');draftAvailable.value=Object.keys(draft.data).length>0}await load();if(route.query.requestId)await openDetail({request_id:Number(route.query.requestId)})})
</script>

<template>
  <div class="leave-page">
    <header class="page-head"><div><h1>{{employee?'我的请假':'请假审批'}}</h1><p>{{employee?'查看请假进度和回段记录':'处理申请与逾期未销假事项'}}</p></div><div class="head-actions"><el-button v-if="employee&&draftAvailable" @click="resumeDraft">继续草稿</el-button><el-button :icon="Refresh" @click="load">刷新</el-button><el-button v-if="employee" type="primary" :icon="Plus" @click="openForm()">新建请假</el-button></div></header>
    <el-tabs v-model="tab"><el-tab-pane v-for="item in tabs" :key="item" :label="item" :name="item"/></el-tabs>
    <div class="filters"><el-input v-if="!employee" v-model="keyword" clearable :prefix-icon="Search" placeholder="姓名或工号" @keyup.enter="load"/><el-select filterable v-model="status" clearable placeholder="全部状态" @change="load"><el-option v-for="(label,key) in statusLabel" :key="key" :label="label" :value="key"/></el-select><el-button @click="load">查询</el-button></div>
    <div class="filters extended-filters">
      <el-select v-if="!employee" v-model="filters.stationId" filterable clearable placeholder="全部服务站"><el-option v-for="station in context.stations" :key="station.id" :label="station.name" :value="station.id"/></el-select>
      <el-select filterable v-model="filters.scenario" clearable placeholder="全部场景"><el-option v-for="(label,key) in scenarioLabel" :key="key" :label="label" :value="key"/></el-select>
      <el-select filterable v-model="filters.approvalStatus" clearable placeholder="审批状态"><el-option label="待审批" value="PENDING"/><el-option label="已批准" value="APPROVED"/><el-option label="已驳回" value="REJECTED"/><el-option label="已撤回" value="WITHDRAWN"/></el-select>
      <el-select filterable v-model="filters.returnStatus" clearable placeholder="销假状态"><el-option label="未销假" value="UNRETURNED"/><el-option label="已销假" value="RETURNED"/></el-select>
      <el-date-picker v-model="filters.dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="请假开始日期" end-placeholder="请假结束日期"/>
      <el-button @click="page=1;load()">应用筛选</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" row-key="id" class="leave-table">
      <el-table-column v-if="!employee" label="请假人" min-width="110" prop="employee_name"/>
      <el-table-column v-if="!employee" label="服务站" min-width="120" prop="station_name"/>
      <el-table-column label="场景" min-width="130"><template #default="{row}">{{scenarioLabel[row.scenario]||row.scenario}}</template></el-table-column>
      <el-table-column label="请假时间" min-width="255"><template #default="{row}">{{dateTime(row.start_at)}} 至 {{dateTime(row.end_at)}}</template></el-table-column>
      <el-table-column label="时长" width="75"><template #default="{row}">{{row.day_units/2}}天</template></el-table-column>
      <el-table-column label="审批层级" width="115"><template #default="{row}">{{levelLabel[row.approver_level]}}</template></el-table-column>
      <el-table-column label="审批人" width="110" prop="approver_name"/>
      <el-table-column label="状态" width="145"><template #default="{row}"><el-tag :type="row.request_status==='RETURNED'?'success':row.status==='PENDING'?'warning':'info'">{{statusLabel[['RETURNED','CANCELLED'].includes(row.request_status)?row.request_status:row.status]||row.status}}</el-tag><el-tag v-if="truth(row.overdue)" type="danger" size="small">逾期未销假</el-tag></template></el-table-column>
      <el-table-column label="操作" min-width="245" fixed="right"><template #default="{row}">
        <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        <template v-if="employee">
          <el-button v-if="row.status==='PENDING'" link @click="withdraw(row)">撤回</el-button>
          <el-button v-if="['REJECTED','WITHDRAWN'].includes(row.request_status)" link @click="openForm(row)">修改重提</el-button>
          <template v-if="row.request_status==='APPROVED'&&row.version_no===row.current_version">
            <el-button v-if="!truth(row.has_pending_change)" link @click="openForm(row)">申请变更</el-button>
            <el-button link type="primary" @click="openReturn(row)">销假</el-button>
            <el-button v-if="canCancel(row)" link @click="cancel(row)">取消</el-button>
          </template>
        </template>
        <template v-else>
          <el-button v-if="canReview(row)" link type="success" @click="review(row,'APPROVE')">同意</el-button>
          <el-button v-if="canReview(row)" link type="danger" @click="review(row,'REJECT')">驳回</el-button>
          <el-button v-if="administrator&&(row.status==='PENDING'||row.request_status==='APPROVED')" link @click="openTransfer(row)">转交</el-button>
        </template>
      </template></el-table-column>
      <template #empty><el-empty description="暂无请假记录"/></template>
    </el-table>
    <div class="pager"><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" @current-change="load"/></div>

    <el-dialog v-model="formOpen" :title="changing?'申请变更':'新建请假'" width="min(640px,94vw)" destroy-on-close>
      <p class="identity">{{context.employee.name}} · {{context.employee.employee_no}} · {{context.employee.station_name||'未分配服务站'}}</p>
      <el-form label-position="top"><div class="two"><el-form-item label="请假场景" required><el-select filterable v-model="form.scenario"><el-option v-for="(label,key) in scenarioLabel" :key="key" :label="label" :value="key"/></el-select></el-form-item><el-form-item label="请假性质" required><el-segmented v-model="form.leaveType" :options="[{label:'因私',value:'PERSONAL'},{label:'因公',value:'OFFICIAL'}]"/></el-form-item></div>
      <div class="two"><el-form-item label="开始时间" required><el-date-picker v-model="form.startAt" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm" placeholder="选择开始时间"/></el-form-item><el-form-item label="结束及预计回段时间" required><el-date-picker v-model="form.endAt" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm" placeholder="选择结束时间"/></el-form-item></div>
      <div v-if="preview" class="calculation">{{preview.periods.join('、')}}，共 {{preview.days}} 天，需 {{levelLabel[preview.level]}}审批。</div>
      <div class="two"><el-form-item label="请假去向地点" required><el-input v-model="form.destination" maxlength="128"/></el-form-item><el-form-item label="预计回段地点" required><el-input v-model="form.returnLocation" maxlength="128"/></el-form-item></div>
      <el-form-item label="请假原因" required><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="1000" show-word-limit/></el-form-item>
      <el-form-item v-if="form.startAt&&new Date(form.startAt.replace(' ','T')).getTime()<Date.now()" label="补提说明" required><el-input v-model="form.supplementalReason" type="textarea" maxlength="500"/></el-form-item>
      <el-form-item :label="`选择${levelLabel[preview?.level||'']||''}审批人`" required><el-select v-model="form.approverUserId" filterable placeholder="选择审批人员"><el-option v-for="person in preview?.approvers||[]" :key="person.id" :label="`${person.display_name} · ${preview?.level==='TRAINING_DIRECTOR'?'全部员工':context.employee.station_name}`" :value="person.id"/></el-select><span v-if="preview&&!preview.approvers.length" class="warning">当前缺少{{levelLabel[preview.level]}}审批账号或服务站范围配置，请联系管理员。</span></el-form-item></el-form>
      <template #footer><el-button @click="formOpen=false">关闭</el-button><el-button @click="saveDraft">保存草稿</el-button><el-button type="primary" @click="validate">提交申请</el-button></template>
    </el-dialog>
    <el-dialog v-model="noticeOpen" title="请假须知" width="min(500px,94vw)" append-to-body><ol><li>半天请假需站长审批，一天请假需区域经理批，一天以上请假需培训负责人批。</li><li>因私请假不报销差旅费和交通费用。</li><li>赴段后需进行销假。</li></ol><el-checkbox v-model="ack">我已阅读并知悉</el-checkbox><template #footer><el-button @click="noticeOpen=false">返回修改</el-button><el-button type="primary" :disabled="!ack" :loading="busy" @click="submit">确认提交</el-button></template></el-dialog>
    <el-dialog v-model="returnOpen" title="办理销假" width="min(480px,94vw)"><el-form label-position="top"><el-form-item label="实际返回时间" required><el-date-picker v-model="returnForm.actualReturnAt" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm"/></el-form-item><el-form-item label="实际返回地点" required><el-input v-model="returnForm.actualReturnLocation" maxlength="128"/></el-form-item><el-form-item label="晚归或地点变化说明"><el-input v-model="returnForm.explanation" type="textarea" maxlength="1000"/></el-form-item></el-form><template #footer><el-button @click="returnOpen=false">取消</el-button><el-button type="primary" :loading="busy" @click="submitReturn">确认销假</el-button></template></el-dialog>
    <el-dialog v-model="transferOpen" title="转交请假责任" width="min(480px,94vw)"><el-form label-position="top"><el-form-item label="同级接收人" required><el-select v-model="transferForm.approverUserId" filterable><el-option v-for="person in transferPeople" :key="person.id" :label="person.display_name" :value="person.id"/></el-select></el-form-item><el-form-item label="转交原因" required><el-input v-model="transferForm.reason" type="textarea" maxlength="500"/></el-form-item></el-form><template #footer><el-button @click="transferOpen=false">取消</el-button><el-button type="primary" :loading="busy" @click="submitTransfer">确认转交</el-button></template></el-dialog>
    <el-drawer v-model="detailOpen" title="请假详情" size="min(560px,100vw)"><template v-if="detail?.request"><p><strong>当前状态：</strong>{{statusLabel[detail.request.status]}}</p><h3>申请版本</h3><article v-for="version in detail.versions" :key="version.id" class="detail-item"><strong>第 {{version.version_no}} 版 · {{statusLabel[version.status]}}</strong><p>{{dateTime(version.start_at)}} 至 {{dateTime(version.end_at)}} · {{version.day_units/2}} 天</p><p>请假去向：{{version.destination}}；预计回段：{{version.return_location}}</p><p>原因：{{version.reason}}</p><p v-if="version.supplemental_reason">补提说明：{{version.supplemental_reason}}</p><p>审批人：{{version.approver_name}}</p><p>计假依据：{{version.calculation_periods?.join('、')}} · {{version.rule_version}}</p><p>须知确认：{{dateTime(version.notice_acknowledged_at)}} · {{version.notice_version}}</p></article><h3>操作记录</h3><article v-for="action in detail.actions" :key="action.id" class="detail-item">{{dateTime(action.created_at)}} · {{action.actor_name}} · {{actionLabel[action.action]||action.action}}<p v-if="action.comment">{{action.comment}}</p><p v-if="action.actual_return_at">实际返回：{{dateTime(action.actual_return_at)}} · {{action.actual_return_location}}</p><el-tag v-if="truth(action.actual_late_return)" type="danger">实际晚归</el-tag><el-tag v-if="truth(action.late_filing)" type="warning">按时返回，销假填报较晚</el-tag></article><h3 v-if="detail.movements?.length">关联实际轨迹</h3><article v-for="movement in detail.movements" :key="movement.id" class="detail-item">{{dateTime(movement.occurred_at)}} · {{movement.to_location}}</article></template></el-drawer>
  </div>
</template>

<style scoped>
.identity{padding:10px 12px;background:#f2f6fa;color:#536474;border-radius:6px}.extended-filters{flex-wrap:wrap}.extended-filters .el-date-editor{max-width:100%;flex-grow:0}
.leave-page{padding:24px;min-height:100%;background:#f7f8fa;color:#24313d}.page-head,.head-actions,.filters,.pager{display:flex;align-items:center;justify-content:space-between;gap:12px}.page-head{margin-bottom:20px}.page-head h1{font-size:25px;margin:0 0 6px}.page-head p{margin:0;color:#667580}.filters{justify-content:flex-start;margin:12px 0}.filters .el-input{width:220px}.filters .el-select{width:160px}.leave-table{border:1px solid #e2e6e8;border-radius:6px}.pager{justify-content:flex-end;margin-top:15px}.two{display:grid;grid-template-columns:1fr 1fr;gap:16px}.two .el-date-editor,.two .el-select{width:100%}.calculation{padding:10px 12px;margin-bottom:18px;border-left:3px solid #287f77;background:#eef8f5;color:#215f59;line-height:1.5}.warning{color:#b5502c;font-size:12px}.detail-item{padding:12px 0;border-bottom:1px solid #e5e8ea}.detail-item p{margin:5px 0;color:#52616d}ol{padding-left:22px;line-height:1.8}@media(max-width:650px){.leave-page{padding:14px}.page-head{align-items:flex-start}.head-actions{flex-wrap:wrap;justify-content:flex-end}.two{grid-template-columns:1fr}.filters{flex-wrap:wrap}.filters .el-input,.filters .el-select{width:100%}}
@media(max-width:650px){.leave-page :deep(.el-dialog__body){max-height:60vh;overflow-y:auto}}
</style>
