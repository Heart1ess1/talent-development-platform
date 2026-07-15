<script setup lang="ts">
import {computed} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {useAuthStore} from '@/stores/auth'

const auth=useAuthStore(),route=useRoute(),router=useRouter()
const menus=computed(()=>[
  {to:'/dashboard',label:'进度概览'},
  {to:'/employees',label:'人员台账',permission:'employee:read',hideFor:['EMPLOYEE']},
  {to:'/employee-directory',label:'人员信息',permission:'employee:export'},
  {to:'/courses',label:'课程与签到'},
  {to:'/training-plans',label:'培养计划',permission:'task:manage'},
  {to:'/tasks',label:'闯关任务'},
  {to:'/evaluation',label:'综合评价',permission:'evaluation:view'},
  {to:'/exams',label:'考试中心'},
  {to:'/users',label:'账号管理',permission:'user:employee:manage'},
  {to:'/profile',label:auth.user?.role==='EMPLOYEE'?'个人信息':'个人设置'}
].filter(x=>(!x.permission||auth.can(x.permission))&&!x.hideFor?.includes(auth.user?.role||'')))
function logout(){auth.logout();router.push('/login')}
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px" class="sidebar desktop-only">
      <div class="brand">人才培养平台</div>
      <el-menu :default-active="route.path" router>
        <el-menu-item v-for="m in menus" :key="m.to" :index="m.to">{{m.label}}</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header>
        <div class="mobile-brand">人才培养平台</div>
        <div class="user">{{auth.user?.displayName}} · {{auth.user?.role}} <el-button link type="primary" @click="logout">退出</el-button></div>
      </el-header>
      <el-main><router-view/></el-main>
      <el-footer class="mobile-nav"><router-link v-for="m in menus.slice(0,4)" :key="m.to" :to="m.to">{{m.label}}</router-link></el-footer>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell{min-height:100vh}.sidebar{background:white;border-right:1px solid #e5e7eb}.brand,.mobile-brand{height:64px;display:flex;align-items:center;font-size:19px;font-weight:700;padding:0 22px;color:#1769aa}header{background:white;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;justify-content:flex-end}.mobile-brand{display:none}.user{font-size:14px}main{padding:0}.mobile-nav{display:none}@media(max-width:800px){header{justify-content:space-between}.mobile-brand{display:flex;padding:0}.mobile-nav{position:fixed;display:flex;bottom:0;left:0;right:0;background:#fff;height:54px;justify-content:space-around;align-items:center;border-top:1px solid #ddd;z-index:10}.mobile-nav a{font-size:12px;color:#606266;text-decoration:none}.mobile-nav a.router-link-active{color:#1769aa}main{padding-bottom:55px}}
</style>
