<script setup lang="ts">
import {reactive,ref} from 'vue';
import type {FormInstance,FormRules} from 'element-plus';
import {useRouter} from 'vue-router';
import {useAuthStore} from '@/stores/auth';

const formRef=ref<FormInstance>();
const form=reactive({username:'',password:''});
const loading=ref(false);
const error=ref('');
const router=useRouter();
const auth=useAuthStore();
const rules:FormRules={
  username:[{required:true,message:'请输入用户名',trigger:'blur'}],
  password:[{required:true,message:'请输入密码',trigger:'blur'}]
};

function loginErrorMessage(e:unknown){
  return (e as any)?.response?.data?.message||'登录失败，请检查用户名和密码';
}

async function submit(){
  if(loading.value)return;
  error.value='';
  const valid=await formRef.value?.validate().catch(()=>false);
  if(!valid)return;
  loading.value=true;
  try{
    await auth.login(form.username.trim(),form.password);
    router.push(auth.user?.mustChangePassword?'/profile':'/dashboard');
  }catch(e){
    error.value=loginErrorMessage(e);
  }finally{
    loading.value=false;
  }
}
</script>
<template>
  <div class="login">
    <el-card class="box">
      <h1>新员工培养管理平台</h1>
      <el-alert v-if="error" class="login-error" :title="error" type="error" show-icon :closable="false"/>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model.trim="form.username" autocomplete="username" placeholder="请输入用户名"/>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="请输入密码"/>
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" native-type="submit" style="width:100%">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>
<style scoped>
.login{min-height:100vh;display:grid;place-items:center;background:linear-gradient(135deg,#0c4a6e,#38bdf8)}
.box{width:min(420px,calc(100vw - 28px));padding:20px}
.box h1{margin-bottom:4px;font-size:24px}
.login-error{margin:16px 0}
</style>
