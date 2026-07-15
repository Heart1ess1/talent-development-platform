<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue';
import {api,type Envelope} from '@/api';
import {ElMessage,ElMessageBox} from 'element-plus';
import {useAuthStore} from '@/stores/auth';

const auth=useAuthStore(),canOps=computed(()=>auth.can('user:ops-role:manage')),superAdmin=computed(()=>auth.can('user:admin:manage'));
const rows=ref<any[]>([]),stations=ref<any[]>([]),dialog=ref(false),scopeDialog=ref(false),passwordDialog=ref(false),scopeUser=ref<any>(null);
const form=reactive<any>({username:'',displayName:'',role:'MENTOR',stationIds:[] as number[]});
const scopeForm=reactive<{stationIds:number[]}>({stationIds:[]});
const passwordResult=reactive({title:'',password:''});
const createRoles=computed(()=>superAdmin.value?['MENTOR','STATION_MANAGER','TRAINING_ADMIN','ADMIN']:['MENTOR','STATION_MANAGER','TRAINING_ADMIN']);
const changeRoles=['MENTOR','TRAINING_ADMIN','ADMIN','SUPER_ADMIN'];
const roleLabels:Record<string,string>={EMPLOYEE:'员工',MENTOR:'导师',STATION_MANAGER:'服务站负责人',TRAINING_ADMIN:'培训管理员',ADMIN:'管理员',SUPER_ADMIN:'超级管理员'};

async function load(){rows.value=(await api.get<any,Envelope<any[]>>('/users')).data;if(canOps.value||superAdmin.value)stations.value=(await api.get<any,Envelope<any[]>>('/stations')).data}
function openCreate(){Object.assign(form,{username:'',displayName:'',role:'MENTOR',stationIds:[]});dialog.value=true}
function showTemporaryPassword(title:string,password:string){Object.assign(passwordResult,{title,password});passwordDialog.value=true}
async function create(){const r=await api.post<any,Envelope<any>>('/users',form);dialog.value=false;showTemporaryPassword('账号已创建',r.data.temporaryPassword);load()}
async function reset(row:any){const r=await api.post<any,Envelope<any>>(`/users/${row.id}/reset-password`);showTemporaryPassword('密码已重置',r.data.temporaryPassword)}
async function copyTemporaryPassword(){try{if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(passwordResult.password);else{const input=document.createElement('textarea');input.value=passwordResult.password;input.style.position='fixed';input.style.opacity='0';document.body.appendChild(input);input.select();document.execCommand('copy');document.body.removeChild(input)}ElMessage.success('临时密码已复制')}catch{ElMessage.error('复制失败，请手动复制')}}
async function toggle(row:any){await api.put(`/users/${row.id}/enabled`,{enabled:!row.enabled});ElMessage.success('状态已更新');load()}
async function changeRole(row:any,role:string){await api.put(`/users/${row.id}/role`,{role});ElMessage.success('角色已更新，旧令牌已失效');load()}
async function changeDisplayName(row:any){const {value}=await ElMessageBox.prompt('请输入姓名','修改姓名',{inputValue:row.display_name,inputPattern:/\S/,inputErrorMessage:'姓名不能为空'});await api.put(`/users/${row.id}/display-name`,{displayName:value});ElMessage.success('姓名已更新');if(row.id===auth.user?.id)await auth.refresh();load()}
function stationIds(row:any){return String(row.station_ids||'').split(',').filter(Boolean).map((x:string)=>Number(x))}
function openScope(row:any){scopeUser.value=row;scopeForm.stationIds=stationIds(row);scopeDialog.value=true}
async function saveScope(){await api.put(`/users/${scopeUser.value.id}/stations`,{stationIds:scopeForm.stationIds});ElMessage.success('服务站范围已更新');scopeDialog.value=false;load()}
function canOperate(row:any){return row.role==='EMPLOYEE'||['MENTOR','STATION_MANAGER','TRAINING_ADMIN'].includes(row.role)&&canOps.value||superAdmin.value}
function isEmployeeAccount(row:any){return row.has_employee_profile||row.role==='EMPLOYEE'}
function canChangeRole(row:any){return superAdmin.value&&row.id!==auth.user?.id&&!isEmployeeAccount(row)&&row.role!=='STATION_MANAGER'}
function canChangeDisplayName(){return superAdmin.value}
function needsEmployeeRoleRepair(row:any){return superAdmin.value&&row.id!==auth.user?.id&&row.has_employee_profile&&row.role!=='EMPLOYEE'}
onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>账号管理</h2><el-button v-if="canOps||superAdmin" type="primary" @click="openCreate">创建账号</el-button></div>
    <el-table :data="rows">
      <el-table-column prop="username" label="用户名"/>
      <el-table-column label="姓名">
        <template #default="s">
          <span>{{s.row.display_name}}</span>
          <el-button v-if="canChangeDisplayName()" link type="primary" @click="changeDisplayName(s.row)">修改</el-button>
        </template>
      </el-table-column>
      <el-table-column label="角色">
        <template #default="s">
          <el-button v-if="needsEmployeeRoleRepair(s.row)" link type="warning" @click="changeRole(s.row,'EMPLOYEE')">恢复为员工</el-button>
          <el-select v-else-if="canChangeRole(s.row)" :model-value="s.row.role" @change="(v:any)=>changeRole(s.row,v)">
            <el-option v-for="r in changeRoles" :key="r" :value="r" :label="roleLabels[r]"/>
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

    <el-dialog v-model="passwordDialog" :title="passwordResult.title" width="460px">
      <div class="temporary-password">
        <span class="temporary-password-label">临时密码</span>
        <code>{{passwordResult.password}}</code>
      </div>
      <template #footer>
        <el-button @click="passwordDialog=false">关闭</el-button>
        <el-button type="primary" @click="copyTemporaryPassword">复制密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.temporary-password{display:flex;align-items:center;gap:12px;min-height:40px}
.temporary-password-label{color:var(--el-text-color-secondary)}
.temporary-password code{flex:1;padding:10px 12px;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-fill-color-light);font-size:15px;word-break:break-all}
</style>
