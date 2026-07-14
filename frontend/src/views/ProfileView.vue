<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue';
import {ElMessage} from 'element-plus';
import {api,type Envelope} from '@/api';
import {useAuthStore} from '@/stores/auth';

const auth=useAuthStore(),isEmployee=computed(()=>auth.user?.role==='EMPLOYEE');
const form=reactive({oldPassword:'',newPassword:'',confirm:''}),loading=ref(false),profileLoading=ref(false);
const profile=reactive<any>({phone:'',email:'',birthDate:null,nativePlace:'',residence:'',school:'',major:'',education:''});

async function loadProfile(){if(!isEmployee.value)return;const r=await api.get<any,Envelope<any>>('/profile/employee');Object.assign(profile,{phone:r.data.phone||'',email:r.data.email||'',birthDate:r.data.birth_date,nativePlace:r.data.native_place||'',residence:r.data.residence||'',school:r.data.school||'',major:r.data.major||'',education:r.data.education||''})}
async function save(){if(form.newPassword!==form.confirm)return ElMessage.warning('两次密码不一致');loading.value=true;try{await auth.changePassword(form.oldPassword,form.newPassword);ElMessage.success('密码已修改');Object.assign(form,{oldPassword:'',newPassword:'',confirm:''})}finally{loading.value=false}}
async function saveProfile(){profileLoading.value=true;try{await api.put('/profile/employee',profile);ElMessage.success('个人资料已保存')}finally{profileLoading.value=false}}
onMounted(loadProfile);
</script>

<template>
  <div class="page">
    <div class="page-head"><h2>个人设置</h2></div>
    <el-alert v-if="auth.user?.mustChangePassword" title="当前使用临时密码，修改后才能使用其他功能" type="warning" show-icon :closable="false"/>
    <el-row :gutter="16" style="margin-top:16px">
      <el-col :md="isEmployee?12:24">
        <el-card>
          <template #header>账号信息</template>
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
          <template #header>个人资料</template>
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
</template>
