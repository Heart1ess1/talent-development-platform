<script setup lang="ts">
import {computed} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ArrowDown,Lock,SwitchButton} from '@element-plus/icons-vue'
import {useAuthStore} from '@/stores/auth'
import {avatarUrl,nameInitial} from '@/utils/avatar'
import {roleLabel as formatRoleLabel} from '@/utils/role'
import ComplianceFooter from '@/components/ComplianceFooter.vue'

const auth=useAuthStore(),route=useRoute(),router=useRouter()
const userInitials=computed(()=>{
  const name=auth.user?.displayName?.trim()||auth.user?.username||'用户'
  return nameInitial(name)
})
const userAvatar=computed(()=>avatarUrl(auth.user?.avatarToken))
const roleLabel=computed(()=>formatRoleLabel(auth.user?.role))
const isEmployee=computed(()=>auth.user?.role==='EMPLOYEE')
const accountEntryTitle=computed(()=>isEmployee.value?'个人资料':'账号设置')
const accountEntryDescription=computed(()=>isEmployee.value
  ?'修改证件照、个人资料与登录密码'
  :'修改头像与登录密码'
)
type MenuItem={to?:string;label:string;permission?:string;children?:MenuItem[]}
const examChildren=computed<MenuItem[]>(()=>{
  const children:MenuItem[]=auth.can('exam:manage')
    ?[
      {to:'/exams/questions',label:'题库管理'},
      {to:'/exams/papers',label:'试卷管理'},
      {to:'/exams/plans',label:'考试计划'},
      {to:'/exams/results',label:'成绩管理'}
    ]
    :[{to:'/exams/results',label:'考试成绩'}]
  if(auth.can('history:import'))children.push({to:'/exams/results/history-imports',label:'历史成绩导入'})
  return children
})
const courseChildren=computed<MenuItem[]>(()=>{
  if(isEmployee.value)return [
    {to:'/courses/my',label:'我的课程'},
    {to:'/courses/learning',label:'课件学习'},
    {to:'/courses/attendance',label:'签到记录'}
  ]
  const children:MenuItem[]=[]
  if(auth.can('course:manage'))children.push(
    {to:'/courses/manage',label:'课程库'},
    {to:'/courses/sessions',label:'场次安排'},
    {to:'/courses/materials',label:'课件管理'}
  )
  children.push({to:'/courses/attendance',label:auth.can('attendance:manage')?'签到管理':'签到记录'})
  return children
})
const personnelChildren=computed<MenuItem[]>(()=>{
  const children:MenuItem[]=[
    {to:'/employee-directory',label:'人员台账'},
    {to:'/location-reports',label:'人员流动'}
  ]
  if(auth.can('master:manage'))children.push({to:'/station-change-review',label:'调站审批'})
  if(auth.can('user:ops-role:manage'))children.push({to:'/station-responsibilities',label:'站长与区域经理管理'})
  if(auth.can('master:manage'))children.push({to:'/dictionaries',label:'字典值管理'})
  return children
})
const evaluationChildren=computed<MenuItem[]>(()=>{
  if(isEmployee.value)return [{to:'/evaluation/results',label:'我的评价'}]
  const children:MenuItem[]=[
    {to:'/evaluation/workbench',label:'评价工作台'}
  ]
  if(auth.can('evaluation:manage'))children.push({to:'/evaluation/templates',label:'评价模板'})
  if(auth.can('evaluation:manage'))children.push({to:'/evaluation/assignments',label:'评分任务'})
  if(auth.can('evaluation:submit'))children.push({to:'/evaluation/my-tasks',label:'我的评分任务'})
  children.push({to:'/evaluation/monthly',label:'月度评分'})
  if(auth.can('evaluation:manage'))children.push({to:'/evaluation/defenses',label:'答辩评价'},{to:'/evaluation/revisions',label:'关联修订审核'})
  children.push({to:'/evaluation/results',label:'结果中心'})
  if(auth.can('history:import'))children.push({to:'/evaluation/results/history-imports',label:'历史评价导入'})
  return children
})
const menus=computed<MenuItem[]>(()=>{
  if(['AREA_MANAGER','TRAINING_DIRECTOR'].includes(auth.user?.role||''))return [
    {to:'/dashboard',label:'进度概览'},
    {label:'人员管理',children:personnelChildren.value},
    {to:'/leave-requests',label:'请假审批'}
  ]
  return [
  {to:'/dashboard',label:'进度概览'},
  ...(isEmployee.value
    ?[{to:'/location-reports',label:'位置报备',permission:'employee:read'}]
    :[{label:'人员管理',permission:'employee:read',children:personnelChildren.value}]
  ),
  ...(isEmployee.value||auth.can('leave:view')?[{to:'/leave-requests',label:isEmployee.value?'我的请假':'请假审批'}]:[]),
  {label:isEmployee.value?'课程学习':'课程管理',children:courseChildren.value},
  ...(isEmployee.value
    ?[{to:'/tasks',label:'我的任务'}]
    :[{label:'闯关任务',children:[
      ...(auth.can('task:manage')?[
        {to:'/training-plans/manage',label:'任务管理'},
        {to:'/training-plans/tasks',label:'任务编排'},
        {to:'/tasks',label:'任务下发'}
      ]:[]),
      {to:'/training-plans/tracking',label:'任务跟踪'},
      {to:'/task-scoring',label:'任务评分',permission:'task:score'}
    ]}]
  ),
  {label:'综合评价',permission:'evaluation:view',children:evaluationChildren.value},
  ...(isEmployee.value
    ?[{to:'/exams/my',label:'我的考试'}]
    :[{label:'考试中心',children:examChildren.value}]
  ),
  {to:'/users',label:'账号管理',permission:'user:employee:manage'}
].filter(x=>!x.permission||auth.can(x.permission))})
const activeMenuGroups=computed(()=>menus.value
  .filter(item=>item.children?.some(child=>child.to===route.path))
  .map(item=>item.label)
)
const mobileMenus=computed(()=>{
  if(isEmployee.value)return menus.value.filter(item=>item.to).slice(0,4)
  return menus.value.flatMap(item=>item.children?.length
    ?item.children
    :item.to?[item]:[]
  ).slice(0,4)
})
function logout(){auth.logout();router.push('/login')}
function handleUserCommand(command:string){
  if(command==='settings')router.push('/profile')
  if(command==='logout')logout()
}
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px" class="sidebar desktop-only">
      <div class="brand">人才培养平台</div>
      <el-menu :default-active="route.path" :default-openeds="activeMenuGroups" router>
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
        <div class="mobile-brand">一人一画像人才培养平台</div>
        <el-dropdown trigger="click" placement="bottom-end" @command="handleUserCommand">
          <button class="profile-trigger" type="button" aria-label="打开个人账号菜单">
            <el-avatar :size="36" :src="userAvatar" class="header-avatar">{{userInitials}}</el-avatar>
            <span class="user-copy">
              <strong>{{auth.user?.displayName}}</strong>
              <span>{{roleLabel}}</span>
            </span>
            <el-icon class="dropdown-arrow"><ArrowDown/></el-icon>
            <span v-if="auth.user?.mustChangePassword" class="security-dot" title="需要修改密码"></span>
          </button>
          <template #dropdown>
            <el-dropdown-menu class="account-menu">
              <div class="account-menu-head">
                <el-avatar :size="40" :src="userAvatar" class="header-avatar">{{userInitials}}</el-avatar>
                <div>
                  <strong>{{auth.user?.displayName}}</strong>
                  <span>@{{auth.user?.username}} · {{roleLabel}}</span>
                </div>
              </div>
              <el-dropdown-item command="settings" :icon="Lock">
                <div class="account-menu-item">
                  <strong>{{accountEntryTitle}}</strong>
                  <span>{{accountEntryDescription}}</span>
                </div>
              </el-dropdown-item>
              <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main><router-view/></el-main>
      <ComplianceFooter class="app-compliance"/>
      <el-footer class="mobile-nav"><router-link v-for="m in mobileMenus" :key="m.to" :to="m.to!">{{m.label}}</router-link></el-footer>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell{min-height:100vh}
.sidebar{background:#f7f7f7;border-right:1px solid var(--app-border)}
.brand,.mobile-brand{height:64px;display:flex;align-items:center;font-size:20px;font-weight:700;padding:0 22px;color:var(--app-primary)}
header{height:64px;padding:0 22px;background:var(--app-primary);color:#fff;display:flex;align-items:center;justify-content:space-between}
.brand{background:var(--app-primary);color:#fff}
.mobile-brand{padding:0;color:#fff}
.sidebar :deep(.el-menu){border-right:0;background:transparent;--el-menu-item-font-size:18px;--el-menu-text-color:#606060;--el-menu-hover-bg-color:#eeeeee}
.sidebar :deep(.el-menu-item),.sidebar :deep(.el-sub-menu__title){font-size:18px;font-weight:500}
.sidebar :deep(.el-sub-menu .el-menu-item){height:auto;min-height:50px;padding:12px 16px 12px 32px;line-height:1.5;white-space:normal}
.sidebar :deep(.el-menu-item.is-active){border-left:4px solid var(--app-primary);background:var(--app-primary-soft);color:var(--app-primary);font-weight:600}
.profile-trigger{position:relative;display:flex;align-items:center;gap:10px;min-height:46px;padding:5px 8px 5px 6px;border:0;border-radius:10px;color:inherit;background:transparent;font:inherit;text-align:left;cursor:pointer;transition:background .18s ease}
.profile-trigger:hover{background:rgba(255,255,255,.14)}
.profile-trigger:focus-visible{background:rgba(255,255,255,.14);outline:2px solid #fff;outline-offset:2px}
.header-avatar{color:var(--app-primary);background:linear-gradient(145deg,var(--app-primary-soft),var(--app-primary-soft));font-size:14px;font-weight:700}
.header-avatar :deep(img){object-fit:cover}
.user-copy{display:flex;flex-direction:column;gap:2px;min-width:80px}
.user-copy strong{max-width:150px;overflow:hidden;color:#fff;font-size:14px;font-weight:600;text-overflow:ellipsis;white-space:nowrap}
.user-copy>span{color:rgba(255,255,255,.8);font-size:14px}
.dropdown-arrow{color:rgba(255,255,255,.8);font-size:14px;transition:transform .18s ease}
.profile-trigger:hover .dropdown-arrow{transform:translateY(1px)}
.security-dot{position:absolute;top:4px;left:34px;width:8px;height:8px;border:2px solid #fff;border-radius:50%;background:#e99a2b}
.account-menu{width:278px;padding:7px}
.account-menu-head{display:flex;align-items:center;gap:11px;padding:8px 9px 12px;margin-bottom:4px;border-bottom:1px solid #f0f0f0}
.account-menu-head>div{display:flex;flex-direction:column;gap:3px;min-width:0}
.account-menu-head strong{overflow:hidden;color:#313131;font-size:14px;text-overflow:ellipsis;white-space:nowrap}
.account-menu-head span{overflow:hidden;color:#959595;font-size:14px;text-overflow:ellipsis;white-space:nowrap}
.account-menu-item{display:flex;flex-direction:column;gap:2px;padding:3px 0}
.account-menu-item strong{color:#3f3f3f;font-size:14px;font-weight:500}
.account-menu-item span{color:#a1a1a1;font-size:14px}
main{padding:0}
.app-compliance{flex:0 0 auto}
.mobile-nav{display:none}
@media(max-width:800px){
  header{padding:0 12px;justify-content:space-between}
  .mobile-brand{display:flex;padding:0;font-size:16px;flex:1;min-width:0;white-space:nowrap}
  .profile-trigger{gap:7px;padding-right:5px}
  .user-copy{min-width:0}
  .user-copy strong{max-width:86px}
  .user-copy>span{display:none}
  .mobile-nav{position:fixed;display:flex;bottom:0;left:0;right:0;background:#fff;height:54px;justify-content:space-around;align-items:center;border-top:1px solid #ddd;z-index:10}
  .mobile-nav a{font-size:14px;color:#626262;text-decoration:none}
  .mobile-nav a.router-link-active{color:var(--app-primary)}
  main{padding-bottom:55px}
  .app-compliance{padding-bottom:68px}
}
</style>
