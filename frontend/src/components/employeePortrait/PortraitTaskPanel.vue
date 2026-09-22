<script setup lang="ts">
import {onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {Download,View} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {api} from '@/api'
import {usePortraitPage,dateTime,score,statusLabel,taskStatusLabel} from './usePortraitPage'

const props=defineProps<{employeeId:number}>()
const status=ref('')
const dateRange=ref<string[]>([])
const q=usePortraitPage<any>(()=>props.employeeId,'tasks',()=>({status:status.value||undefined,dateFrom:dateRange.value[0],dateTo:dateRange.value[1]}))
const taskStateLabel=(row:any)=>row.overdue?'逾期未完成':row.completedLate?'已通过（延期）':taskStatusLabel(row.status)
const taskStateType=(row:any):'success'|'warning'|'danger'|'info'=>row.overdue?'danger':row.completedLate?'warning':row.status==='APPROVED'?'success':row.status==='RETURNED'?'danger':row.status==='PENDING_REVIEW'?'warning':'info'
const versionScore=(row:any)=>row.score!==null&&row.score!==undefined?String(row.score):['SUPERSEDED','RETURNED'].includes(row.status)?'-':'待评分'
const previewOpen=ref(false),previewLoading=ref(false),previewType=ref<'PDF'|'IMAGE'|'TEXT'|'HTML'|'UNSUPPORTED'>('UNSUPPORTED'),previewUrl=ref(''),previewContent=ref(''),previewFile=ref<any>()
function clearPreview(){if(previewUrl.value)URL.revokeObjectURL(previewUrl.value);previewUrl.value='';previewContent.value='';previewFile.value=undefined;previewType.value='UNSUPPORTED'}
function saveBlob(blob:Blob,name:string){const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=name;link.click();URL.revokeObjectURL(url)}
async function downloadFile(file:any){if(!file?.id)return;saveBlob(await api.get<any,Blob>(`/files/${file.id}`,{responseType:'blob'}),file.originalName||'提交文件')}
function sanitizeDocxHtml(html:string){const container=document.createElement('div');container.innerHTML=html;container.querySelectorAll('script,style,iframe,object,embed,link,form').forEach(node=>node.remove());container.querySelectorAll('*').forEach(node=>Array.from(node.attributes).forEach(attr=>{const name=attr.name.toLowerCase(),value=attr.value.trim().toLowerCase();if(name.startsWith('on')||name==='style'||(name==='href'&&!value.startsWith('#'))||(name==='src'&&!value.startsWith('data:image/')))node.removeAttribute(attr.name)}));return container.innerHTML}
async function previewSubmissionFile(file:any){if(!file?.id)return;clearPreview();previewFile.value=file;previewLoading.value=true;try{const blob=await api.get<any,Blob>(`/files/${file.id}`,{responseType:'blob'}),name=String(file.originalName||'').toLowerCase(),type=String(file.contentType||blob.type||'').toLowerCase();if(type==='application/pdf'||name.endsWith('.pdf')){previewType.value='PDF';previewUrl.value=URL.createObjectURL(blob)}else if(type.startsWith('image/')||/\.(png|jpe?g|gif|webp|bmp)$/i.test(name)){previewType.value='IMAGE';previewUrl.value=URL.createObjectURL(blob)}else if(type.startsWith('text/')||/\.(txt|md|csv|json|log)$/i.test(name)){previewType.value='TEXT';previewContent.value=await blob.text()}else if(name.endsWith('.docx')||type.includes('wordprocessingml.document')){const mammoth=(await import('mammoth')).default;previewType.value='HTML';previewContent.value=sanitizeDocxHtml((await mammoth.convertToHtml({arrayBuffer:await blob.arrayBuffer()},{externalFileAccess:false})).value)}else{previewType.value='UNSUPPORTED'}previewOpen.value=true}catch(e){ElMessage.error('文件加载失败')}finally{previewLoading.value=false}}
watch([status,dateRange],()=>q.load(1),{deep:true})
onMounted(()=>q.load())
onBeforeUnmount(clearPreview)
</script>

<template>
  <section class="panel">
    <header>
      <div class="filters"><el-select v-model="status" clearable filterable placeholder="全部任务状态" style="width:160px">
        <el-option label="待提交" value="NOT_SUBMITTED"/>
        <el-option label="待评分" value="PENDING_REVIEW"/>
        <el-option label="已退回" value="RETURNED"/>
        <el-option label="逾期未完成" value="OVERDUE"/>
        <el-option label="已通过" value="APPROVED"/>
      </el-select><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="截止日期起" end-placeholder="截止日期止" range-separator="至"/></div>
      <span>按任务截止时间排列；状态根据提交、审核和截止时间综合判定</span>
    </header>
    <el-alert v-if="q.error.value" type="error" :title="q.error.value" show-icon>
      <template #default><el-button link type="primary" @click="q.load(q.page.value)">重试</el-button></template>
    </el-alert>
    <el-table v-else :data="q.records.value" v-loading="q.loading.value" height="100%" stripe empty-text="暂无闯关任务">
      <el-table-column type="expand">
        <template #default="s">
          <div class="task-detail">
            <dl>
              <div><dt>下发时归属快照</dt><dd>{{s.row.batchSnapshot||'-'}} / {{s.row.businessUnitSnapshot||'-'}} / {{s.row.classSnapshot||'-'}}</dd></div>
              <div><dt>评分范围</dt><dd>{{s.row.scoringScopeLabel||'未配置'}}</dd></div>
              <div><dt>评分人</dt><dd>{{s.row.reviewerNames||'待分配'}}</dd></div>
              <div><dt>最新评分进度</dt><dd>{{s.row.reviewedCount}} / {{s.row.reviewerCount}}</dd></div>
              <div><dt>最新审核时间</dt><dd>{{dateTime(s.row.reviewedAt)}}</dd></div>
            </dl>
            <h4>提交与评分历史</h4>
            <el-table :data="s.row.submissions" size="small" empty-text="暂无提交记录">
              <el-table-column label="版本" width="72"><template #default="x">V{{x.row.version}}</template></el-table-column>
              <el-table-column label="提交状态" min-width="125"><template #default="x">{{statusLabel(x.row.status)}}</template></el-table-column>
              <el-table-column label="提交 / 审核时间" min-width="180"><template #default="x">{{dateTime(x.row.submittedAt)}}<br>{{dateTime(x.row.reviewedAt)}}</template></el-table-column>
              <el-table-column label="提交文件" min-width="245">
                <template #default="x">
                  <div v-if="x.row.files?.length" class="submission-files">
                    <div v-for="file in x.row.files" :key="file.id" class="submission-file">
                      <button type="button" class="file-name" :title="`预览 ${file.originalName}`" @click="previewSubmissionFile(file)">{{file.originalName}}</button>
                      <el-button link size="small" :icon="View" :loading="previewLoading&&previewFile?.id===file.id" @click="previewSubmissionFile(file)">预览</el-button>
                      <el-button link size="small" :icon="Download" @click="downloadFile(file)">下载</el-button>
                    </div>
                  </div>
                  <span v-else class="muted">无附件</span>
                </template>
              </el-table-column>
              <el-table-column label="评分进度" width="100"><template #default="x">{{x.row.reviewedCount}} / {{x.row.reviewerCount}}</template></el-table-column>
              <el-table-column label="版本得分" width="90" align="right"><template #default="x">{{versionScore(x.row)}}</template></el-table-column>
              <el-table-column label="评分结果" min-width="280">
                <template #default="x">
                  <div v-if="x.row.reviews?.length" class="reviews">
                    <span v-for="review in x.row.reviews" :key="review.reviewerId">
                      {{review.reviewerName}}：{{statusLabel(review.status)}}<template v-if="review.score!==null">，{{review.score}}分</template><template v-if="review.comment">，{{review.comment}}</template>
                    </span>
                  </div>
                  <span v-else>{{x.row.reviewComment||'暂无评分结果'}}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="taskName" label="任务" min-width="190"/>
      <el-table-column prop="trainingPlanName" label="培养计划" min-width="150"/>
      <el-table-column label="下发 / 截止" min-width="170"><template #default="s">{{dateTime(s.row.assignedAt)}}<br>{{dateTime(s.row.deadline)}}</template></el-table-column>
      <el-table-column label="任务状态" width="150"><template #default="s"><el-tag :type="taskStateType(s.row)">{{taskStateLabel(s.row)}}</el-tag></template></el-table-column>
      <el-table-column prop="latestVersion" label="最新版本" width="90"><template #default="s">{{s.row.latestVersion?`V${s.row.latestVersion}`:'-'}}</template></el-table-column>
      <el-table-column label="最终得分" width="95"><template #default="s">{{score(s.row.finalScore)}}</template></el-table-column>
    </el-table>
    <footer><el-pagination v-model:current-page="q.page.value" :page-size="q.size.value" :total="q.total.value" layout="total, prev, pager, next" @current-change="q.load"/></footer>
  </section>
  <el-dialog v-model="previewOpen" :title="`预览：${previewFile?.originalName||''}`" width="min(920px,94vw)" append-to-body @closed="clearPreview">
    <div v-if="previewLoading" v-loading="true" style="min-height:180px" />
    <iframe v-else-if="previewType==='PDF'" :src="previewUrl" class="preview-frame" title="PDF预览" />
    <img v-else-if="previewType==='IMAGE'" :src="previewUrl" class="preview-image" :alt="previewFile?.originalName||'提交文件'" />
    <div v-else-if="previewType==='HTML'" class="preview-html" v-html="previewContent" />
    <pre v-else-if="previewType==='TEXT'" class="preview-text">{{previewContent}}</pre>
    <el-empty v-else description="该格式暂不支持在线预览，请下载后查看" />
    <template #footer><el-button @click="previewOpen=false">关闭</el-button><el-button type="primary" :icon="Download" @click="downloadFile(previewFile)">下载</el-button></template>
  </el-dialog>
</template>

<style scoped>
.panel{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:10px}.panel header,.filters{display:flex;justify-content:space-between;align-items:center;gap:8px}.panel header span{font-size:12px;color:#7b8798}.panel footer{display:flex;justify-content:flex-end}.task-detail{padding:4px 18px 14px}.task-detail dl{display:flex;flex-wrap:wrap;gap:18px 40px;margin:0 0 12px}.task-detail dt{color:#8a94a6;font-size:12px}.task-detail dd{margin:4px 0 0}.task-detail h4{margin:0 0 8px;font-size:13px}.reviews{display:flex;flex-direction:column;gap:3px}.submission-files{display:flex;flex-direction:column;gap:4px}.submission-file{display:flex;align-items:center;min-width:0;gap:2px}.submission-file .file-name{min-width:0;max-width:155px;overflow:hidden;padding:0;border:0;color:#347cc5;background:none;text-align:left;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}.submission-file .file-name:hover{text-decoration:underline}.submission-file .el-button{flex:none;margin:0}.muted{color:#98a2b3}.preview-frame{width:100%;height:68vh;border:0}.preview-image{display:block;max-width:100%;max-height:68vh;margin:auto}.preview-html,.preview-text{max-height:68vh;padding:20px;overflow:auto;white-space:pre-wrap}.preview-html{white-space:normal}.preview-html :deep(img){max-width:100%}@media(max-width:760px){.panel header,.filters{align-items:flex-start;flex-direction:column}.task-detail{padding-left:0;padding-right:0}}
</style>
