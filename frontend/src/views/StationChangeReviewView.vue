<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue';
import {ArrowRight,CircleCheck,CircleClose,Clock,Document,Refresh,Search,User} from '@element-plus/icons-vue';
import {ElMessage} from 'element-plus';
import {api,type Envelope} from '@/api';
import {avatarUrl,nameInitial} from '@/utils/avatar';
import {loadDictionaryValues,type DictionaryOption} from '@/utils/masterData';

type ReviewStatus='PENDING'|'APPROVED'|'REJECTED';
type ReviewAction='APPROVE'|'REJECT';

interface ReviewRow {
  id:number;
  employee_id:number;
  employee_no:string;
  employee_name:string;
  employee_status:string;
  class_id?:number|null;
  class_name?:string|null;
  class_position_id?:number|null;
  class_position_name?:string|null;
  avatar_token?:string|null;
  current_station_id?:number|null;
  current_station_name?:string|null;
  requested_station_id:number;
  requested_station_name:string;
  status:ReviewStatus;
  batch_name?:string|null;
  business_unit_name?:string|null;
  mentor_name?:string|null;
  skill_mentor_name?:string|null;
  reviewer_name?:string|null;
  review_comment?:string|null;
  created_at:string;
  reviewed_at?:string|null;
  waiting_hours:number;
  approved_change_count:number;
}

interface ReviewSummary {
  total:number;
  pending:number;
  approvedToday:number;
  rejectedToday:number;
  averagePendingHours:number;
}

const rows=ref<ReviewRow[]>([]);
const stations=ref<any[]>([]);
const classOptions=ref<DictionaryOption[]>([]);
const classPositionOptions=ref<DictionaryOption[]>([]);
const loading=ref(false);
const summaryLoading=ref(false);
const detailOpen=ref(false);
const selected=ref<ReviewRow|null>(null);
const history=ref<any[]>([]);
const historyLoading=ref(false);
const reviewOpen=ref(false);
const submitting=ref(false);
const reviewAction=ref<ReviewAction>('APPROVE');
const reviewComment=ref('');
const dateRange=ref<[string,string]|[]>([]);
const filters=reactive({status:'PENDING',keyword:'',classId:undefined as number|undefined,classPositionId:undefined as number|undefined,stationId:undefined as number|undefined});
const summary=reactive<ReviewSummary>({
  total:0,
  pending:0,
  approvedToday:0,
  rejectedToday:0,
  averagePendingHours:0
});

const statusOptions=[
  {label:'待我处理',value:'PENDING'},
  {label:'全部申请',value:''},
  {label:'已通过',value:'APPROVED'},
  {label:'已拒绝',value:'REJECTED'}
];

const reviewTitle=computed(()=>reviewAction.value==='APPROVE'?'确认通过申请':'拒绝调站申请');
const reviewTone=computed(()=>reviewAction.value==='APPROVE'?'approve':'reject');

function fmt(value?:string|null){
  if(!value)return '-';
  return value.substring(0,16).replace('T',' ');
}

function statusLabel(status:ReviewStatus){
  return status==='PENDING'?'待审批':status==='APPROVED'?'已通过':'已拒绝';
}

function statusType(status:ReviewStatus){
  return status==='PENDING'?'warning':status==='APPROVED'?'success':'info';
}

function waitingLabel(hours:number){
  if(!Number.isFinite(hours))return '待计算';
  if(hours<1)return '不足 1 小时';
  if(hours<24)return `${hours} 小时`;
  const days=Math.floor(hours/24);
  const remain=hours%24;
  return remain?`${days} 天 ${remain} 小时`:`${days} 天`;
}

function calculateWaitingHours(row:ReviewRow){
  const start=new Date(String(row.created_at).replace(' ','T')).getTime();
  const end=row.reviewed_at?new Date(String(row.reviewed_at).replace(' ','T')).getTime():Date.now();
  if(!Number.isFinite(start)||!Number.isFinite(end))return 0;
  return Math.max(0,Math.floor((end-start)/3600000));
}

function normalizeRow(row:ReviewRow):ReviewRow{
  return {
    ...row,
    waiting_hours:Number.isFinite(Number(row.waiting_hours))
      ?Number(row.waiting_hours)
      :calculateWaitingHours(row),
    approved_change_count:Number(row.approved_change_count||0)
  };
}

async function loadSummary(){
  summaryLoading.value=true;
  try{
    const response=await api.get<any,Envelope<ReviewSummary>>(
      '/station-change-requests/summary',
      {silentError:true} as any
    );
    Object.assign(summary,response.data);
  }catch{
    try{
      const response=await api.get<any,Envelope<ReviewRow[]>>(
        '/station-change-requests',
        {silentError:true} as any
      );
      const all=response.data.map(normalizeRow);
      const pending=all.filter(row=>row.status==='PENDING');
      const today=new Date().toISOString().slice(0,10);
      summary.total=all.length;
      summary.pending=pending.length;
      summary.approvedToday=all.filter(row=>row.status==='APPROVED'&&String(row.reviewed_at||'').slice(0,10)===today).length;
      summary.rejectedToday=all.filter(row=>row.status==='REJECTED'&&String(row.reviewed_at||'').slice(0,10)===today).length;
      summary.averagePendingHours=pending.length
        ?pending.reduce((total,row)=>total+row.waiting_hours,0)/pending.length
        :0;
    }catch{
      Object.assign(summary,{total:0,pending:0,approvedToday:0,rejectedToday:0,averagePendingHours:0});
    }
  }finally{
    summaryLoading.value=false;
  }
}

async function loadList(){
  loading.value=true;
  try{
    const params:any={};
    if(filters.status)params.status=filters.status;
    if(filters.keyword.trim())params.keyword=filters.keyword.trim();
    if(filters.classId)params.classId=filters.classId;
    if(filters.classPositionId)params.classPositionId=filters.classPositionId;
    if(filters.stationId)params.stationId=filters.stationId;
    if(dateRange.value.length===2){
      params.dateFrom=dateRange.value[0];
      params.dateTo=dateRange.value[1];
    }
    const response=await api.get<any,Envelope<ReviewRow[]>>('/station-change-requests',{params});
    rows.value=response.data.map(normalizeRow).filter(row=>{
      const keyword=filters.keyword.trim().toLowerCase();
      if(keyword&&!`${row.employee_name} ${row.employee_no}`.toLowerCase().includes(keyword))return false;
      if(filters.classId&&row.class_id!==filters.classId)return false;
      if(filters.classPositionId&&row.class_position_id!==filters.classPositionId)return false;
      if(filters.stationId&&row.current_station_id!==filters.stationId&&row.requested_station_id!==filters.stationId)return false;
      const requestDate=String(row.created_at).slice(0,10);
      if(dateRange.value.length===2&&(requestDate<dateRange.value[0]||requestDate>dateRange.value[1]))return false;
      return true;
    });
  }finally{
    loading.value=false;
  }
}

async function loadStations(){
  const [response,classValues,classPositionValues]=await Promise.all([api.get<any,Envelope<any[]>>('/stations'),loadDictionaryValues('CLASS'),loadDictionaryValues('CLASS_POSITION')]);
  stations.value=response.data;
  classOptions.value=classValues;
  classPositionOptions.value=classPositionValues;
}

async function refresh(){
  await Promise.all([loadSummary(),loadList()]);
}

function resetFilters(){
  filters.keyword='';
  filters.classId=undefined;
  filters.classPositionId=undefined;
  filters.stationId=undefined;
  dateRange.value=[];
  filters.status='PENDING';
  loadList();
}

async function openDetail(row:ReviewRow){
  selected.value=row;
  detailOpen.value=true;
  historyLoading.value=true;
  try{
    const response=await api.get<any,Envelope<any[]>>(`/station-change-requests/employee/${row.employee_id}`);
    history.value=response.data;
  }finally{
    historyLoading.value=false;
  }
}

function openReview(row:ReviewRow,action:ReviewAction){
  selected.value=row;
  reviewAction.value=action;
  reviewComment.value='';
  reviewOpen.value=true;
}

async function submitReview(){
  if(!selected.value)return;
  const comment=reviewComment.value.trim();
  if(reviewAction.value==='REJECT'&&!comment){
    ElMessage.warning('请填写拒绝原因，便于员工理解并重新申请');
    return;
  }
  submitting.value=true;
  try{
    const action=reviewAction.value==='APPROVE'?'approve':'reject';
    await api.put(`/station-change-requests/${selected.value.id}/${action}`,{comment:comment||null});
    ElMessage.success(reviewAction.value==='APPROVE'?'申请已通过，人员服务站已更新':'申请已拒绝，原因已记录');
    reviewOpen.value=false;
    detailOpen.value=false;
    await refresh();
  }finally{
    submitting.value=false;
  }
}

onMounted(()=>Promise.all([loadStations(),refresh()]));
</script>

<template>
  <div class="review-page">
    <section class="review-hero">
      <div>
        <span class="eyebrow">人员管理 · 调站审批</span>
        <h1>调站审批</h1>
        <p>集中处理新员工服务站变更申请，核对人员归属与申请路径，确保每次调整清晰、可追溯。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading||summaryLoading" @click="refresh">刷新数据</el-button>
    </section>

    <section class="summary-grid" v-loading="summaryLoading">
      <article class="summary-card pending">
        <span class="summary-icon"><el-icon><Clock/></el-icon></span>
        <div><small>待审批</small><strong>{{summary.pending}}</strong><span>需优先处理</span></div>
      </article>
      <article class="summary-card approved">
        <span class="summary-icon"><el-icon><CircleCheck/></el-icon></span>
        <div><small>今日通过</small><strong>{{summary.approvedToday}}</strong><span>已更新人员归属</span></div>
      </article>
      <article class="summary-card rejected">
        <span class="summary-icon"><el-icon><CircleClose/></el-icon></span>
        <div><small>今日拒绝</small><strong>{{summary.rejectedToday}}</strong><span>已反馈处理原因</span></div>
      </article>
      <article class="summary-card waiting">
        <span class="summary-icon"><el-icon><Document/></el-icon></span>
        <div><small>平均等待</small><strong>{{Math.round(summary.averagePendingHours)}}<em>小时</em></strong><span>基于当前待办</span></div>
      </article>
    </section>

    <section class="workspace-card">
      <div class="workspace-head">
        <div>
          <h2>审批工作台</h2>
          <p>待办按申请时间由早到晚排列，优先处理等待较久的申请</p>
        </div>
        <span class="result-count">共 {{rows.length}} 条</span>
      </div>

      <div class="status-tabs">
        <button
          v-for="option in statusOptions"
          :key="option.value"
          type="button"
          :class="{active:filters.status===option.value}"
          @click="filters.status=option.value;loadList()"
        >
          {{option.label}}
          <span v-if="option.value==='PENDING'">{{summary.pending}}</span>
        </button>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="filters.keyword"
          :prefix-icon="Search"
          clearable
          placeholder="搜索姓名或工号"
          @keyup.enter="loadList"
        />
        <el-select v-model="filters.stationId" clearable filterable placeholder="当前或目标服务站">
          <el-option v-for="station in stations" :key="station.id" :label="station.name" :value="station.id"/>
        </el-select>
        <el-select v-model="filters.classId" clearable filterable placeholder="全部班级"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
        <el-select v-model="filters.classPositionId" clearable filterable placeholder="全部班级职务"><el-option v-for="item in classPositionOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="申请开始日期"
          end-placeholder="申请结束日期"
          range-separator="至"
        />
        <el-button type="primary" @click="loadList">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>

      <div class="desktop-table">
        <el-table :data="rows" v-loading="loading" row-key="id">
          <el-table-column label="申请人" min-width="190">
            <template #default="{row}">
              <div class="person-cell">
                <el-avatar :size="42" :src="avatarUrl(row.avatar_token)">{{nameInitial(row.employee_name)}}</el-avatar>
                <div><strong>{{row.employee_name}}</strong><span>{{row.employee_no}}</span></div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="调站路径" min-width="260">
            <template #default="{row}">
              <div class="route-cell">
                <span>{{row.current_station_name||'未分配服务站'}}</span>
                <el-icon><ArrowRight/></el-icon>
                <strong>{{row.requested_station_name}}</strong>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="人员归属" min-width="210">
            <template #default="{row}">
              <div class="meta-lines">
                <span>{{row.batch_name||'未分配批次'}} · {{row.business_unit_name||'未分配板块'}}</span>
                <small>技术导师：{{row.mentor_name||'未分配'}}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="申请与等待" min-width="170">
            <template #default="{row}">
              <div class="meta-lines"><span>{{fmt(row.created_at)}}</span><small>已等待 {{waitingLabel(row.waiting_hours)}}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="105">
            <template #default="{row}"><el-tag :type="statusType(row.status)" effect="light">{{statusLabel(row.status)}}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{row}">
              <div class="action-cell">
                <el-button link @click="openDetail(row)">查看详情</el-button>
                <template v-if="row.status==='PENDING'">
                  <el-button type="primary" size="small" @click="openReview(row,'APPROVE')">通过</el-button>
                  <el-button size="small" @click="openReview(row,'REJECT')">拒绝</el-button>
                </template>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-state"><el-icon><Document/></el-icon><strong>暂无匹配的调站申请</strong><span>可以调整筛选条件后重新查询</span></div>
          </template>
        </el-table>
      </div>

      <div class="mobile-list" v-loading="loading">
        <article v-for="row in rows" :key="row.id" class="request-card">
          <div class="request-card-head">
            <div class="person-cell">
              <el-avatar :size="42" :src="avatarUrl(row.avatar_token)">{{nameInitial(row.employee_name)}}</el-avatar>
              <div><strong>{{row.employee_name}}</strong><span>{{row.employee_no}}</span></div>
            </div>
            <el-tag :type="statusType(row.status)" effect="light">{{statusLabel(row.status)}}</el-tag>
          </div>
          <div class="mobile-route">
            <span>{{row.current_station_name||'未分配服务站'}}</span>
            <el-icon><ArrowRight/></el-icon>
            <strong>{{row.requested_station_name}}</strong>
          </div>
          <div class="mobile-meta">
            <span>申请时间 {{fmt(row.created_at)}}</span>
            <span>已等待 {{waitingLabel(row.waiting_hours)}}</span>
          </div>
          <div class="mobile-actions">
            <el-button @click="openDetail(row)">详情</el-button>
            <template v-if="row.status==='PENDING'">
              <el-button type="primary" @click="openReview(row,'APPROVE')">通过</el-button>
              <el-button @click="openReview(row,'REJECT')">拒绝</el-button>
            </template>
          </div>
        </article>
        <div v-if="!loading&&!rows.length" class="empty-state"><el-icon><Document/></el-icon><strong>暂无匹配的调站申请</strong><span>可以调整筛选条件后重新查询</span></div>
      </div>
    </section>

    <el-drawer v-model="detailOpen" title="申请详情" size="min(520px, 92vw)">
      <template v-if="selected">
        <section class="detail-person">
          <el-avatar :size="58" :src="avatarUrl(selected.avatar_token)">{{nameInitial(selected.employee_name)}}</el-avatar>
          <div><h3>{{selected.employee_name}}</h3><p>{{selected.employee_no}} · {{selected.batch_name||'未分配批次'}}</p></div>
          <el-tag :type="statusType(selected.status)">{{statusLabel(selected.status)}}</el-tag>
        </section>

        <section class="detail-section">
          <h4>本次调站路径</h4>
          <div class="detail-route">
            <div><small>当前服务站</small><strong>{{selected.current_station_name||'未分配服务站'}}</strong></div>
            <el-icon><ArrowRight/></el-icon>
            <div><small>申请调往</small><strong>{{selected.requested_station_name}}</strong></div>
          </div>
        </section>

        <section class="detail-section detail-grid">
          <div><small>所属板块</small><strong>{{selected.business_unit_name||'未分配'}}</strong></div>
          <div><small>技术导师</small><strong>{{selected.mentor_name||'未分配'}}</strong></div>
          <div><small>技能导师</small><strong>{{selected.skill_mentor_name||'未分配'}}</strong></div>
          <div><small>历史调站</small><strong>{{selected.approved_change_count}} 次</strong></div>
          <div><small>申请时间</small><strong>{{fmt(selected.created_at)}}</strong></div>
          <div><small>{{selected.status==='PENDING'?'当前等待':'处理时间'}}</small><strong>{{selected.status==='PENDING'?waitingLabel(selected.waiting_hours):fmt(selected.reviewed_at)}}</strong></div>
        </section>

        <section v-if="selected.status!=='PENDING'" class="detail-section decision-box">
          <h4>审批结果</h4>
          <p><strong>{{selected.reviewer_name||'管理员'}}</strong> 于 {{fmt(selected.reviewed_at)}} 完成处理</p>
          <blockquote>{{selected.review_comment||'未填写审批备注'}}</blockquote>
        </section>

        <section class="detail-section">
          <h4>已生效调站历史</h4>
          <div v-loading="historyLoading" class="history-list">
            <div v-for="item in history" :key="item.id" class="history-item">
              <span class="history-dot"></span>
              <div><strong>{{item.current_station_name||'未分配服务站'}} → {{item.requested_station_name}}</strong><small>{{fmt(item.effective_at)}} · {{item.reviewer_name||'管理员'}}</small></div>
            </div>
            <p v-if="!historyLoading&&!history.length" class="history-empty">暂无已生效的调站记录</p>
          </div>
        </section>
      </template>
      <template #footer v-if="selected?.status==='PENDING'">
        <el-button @click="openReview(selected!,'REJECT')">拒绝申请</el-button>
        <el-button type="primary" @click="openReview(selected!,'APPROVE')">通过申请</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="reviewOpen" :title="reviewTitle" width="min(520px, 92vw)" destroy-on-close>
      <template v-if="selected">
        <div class="review-confirm" :class="reviewTone">
          <span class="confirm-icon"><el-icon><CircleCheck v-if="reviewAction==='APPROVE'"/><CircleClose v-else/></el-icon></span>
          <div>
            <strong>{{selected.employee_name}}</strong>
            <p>{{selected.current_station_name||'未分配服务站'}} → {{selected.requested_station_name}}</p>
          </div>
        </div>
        <el-alert
          v-if="reviewAction==='APPROVE'"
          title="通过后将立即更新该员工的归属服务站，并保留完整变更记录。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          v-else
          title="拒绝原因将展示给员工，请使用明确、可执行的说明。"
          type="warning"
          :closable="false"
          show-icon
        />
        <label class="comment-label">{{reviewAction==='REJECT'?'拒绝原因（必填）':'审批备注（选填）'}}</label>
        <el-input
          v-model="reviewComment"
          type="textarea"
          :rows="4"
          maxlength="255"
          show-word-limit
          :placeholder="reviewAction==='REJECT'?'例如：当前服务站培养任务尚未完成，请完成交接后重新申请':'可填写交接安排或其他说明'"
        />
      </template>
      <template #footer>
        <el-button @click="reviewOpen=false">取消</el-button>
        <el-button :type="reviewAction==='APPROVE'?'primary':'danger'" :loading="submitting" @click="submitReview">
          {{reviewAction==='APPROVE'?'确认通过':'确认拒绝'}}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.review-page{min-height:100%;padding:22px 22px 42px;background:#f5f7fb;color:#172033}
.review-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;max-width:none;margin:0 auto 22px}
.eyebrow{display:block;margin-bottom:7px;color:#356bd8;font-size:12px;font-weight:700;letter-spacing:.12em}
.review-hero h1{margin:0;font-size:28px;line-height:1.25;letter-spacing:-.02em}
.review-hero p{max-width:720px;margin:8px 0 0;color:#64748b;font-size:14px;line-height:1.7}
.review-hero .el-button{height:38px;border-radius:9px}
.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;max-width:none;margin:0 auto 18px}
.summary-card{display:flex;align-items:center;gap:13px;min-height:96px;padding:17px 18px;border:1px solid #e7ebf2;border-radius:12px;background:#fff}
.summary-icon{display:grid;flex:0 0 44px;height:44px;place-items:center;border-radius:13px;font-size:22px}
.summary-card>div{display:grid;grid-template-columns:auto 1fr;align-items:baseline;column-gap:7px}
.summary-card small{grid-column:1/-1;color:#788397;font-size:13px}
.summary-card strong{font-size:25px;line-height:1.3}.summary-card strong em{margin-left:3px;font-size:12px;font-style:normal;font-weight:500;color:#8b95a7}
.summary-card div>span{font-size:12px;color:#9aa3b2}
.pending .summary-icon{color:#b76e00;background:#fff7e8}.approved .summary-icon{color:#15966b;background:#eafaf4}
.rejected .summary-icon{color:#cf4a54;background:#fff0f1}.waiting .summary-icon{color:#5a68c7;background:#f0f1ff}
.workspace-card{max-width:none;margin:0 auto;overflow:hidden;border:1px solid #e4e9f1;border-radius:14px;background:#fff;box-shadow:0 5px 18px rgba(32,51,82,.04)}
.workspace-head{display:flex;align-items:center;justify-content:space-between;padding:19px 20px 13px}
.workspace-head h2{margin:0;font-size:17px}.workspace-head p{margin:6px 0 0;color:#8992a3;font-size:12px}.result-count{color:#8a96a8;font-size:12px}
.status-tabs{display:flex;gap:6px;padding:0 20px;border-bottom:1px solid #edf0f5;scrollbar-width:none}
.status-tabs::-webkit-scrollbar{display:none}
.status-tabs button{position:relative;padding:11px 13px;border:0;background:transparent;color:#667085;cursor:pointer;font-size:14px}
.status-tabs button span{display:inline-grid;min-width:20px;height:20px;margin-left:4px;place-items:center;border-radius:10px;background:#fff1df;color:#b66a00;font-size:11px}
.status-tabs button.active{color:#2077c9;font-weight:700}.status-tabs button.active:after{position:absolute;right:8px;bottom:-1px;left:8px;height:2px;border-radius:2px;background:#409eff;content:""}
.filter-bar{display:grid;grid-template-columns:minmax(170px,1fr) minmax(150px,.8fr) minmax(180px,1fr) minmax(280px,1.35fr) auto auto;gap:9px;padding:16px 20px;background:#fff}
.person-cell{display:flex;align-items:center;gap:11px}.person-cell>div{display:flex;min-width:0;flex-direction:column}.person-cell strong{font-size:14px}.person-cell span{margin-top:4px;color:#8a94a5;font-size:12px}
.route-cell{display:flex;align-items:center;gap:9px}.route-cell span{color:#687386}.route-cell .el-icon{color:#aab2bf}.route-cell strong{color:#1677c8}
.meta-lines{display:flex;flex-direction:column;gap:5px}.meta-lines span{color:#3f4b5f}.meta-lines small{color:#8b95a6}
.action-cell{display:flex;align-items:center;white-space:nowrap}.empty-state{display:flex;min-height:220px;align-items:center;justify-content:center;flex-direction:column;color:#a1a9b7}
.empty-state .el-icon{margin-bottom:10px;font-size:34px}.empty-state strong{color:#566174}.empty-state span{margin-top:8px;font-size:12px}
.mobile-list{display:none}
.detail-person{display:flex;align-items:center;gap:14px;padding:3px 0 20px;border-bottom:1px solid #edf0f5}.detail-person>div{flex:1}.detail-person h3{margin:0 0 5px}.detail-person p{margin:0;color:#818b9c;font-size:13px}
.detail-section{padding:20px 0;border-bottom:1px solid #edf0f5}.detail-section h4{margin:0 0 14px;font-size:15px}
.detail-route{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;gap:12px;padding:16px;border-radius:12px;background:#f6f9fd}.detail-route>div{display:flex;flex-direction:column;gap:6px}.detail-route small,.detail-grid small{color:#8993a4}.detail-route strong{font-size:15px}.detail-route>div:last-child strong{color:#1579c9}.detail-route .el-icon{color:#8ea3b8}
.detail-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:18px}.detail-grid>div{display:flex;flex-direction:column;gap:6px}.detail-grid strong{font-size:14px}
.decision-box p{margin:0 0 12px;color:#626d7f;font-size:13px}.decision-box blockquote{margin:0;padding:12px 14px;border-left:3px solid #aab6c4;border-radius:4px;background:#f7f8fa;color:#566174;font-size:13px;line-height:1.6}
.history-list{min-height:60px}.history-item{position:relative;display:flex;gap:12px;padding:0 0 18px 3px}.history-item:after{position:absolute;top:12px;bottom:0;left:7px;width:1px;background:#dde4ed;content:""}.history-item:last-child:after{display:none}.history-dot{z-index:1;flex:0 0 9px;height:9px;margin-top:4px;border:2px solid #409eff;border-radius:50%;background:#fff}.history-item>div{display:flex;flex-direction:column;gap:5px}.history-item strong{font-size:13px}.history-item small{color:#8a94a5}.history-empty{margin:8px 0;color:#9ba4b2;text-align:center;font-size:13px}
.review-confirm{display:flex;align-items:center;gap:13px;margin-bottom:16px;padding:14px;border-radius:12px}.review-confirm.approve{background:#eff8ff}.review-confirm.reject{background:#fff4f4}.confirm-icon{display:grid;width:38px;height:38px;place-items:center;border-radius:50%;font-size:20px}.approve .confirm-icon{color:#1979c8;background:#dcedfb}.reject .confirm-icon{color:#d34b55;background:#ffe0e2}.review-confirm p{margin:5px 0 0;color:#647083;font-size:13px}.comment-label{display:block;margin:18px 0 8px;color:#424d60;font-size:14px;font-weight:600}
@media(max-width:1350px){.filter-bar{grid-template-columns:repeat(2,minmax(0,1fr))}.filter-bar :deep(.el-date-editor){grid-column:1/-1;width:100%}}
@media(max-width:1100px){.summary-grid{grid-template-columns:repeat(2,1fr)}}
@media(max-width:760px){
  .review-page{padding:18px 12px 80px}.review-hero{align-items:flex-start;flex-direction:column}.review-hero h1{font-size:24px}.review-hero .el-button{width:100%}
  .summary-grid{gap:10px}.summary-card{min-height:92px;padding:14px}.summary-card div>span{display:none}
  .workspace-head{padding:18px 16px 12px}.workspace-head p{display:none}.status-tabs{overflow-x:auto;padding:0 10px}.status-tabs button{flex:0 0 auto}
  .filter-bar{display:flex;padding:14px 16px;flex-direction:column}.filter-bar :deep(.el-date-editor){width:100%}
  .desktop-table{display:none}.mobile-list{display:block;padding:14px;background:#f6f8fb}
  .request-card{margin-bottom:12px;padding:15px;border:1px solid #e4e9f1;border-radius:13px;background:#fff}.request-card-head{display:flex;align-items:center;justify-content:space-between}
  .mobile-route{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;gap:8px;margin:14px 0;padding:13px;border-radius:10px;background:#f5f8fc;font-size:13px}.mobile-route strong{color:#1478c8}.mobile-route .el-icon{color:#9ca8b8}
  .mobile-meta{display:flex;justify-content:space-between;color:#8993a4;font-size:12px}.mobile-actions{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:14px}.mobile-actions .el-button{width:100%;margin:0}
  .detail-grid{grid-template-columns:1fr}.detail-route{font-size:12px}
}
</style>
