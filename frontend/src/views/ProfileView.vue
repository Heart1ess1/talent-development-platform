<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue';
import {ElMessage} from 'element-plus';
import {api,type Envelope} from '@/api';
import {useAuthStore} from '@/stores/auth';

const auth=useAuthStore(),isEmployee=computed(()=>auth.user?.role==='EMPLOYEE');
const stations=ref<any[]>([]),stationDialog=ref(false),selectedStationId=ref<number|null>(null),submitting=ref(false);
const myRequests=ref<any[]>([]);
const form=reactive({oldPassword:'',newPassword:'',confirm:''}),loading=ref(false),profileLoading=ref(false);
const profile=reactive<any>({employeeNo:'',name:'',batchName:'',stationName:'',mentorName:'',onboardDate:null,status:'',phone:'',email:'',birthDate:null,nativePlace:'',residence:'',school:'',major:'',education:''});

async function loadProfile(){if(!isEmployee.value)return;const r=await api.get<any,Envelope<any>>('/profile/employee');Object.assign(profile,{employeeNo:r.data.employee_no||'',name:r.data.name||'',batchName:r.data.batch_name||'',stationName:r.data.station_name||'',mentorName:r.data.mentor_name||'',onboardDate:r.data.onboard_date,status:r.data.status||'',phone:r.data.phone||'',email:r.data.email||'',birthDate:r.data.birth_date,nativePlace:r.data.native_place||'',residence:r.data.residence||'',school:r.data.school||'',major:r.data.major||'',education:r.data.education||''})}
async function save(){if(form.newPassword!==form.confirm)return ElMessage.warning('两次密码不一致');loading.value=true;try{await auth.changePassword(form.oldPassword,form.newPassword);ElMessage.success('密码已修改');Object.assign(form,{oldPassword:'',newPassword:'',confirm:''})}finally{loading.value=false}}
async function saveProfile(){profileLoading.value=true;try{await api.put('/profile/employee',profile);ElMessage.success('个人资料已保存')}finally{profileLoading.value=false}}
async function loadStations(){const r=await api.get<any,Envelope<any[]>>('/stations');stations.value=r.data}
async function loadMyRequests(){const r=await api.get<any,Envelope<any[]>>('/station-change-requests',{params:{mine:true}});myRequests.value=r.data}
async function submitStationChange(){if(!selectedStationId.value)return;submitting.value=true;try{await api.post('/station-change-requests',{stationId:selectedStationId.value});ElMessage.success('申请已提交，等待管理员审批');stationDialog.value=false;selectedStationId.value=null;loadMyRequests()}finally{submitting.value=false}}
onMounted(()=>{loadProfile();if(isEmployee.value){loadStations();loadMyRequests()}});
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>{{isEmployee?'个人信息':'个人设置'}}</h2></div>
    <el-alert v-if="auth.user?.mustChangePassword" title="当前使用临时密码，修改后才能使用其他功能" type="warning" show-icon :closable="false"/>
    <el-row :gutter="16" style="margin-top:16px">
      <el-col :md="isEmployee?12:24">
        <el-card>
          <template #header>账号信息    <el-dialog v-model="stationDialog" title="申请变更服务站" width="400px">
      <el-form label-position="top">
        <el-form-item label="目标服务站">
          <el-select v-model="selectedStationId" placeholder="选择服务站" style="width:100%"><el-option v-for="s in stations" :key="s.id" :label="s.name" :value="s.id"/></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="stationDialog=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitStationChange">提交申请</el-button></template>
    </el-dialog>
</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="用户名">{{auth.user?.username}}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{auth.user?.displayName}}</el-descriptions-item>
            <el-descriptions-item label="角色">{{auth.user?.role}}</el-descriptions-item>
          </el-descriptions>
          <el-divider>修改密码</el-divider>
          <el-form label-position="top">
            <el-form-item label="原密码"><el-input v-model="form.oldPassword" type="password" show-password/></el-form-item>
            <el-form-item label="新密码（至少8位）"><el-input v-model="form.newPassword" type="password" show-password/></el-form-item>
            <el-form-item label="确认新密码"><el-input v-model="form.confirm" type="password" show-password/></el-form-item>
            <el-button type="primary" :loading="loading" @click="save">保存密码</el-button>
          </el-form>
        </el-card>
      </el-col>
      <el-col v-if="isEmployee" :md="12">
        <el-card>
          <template #header>工作信息    <el-dialog v-model="stationDialog" title="申请变更服务站" width="400px">
      <el-form label-position="top">
        <el-form-item label="目标服务站">
          <el-select v-model="selectedStationId" placeholder="选择服务站" style="width:100%"><el-option v-for="s in stations" :key="s.id" :label="s.name" :value="s.id"/></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="stationDialog=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitStationChange">提交申请</el-button></template>
    </el-dialog>
</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="工号">{{profile.employeeNo||'-'}}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{profile.name||'-'}}</el-descriptions-item>
            <el-descriptions-item label="批次">{{profile.batchName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="服务站">{{profile.stationName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="导师">{{profile.mentorName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="入职日期">{{profile.onboardDate||'-'}}</el-descriptions-item>
            <el-descriptions-item label="状态">{{profile.status||'-'}}</el-descriptions-item>
          </el-descriptions>
          <el-divider>服务站变更</el-divider>
          <div v-if="myRequests.length" style="margin-bottom:12px">
            <div v-for="req in myRequests" :key="req.id" style="margin-bottom:6px">
              <el-tag :type="req.status==='PENDING'?'warning':req.status==='APPROVED'?'success':'info'" size="small">{{req.status==='PENDING'?'待审批':req.status==='APPROVED'?'已通过':'已拒绝'}}</el-tag>
              <span style="margin-left:8px">变更至：{{req.requested_station_name}}<span v-if="req.reviewer_name" style="color:#999;margin-left:8px">｜审批人：{{req.reviewer_name}}</span><span v-if="req.review_comment" style="color:#999;margin-left:8px">｜备注：{{req.review_comment}}</span></span>
            </div>
          </div>
          <el-button size="small" type="primary" @click="loadStations();stationDialog=true" :disabled="myRequests.some(r=>r.status==='PENDING')">申请变更服务站</el-button>
        </el-card>
        <el-card style="margin-top:16px">
          <template #header>个人资料    <el-dialog v-model="stationDialog" title="申请变更服务站" width="400px">
      <el-form label-position="top">
        <el-form-item label="目标服务站">
          <el-select v-model="selectedStationId" placeholder="选择服务站" style="width:100%"><el-option v-for="s in stations" :key="s.id" :label="s.name" :value="s.id"/></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="stationDialog=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitStationChange">提交申请</el-button></template>
    </el-dialog>
</template>
          <el-form label-position="top">
            <div class="form-grid">
              <el-form-item label="手机号"><el-input v-model="profile.phone"/></el-form-item>
              <el-form-item label="常用邮箱"><el-input v-model="profile.email"/></el-form-item>
              <el-form-item label="出生日期"><el-date-picker v-model="profile.birthDate" type="date" value-format="YYYY-MM-DD"/></el-form-item>
              <el-form-item label="学历"><el-input v-model="profile.education"/></el-form-item>
              <el-form-item label="毕业学校"><el-input v-model="profile.school"/></el-form-item>
              <el-form-item label="专业"><el-input v-model="profile.major"/></el-form-item>
              <el-form-item label="籍贯"><el-input v-model="profile.nativePlace"/></el-form-item>
              <el-form-item label="常住地"><el-input v-model="profile.residence"/></el-form-item>
            </div>
            <el-button type="primary" :loading="profileLoading" @click="saveProfile">保存资料</el-button>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
    <el-dialog v-model="stationDialog" title="申请变更服务站" width="400px">
      <el-form label-position="top">
        <el-form-item label="目标服务站">
          <el-select v-model="selectedStationId" placeholder="选择服务站" style="width:100%"><el-option v-for="s in stations" :key="s.id" :label="s.name" :value="s.id"/></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="stationDialog=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitStationChange">提交申请</el-button></template>
    </el-dialog>
</template>
