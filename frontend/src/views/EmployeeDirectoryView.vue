<script setup lang="ts">
import {onMounted,reactive,ref} from 'vue';
import {api,type Envelope} from '@/api';

const rows=ref<any[]>([]),total=ref(0),page=ref(1),exporting=ref(false);
const batches=ref<any[]>([]),stations=ref<any[]>([]),mentors=ref<any[]>([]);
const filters=reactive({keyword:'',batchId:null as number|null,stationId:null as number|null,mentorId:null as number|null,education:'',status:''});

function params(){return {...filters,page:page.value,size:20}}
async function load(){const r=await api.get<any,Envelope<any>>('/employee-directory',{params:params()});rows.value=r.data.records;total.value=r.data.total}
async function masters(){const [b,s,m]=await Promise.all([api.get<any,Envelope<any[]>>('/batches'),api.get<any,Envelope<any[]>>('/stations'),api.get<any,Envelope<any[]>>('/mentors')]);batches.value=b.data;stations.value=s.data;mentors.value=m.data}
function search(){page.value=1;load()}
function reset(){Object.assign(filters,{keyword:'',batchId:null,stationId:null,mentorId:null,education:'',status:''});search()}
async function exportRows(){exporting.value=true;try{const blob=await api.get<any,Blob>('/employee-directory/export',{params:filters,responseType:'blob'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='人员信息.xlsx';a.click();URL.revokeObjectURL(url)}finally{exporting.value=false}}
onMounted(()=>{load();masters()});
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>人员信息</h2><el-button type="primary" :loading="exporting" @click="exportRows">按当前筛选导出</el-button></div>
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
      <el-table-column prop="station_name" label="服务站" width="120"/>
      <el-table-column prop="mentor_name" label="导师" width="100"/>
      <el-table-column prop="school" label="毕业学校" min-width="150"/>
      <el-table-column prop="major" label="专业" min-width="120"/>
      <el-table-column prop="education" label="学历" width="90"/>
      <el-table-column prop="birth_date" label="出生日期" width="120"/>
      <el-table-column prop="native_place" label="籍贯" width="120"/>
      <el-table-column prop="residence" label="常住地" width="140"/>
      <el-table-column prop="phone" label="手机号" width="130"/>
      <el-table-column prop="onboard_date" label="入职日期" width="120"/>
      <el-table-column prop="status" label="状态" width="90"/>
    </el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" style="margin-top:16px" @change="load"/>
  </div>
</template>

<style scoped>.directory-filters>*{width:160px}.directory-filters .el-input{width:200px}</style>
