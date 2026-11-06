<script setup lang="ts">
import {computed,reactive,ref} from 'vue'
import {ElMessage,type UploadFile} from 'element-plus'
import {api,type Envelope} from '@/api'

type Diagnostic={row:number;field:string;message:string;suggestion:string}
type Person={ref:string;employeeNo:string;name:string;role:string;userId:number|null;action:'CREATE'|'REUSE'}
type Group={stationId:number;stationName:string;level:string;before:string[];after:string[];changed:boolean}
type Impact={key:string;stationName:string;level:string;fromName:string;count:number;candidateRefs:string[]}
type InputRow={row:number;station:string;stationName:string;stationNo:string;areaName:string;areaNo:string}
type Result={createdAccounts:number;reusedAccounts:number;changedStations:number;affectedVersions:number;unchangedRelations:number}
type Preview={batchId:string;status:string;expiresAt:string;summary:{validRows:number;skippedRows:number;duplicateRelations:number;createdAccounts:number;reusedAccounts:number;changedStations:number;unchangedRelations:number;affectedVersions:number};rows:InputRow[];errors:Diagnostic[];warnings:Diagnostic[];people:Person[];groups:Group[];impacts:Impact[]}
type Batch={status:string;preview?:Preview;result?:Result}
const emit=defineEmits<{completed:[]}>()
const visible=ref(false),busy=ref(false),downloading=ref(false),file=ref<File|null>(null),preview=ref<Preview|null>(null),result=ref<Result|null>(null)
const reason=ref(''),onlyErrors=ref(false),onlyChanges=ref(false),resultUncertain=ref(false),fileList=ref<UploadFile[]>([])
const transfers=reactive<Record<string,string>>({})
const labels:Record<string,string>={STATION_MANAGER:'站长',AREA_MANAGER:'区域经理'}
const groups=computed(()=>preview.value?.groups.filter(g=>!onlyChanges.value||g.changed)||[])
const errorRows=computed(()=>new Set(preview.value?.errors.map(x=>x.row)))
const rows=computed(()=>preview.value?.rows.filter(row=>!onlyErrors.value||errorRows.value.has(row.row))||[])
const canCommit=computed(()=>preview.value?.status==='VALIDATED'&&!preview.value.errors.length&&reason.value.trim().length>0&&preview.value.impacts.every(x=>transfers[x.key])&&!busy.value&&!result.value)
const summaryLabels:Record<string,string>={validRows:'有效行',skippedRows:'跳过行',duplicateRelations:'重复关系',createdAccounts:'新建账号',reusedAccounts:'复用账号',changedStations:'变更站点',unchangedRelations:'保留相同关系',affectedVersions:'交接请假责任'}
const personLabel=(key:string)=>{const p=preview.value?.people.find(x=>x.ref===key);return p?`${p.name}（${p.employeeNo}）${p.action==='CREATE'?' · 新建账号':''}`:key}
function open(){if(busy.value)return;visible.value=true;preview.value=null;result.value=null;reason.value='';file.value=null;fileList.value=[];resultUncertain.value=false;onlyErrors.value=false;onlyChanges.value=false;Object.keys(transfers).forEach(key=>delete transfers[key])}
function close(done:()=>void){if(!busy.value)done()}
function choose(upload:UploadFile){file.value=upload.raw||null;preview.value=null;result.value=null;resultUncertain.value=false}
async function download(path:string,name:string){
  downloading.value=true
  try{const blob=await api.get<unknown,Blob>(path,{responseType:'blob'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}finally{downloading.value=false}
}
async function inspect(){
  if(!file.value)return ElMessage.warning('请先选择填写后的.xlsx文件')
  if(!file.value.name.toLowerCase().endsWith('.xlsx')||file.value.size>5*1024*1024)return ElMessage.warning('仅支持不超过5MB的.xlsx文件')
  busy.value=true
  try{
    const form=new FormData();form.append('file',file.value)
    const response=await api.post<unknown,Envelope<Preview>>('/station-responsibilities/imports/preview',form,{timeout:60000})
    preview.value=response.data;Object.keys(transfers).forEach(key=>delete transfers[key])
    for(const impact of response.data.impacts)if(impact.candidateRefs.length===1)transfers[impact.key]=impact.candidateRefs[0]!
    onlyErrors.value=response.data.errors.length>0
  }finally{busy.value=false}
}
function finished(value:Result){result.value=value;resultUncertain.value=false;emit('completed');ElMessage.success('负责人导入完成')}
async function query(){
  if(!preview.value)return
  busy.value=true
  try{const response=await api.get<unknown,Envelope<Batch>>(`/station-responsibilities/imports/${preview.value.batchId}`);if(response.data.status==='COMPLETED'&&response.data.result)finished(response.data.result);else ElMessage.info('本次导入尚未完成，可继续确认导入或重新预览')}finally{busy.value=false}
}
async function commit(){
  if(!canCommit.value||!preview.value)return
  busy.value=true;resultUncertain.value=false
  try{
    const response=await api.post<unknown,Envelope<Batch>>(`/station-responsibilities/imports/${preview.value.batchId}/commit`,{reason:reason.value.trim(),transfers},{timeout:60000})
    if(response.data.result)finished(response.data.result)
  }catch(error){
    const status=(error as {response?:{status:number}}).response?.status
    if(!status||status>=500)resultUncertain.value=true
    if(status===409){preview.value.status='STALE';ElMessage.warning('数据或预览有效期已变化，请重新校验预览')}
  }finally{busy.value=false}
}
</script>

<template>
  <el-button :loading="downloading" @click="download('/station-responsibilities/import-template','站长与区域经理导入模板.xlsx')">下载模板</el-button>
  <el-button type="primary" @click="open">导入负责人</el-button>
  <el-dialog v-model="visible" class="station-import-dialog" title="导入站长与区域经理" width="min(1160px,96vw)" :before-close="close" :close-on-click-modal="false" :close-on-press-escape="!busy" destroy-on-close>
    <div v-if="result" class="import-result">
      <el-result icon="success" title="负责人导入完成" :sub-title="`已创建${result.createdAccounts}个账号，复用${result.reusedAccounts}个账号，更新${result.changedStations}个服务站，交接${result.affectedVersions}项请假责任。`"/>
      <el-alert title="新账号使用工号登录；初始密码为工号后六位，不足六位使用完整工号，首次登录必须修改密码。已有账号继续使用原密码。" type="info" :closable="false" show-icon/>
    </div>
    <template v-else>
      <el-alert title="填写的负责人将替换该站同级负责人；姓名与工号同时留空保持原配置。确认导入后统一创建账号、更新范围并交接请假责任。" type="info" :closable="false" show-icon/>
      <div class="upload-bar">
        <el-upload v-model:file-list="fileList" accept=".xlsx" :auto-upload="false" :limit="1" :on-change="choose" :on-remove="()=>{file=null;preview=null}" :on-exceed="()=>ElMessage.warning('请先移除已选文件，再选择新文件')" :disabled="busy">
          <el-button :disabled="busy">选择Excel文件</el-button>
          <template #tip><div class="el-upload__tip">仅.xlsx，最多5MB、1000条非空数据行；工号请按文本填写。</div></template>
        </el-upload>
        <el-button type="primary" :loading="busy" :disabled="!file" @click="inspect">校验并预览</el-button>
      </div>
      <template v-if="preview">
        <div class="import-summary"><div v-for="(value,key) in preview.summary" :key="key"><span>{{summaryLabels[key]}}</span><strong>{{value}}</strong></div></div>
        <el-alert v-if="preview.errors.length" :title="`发现${preview.errors.length}项错误，本批次不能导入。请修正后重新上传。`" type="error" :closable="false" show-icon/>
        <div class="section-toolbar"><strong>数据核对</strong><el-checkbox v-model="onlyErrors">仅看错误</el-checkbox><el-button v-if="preview.errors.length" :loading="downloading" @click="download(`/station-responsibilities/imports/${preview.batchId}/errors`,'负责人导入错误明细.xlsx')">下载错误明细</el-button></div>
        <p v-for="(error,index) in preview.errors.filter(x=>x.row===0)" :key="index" class="error-text">{{error.message}}；{{error.suggestion}}</p>
        <el-table :data="rows" max-height="270" border empty-text="没有符合筛选条件的数据">
          <el-table-column prop="row" label="原行号" width="75"/>
          <el-table-column prop="station" label="服务站" min-width="160"/>
          <el-table-column label="站长" min-width="135"><template #default="{row}">{{row.stationName}}{{row.stationNo?`（${row.stationNo}）`:''}}</template></el-table-column>
          <el-table-column label="区域经理" min-width="135"><template #default="{row}">{{row.areaName}}{{row.areaNo?`（${row.areaNo}）`:''}}</template></el-table-column>
          <el-table-column label="校验结果与处理建议" min-width="260"><template #default="{row}"><div v-for="(error,index) in preview.errors.filter(x=>x.row===row.row)" :key="index" class="error-text">{{error.field}}：{{error.message}}；{{error.suggestion}}</div><div v-for="(warning,index) in preview.warnings.filter(x=>x.row===row.row)" :key="index" class="warning-text">{{warning.message}}</div><span v-if="!errorRows.has(row.row)">{{[row.stationName,row.stationNo,row.areaName,row.areaNo].some(Boolean)?'通过':'未填写负责人，跳过'}}</span></template></el-table-column>
        </el-table>
        <div class="section-toolbar"><strong>负责人变更预览</strong><el-checkbox v-model="onlyChanges">仅看变更</el-checkbox></div>
        <el-table :data="groups" max-height="260" border>
          <el-table-column prop="stationName" label="服务站" min-width="170"/>
          <el-table-column label="角色" width="100"><template #default="{row}">{{labels[row.level]}}</template></el-table-column>
          <el-table-column label="当前负责人" min-width="220"><template #default="{row}">{{row.before.join('、')||'未配置'}}</template></el-table-column>
          <el-table-column label="导入后负责人" min-width="220"><template #default="{row}">{{row.after.join('、')}}</template></el-table-column>
          <el-table-column label="处理" width="100"><template #default="{row}"><el-tag :type="row.changed?'warning':'info'">{{row.changed?'变更':'无需变更'}}</el-tag></template></el-table-column>
        </el-table>
        <el-collapse class="accounts"><el-collapse-item title="查看账号创建与复用明细" name="accounts"><el-table :data="preview.people" max-height="240"><el-table-column prop="name" label="姓名"/><el-table-column prop="employeeNo" label="工号 / 登录账号"/><el-table-column label="角色"><template #default="{row}">{{labels[row.role]}}</template></el-table-column><el-table-column label="处理"><template #default="{row}">{{row.action==='CREATE'?'新建账号':'复用账号'}}</template></el-table-column></el-table></el-collapse-item></el-collapse>
        <template v-if="!preview.errors.length">
          <div v-if="preview.impacts.length" class="handover"><strong>请假责任交接</strong><div v-for="impact in preview.impacts" :key="impact.key" class="handover-row"><span>{{impact.stationName}} · {{labels[impact.level]}}<br>{{impact.fromName}}移出范围，交接{{impact.count}}项责任</span><el-select v-model="transfers[impact.key]" filterable :disabled="busy" placeholder="请选择同级接任人"><el-option v-for="ref in impact.candidateRefs" :key="ref" :value="ref" :label="personLabel(ref)"/></el-select></div></div>
          <el-form label-position="top" class="reason"><el-form-item label="调整原因" required><el-input v-model="reason" :disabled="busy" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="填写本次负责人调整原因"/></el-form-item></el-form>
          <p class="password-note">新账号初始密码为工号后六位，不足六位使用完整工号，首次登录必须修改。预览有效期30分钟。</p>
        </template>
        <el-alert v-if="resultUncertain" title="尚未确认导入结果，请先查询；再次确认同一批次不会重复执行。" type="warning" :closable="false"/>
      </template>
    </template>
    <template #footer><el-button :disabled="busy" @click="visible=false">{{result?'完成':'取消'}}</el-button><el-button v-if="preview&&!result" :disabled="busy" @click="query">查询导入结果</el-button><el-button v-if="preview&&!result" type="primary" :loading="busy" :disabled="!canCommit" @click="commit">确认导入</el-button></template>
  </el-dialog>
</template>

<style scoped>
:global(.station-import-dialog .el-dialog__body){max-height:65vh;overflow-y:auto}
.upload-bar,.section-toolbar{display:flex;align-items:center;gap:18px;margin:18px 0;flex-wrap:wrap}.upload-bar{justify-content:space-between}.import-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:16px 0}.import-summary>div{padding:12px 16px;background:#f5f8fa;border-radius:8px;display:flex;align-items:center;justify-content:space-between;gap:8px}.import-summary span,.password-note{color:#637581}.import-summary strong{font-size:22px;color:#286c8f}.section-toolbar strong{margin-right:auto}.error-text{color:#c13e3e;line-height:1.6}.warning-text{color:#a56813}.accounts,.reason{margin-top:18px}.handover{margin-top:20px}.handover-row{display:flex;gap:20px;align-items:center;justify-content:space-between;padding:12px 0;border-bottom:1px solid #e2e8ee}.handover-row span{line-height:1.7}.handover-row .el-select{width:340px;max-width:100%}.password-note{font-size:13px;line-height:1.6}@media(max-width:650px){.import-summary{grid-template-columns:repeat(2,minmax(0,1fr))}.handover-row{align-items:stretch;flex-direction:column}.handover-row .el-select{width:100%}}
</style>
