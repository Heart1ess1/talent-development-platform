<script setup lang="ts">
import {onMounted,reactive,ref} from 'vue';
import {ElMessage,ElMessageBox} from 'element-plus';
import {api,type Envelope} from '@/api';

const rows=ref<any[]>([]),statusFilter=ref(''),loading=ref(false);
function fmt(d:string){return d?d.substring(0,16).replace('T',' '):'-'}
const statusOptions=[{label:'全部',value:''},{label:'待审批',value:'PENDING'},{label:'已通过',value:'APPROVED'},{label:'已拒绝',value:'REJECTED'}];

async function load(){loading.value=true;try{const p:any={};if(statusFilter.value)p.status=statusFilter.value;const r=await api.get<any,Envelope<any[]>>('/station-change-requests',{params:p});rows.value=r.data}finally{loading.value=false}}
async function approve(id:number){await api.put(`/station-change-requests/${id}/approve`);ElMessage.success('已通过');load()}
async function reject(id:number){try{const {value}=await ElMessageBox.prompt('请输入拒绝原因','拒绝申请');await api.put(`/station-change-requests/${id}/reject`,{comment:value});ElMessage.success('已拒绝');load()}catch(e){if(e!=='cancel')throw e}}
onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>服务站变更审核</h2></div>
    <div class="toolbar" style="margin-bottom:16px">
      <el-radio-group v-model="statusFilter" @change="load">
        <el-radio-button v-for="o in statusOptions" :key="o.value" :value="o.value">{{o.label}}</el-radio-button>
      </el-radio-group>
    </div>
    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="employee_no" label="工号" width="110"/>
      <el-table-column prop="employee_name" label="姓名" width="100"/>
      <el-table-column prop="current_station_name" label="当前服务站" min-width="140">
        <template #default="s">{{s.row.current_station_name||'-'}}</template>
      </el-table-column>
      <el-table-column prop="requested_station_name" label="申请变更为" min-width="140"/>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="s">
          <el-tag :type="s.row.status==='PENDING'?'warning':s.row.status==='APPROVED'?'success':'info'" size="small">
            {{s.row.status==='PENDING'?'待审批':s.row.status==='APPROVED'?'已通过':'已拒绝'}}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reviewer_name" label="审批人" width="110"/>
      <el-table-column prop="review_comment" label="审批备注" min-width="140"/>
      <el-table-column label="申请时间" width="160"><template #default="s">{{fmt(s.row.created_at)}}</template></el-table-column>
      <el-table-column v-if="statusFilter===''||statusFilter==='PENDING'" label="操作" width="180" fixed="right">
        <template #default="s">
          <el-button v-if="s.row.status==='PENDING'" size="small" type="primary" @click="approve(s.row.id)">通过</el-button>
          <el-button v-if="s.row.status==='PENDING'" size="small" @click="reject(s.row.id)">拒绝</el-button>
          <span v-else style="color:#999">已处理</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
