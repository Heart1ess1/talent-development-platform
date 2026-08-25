<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import type {FormInstance,FormRules} from 'element-plus'
import {ElMessage,ElMessageBox} from 'element-plus'
import {
  Check,
  CircleCheck,
  CircleClose,
  CopyDocument,
  EditPen,
  Key,
  Location,
  Lock,
  MoreFilled,
  Plus,
  Refresh,
  Search,
  UserFilled,
  User as UserIcon
} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {avatarUrl,nameInitial} from '@/utils/avatar'
import {ROLE_LABELS as roleLabels,roleLabel,type Role} from '@/utils/role'

type UserRow={
  id:number
  username:string
  display_name:string
  role:Role
  enabled:boolean
  must_change_password:boolean
  created_at?:string
  has_employee_profile?:boolean
  station_ids?:string
  station_names?:string
  avatar_token?:string
}
type Station={id:number;name:string;enabled?:boolean}

const auth=useAuthStore()
const canOps=computed(()=>auth.can('user:ops-role:manage'))
const superAdmin=computed(()=>auth.can('user:admin:manage'))

const rows=ref<UserRow[]>([])
const stations=ref<Station[]>([])
const loading=ref(false)
const saving=ref(false)
const dialog=ref(false)
const scopeDialog=ref(false)
const passwordDialog=ref(false)
const scopeUser=ref<UserRow|null>(null)
const formRef=ref<FormInstance>()
const page=ref(1)
const pageSize=ref(10)

const filters=reactive<{keyword:string;role:''|Role;enabled:''|'enabled'|'disabled'}>({
  keyword:'',
  role:'',
  enabled:''
})
const form=reactive<{username:string;displayName:string;role:Role;stationIds:number[]}>({
  username:'',
  displayName:'',
  role:'MENTOR',
  stationIds:[]
})
const scopeForm=reactive<{stationIds:number[]}>({stationIds:[]})
const passwordResult=reactive({title:'',password:'',description:''})

const roleDescriptions:Partial<Record<Role,string>>={
  MENTOR:'负责学员培养与任务指导',
  STATION_MANAGER:'管理指定服务站的人员与业务',
  TRAINING_ADMIN:'管理培训计划、课程与考试',
  ADMIN:'拥有平台级管理权限'
}
const createRoles=computed<Role[]>(()=>superAdmin.value
  ?['MENTOR','STATION_MANAGER','TRAINING_ADMIN','ADMIN']
  :['MENTOR','STATION_MANAGER','TRAINING_ADMIN']
)
const changeRoles:Role[]=['MENTOR','TRAINING_ADMIN','ADMIN','SUPER_ADMIN']
const formRules:FormRules={
  username:[
    {required:true,message:'请输入用户名',trigger:'blur'},
    {pattern:/^[A-Za-z0-9._-]+$/,message:'仅支持字母、数字、点、下划线和连字符',trigger:'blur'},
    {min:2,max:64,message:'长度应为 2–64 个字符',trigger:'blur'}
  ],
  displayName:[
    {required:true,message:'请输入姓名',trigger:'blur'},
    {min:2,max:40,message:'长度应为 2–40 个字符',trigger:'blur'}
  ],
  role:[{required:true,message:'请选择角色',trigger:'change'}],
  stationIds:[{
    validator:(_rule,value:number[],callback)=>{
      if(form.role==='STATION_MANAGER'&&!value.length)callback(new Error('请至少选择一个服务站'))
      else callback()
    },
    trigger:'change'
  }]
}

const stats=computed(()=>({
  total:rows.value.length,
  enabled:rows.value.filter(row=>row.enabled).length,
  admin:rows.value.filter(row=>['ADMIN','SUPER_ADMIN','TRAINING_ADMIN'].includes(row.role)).length,
  pending:rows.value.filter(row=>row.must_change_password).length
}))
const filteredRows=computed(()=>{
  const keyword=filters.keyword.trim().toLowerCase()
  return rows.value.filter(row=>{
    const matchesKeyword=!keyword
      || row.username.toLowerCase().includes(keyword)
      || row.display_name.toLowerCase().includes(keyword)
      || (row.station_names||'').toLowerCase().includes(keyword)
    const matchesRole=!filters.role||row.role===filters.role
    const matchesStatus=!filters.enabled
      || (filters.enabled==='enabled'&&row.enabled)
      || (filters.enabled==='disabled'&&!row.enabled)
    return matchesKeyword&&matchesRole&&matchesStatus
  })
})
const pagedRows=computed(()=>filteredRows.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
const hasFilters=computed(()=>Boolean(filters.keyword||filters.role||filters.enabled))

watch(filters,()=>{page.value=1})
watch(()=>form.role,role=>{
  if(role!=='STATION_MANAGER')form.stationIds=[]
  formRef.value?.clearValidate('stationIds')
})

async function load(){
  loading.value=true
  try{
    const userRequest=api.get<any,Envelope<UserRow[]>>('/users')
    const stationRequest=(canOps.value||superAdmin.value)
      ?api.get<any,Envelope<Station[]>>('/stations')
      :Promise.resolve(null)
    const [userResponse,stationResponse]=await Promise.all([userRequest,stationRequest])
    rows.value=userResponse.data
    if(stationResponse)stations.value=stationResponse.data.filter(station=>station.enabled!==false)
    const maxPage=Math.max(1,Math.ceil(filteredRows.value.length/pageSize.value))
    if(page.value>maxPage)page.value=maxPage
  }finally{
    loading.value=false
  }
}

function resetFilters(){
  Object.assign(filters,{keyword:'',role:'',enabled:''})
}

function openCreate(){
  Object.assign(form,{username:'',displayName:'',role:'MENTOR',stationIds:[]})
  dialog.value=true
  requestAnimationFrame(()=>formRef.value?.clearValidate())
}

function showTemporaryPassword(title:string,password:string,description:string){
  Object.assign(passwordResult,{title,password,description})
  passwordDialog.value=true
}

async function create(){
  if(!await formRef.value?.validate().catch(()=>false))return
  saving.value=true
  try{
    const response=await api.post<any,Envelope<{temporaryPassword:string}>>('/users',{
      ...form,
      username:form.username.trim(),
      displayName:form.displayName.trim()
    })
    dialog.value=false
    showTemporaryPassword(
      '账号创建成功',
      response.data.temporaryPassword,
      `请将临时密码安全地发送给 ${form.displayName.trim()}。首次登录后系统会要求修改密码。`
    )
    await load()
  }finally{
    saving.value=false
  }
}

async function resetPassword(row:UserRow){
  await ElMessageBox.confirm(
    `重置后，${row.display_name} 的当前登录状态将失效，并需要使用新临时密码登录。`,
    '确认重置密码？',
    {confirmButtonText:'确认重置',cancelButtonText:'取消',type:'warning'}
  )
  const response=await api.post<any,Envelope<{temporaryPassword:string}>>(`/users/${row.id}/reset-password`)
  showTemporaryPassword(
    '密码重置成功',
    response.data.temporaryPassword,
    `请将新密码安全地发送给 ${row.display_name}。首次登录后必须修改密码，此密码关闭后将不再显示。`
  )
  await load()
}

async function copyTemporaryPassword(){
  try{
    if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(passwordResult.password)
    else{
      const input=document.createElement('textarea')
      input.value=passwordResult.password
      input.style.position='fixed'
      input.style.opacity='0'
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    ElMessage.success('临时密码已复制')
  }catch{
    ElMessage.error('复制失败，请手动复制')
  }
}

async function toggle(row:UserRow){
  const enabling=!row.enabled
  await ElMessageBox.confirm(
    enabling
      ?`启用后，${row.display_name} 将可以重新登录平台。`
      :`停用后，${row.display_name} 将立即无法登录平台。已有登录状态也会失效。`,
    `确认${enabling?'启用':'停用'}该账号？`,
    {
      confirmButtonText:enabling?'确认启用':'确认停用',
      cancelButtonText:'取消',
      type:enabling?'info':'warning'
    }
  )
  await api.put(`/users/${row.id}/enabled`,{enabled:enabling})
  ElMessage.success(`账号已${enabling?'启用':'停用'}`)
  await load()
}

async function changeRole(row:UserRow,role:Role){
  if(role===row.role)return
  await ElMessageBox.confirm(
    `将 ${row.display_name} 的角色从“${roleLabels[row.role]}”调整为“${roleLabels[role]}”。调整后原登录状态将失效。`,
    '确认变更角色？',
    {confirmButtonText:'确认变更',cancelButtonText:'取消',type:'warning'}
  )
  await api.put(`/users/${row.id}/role`,{role})
  ElMessage.success('角色已更新')
  await load()
}

async function changeDisplayName(row:UserRow){
  const {value}=await ElMessageBox.prompt(
    '姓名会同步显示在平台各业务模块中。',
    '修改姓名',
    {
      inputValue:row.display_name,
      inputPattern:/^\S(?:.*\S)?$/,
      inputErrorMessage:'请输入有效姓名',
      confirmButtonText:'保存',
      cancelButtonText:'取消'
    }
  )
  const displayName=value.trim()
  if(displayName===row.display_name)return
  await api.put(`/users/${row.id}/display-name`,{displayName})
  ElMessage.success('姓名已更新')
  if(row.id===auth.user?.id)await auth.refresh()
  await load()
}

async function changeUsername(row:UserRow){
  const {value}=await ElMessageBox.prompt(
    '仅支持字母、数字、点、下划线和连字符。修改后原登录状态会失效。',
    '修改用户名',
    {
      inputValue:row.username,
      inputPattern:/^[A-Za-z0-9._-]{2,64}$/,
      inputErrorMessage:'请输入 2–64 位合法用户名',
      confirmButtonText:'保存',
      cancelButtonText:'取消'
    }
  )
  const username=value.trim()
  if(username===row.username)return
  await api.put(`/users/${row.id}/username`,{username})
  ElMessage.success('用户名已更新')
  await load()
}

function stationIds(row:UserRow){
  return String(row.station_ids||'').split(',').filter(Boolean).map(value=>Number(value))
}

function openScope(row:UserRow){
  scopeUser.value=row
  scopeForm.stationIds=stationIds(row)
  scopeDialog.value=true
}

async function saveScope(){
  if(!scopeUser.value)return
  if(!scopeForm.stationIds.length){
    ElMessage.warning('请至少选择一个服务站')
    return
  }
  saving.value=true
  try{
    await api.put(`/users/${scopeUser.value.id}/stations`,{stationIds:scopeForm.stationIds})
    ElMessage.success('服务站范围已更新')
    scopeDialog.value=false
    await load()
  }finally{
    saving.value=false
  }
}

async function handleMore(command:string,row:UserRow){
  if(command==='displayName')await changeDisplayName(row)
  if(command==='username')await changeUsername(row)
  if(command==='scope')openScope(row)
  if(command==='toggle')await toggle(row)
}

function canOperate(row:UserRow){
  return row.role==='EMPLOYEE'
    || (['MENTOR','STATION_MANAGER','TRAINING_ADMIN'].includes(row.role)&&canOps.value)
    || superAdmin.value
}
function isEmployeeAccount(row:UserRow){
  return row.has_employee_profile||row.role==='EMPLOYEE'
}
function canChangeRole(row:UserRow){
  return superAdmin.value&&row.id!==auth.user?.id&&!isEmployeeAccount(row)&&row.role!=='STATION_MANAGER'
}
function canChangeUsername(row:UserRow){
  return superAdmin.value&&row.role!=='SUPER_ADMIN'
}
function needsEmployeeRoleRepair(row:UserRow){
  return superAdmin.value&&row.id!==auth.user?.id&&row.has_employee_profile&&row.role!=='EMPLOYEE'
}
function hasMoreActions(row:UserRow){
  return superAdmin.value
    || (row.role==='STATION_MANAGER'&&(canOps.value||superAdmin.value))
    || (canOperate(row)&&row.id!==auth.user?.id)
}
function initials(name:string){
  return nameInitial(name)
}
function formatDate(value?:string){
  if(!value)return '-'
  return value.replace('T',' ').slice(0,16)
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <section class="hero">
      <div>
        <div class="eyebrow">系统与权限</div>
        <h1>账号管理</h1>
        <p>集中维护全体人员的登录账号、角色权限与服务站数据范围。</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="canOps||superAdmin" type="primary" :icon="Plus" @click="openCreate">创建账号</el-button>
      </div>
    </section>

    <section class="metrics" aria-label="账号概览">
      <div class="metric-card">
        <div class="metric-icon total"><UserFilled/></div>
        <div><span>账号总数</span><strong>{{stats.total}}</strong></div>
      </div>
      <div class="metric-card">
        <div class="metric-icon active"><CircleCheck/></div>
        <div><span>正常启用</span><strong>{{stats.enabled}}</strong></div>
      </div>
      <div class="metric-card">
        <div class="metric-icon admin"><Lock/></div>
        <div><span>管理角色</span><strong>{{stats.admin}}</strong></div>
      </div>
      <div class="metric-card">
        <div class="metric-icon pending"><Key/></div>
        <div><span>待修改密码</span><strong>{{stats.pending}}</strong></div>
      </div>
    </section>

    <section class="content-card">
      <div class="card-heading">
        <div>
          <h2>账号列表</h2>
          <span>共 {{filteredRows.length}} 个{{hasFilters?'符合条件的':''}}账号</span>
        </div>
        <div class="filters">
          <el-input
            v-model="filters.keyword"
            class="search-input"
            clearable
            :prefix-icon="Search"
            placeholder="搜索姓名、用户名或服务站"
          />
          <el-select v-model="filters.role" clearable filterable placeholder="全部角色">
            <el-option v-for="(label,role) in roleLabels" :key="role" :label="label" :value="role"/>
          </el-select>
          <el-select v-model="filters.enabled" clearable filterable placeholder="全部状态">
            <el-option label="正常启用" value="enabled"/>
            <el-option label="已停用" value="disabled"/>
          </el-select>
          <el-button v-if="hasFilters" @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="pagedRows"
        row-key="id"
        class="account-table"
        :header-cell-style="{background:'#f8fafc',color:'#64748b',fontWeight:'600'}"
      >
        <el-table-column label="账号" min-width="220">
          <template #default="{row}">
            <div class="account-cell">
              <el-avatar
                :size="38"
                :src="avatarUrl(row.avatar_token)"
                :class="['user-avatar',`role-${row.role.toLowerCase()}`]"
              >{{initials(row.display_name)}}</el-avatar>
              <div class="account-copy">
                <div class="name-line">
                  <strong>{{row.display_name}}</strong>
                  <span v-if="row.id===auth.user?.id" class="self-badge">当前账号</span>
                </div>
                <span class="username">@{{row.username}}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="角色" min-width="150">
          <template #default="{row}">
            <div class="role-cell">
              <el-button
                v-if="needsEmployeeRoleRepair(row)"
                link
                type="warning"
                @click="changeRole(row,'EMPLOYEE')"
              >恢复为员工</el-button>
              <el-select
                v-else-if="canChangeRole(row)"
                class="role-select"
                :model-value="row.role"
                filterable
                @change="(value:Role)=>changeRole(row,value)"
              >
                <el-option v-for="role in changeRoles" :key="role" :value="role" :label="roleLabels[role]"/>
              </el-select>
              <span v-else :class="['role-badge',`role-${row.role.toLowerCase()}`]">{{roleLabel(row.role)}}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="服务站范围" min-width="190">
          <template #default="{row}">
            <div v-if="row.station_names" class="station-cell">
              <el-icon><Location/></el-icon>
              <el-tooltip :content="row.station_names" placement="top">
                <span>{{row.station_names}}</span>
              </el-tooltip>
            </div>
            <span v-else class="empty-value">—</span>
          </template>
        </el-table-column>

        <el-table-column label="安全状态" min-width="145">
          <template #default="{row}">
            <div class="security-cell">
              <span :class="['status-dot',row.enabled?'is-enabled':'is-disabled']"></span>
              <div>
                <strong>{{row.enabled?'正常启用':'已停用'}}</strong>
                <span v-if="row.must_change_password">待修改初始密码</span>
                <span v-else>账号状态正常</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" min-width="150">
          <template #default="{row}"><span class="date-text">{{formatDate(row.created_at)}}</span></template>
        </el-table-column>

        <el-table-column label="操作" fixed="right" width="168" align="right">
          <template #default="{row}">
            <div class="row-actions">
              <el-button v-if="canOperate(row)" link type="primary" :icon="Key" @click="resetPassword(row)">重置密码</el-button>
              <el-dropdown v-if="hasMoreActions(row)" trigger="click" @command="(command:string)=>handleMore(command,row)">
                <el-button class="more-button" text :icon="MoreFilled" aria-label="更多操作"/>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="superAdmin" command="displayName" :icon="EditPen">修改姓名</el-dropdown-item>
                    <el-dropdown-item v-if="canChangeUsername(row)" command="username" :icon="UserIcon">修改用户名</el-dropdown-item>
                    <el-dropdown-item
                      v-if="row.role==='STATION_MANAGER'&&(canOps||superAdmin)"
                      command="scope"
                      :icon="Location"
                    >服务站范围</el-dropdown-item>
                    <el-dropdown-item
                      v-if="canOperate(row)&&row.id!==auth.user?.id"
                      command="toggle"
                      :icon="row.enabled?CircleClose:CircleCheck"
                      :class="{'danger-item':row.enabled}"
                      divided
                    >{{row.enabled?'停用账号':'启用账号'}}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-state">
            <el-icon><Search/></el-icon>
            <strong>{{hasFilters?'没有找到匹配账号':'暂无账号数据'}}</strong>
            <span>{{hasFilters?'请尝试调整搜索词或筛选条件':'创建账号后将在这里统一管理'}}</span>
            <el-button v-if="hasFilters" type="primary" plain @click="resetFilters">清除筛选</el-button>
          </div>
        </template>
      </el-table>

      <div v-if="filteredRows.length" class="pagination-bar">
        <span>显示 {{(page-1)*pageSize+1}}–{{Math.min(page*pageSize,filteredRows.length)}} 条，共 {{filteredRows.length}} 条</span>
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10,20,50]"
          :total="filteredRows.length"
          layout="sizes, prev, pager, next"
          background
        />
      </div>
    </section>

    <el-dialog v-model="dialog" class="account-dialog" title="创建账号" width="560px" destroy-on-close>
      <div class="dialog-intro">
        <div class="intro-icon"><UserFilled/></div>
        <div><strong>新增平台账号</strong><span>创建后会自动生成一次性临时密码</span></div>
      </div>
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <div class="form-row">
          <el-form-item label="姓名" prop="displayName">
            <el-input v-model="form.displayName" maxlength="40" placeholder="请输入账号使用人姓名"/>
          </el-form-item>
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" maxlength="64" placeholder="如 zhangsan"/>
          </el-form-item>
        </div>
        <el-form-item label="账号角色" prop="role">
          <el-select v-model="form.role" class="full-width" filterable>
            <el-option v-for="role in createRoles" :key="role" :value="role" :label="roleLabels[role]">
              <div class="role-option">
                <strong>{{roleLabels[role]}}</strong>
                <span>{{roleDescriptions[role]}}</span>
              </div>
            </el-option>
          </el-select>
          <div class="field-help">{{roleDescriptions[form.role]}}</div>
        </el-form-item>
        <el-form-item v-if="form.role==='STATION_MANAGER'" label="负责服务站" prop="stationIds">
          <el-select
            v-model="form.stationIds"
            class="full-width"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="请选择负责的服务站"
          >
            <el-option v-for="station in stations" :key="station.id" :label="station.name" :value="station.id"/>
          </el-select>
          <div class="field-help">该账号仅能管理所选服务站范围内的数据</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">创建账号</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scopeDialog" class="account-dialog" title="设置服务站范围" width="520px">
      <div class="scope-user">
        <el-avatar :size="36">{{initials(scopeUser?.display_name||'')}}</el-avatar>
        <div><strong>{{scopeUser?.display_name}}</strong><span>@{{scopeUser?.username}}</span></div>
      </div>
      <label class="dialog-field-label">负责服务站</label>
      <el-select
        v-model="scopeForm.stationIds"
        class="full-width"
        multiple
        filterable
        collapse-tags
        collapse-tags-tooltip
        placeholder="请至少选择一个服务站"
      >
        <el-option v-for="station in stations" :key="station.id" :label="station.name" :value="station.id"/>
      </el-select>
      <p class="scope-tip">保存后，该账号的数据访问范围会立即更新。</p>
      <template #footer>
        <el-button @click="scopeDialog=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveScope">保存设置</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordDialog" class="password-dialog" :title="passwordResult.title" width="480px" :close-on-click-modal="false">
      <div class="success-mark"><el-icon><Check/></el-icon></div>
      <p class="password-description">{{passwordResult.description}}</p>
      <div class="temporary-password">
        <div>
          <span>临时密码</span>
          <code>{{passwordResult.password}}</code>
        </div>
        <el-button :icon="CopyDocument" @click="copyTemporaryPassword">复制</el-button>
      </div>
      <div class="security-notice"><el-icon><Lock/></el-icon><span>为保障账号安全，请勿通过公开群聊发送此密码。</span></div>
      <template #footer>
        <el-button type="primary" @click="passwordDialog=false">我已妥善保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.account-page{padding:28px 30px 40px;min-height:100%;background:#f5f7fb;color:#172033}
.hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin:0 auto 22px;max-width:1500px}
.eyebrow{margin-bottom:7px;color:#356bd8;font-size:12px;font-weight:700;letter-spacing:.12em}
.hero h1{margin:0;color:#172033;font-size:28px;line-height:1.25;letter-spacing:-.02em}
.hero p{margin:8px 0 0;color:#64748b;font-size:14px}
.hero-actions{display:flex;gap:10px;flex-shrink:0}
.hero-actions :deep(.el-button){height:38px;padding:0 17px;border-radius:9px}
.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;max-width:1500px;margin:0 auto 18px}
.metric-card{display:flex;align-items:center;gap:13px;padding:17px 18px;background:#fff;border:1px solid #e7ebf2;border-radius:12px;box-shadow:0 1px 2px rgba(15,23,42,.02)}
.metric-icon{display:flex;align-items:center;justify-content:center;width:42px;height:42px;border-radius:11px}
.metric-icon :deep(svg){width:20px;height:20px}
.metric-icon.total{color:#3468d4;background:#eef4ff}
.metric-icon.active{color:#14815e;background:#eaf8f2}
.metric-icon.admin{color:#7656c8;background:#f2effd}
.metric-icon.pending{color:#b46912;background:#fff5e6}
.metric-card>div:last-child{display:flex;flex-direction:column;gap:3px}
.metric-card span{color:#7a879b;font-size:12px}
.metric-card strong{color:#172033;font-size:22px;line-height:1.1}
.content-card{max-width:1500px;margin:0 auto;background:#fff;border:1px solid #e4e9f1;border-radius:14px;box-shadow:0 5px 18px rgba(32,51,82,.04);overflow:hidden}
.card-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;padding:20px 20px 16px}
.card-heading>div:first-child{display:flex;align-items:baseline;gap:10px;flex-shrink:0}
.card-heading h2{margin:0;color:#1e293b;font-size:17px}
.card-heading span{color:#8a96a8;font-size:12px}
.filters{display:flex;align-items:center;justify-content:flex-end;gap:9px;min-width:0}
.filters .search-input{width:280px}
.filters :deep(.el-select){width:138px}
.filters :deep(.el-input__wrapper){min-height:36px;border-radius:8px;box-shadow:0 0 0 1px #dfe5ee inset}
.account-table{width:100%;border-top:1px solid #edf0f5}
.account-table :deep(th.el-table__cell){height:45px;padding:0}
.account-table :deep(td.el-table__cell){padding:13px 0;border-bottom-color:#edf0f4}
.account-table :deep(.el-table__row:hover>td.el-table__cell){background:#fafcff}
.account-cell{display:flex;align-items:center;gap:11px;min-width:0}
.user-avatar{flex:0 0 auto;border:1px solid rgba(52,104,212,.08);color:#3468d4;background:#eef4ff;font-size:12px;font-weight:700}
.user-avatar :deep(img){object-fit:cover}
.user-avatar.role-mentor,.role-badge.role-mentor{color:#28715b;background:#eaf8f2}
.user-avatar.role-station_manager,.role-badge.role-station_manager{color:#9a5e13;background:#fff3df}
.user-avatar.role-training_admin,.role-badge.role-training_admin{color:#6d50b5;background:#f1edfc}
.user-avatar.role-admin,.user-avatar.role-super_admin,.role-badge.role-admin,.role-badge.role-super_admin{color:#b04444;background:#fff0f0}
.account-copy{display:flex;flex-direction:column;gap:3px;min-width:0}
.name-line{display:flex;align-items:center;gap:7px;min-width:0}
.name-line strong{overflow:hidden;color:#263247;font-size:14px;text-overflow:ellipsis;white-space:nowrap}
.username{overflow:hidden;color:#8a96a8;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;text-overflow:ellipsis;white-space:nowrap}
.self-badge{padding:2px 6px;border-radius:4px;color:#356bd8;background:#edf3ff;font-size:10px;white-space:nowrap}
.role-badge{display:inline-flex;align-items:center;padding:5px 9px;border-radius:6px;color:#3468d4;background:#eef4ff;font-size:12px;font-weight:600}
.role-badge.role-employee{color:#536174;background:#f0f3f7}
.role-select{width:130px}
.role-select :deep(.el-select__wrapper){min-height:30px;border-radius:7px}
.station-cell{display:flex;align-items:center;gap:6px;min-width:0;color:#526176}
.station-cell .el-icon{flex:0 0 auto;color:#8c9aae}
.station-cell span{overflow:hidden;max-width:220px;text-overflow:ellipsis;white-space:nowrap;font-size:13px}
.empty-value,.date-text{color:#99a4b5;font-size:12px}
.security-cell{display:flex;align-items:flex-start;gap:9px}
.status-dot{width:8px;height:8px;margin-top:5px;border-radius:50%;box-shadow:0 0 0 3px #e9f8f2}
.status-dot.is-enabled{background:#20a77a}
.status-dot.is-disabled{background:#aab3c0;box-shadow:0 0 0 3px #f0f2f5}
.security-cell>div{display:flex;flex-direction:column;gap:2px}
.security-cell strong{color:#344054;font-size:12px;font-weight:600}
.security-cell span:last-child{color:#99a4b5;font-size:11px}
.row-actions{display:flex;align-items:center;justify-content:flex-end;gap:2px}
.row-actions :deep(.el-button){font-size:12px}
.more-button{width:30px!important;height:30px!important;padding:0!important;color:#64748b}
.pagination-bar{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:16px 20px;border-top:1px solid #edf0f4}
.pagination-bar>span{color:#8a96a8;font-size:12px}
.empty-state{display:flex;flex-direction:column;align-items:center;gap:8px;padding:42px 0;color:#94a0b2}
.empty-state .el-icon{font-size:32px;color:#b5bfcd}
.empty-state strong{color:#556276;font-size:14px}
.empty-state span{font-size:12px}
.dialog-intro{display:flex;align-items:center;gap:11px;margin:-4px 0 20px;padding:13px 14px;border-radius:10px;background:#f6f8fc}
.intro-icon{display:flex;align-items:center;justify-content:center;width:36px;height:36px;border-radius:9px;color:#356bd8;background:#eaf1ff}
.intro-icon :deep(svg){width:18px}
.dialog-intro>div:last-child{display:flex;flex-direction:column;gap:2px}
.dialog-intro strong{color:#273348;font-size:13px}
.dialog-intro span{color:#8490a2;font-size:12px}
.form-row{display:grid;grid-template-columns:1fr 1fr;gap:14px}
.full-width{width:100%}
.field-help{margin-top:6px;color:#929daf;font-size:11px;line-height:1.5}
.role-option{display:flex;align-items:center;justify-content:space-between;gap:24px;width:100%}
.role-option span{color:#9aa4b3;font-size:11px}
.scope-user{display:flex;align-items:center;gap:10px;margin-bottom:20px;padding:12px;border:1px solid #e8ecf2;border-radius:9px;background:#fafbfc}
.scope-user>div{display:flex;flex-direction:column}
.scope-user strong{font-size:13px}
.scope-user span{color:#8d98a8;font-size:11px}
.dialog-field-label{display:block;margin-bottom:8px;color:#3d485a;font-size:13px;font-weight:600}
.scope-tip{margin:8px 0 0;color:#929daf;font-size:11px}
.success-mark{display:flex;align-items:center;justify-content:center;width:46px;height:46px;margin:0 auto 12px;border-radius:50%;color:#16805d;background:#e9f8f2}
.success-mark .el-icon{font-size:22px}
.password-description{max-width:390px;margin:0 auto 18px;color:#657287;font-size:13px;line-height:1.7;text-align:center}
.temporary-password{display:flex;align-items:center;gap:12px;padding:12px 14px;border:1px solid #dce3ed;border-radius:10px;background:#f8fafc}
.temporary-password>div{display:flex;flex:1;flex-direction:column;gap:5px;min-width:0}
.temporary-password span{color:#8995a8;font-size:11px}
.temporary-password code{overflow:hidden;color:#1f2b3e;font-size:17px;font-weight:700;letter-spacing:.06em;text-overflow:ellipsis;white-space:nowrap}
.security-notice{display:flex;align-items:flex-start;gap:7px;margin-top:12px;color:#a26a25;font-size:11px;line-height:1.5}
.security-notice .el-icon{flex:0 0 auto;margin-top:2px}
:deep(.account-dialog .el-dialog),:deep(.password-dialog .el-dialog){border-radius:14px}
:deep(.account-dialog .el-dialog__header),:deep(.password-dialog .el-dialog__header){padding-bottom:13px;border-bottom:1px solid #eef1f5}
:deep(.account-dialog .el-dialog__title),:deep(.password-dialog .el-dialog__title){color:#263247;font-size:16px;font-weight:700}
:deep(.account-dialog .el-dialog__footer),:deep(.password-dialog .el-dialog__footer){padding-top:14px;border-top:1px solid #eef1f5}
:deep(.account-dialog .el-form-item__label){color:#3d485a;font-size:12px;font-weight:600}
:deep(.account-dialog .el-input__wrapper),:deep(.account-dialog .el-select__wrapper){min-height:38px;border-radius:8px}
:global(.danger-item){color:#d14343!important}

@media(max-width:1100px){
  .metrics{grid-template-columns:repeat(2,minmax(0,1fr))}
  .card-heading{align-items:flex-start;flex-direction:column}
  .filters{justify-content:flex-start;width:100%;flex-wrap:wrap}
  .filters .search-input{flex:1;min-width:240px}
}
@media(max-width:700px){
  .account-page{padding:18px 12px 80px}
  .hero{align-items:flex-start;flex-direction:column}
  .hero-actions{width:100%}
  .hero-actions .el-button{flex:1}
  .hero h1{font-size:24px}
  .metrics{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}
  .metric-card{padding:13px 12px}
  .metric-icon{width:36px;height:36px}
  .metric-card strong{font-size:19px}
  .card-heading{padding:16px 14px}
  .filters{display:grid;grid-template-columns:1fr 1fr}
  .filters .search-input{grid-column:1/-1;width:100%;min-width:0}
  .filters :deep(.el-select){width:100%}
  .pagination-bar{align-items:flex-start;flex-direction:column;padding:14px}
  .pagination-bar :deep(.el-pagination__sizes){display:none}
  .form-row{grid-template-columns:1fr;gap:0}
  :deep(.account-dialog),:deep(.password-dialog){width:calc(100% - 24px)!important}
}
</style>
