<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue';
import {api,type Envelope} from '@/api';
import {ElMessage,ElMessageBox} from 'element-plus';
import {useAuthStore} from '@/stores/auth';

const auth=useAuthStore(),canWrite=computed(()=>auth.can('employee:write')),canMaster=computed(()=>auth.can('master:manage'));
const rows=ref<any[]>([]),selectedRows=ref<any[]>([]),total=ref(0),page=ref(1),dialog=ref(false),bindDialog=ref(false),importing=ref(false);
const batches=ref<any[]>([]),stations=ref<any[]>([]),mentors=ref<any[]>([]),editingId=ref<number|null>(null),bindMentorId=ref<number|null>(null);
const filters=reactive({keyword:'',batchId:null as number|null,stationId:null as number|null,mentorId:null as number|null});
const emptyForm=()=>({employeeNo:'',name:'',batchId:null,stationId:null,mentorUserId:null,school:'',major:'',education:'',birthDate:null,nativePlace:'',residence:'',phone:'',email:'',onboardDate:null});
const form=reactive<any>(emptyForm());

function params(){return {...filters,page:page.value,size:20}}
async function load(){const r=await api.get<any,Envelope<any>>('/employees',{params:params()});rows.value=r.data.records;total.value=r.data.total}
async function masters(){const [b,s,m]=await Promise.all([api.get<any,Envelope<any[]>>('/batches'),api.get<any,Envelope<any[]>>('/stations'),api.get<any,Envelope<any[]>>('/mentors')]);batches.value=b.data;stations.value=s.data;mentors.value=m.data}
function search(){page.value=1;load()}
function reset(){Object.assign(filters,{keyword:'',batchId:null,stationId:null,mentorId:null});search()}
function openCreate(){editingId.value=null;Object.assign(form,emptyForm());dialog.value=true}
function openEdit(row:any){editingId.value=row.id;Object.assign(form,{employeeNo:row.employee_no,name:row.name,batchId:row.batch_id,stationId:row.station_id,mentorUserId:row.mentor_user_id,school:row.school||'',major:row.major||'',education:row.education||'',birthDate:row.birth_date,nativePlace:row.native_place||'',residence:row.residence||'',phone:row.phone||'',email:row.email||'',onboardDate:row.onboard_date});dialog.value=true}
async function save(){editingId.value?await api.put(`/employees/${editingId.value}`,form):await api.post('/employees',form);ElMessage.success(editingId.value?'员工已更新':'员工已创建');dialog.value=false;load()}
async function bindMentor(){if(!selectedRows.value.length)return ElMessage.warning('请选择员工');if(!bindMentorId.value)return ElMessage.warning('请选择导师');const r=await api.post<any,Envelope<number>>('/employees/bind-mentor',{employeeIds:selectedRows.value.map(x=>x.id),mentorUserId:bindMentorId.value});ElMessage.success(`已绑定 ${r.data} 人`);bindDialog.value=false;bindMentorId.value=null;load()}
async function upload(o:any){importing.value=true;try{const fd=new FormData();fd.append('file',o.file);const r=await api.post<any,Envelope<any>>('/imports/employees',fd);r.data.errors.length?ElMessage.error(`第${r.data.errors[0].row}行：${r.data.errors[0].message}`):ElMessage.success(`已导入 ${r.data.imported} 人`);load()}finally{importing.value=false}}
async function template(){const blob=await api.get<any,Blob>('/imports/employees/template',{responseType:'blob'});download(blob,'新员工导入模板.xlsx')}
function download(blob:Blob,name:string){const u=URL.createObjectURL(blob),a=document.createElement('a');a.href=u;a.download=name;a.click();URL.revokeObjectURL(u)}
async function addMaster(type:'batches'|'stations'){const {value}=await ElMessageBox.prompt('请输入名称',type==='batches'?'新增批次':'新增服务站');await api.post('/'+type,{name:value});masters()}
onMounted(()=>{load();masters()});
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>人员台账</h2><el-button v-if="canWrite" type="primary" @click="openCreate">新增员工</el-button></div>
    <div class="toolbar employees-filters">
      <el-input v-model="filters.keyword" placeholder="姓名或工号" clearable @keyup.enter="search"/>
      <el-select v-model="filters.batchId" placeholder="批次" clearable><el-option v-for="x in batches" :key="x.id" :label="x.name" :value="x.id"/></el-select>
      <el-select v-model="filters.stationId" placeholder="服务站" clearable><el-option v-for="x in stations" :key="x.id" :label="x.name" :value="x.id"/></el-select>
      <el-select v-model="filters.mentorId" placeholder="导师" clearable filterable><el-option v-for="x in mentors" :key="x.id" :label="x.display_name" :value="x.id"/></el-select>
      <el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button>
      <el-button v-if="canWrite" :disabled="!selectedRows.length" @click="bindDialog=true">批量绑定导师</el-button>
      <el-button v-if="canMaster" @click="addMaster('batches')">新增批次</el-button>
      <el-button v-if="canMaster" @click="addMaster('stations')">新增服务站</el-button>
      <el-button v-if="canWrite" @click="template">导入模板</el-button>
      <el-upload v-if="canWrite" :show-file-list="false" :http-request="upload" accept=".xlsx"><el-button :loading="importing">导入 Excel</el-button></el-upload>
    </div>
    <el-table :data="rows" stripe @selection-change="(v:any[])=>selectedRows=v">
      <el-table-column v-if="canWrite" type="selection" width="42"/>
      <el-table-column prop="employee_no" label="工号" width="110"/>
      <el-table-column prop="name" label="姓名" width="100"/>
      <el-table-column prop="batch_name" label="批次" width="100"/>
      <el-table-column prop="station_name" label="服务站" width="120"/>
      <el-table-column prop="mentor_name" label="导师" width="110"/>
      <el-table-column prop="school" label="毕业学校" min-width="140"/>
      <el-table-column prop="major" label="专业" min-width="120"/>
      <el-table-column prop="education" label="学历" width="90"/>
      <el-table-column prop="phone" label="手机号" width="130"/>
      <el-table-column prop="email" label="常用邮箱" min-width="160"/>
      <el-table-column v-if="canWrite" label="操作" width="90" fixed="right"><template #default="s"><el-button link type="primary" @click="openEdit(s.row)">编辑</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" style="margin-top:16px" @change="load"/>

    <el-dialog v-model="dialog" :title="editingId?'编辑员工':'新增员工'" width="760px">
      <el-form label-position="top"><div class="form-grid">
        <el-form-item label="工号"><el-input v-model="form.employeeNo"/></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name"/></el-form-item>
        <el-form-item label="批次"><el-select v-model="form.batchId" clearable><el-option v-for="x in batches" :key="x.id" :label="x.name" :value="x.id"/></el-select></el-form-item>
        <el-form-item label="服务站"><el-select v-model="form.stationId" clearable><el-option v-for="x in stations" :key="x.id" :label="x.name" :value="x.id"/></el-select></el-form-item>
        <el-form-item label="导师"><el-select v-model="form.mentorUserId" clearable filterable><el-option v-for="x in mentors" :key="x.id" :label="x.display_name" :value="x.id"/></el-select></el-form-item>
        <el-form-item label="学历"><el-input v-model="form.education"/></el-form-item>
        <el-form-item label="学校"><el-input v-model="form.school"/></el-form-item>
        <el-form-item label="专业"><el-input v-model="form.major"/></el-form-item>
        <el-form-item label="出生日期"><el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD"/></el-form-item>
        <el-form-item label="入职日期"><el-date-picker v-model="form.onboardDate" type="date" value-format="YYYY-MM-DD"/></el-form-item>
        <el-form-item label="籍贯"><el-input v-model="form.nativePlace"/></el-form-item>
        <el-form-item label="常住地"><el-input v-model="form.residence"/></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone"/></el-form-item>
        <el-form-item label="常用邮箱"><el-input v-model="form.email"/></el-form-item>
      </div></el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="bindDialog" title="批量绑定导师" width="420px">
      <el-select v-model="bindMentorId" filterable placeholder="选择导师" style="width:100%"><el-option v-for="x in mentors" :key="x.id" :label="x.display_name" :value="x.id"/></el-select>
      <template #footer><el-button @click="bindDialog=false">取消</el-button><el-button type="primary" @click="bindMentor">确认绑定</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>.employees-filters>*{width:160px}.employees-filters .el-input{width:200px}</style>
