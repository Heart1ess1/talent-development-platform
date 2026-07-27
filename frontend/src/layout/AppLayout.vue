<script setup lang="ts">
import {computed} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {useAuthStore} from '@/stores/auth'

const auth=useAuthStore(),route=useRoute(),router=useRouter()
type MenuItem={to?:string;label:string;permission?:string;hideFor?:string[];children?:MenuItem[]}
const examChildren=computed<MenuItem[]>(()=>{
  if(auth.can('exam:manage'))return [
    {to:'/exams/plans',label:'考试计划'},
    {to:'/exams/results',label:'成绩管理'},
    {to:'/exams/papers',label:'试卷管理'},
    {to:'/exams/questions',label:'题库管理'}
  ]
  if(auth.user?.role==='EMPLOYEE')return [{to:'/exams/my',label:'我的考试'},{to:'/exams/results',label:'我的成绩'}]
  return [{to:'/exams/results',label:'考试成绩'}]
})
const menus=computed<MenuItem[]>(()=>[
  {to:'/dashboard',label:'进度概览'},
  {to:'/employees',label:'人员台账',permission:'employee:read',hideFor:['EMPLOYEE']},
  {to:'/employee-directory',label:'人员信息',permission:'employee:export'},
  {to:'/station-change-review',label:'调站审批',permission:'master:manage'},
  {to:'/courses',label:'课程与签到'},
  {to:'/training-plans',label:'培养计划',permission:'task:manage'},
  {to:'/tasks',label:'闯关任务'},
  {to:'/evaluation',label:'综合评价',permission:'evaluation:view'},
  {label:'考试中心',children:examChildren.value},
  {to:'/users',label:'账号管理',permission:'user:employee:manage'},
  {to:'/profile',label:auth.user?.role==='EMPLOYEE'?'个人信息':'个人设置'}
].filter(x=>(!x.permission||auth.can(x.permission))&&!x.hideFor?.includes(auth.user?.role||'')))
const mobileMenus=computed(()=>menus.value.filter(x=>x.to).slice(0,4))
function logout(){auth.logout();router.push('/login')}
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px" class="sidebar desktop-only">
      <div class="brand">人才培养平台</div>
      <el-menu :default-active="route.path" router>
        <template v-for="m in menus" :key="m.to||m.label">
          <el-sub-menu v-if="m.children?.length" :index="m.label">
            <template #title>{{m.label}}</template>
            <el-menu-item v-for="child in m.children" :key="child.to" :index="child.to">{{child.label}}</el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.to">{{m.label}}</el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header>
        <div class="mobile-brand">人才培养平台</div>
        <div class="user">{{auth.user?.displayName}} · {{auth.user?.role}} <el-button link type="primary" @click="logout">退出</el-button></div>
      </el-header>
      <el-main><router-view/></el-main>
      <el-footer class="mobile-nav"><router-link v-for="m in mobileMenus" :key="m.to" :to="m.to!">{{m.label}}</router-link></el-footer>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell{min-height:100vh}.sidebar{background:white;border-right:1px solid #e5e7eb}.brand,.mobile-brand{height:64px;display:flex;align-items:center;font-size:19px;font-weight:700;padding:0 22px;color:#1769aa}header{background:white;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;justify-content:flex-end}.mobile-brand{display:none}.user{font-size:14px}main{padding:0}.mobile-nav{display:none}@media(max-width:800px){header{justify-content:space-between}.mobile-brand{display:flex;padding:0}.mobile-nav{position:fixed;display:flex;bottom:0;left:0;right:0;background:#fff;height:54px;justify-content:space-around;align-items:center;border-top:1px solid #ddd;z-index:10}.mobile-nav a{font-size:12px;color:#606266;text-decoration:none}.mobile-nav a.router-link-active{color:#1769aa}main{padding-bottom:55px}}
</style>
