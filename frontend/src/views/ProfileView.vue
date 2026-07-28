<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Delete,Upload} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {useAuthStore} from '@/stores/auth'
import {avatarUrl,nameInitial} from '@/utils/avatar'
import {roleLabel} from '@/utils/role'

const auth=useAuthStore()
const isEmployee=computed(()=>auth.user?.role==='EMPLOYEE')
const stations=ref<any[]>([])
const stationDialog=ref(false)
const selectedStationId=ref<number|null>(null)
const submitting=ref(false)
const myRequests=ref<any[]>([])
const form=reactive({oldPassword:'',newPassword:'',confirm:''})
const loading=ref(false),profileLoading=ref(false),avatarUploading=ref(false)
const profile=reactive<any>({
  employeeNo:'',name:'',batchName:'',businessUnitName:'',stationId:null,stationName:'',
  technicalMentorName:'',skillMentorName:'',
  onboardDate:null,status:'',phone:'',email:'',birthDate:null,nativePlace:'',
  residence:'',school:'',major:'',education:''
})
const availableStations=computed(()=>stations.value.filter(x=>x.enabled&&x.id!==profile.stationId))
const hasPendingRequest=computed(()=>myRequests.value.some(x=>x.status==='PENDING'))
const currentAvatar=computed(()=>avatarUrl(auth.user?.avatarToken))

async function loadProfile(){
  if(!isEmployee.value)return
  const r=await api.get<any,Envelope<any>>('/profile/employee')
  Object.assign(profile,{
    employeeNo:r.data.employee_no||'',name:r.data.name||'',batchName:r.data.batch_name||'',
    businessUnitName:r.data.business_unit_name||'',
    stationId:r.data.station_id??null,stationName:r.data.station_name||'',
    technicalMentorName:r.data.technical_mentor_name||'',
    skillMentorName:r.data.skill_mentor_name||'',
    onboardDate:r.data.onboard_date,status:r.data.status||'',
    phone:r.data.phone||'',email:r.data.email||'',birthDate:r.data.birth_date,
    nativePlace:r.data.native_place||'',residence:r.data.residence||'',
    school:r.data.school||'',major:r.data.major||'',education:r.data.education||''
  })
}
async function save(){
  if(form.newPassword!==form.confirm)return ElMessage.warning('两次密码不一致')
  loading.value=true
  try{
    await auth.changePassword(form.oldPassword,form.newPassword)
    ElMessage.success('密码已修改')
    Object.assign(form,{oldPassword:'',newPassword:'',confirm:''})
  }finally{loading.value=false}
}
async function saveProfile(){
  profileLoading.value=true
  try{
    await api.put('/profile/employee',profile)
    ElMessage.success('个人资料已保存')
  }finally{profileLoading.value=false}
}
function beforeAvatarUpload(file:File){
  if(!['image/jpeg','image/png'].includes(file.type)){
    ElMessage.warning('仅支持 JPG 或 PNG 格式的图片')
    return false
  }
  if(file.size>5*1024*1024){
    ElMessage.warning('图片大小不能超过 5MB')
    return false
  }
  return true
}
async function uploadAvatar(options:any){
  avatarUploading.value=true
  try{
    const data=new FormData()
    data.append('file',options.file)
    await api.post('/profile/avatar',data)
    await auth.refresh()
    profile.avatarToken=auth.user?.avatarToken||null
    ElMessage.success(isEmployee.value?'证件照已更新':'头像已更新')
    options.onSuccess?.({})
  }catch(error){
    options.onError?.(error)
  }finally{
    avatarUploading.value=false
  }
}
async function removeAvatar(){
  await ElMessageBox.confirm(
    isEmployee.value?'删除后，人员档案将恢复显示姓名首字头像。':'删除后将恢复显示姓名首字头像。',
    `确认删除${isEmployee.value?'证件照':'头像'}？`,
    {confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'}
  )
  await api.delete('/profile/avatar')
  await auth.refresh()
  profile.avatarToken=null
  ElMessage.success('照片已删除')
}
async function loadStations(){
  const r=await api.get<any,Envelope<any[]>>('/stations')
  stations.value=r.data
}
async function loadMyRequests(){
  const r=await api.get<any,Envelope<any[]>>('/station-change-requests',{params:{mine:true}})
  myRequests.value=r.data
}
async function submitStationChange(){
  if(!selectedStationId.value)return ElMessage.warning('请选择目标服务站')
  submitting.value=true
  try{
    await api.post('/station-change-requests',{stationId:selectedStationId.value})
    ElMessage.success('申请已提交，等待管理员审批')
    stationDialog.value=false
    selectedStationId.value=null
    await loadMyRequests()
  }finally{submitting.value=false}
}
function requestStatus(status:string){
  return ({
    PENDING:{label:'待审批',type:'warning'},
    APPROVED:{label:'已通过',type:'success'},
    REJECTED:{label:'已拒绝',type:'info'}
  } as any)[status]??{label:status,type:'info'}
}
function openStationDialog(){
  selectedStationId.value=null
  stationDialog.value=true
}
onMounted(async()=>{
  if(isEmployee.value)await Promise.all([loadProfile(),loadStations(),loadMyRequests()])
})
</script>

<template>
  <div class="page">
    <div class="page-head profile-head">
      <div>
        <h2>{{isEmployee?'个人资料':'账号设置'}}</h2>
        <p>{{isEmployee?'维护个人证件照、联系方式与登录密码':'管理当前账号的头像与登录密码'}}</p>
      </div>
    </div>
    <el-alert v-if="auth.user?.mustChangePassword" title="当前使用临时密码，修改后才能使用其他功能" type="warning" show-icon :closable="false"/>
    <el-row :gutter="16" style="margin-top:16px">
      <el-col :md="isEmployee?12:24">
        <el-card>
          <template #header>当前账号</template>
          <div class="avatar-settings">
            <el-avatar :size="88" :src="currentAvatar" class="profile-avatar">
              {{nameInitial(auth.user?.displayName)}}
            </el-avatar>
            <div class="avatar-copy">
              <strong>{{isEmployee?'个人证件照':'账号头像'}}</strong>
              <p>
                {{isEmployee
                  ?'证件照由本人维护，并会同步作为右上角账号头像及人员档案照片。'
                  :'上传后将用于右上角账号头像及账号管理列表。'}}
              </p>
              <div class="avatar-actions">
                <el-upload
                  accept=".jpg,.jpeg,.png,image/jpeg,image/png"
                  :show-file-list="false"
                  :before-upload="beforeAvatarUpload"
                  :http-request="uploadAvatar"
                >
                  <el-button :icon="Upload" :loading="avatarUploading">
                    {{isEmployee
                      ?(currentAvatar?'更换证件照':'上传证件照')
                      :(currentAvatar?'更换头像':'上传头像')}}
                  </el-button>
                </el-upload>
                <el-button
                  v-if="currentAvatar"
                  :icon="Delete"
                  :disabled="avatarUploading"
                  text
                  type="danger"
                  @click="removeAvatar"
                >删除</el-button>
              </div>
              <span class="avatar-help">
                {{isEmployee
                  ?'JPG 或 PNG，最大 5MB；建议上传近期、正面、免冠且背景简洁的竖版照片。'
                  :'JPG 或 PNG，最大 5MB；建议使用主体清晰的正方形图片。'}}
              </span>
            </div>
          </div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="用户名">{{auth.user?.username}}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{auth.user?.displayName}}</el-descriptions-item>
            <el-descriptions-item label="角色">{{roleLabel(auth.user?.role)}}</el-descriptions-item>
          </el-descriptions>
          <el-divider>密码与安全</el-divider>
          <el-form label-position="top">
            <el-form-item label="原密码"><el-input v-model="form.oldPassword" type="password" show-password/></el-form-item>
            <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" show-password/></el-form-item>
            <el-form-item label="确认新密码"><el-input v-model="form.confirm" type="password" show-password/></el-form-item>
            <el-button type="primary" :loading="loading" @click="save">保存密码</el-button>
          </el-form>
        </el-card>
      </el-col>

      <el-col v-if="isEmployee" :md="12">
        <el-card>
          <template #header>工作信息</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="工号">{{profile.employeeNo||'-'}}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{profile.name||'-'}}</el-descriptions-item>
            <el-descriptions-item label="批次">{{profile.batchName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="所属板块">{{profile.businessUnitName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="服务站点">{{profile.stationName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="指导老师（技术）">{{profile.technicalMentorName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="指导老师（技能）">{{profile.skillMentorName||'-'}}</el-descriptions-item>
            <el-descriptions-item label="入职日期">{{profile.onboardDate||'-'}}</el-descriptions-item>
            <el-descriptions-item label="状态">{{profile.status||'-'}}</el-descriptions-item>
          </el-descriptions>
          <el-divider>服务站变更</el-divider>
          <div v-if="myRequests.length" class="request-list">
            <div v-for="req in myRequests" :key="req.id" class="request-row">
              <el-tag :type="requestStatus(req.status).type" size="small">{{requestStatus(req.status).label}}</el-tag>
              <span>申请变更至 {{req.requested_station_name}}</span>
              <span v-if="req.review_comment" class="muted">备注：{{req.review_comment}}</span>
            </div>
          </div>
          <el-button size="small" type="primary" :disabled="hasPendingRequest" @click="openStationDialog">申请变更服务站</el-button>
        </el-card>

        <el-card style="margin-top:16px">
          <template #header>个人资料</template>
          <el-form label-position="top">
            <div class="form-grid">
              <el-form-item label="联系方式"><el-input v-model="profile.phone"/></el-form-item>
              <el-form-item label="私人邮箱"><el-input v-model="profile.email"/></el-form-item>
              <el-form-item label="出生日期"><el-date-picker v-model="profile.birthDate" type="date" value-format="YYYY-MM-DD"/></el-form-item>
              <el-form-item label="学历"><el-input v-model="profile.education"/></el-form-item>
              <el-form-item label="毕业学校"><el-input v-model="profile.school"/></el-form-item>
              <el-form-item label="所学专业"><el-input v-model="profile.major"/></el-form-item>
              <el-form-item label="籍贯"><el-input v-model="profile.nativePlace"/></el-form-item>
              <el-form-item label="住址（公司）"><el-input v-model="profile.residence"/></el-form-item>
            </div>
            <el-button type="primary" :loading="profileLoading" @click="saveProfile">保存资料</el-button>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="stationDialog" title="申请变更服务站" width="400px">
      <el-form label-position="top">
        <el-form-item label="目标服务站">
          <el-select v-model="selectedStationId" placeholder="选择服务站" style="width:100%">
            <el-option v-for="station in availableStations" :key="station.id" :label="station.name" :value="station.id"/>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stationDialog=false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitStationChange">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.profile-head p{margin:6px 0 0;color:#8492a6;font-size:13px}
.avatar-settings{display:flex;align-items:center;gap:18px;padding:4px 2px 20px}
.profile-avatar{flex:0 0 auto;color:#2868c7;background:#e8f2fb;font-size:26px;font-weight:700}
.profile-avatar :deep(img){object-fit:cover}
.avatar-copy{min-width:0}
.avatar-copy>strong{display:block;color:#273348;font-size:14px}
.avatar-copy>p{margin:5px 0 10px;color:#7b8798;font-size:12px;line-height:1.6}
.avatar-actions{display:flex;align-items:center;gap:8px}
.avatar-help{display:block;margin-top:8px;color:#9aa4b3;font-size:11px}
.request-list{display:flex;flex-direction:column;gap:8px;margin-bottom:12px}
.request-row{display:flex;align-items:center;flex-wrap:wrap;gap:8px;font-size:13px}
@media(max-width:600px){
  .avatar-settings{align-items:flex-start}
  .profile-avatar{width:72px;height:72px}
}
</style>
