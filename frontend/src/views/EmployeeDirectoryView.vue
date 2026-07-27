<script setup lang="ts">
import {onMounted,reactive,ref} from 'vue';
import {api,type Envelope} from '@/api';
import {ElMessage,ElMessageBox} from 'element-plus';

const rows=ref<any[]>([]),total=ref(0),page=ref(1),exporting=ref(false);
const pendingCount=ref(0),pendingRequests=ref<any[]>([]),reviewDialog=ref(false),reviewLoading=ref(false);
const historyDialog=ref(false),historyData=ref<any[]>([]),historyEmployee=ref<any>(null),historyLoading=ref(false);
function fmt(d:any){return d?String(d).substring(0,10):'-'}
const batches=ref<any[]>([]),stations=ref<any[]>([]),mentors=ref<any[]>([]);
const filters=reactive({keyword:'',batchId:null as number|null,stationId:null as number|null,mentorId:null as number|null,education:'',status:''});

function params(){return {...filters,page:page.value,size:20}}
async function load(){const r=await api.get<any,Envelope<any>>('/employee-directory',{params:params()});rows.value=r.data.records;total.value=r.data.total}
async function masters(){const [b,s,m]=await Promise.all([api.get<any,Envelope<any[]>>('/batches'),api.get<any,Envelope<any[]>>('/stations'),api.get<any,Envelope<any[]>>('/mentors')]);batches.value=b.data;stations.value=s.data;mentors.value=m.data}
function search(){page.value=1;load()}
function reset(){Object.assign(filters,{keyword:'',batchId:null,stationId:null,mentorId:null,education:'',status:''});search()}
async function loadPending(){try{const r=await api.get<any,Envelope<any[]>>('/station-change-requests',{params:{status:'PENDING'}});pendingRequests.value=r.data;pendingCount.value=r.data.length}catch{}}
async function approve(id:number){await api.put('/station-change-requests/'+id+'/approve');ElMessage.success('已通过');loadPending();load()}
async function reject(id:number){try{const{value}=await ElMessageBox.prompt('\u8bf7\u8f93\u5165\u62d2\u7edd\u539f\u56e0','\u62d2\u7edd\u7533\u8bf7');await api.put('/station-change-requests/'+id+'/reject',{comment:value});ElMessage.success('\u5df2\u62d2\u7edd');loadPending()}catch(e){if(e!=='cancel')throw e}}
async function showStationHistory(row:any){historyEmployee.value=row;historyLoading.value=true;historyDialog.value=true;try{historyData.value=(await api.get<any,Envelope<any[]>>('/station-change-requests/employee/'+row.id)).data}finally{historyLoading.value=false}}
async function exportRows(){exporting.value=true;try{const blob=await api.get<any,Blob>('/employee-directory/export',{params:filters,responseType:'blob'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='人员信息.xlsx';a.click();URL.revokeObjectURL(url)}finally{exporting.value=false}}
onMounted(()=>{load();masters();loadPending()});
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>人员信息</h2><div><el-badge :value="pendingCount" :hidden="!pendingCount"><el-button @click="reviewDialog=true">审核</el-button></el-badge><el-button type="primary" :loading="exporting" @click="exportRows" style="margin-left:8px">按当前筛选导出</el-button></div></div>
    <el-card style="margin-bottom:16px">
      <div class="toolbar directory-filters">
        <el-input v-model="filters.keyword" placeholder="姓名或工号" clearable @keyup.enter="search"/>
        <el-select v-model="filters.batchId" placeholder="批次" clearable><el-option v-for="x in batches" :key="x.id" :label="x.name" :value="x.id"/></el-select>
        <el-select v-model="filters.stationId" placeholder="服务站" clearable><el-option v-for="x in stations" :key="x.id" :label="x.name" :value="x.id"/></el-select>
        <el-select v-model="filters.mentorId" placeholder="导师" clearable filterable><el-option v-for="x in mentors" :key="x.id" :label="x.display_name" :value="x.id"/></el-select>
        <el-select v-model="filters.education" placeholder="学历" clearable><el-option v-for="x in ['专科','本科','硕士','博士']" :key="x" :label="x" :value="x"/></el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable><el-option label="在职" value="ACTIVE"/><el-option label="停用" value="INACTIVE"/></el-select>
        <el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button>
      </div>
    </el-card>
    <el-table :data="rows" stripe>
      <el-table-column prop="employee_no" label="工号" width="110" fixed/>
      <el-table-column prop="name" label="姓名" width="100" fixed/>
      <el-table-column prop="batch_name" label="批次" width="100"/>
      <el-table-column label="服务站" width="130"><template #default="s"><el-button v-if="s.row.station_name" link type="primary" @click="showStationHistory(s.row)">{{s.row.station_name}}</el-button><span v-else>-</span></template></el-table-column>
      <el-table-column prop="mentor_name" label="导师" width="100"/>
      <el-table-column prop="school" label="毕业学校" min-width="150"/>
      <el-table-column prop="major" label="专业" min-width="120"/>
      <el-table-column prop="education" label="学历" width="90"/>
      <el-table-column prop="birth_date" label="出生日期" width="120"/>
      <el-table-column prop="native_place" label="籍贯" width="120"/>
      <el-table-column prop="residence" label="常住地" width="140"/>
      <el-table-column prop="phone" label="手机号" width="130"/>
      <el-table-column prop="email" label="常用邮箱" min-width="160"/>
      <el-table-column prop="onboard_date" label="入职日期" width="120"/>
      <el-table-column prop="status" label="状态" width="90"/>
    </el-table>
    <el-dialog v-model="reviewDialog" title="服务站变更审核" width="720px">
      <div v-loading="reviewLoading">
        <el-table v-if="pendingRequests.length" :data="pendingRequests" stripe>
          <el-table-column prop="employee_no" label="工号" width="110"/>
          <el-table-column prop="employee_name" label="姓名" width="100"/>
          <el-table-column prop="current_station_name" label="当前服务站" min-width="140"><template #default="s">{{s.row.current_station_name||'-'}}</template></el-table-column>
          <el-table-column prop="requested_station_name" label="申请变更为" min-width="140"/>
          <el-table-column label="申请时间" width="150"><template #default="s">{{fmt(s.row.created_at)}}</template></el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="s">
              <el-button size="small" type="primary" @click="approve(s.row.id)">通过</el-button>
              <el-button size="small" @click="reject(s.row.id)">拒绝</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else style="text-align:center;padding:40px 0;color:#909399">暂无待审核的申请</div>
      </div>
    </el-dialog>
    <el-dialog v-model="historyDialog" :title="historyEmployee?'\u2705 \u670d\u52a1\u7ad9\u53d8\u66f4\u8bb0\u5f55 \u00b7 '+historyEmployee.name+'\uff08'+historyEmployee.employee_no+'\uff09':''" width="520px">
      <div v-loading="historyLoading" style="min-height:60px">
        <div v-if="historyData.length" class="tl-wrap">
          <div v-for="(item,i) in historyData" :key="item.id" class="tl-item">
            <div class="tl-dot" :class="{current:i===0}"></div>
            <div class="tl-content">
              <div class="tl-station">{{item.requested_station_name}}<el-tag v-if="i===0" size="small" type="success" style="margin-left:8px">当前</el-tag></div>
              <div class="tl-meta">{{fmt(item.created_at)}} · 审批通过 · {{item.reviewer_name||'-'}}</div>
                          </div>
          </div>
          <div class="tl-item" style="padding-bottom:0">
            <div class="tl-dot initial"></div>
            <div class="tl-content">
              <div class="tl-station" style="color:#909399">{{historyEmployee?.station_name||'初始分配'}}<el-tag size="small" type="info" style="margin-left:8px">初始</el-tag></div>
              <div class="tl-meta">入职时分配</div>
            </div>
          </div>
        </div>
        <div v-else style="text-align:center;padding:40px 0;color:#909399">
          <p style="font-size:15px;margin-bottom:8px">暂无变更记录</p>
          <p style="font-size:13px">当前服务站：{{historyEmployee?.station_name||'-'}}</p>
        </div>
      </div>
    </el-dialog>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" style="margin-top:16px" @change="load"/>
  </div>
</template>

<style scoped>.directory-filters>*{width:160px}.directory-filters .el-input{width:200px}</style>
<style scoped>
.tl-wrap{position:relative;padding-left:32px;min-height:50px}
.tl-wrap:before{content:'';position:absolute;left:11px;top:6px;bottom:6px;width:2px;background:#e5e7eb}
.tl-item{position:relative;padding-bottom:28px}
.tl-item:last-child{padding-bottom:0}
.tl-dot{position:absolute;left:-20px;top:4px;width:14px;height:14px;border-radius:50%;background:#409eff;border:3px solid #fff;box-shadow:0 0 0 2px #409eff;z-index:1}
.tl-dot.current{background:#67c23a;box-shadow:0 0 0 2px #67c23a}
.tl-dot.initial{background:#c0c4cc;box-shadow:0 0 0 2px #c0c4cc}
.tl-station{font-weight:600;font-size:15px;color:#303133}
.tl-meta{color:#909399;font-size:12px;margin-top:2px}
.tl-from{color:#909399;font-size:12px;margin-top:1px;padding-left:4px}
.tl-from:before{content:'\2191';margin-right:3px}
</style>
