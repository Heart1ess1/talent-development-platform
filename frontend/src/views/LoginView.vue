<script setup lang="ts">
import {reactive,ref} from 'vue'
import type {FormInstance,FormRules} from 'element-plus'
import {Lock,User} from '@element-plus/icons-vue'
import {useRouter} from 'vue-router'
import {useAuthStore} from '@/stores/auth'
import ComplianceFooter from '@/components/ComplianceFooter.vue'
import talentGrowthImage from '@/assets/login/talent-growth-v2.webp'

/** 令牌失效被拦截器跳回登录页时，用一次性提示说明原因；提示保留到登录成功，避免页面二次跳转把它冲掉。 */
const authNotice=ref(sessionStorage.getItem('authNotice')||'')

const rememberedUsername=localStorage.getItem('rememberedUsername')||''
const formRef=ref<FormInstance>()
const form=reactive({username:rememberedUsername,password:''})
const rememberUsername=ref(Boolean(rememberedUsername))
const loading=ref(false),error=ref(''),capsLocked=ref(false)
const router=useRouter(),auth=useAuthStore()
const rules:FormRules={
  username:[{required:true,message:'请输入账号',trigger:'blur'}],
  password:[{required:true,message:'请输入密码',trigger:'blur'}]
}

function loginErrorMessage(e:unknown){
  const message=(e as any)?.response?.data?.message
  if((e as any)?.response?.status===401)return '用户名或密码不正确，请重新输入'
  return message||'暂时无法登录，请检查网络后重试'
}
function updateCapsLock(event:KeyboardEvent){capsLocked.value=event.getModifierState?.('CapsLock')??false}
async function submit(){
  if(loading.value)return
  error.value=''
  const valid=await formRef.value?.validate().catch(()=>false)
  if(!valid)return
  loading.value=true
  try{
    const username=form.username.trim()
    await auth.login(username,form.password)
    sessionStorage.removeItem('authNotice')
    authNotice.value=''
    if(rememberUsername.value)localStorage.setItem('rememberedUsername',username)
    else localStorage.removeItem('rememberedUsername')
    await router.push(auth.user?.mustChangePassword?'/profile':'/dashboard')
  }catch(e){
    error.value=loginErrorMessage(e)
  }finally{
    loading.value=false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="page-backdrop" aria-hidden="true">
      <span class="backdrop-orbit orbit-one"></span>
      <span class="backdrop-orbit orbit-two"></span>
      <svg class="backdrop-waves" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice">
        <defs>
          <linearGradient id="login-wave" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#b0b0b0" stop-opacity=".04"/>
            <stop offset="1" stop-color="#858585" stop-opacity=".16"/>
          </linearGradient>
        </defs>
        <path d="M-160 770C220 430 550 1040 1040 620S1570 470 1630 390V1000H-160Z" fill="url(#login-wave)"/>
        <path d="M-120 800C270 420 580 1070 1100 635S1590 520 1650 370" fill="none" stroke="#999" stroke-opacity=".25"/>
        <path d="M-120 845C290 480 600 1100 1150 700S1600 570 1650 450" fill="none" stroke="#fff" stroke-opacity=".75" stroke-width="2"/>
        <path d="M-130 160C280 400 870-120 1550 150" fill="none" stroke="#aaa" stroke-opacity=".22"/>
      </svg>
    </div>
    <section class="login-shell" aria-label="平台登录">
      <aside class="brand-panel" aria-labelledby="platform-name">
        <img class="brand-art" :src="talentGrowthImage" alt="" fetchpriority="high" width="1086" height="1448"/>
        <div class="brand-top">
          <div class="brand-mark" aria-hidden="true">
            <span></span><span></span><span></span>
          </div>
          <h1 id="platform-name">一人一画像人才培养平台</h1>
        </div>

      </aside>

      <section class="login-panel" aria-labelledby="login-title">
        <div class="mobile-brand">
          <div class="brand-mark" aria-hidden="true"><span></span><span></span><span></span></div>
          <strong>一人一画像人才培养平台</strong>
        </div>

        <div class="login-form-wrap">
          <header class="login-header">
            <h2 id="login-title">欢迎登录</h2>
          </header>

          <el-alert v-if="authNotice" class="login-error" :title="authNotice" type="warning" show-icon :closable="false"/>
          <el-alert v-if="error" class="login-error" :title="error" type="error" show-icon :closable="false"/>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" hide-required-asterisk @submit.prevent="submit">
            <el-form-item label="账号" prop="username" for="login-username">
              <el-input id="login-username" v-model.trim="form.username" size="large" autocomplete="username" placeholder="请输入账号" autofocus :prefix-icon="User"/>
            </el-form-item>
            <el-form-item label="密码" prop="password" for="login-password">
              <el-input id="login-password" v-model="form.password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入密码" :prefix-icon="Lock" @keydown="updateCapsLock" @keyup="updateCapsLock"/>
              <span v-if="capsLocked" class="caps-hint" role="status">大写锁定已开启</span>
            </el-form-item>

            <div class="login-options">
              <el-checkbox v-model="rememberUsername">记住账号</el-checkbox>
            </div>

            <el-button class="login-submit" type="primary" size="large" :loading="loading" native-type="submit">
              {{loading?'正在登录…':'登录'}}
            </el-button>
          </el-form>
        </div>

        <ComplianceFooter class="login-footer" compact/>
      </section>
    </section>
  </main>
</template>

<style scoped>
.login-page{
  position:relative;
  isolation:isolate;
  display:grid;
  min-height:100vh;
  min-height:100svh;
  padding:48px;
  place-items:center;
  background:radial-gradient(ellipse at 12% 8%,#e4e4e4 0,transparent 46%),linear-gradient(125deg,#ededed,#fafafa 52%,#e9e9e9);
  color:#292929;
}
.page-backdrop{position:absolute;z-index:-1;inset:0;overflow:hidden;pointer-events:none}
.page-backdrop:before{position:absolute;inset:0;background-image:linear-gradient(rgba(0,0,0,.045) 1px,transparent 1px),linear-gradient(90deg,rgba(0,0,0,.045) 1px,transparent 1px);background-size:48px 48px;mask-image:linear-gradient(120deg,#000,transparent 65%,#000);content:""}
.backdrop-waves{position:absolute;inset:0;width:100%;height:100%}
.backdrop-orbit{position:absolute;width:600px;height:600px;border:1px solid rgba(0,0,0,.16);border-radius:50%;box-shadow:0 0 0 56px rgba(217,0,27,.04),0 0 0 112px rgba(217,0,27,.025)}
.orbit-one{left:-310px;top:-350px}
.orbit-two{right:-350px;bottom:-390px;width:760px;height:760px}
.login-shell{
  position:relative;
  display:grid;
  width:min(1240px,100%);
  min-height:min(680px,calc(100svh - 96px));
  grid-template-columns:minmax(0,1.1fr) minmax(0,1fr);
  overflow:hidden;
  border:1px solid rgba(255,255,255,.9);
  border-radius:26px;
  background:#fff;
  box-shadow:0 28px 80px rgba(0,0,0,.16),0 4px 14px rgba(0,0,0,.04);
}
.brand-panel{
  position:relative;
  display:flex;
  min-width:0;
  padding:46px 40px;
  flex-direction:column;
  overflow:hidden;
  color:#fff;
  background:var(--app-primary);
}
.brand-art{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:50% 85%;filter:grayscale(1);mix-blend-mode:luminosity;opacity:.8}
.brand-panel:after{
  position:absolute;
  inset:0;
  background:linear-gradient(180deg,rgba(0,0,0,.24),transparent 30%);
  content:"";
  pointer-events:none;
}
.brand-top{position:relative;z-index:1;display:flex;align-items:center;gap:15px}
.brand-top h1{margin:0;font-size:clamp(24px,2vw,34px);font-weight:600;line-height:1.5;letter-spacing:.02em;text-shadow:0 2px 12px rgba(0,0,0,.16)}
.brand-mark{
  display:grid;
  width:50px;
  height:50px;
  padding:12px;
  flex:0 0 auto;
  grid-template-columns:repeat(3,minmax(0,1fr));
  align-items:end;
  gap:4px;
  border-radius:13px;
  background:rgba(255,255,255,.13);
  box-shadow:inset 0 0 0 1px rgba(255,255,255,.24);
}
.brand-mark span{border-radius:2px;background:#fff}
.brand-mark span:nth-child(1){height:42%;opacity:.65}
.brand-mark span:nth-child(2){height:72%;opacity:.82}
.brand-mark span:nth-child(3){height:100%}
.login-panel{position:relative;display:flex;min-width:0;padding:56px 56px 24px;flex-direction:column;gap:32px;background:linear-gradient(160deg,#fff 65%,#fafafa)}
.mobile-brand{display:none}
.login-form-wrap{width:100%;max-width:380px;margin:auto}
.login-header{margin-top:-28px;margin-bottom:40px}
.login-header:after{display:block;width:36px;height:4px;margin-top:16px;border-radius:4px;background:linear-gradient(90deg,var(--app-primary),var(--app-primary));content:""}
.login-header h2{margin:0;color:#292929;font-size:40px;font-weight:600;line-height:1.4;letter-spacing:.03em}
.login-error{margin-bottom:24px}
.login-panel :deep(.el-form-item){margin-bottom:28px}
.login-panel :deep(.el-form-item__label){height:auto;margin-bottom:12px;padding:0;color:#353535;font-size:22px;font-weight:600;line-height:1.5}
.login-panel :deep(.el-input){font-size:16px;--el-input-placeholder-color:#9d9d9d}
.login-panel :deep(.el-input__wrapper){min-height:56px;padding:0 16px;border-radius:10px;background:#fff;box-shadow:0 0 0 1px #e1e1e1 inset;transition:box-shadow .18s ease}
.login-panel :deep(.el-input__wrapper:hover){box-shadow:0 0 0 1px var(--app-primary) inset}
.login-panel :deep(.el-input__wrapper.is-focus){box-shadow:0 0 0 1.5px var(--app-primary) inset,0 0 0 4px rgba(217,0,27,.1)}
.login-panel :deep(.el-input__inner){height:54px;color:#353535}
.login-panel :deep(.el-input__prefix){margin-right:4px;color:#909090;font-size:22px}
.login-panel :deep(.el-input__password){color:#909090;font-size:20px}
.login-panel :deep(.el-form-item__error){padding-top:6px;font-size:14px}
.caps-hint{position:absolute;right:0;bottom:-22px;color:#a6650d;font-size:14px;line-height:1.5}
.login-options{display:flex;align-items:center;margin:-4px 0 26px}
.login-panel :deep(.el-checkbox){height:28px;--el-checkbox-checked-bg-color:var(--app-primary);--el-checkbox-checked-input-border-color:var(--app-primary)}
.login-panel :deep(.el-checkbox__inner){width:17px;height:17px;border-color:#c0c0c0;border-radius:4px}
.login-panel :deep(.el-checkbox__inner:after){top:2px;left:5px}
.login-panel :deep(.el-checkbox__label){padding-left:10px;color:#6c6c6c;font-size:16px}
.login-panel :deep(.el-checkbox__input.is-focus-visible .el-checkbox__inner){outline:3px solid rgba(217,0,27,.3);outline-offset:3px}
.login-submit{width:100%;height:56px;border:0;border-radius:10px;background:linear-gradient(105deg,var(--app-primary),var(--app-primary));font-size:20px;font-weight:600;letter-spacing:.16em;box-shadow:0 8px 20px rgba(217,0,27,.23);transition:filter .18s ease,box-shadow .18s ease}
.login-submit:hover{filter:brightness(1.06);box-shadow:0 10px 24px rgba(217,0,27,.3)}
.login-submit:focus-visible{outline:3px solid rgba(217,0,27,.4);outline-offset:4px}
.login-submit.is-loading{letter-spacing:.04em}
.login-footer{flex-shrink:0;color:#969696}
.login-panel :deep(.login-footer.compact){font-size:14px}
@media(min-width:1700px){
  .login-shell{width:1400px;min-height:min(760px,calc(100svh - 112px))}
  .brand-panel{padding:56px 48px}
  .brand-top h1{font-size:42px}
  .login-panel{padding:64px 72px 28px}
  .login-form-wrap{max-width:400px}
}
@media(max-width:1100px){
  .login-page{padding:28px}
  .login-shell{min-height:min(640px,calc(100svh - 56px));grid-template-columns:minmax(0,1fr) minmax(0,1fr)}
  .brand-panel{padding:40px 28px 30px}
  .brand-top{gap:12px}
  .brand-mark{width:42px;height:42px;padding:10px;gap:3px;border-radius:11px}
  .brand-top h1{font-size:34px;letter-spacing:0}
  .login-panel{padding:48px 36px 24px}
}
@media(max-width:900px){
  .login-page{padding:24px}
  .brand-art{object-position:64% 85%}
  .brand-panel{padding-right:22px;padding-left:22px}
  .brand-top{gap:10px}
  .brand-top h1{font-size:30px}
  .brand-mark{width:38px;height:38px;padding:9px}
  .login-panel{padding-right:28px;padding-left:28px}
  .login-header h2{font-size:36px}
}
@media(max-width:760px){
  .login-page{display:block;padding:16px 12px}
  .login-shell{display:block;width:100%;min-height:calc(100svh - 32px);border-radius:22px;box-shadow:0 16px 44px rgba(0,0,0,.1)}
  .brand-panel{display:none}
  .login-panel{min-height:calc(100svh - 34px);padding:28px 24px max(20px,env(safe-area-inset-bottom));gap:36px}
  .mobile-brand{display:flex;align-items:center;gap:10px;color:var(--app-primary)}
  .mobile-brand .brand-mark{width:40px;height:40px;padding:10px;background:var(--app-primary)}
  .mobile-brand strong{font-size:22px;font-weight:600;line-height:1.5}
  .login-form-wrap{margin:auto}
  .login-header{margin-top:0;margin-bottom:36px}
  .login-header h2{font-size:36px}
  .login-panel :deep(.login-footer.compact){font-size:14px}
}
@media(max-width:360px){
  .login-panel{padding-right:22px;padding-left:22px}
  .mobile-brand{gap:10px}
  .mobile-brand strong{font-size:20px}
  .mobile-brand .brand-mark{width:36px;height:36px;padding:9px}
}
@media(prefers-reduced-motion:reduce){
  .login-submit,.login-panel :deep(.el-input__wrapper){transition:none}
}
</style>
