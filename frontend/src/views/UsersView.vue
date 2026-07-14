<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue';
import {api,type Envelope} from '@/api';
import {ElMessage,ElMessageBox} from 'element-plus';
import {useAuthStore} from '@/stores/auth';

const auth=useAuthStore(),canOps=computed(()=>auth.can('user:ops-role:manage')),superAdmin=computed(()=>auth.can('user:admin:manage'));
const rows=ref<any[]>([]),stations=ref<any[]>([]),dialog=ref(false),scopeDialog=ref(false),scopeUser=ref<any>(null);
const form=reactive<any>({username:'',displayName:'',role:'MENTOR',stationIds:[] as number[]});
const scopeForm=reactive<{stationIds:number[]}>({stationIds:[]});
const createRoles=computed(()=>superAdmin.value?['MENTOR','STATION_MANAGER','TRAINING_ADMIN','ADMIN']:['MENTOR','STATION_MANAGER','TRAINING_ADMIN']);
const roleLabels:Record<string,string>={EMPLOYEE:'员工',MENTOR:'导师',STATION_MANAGER:'服务站负责人',TRAINING_ADMIN:'培训管理员',ADMIN:'管理员',SUPER_ADMIN:'超级管理员'};

async function load(){rows.value=(await api.get<any,Envelope<any[]>>('/users')).data;if(canOps.value||superAdmin.value)stations.value=(await api.get<any,Envelope<any[]>>('/stations')).data}
function openCreate(){Object.assign(form,{username:'',displayName:'',role:'MENTOR',stationIds:[]});dialog.value=true}
async function create(){const r=await api.post<any,Envelope<any>>('/users',form);dialog.value=false;await ElMessageBox.alert(`临时密码：${r.data.temporaryPassword}`,'账号已创建');load()}
async function reset(row:any){const r=await api.post<any,Envelope<any>>(`/users/${row.id}/reset-password`);await ElMessageBox.alert(`临时密码：${r.data.temporaryPassword}`,'密码已重置')}
async function toggle(row:any){await api.put(`/users/${row.id}/enabled`,{enabled:!row.enabled});ElMessage.success('状态已更新');load()}
async function changeRole(row:any,role:string){await api.put(`/users/${row.id}/role`,{role});ElMessage.success('角色已更新，旧令牌已失效');load()}
function stationIds(row:any){return String(row.station_ids||'').split(',').filter(Boolean).map((x:string)=>Number(x))}
function openScope(row:any){scopeUser.value=row;scopeForm.stationIds=stationIds(row);scopeDialog.value=true}
async function saveScope(){await api.put(`/users/${scopeUser.value.id}/stations`,{stationIds:scopeForm.stationIds});ElMessage.success('服务站范围已更新');scopeDialog.value=false;load()}
function canOperate(row:any){return row.role==='EMPLOYEE'||['MENTOR','STATION_MANAGER','TRAINING_ADMIN'].includes(row.role)&&canOps.value||superAdmin.value}
onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>账号管理</h2><el-button v-if="canOps||superAdmin" type="primary" @click="openCreate">创建账号</el-button></div>
    <el-table :data="rows">
      <el-table-column prop="username" label="用户名"/>
      <el-table-column prop="display_name" label="姓名"/>
      <el-table-column label="角色">
        <template #default="s">
          <el-select v-if="superAdmin&&s.row.id!==auth.user?.id&&s.row.role!=='STATION_MANAGER'" :model-value="s.row.role" @change="(v:any)=>changeRole(s.row,v)">
            <el-option v-for="r in ['MENTOR','TRAINING_ADMIN','ADMIN','SUPER_ADMIN']" :key="r" :value="r" :label="roleLabels[r]"/>
          </el-select>
          <span v-else>{{roleLabels[s.row.role]||s.row.role}}</span>
        </template>
      </el-table-column>
      <el-table-column label="服务站范围" min-width="160"><template #default="s">{{s.row.station_names||'-'}}</template></el-table-column>
      <el-table-column label="启用" width="90"><template #default="s">{{s.row.enabled?'是':'否'}}</template></el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="s">
          <el-button v-if="canOperate(s.row)" link @click="reset(s.row)">重置密码</el-button>
          <el-button v-if="canOperate(s.row)&&s.row.id!==auth.user?.id" link @click="toggle(s.row)">{{s.row.enabled?'停用':'启用'}}</el-button>
          <el-button v-if="s.row.role==='STATION_MANAGER'&&(canOps||superAdmin)" link @click="openScope(s.row)">服务站范围</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="创建账号" width="520px">
      <el-form label-position="top">
        <el-form-item label="用户名"><el-input v-model="form.username"/></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.displayName"/></el-form-item>
        <el-form-item label="角色"><el-select v-model="form.role" style="width:100%"><el-option v-for="r in createRoles" :key="r" :label="roleLabels[r]" :value="r"/></el-select></el-form-item>
        <el-form-item v-if="form.role==='STATION_MANAGER'" label="负责服务站"><el-select v-model="form.stationIds" multiple style="width:100%"><el-option v-for="x in stations" :key="x.id" :label="x.name" :value="x.id"/></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="scopeDialog" title="服务站范围" width="520px">
      <el-select v-model="scopeForm.stationIds" multiple style="width:100%"><el-option v-for="x in stations" :key="x.id" :label="x.name" :value="x.id"/></el-select>
      <template #footer><el-button @click="scopeDialog=false">取消</el-button><el-button type="primary" @click="saveScope">保存</el-button></template>
    </el-dialog>
  </div>
</template>
