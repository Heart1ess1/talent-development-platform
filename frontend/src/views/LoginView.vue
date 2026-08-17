<script setup lang="ts">
import {reactive,ref} from 'vue'
import type {FormInstance,FormRules} from 'element-plus'
import {ArrowRight,CircleCheck,Connection,DataAnalysis,Lock,User} from '@element-plus/icons-vue'
import {useRouter} from 'vue-router'
import {useAuthStore} from '@/stores/auth'
import ComplianceFooter from '@/components/ComplianceFooter.vue'

const rememberedUsername=localStorage.getItem('rememberedUsername')||''
const formRef=ref<FormInstance>()
const form=reactive({username:rememberedUsername,password:''})
const rememberUsername=ref(Boolean(rememberedUsername))
const loading=ref(false),error=ref(''),capsLocked=ref(false)
const router=useRouter(),auth=useAuthStore()
const rules:FormRules={
  username:[{required:true,message:'请输入用户名',trigger:'blur'}],
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
    <div class="login-decoration decoration-one"></div>
    <div class="login-decoration decoration-two"></div>
    <div class="background-scene" aria-hidden="true">
      <span class="scene-orbit orbit-one"></span>
      <span class="scene-orbit orbit-two"></span>
      <span class="scene-route route-one"><i></i><i></i><i></i></span>
      <span class="scene-route route-two"><i></i><i></i></span>
      <div class="scene-chip chip-one"><span></span><strong>培养路径</strong><small>LEARNING PATH</small></div>
      <div class="scene-chip chip-two"><span></span><strong>成长记录</strong><small>GROWTH RECORD</small></div>
      <div class="scene-caption"><strong>TALENT · DEVELOPMENT</strong><i></i><span>连接学习与成长的每一个关键节点</span></div>
    </div>

    <section class="login-shell">
      <aside class="brand-panel">
        <div class="brand-top">
          <div class="brand-mark" aria-hidden="true">
            <span></span><span></span><span></span>
          </div>
          <div><strong>人才培养平台</strong><small>TALENT DEVELOPMENT</small></div>
        </div>

        <div class="brand-content">
          <span class="brand-eyebrow">新员工培养数字化工作台</span>
          <h1>让每一步成长<br>清晰、协同、可追踪</h1>
          <p>连接人员、课程、培养任务与考试评价，为新员工成长提供一致、可靠的管理体验。</p>

          <div class="brand-features">
            <article><span><el-icon><Connection/></el-icon></span><div><strong>培养流程一体化</strong><small>从入职建档到培养评价统一协作</small></div></article>
            <article><span><el-icon><DataAnalysis/></el-icon></span><div><strong>进度结果可追踪</strong><small>关键节点、任务成果与考试成绩清晰沉淀</small></div></article>
            <article><span><el-icon><CircleCheck/></el-icon></span><div><strong>角色权限更安全</strong><small>按岗位职责和人员范围访问业务数据</small></div></article>
          </div>
        </div>

        <div class="brand-footer"><span>专业</span><i></i><span>高效</span><i></i><span>可靠</span></div>
      </aside>

      <section class="login-panel">
        <div class="mobile-brand">
          <div class="brand-mark"><span></span><span></span><span></span></div>
          <strong>人才培养平台</strong>
        </div>

        <div class="login-form-wrap">
          <header class="login-header">
            <span class="login-kicker">WELCOME BACK</span>
            <h2>欢迎登录</h2>
            <p>请输入平台账号，继续进入培养管理工作台。</p>
          </header>

          <el-alert v-if="error" class="login-error" :title="error" type="error" show-icon :closable="false"/>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
            <el-form-item label="账号" prop="username">
              <el-input v-model.trim="form.username" size="large" autocomplete="username" placeholder="请输入用户名" autofocus :prefix-icon="User" @keyup.enter="submit"/>
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="form.password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入登录密码" :prefix-icon="Lock" @keydown="updateCapsLock" @keyup="updateCapsLock" @keyup.enter="submit"/>
              <span v-if="capsLocked" class="caps-hint">大写锁定已开启</span>
            </el-form-item>

            <div class="login-options">
              <el-checkbox v-model="rememberUsername">记住账号</el-checkbox>
              <span>如忘记密码，请联系平台管理员重置</span>
            </div>

            <el-button class="login-submit" type="primary" size="large" :loading="loading" native-type="submit">
              <span>{{loading?'正在安全登录':'登录平台'}}</span>
              <el-icon v-if="!loading"><ArrowRight/></el-icon>
            </el-button>
          </el-form>

          <div class="security-tip"><span><el-icon><Lock/></el-icon></span><p><strong>账号安全提示</strong>首次登录或密码被重置后，系统将引导您设置新密码。</p></div>
        </div>

        <ComplianceFooter class="login-footer" compact/>
      </section>
    </section>
  </main>
</template>

<style scoped>
.login-page{position:relative;display:grid;min-height:100vh;padding:28px;place-items:center;overflow:hidden;background:radial-gradient(circle at 8% 12%,rgba(100,176,228,.18),transparent 29%),radial-gradient(circle at 90% 82%,rgba(74,159,194,.13),transparent 31%),linear-gradient(145deg,#e8f1f9 0%,#f7fafc 49%,#eaf3fa 100%);color:#172033}
.login-page:before{position:absolute;inset:0;background-image:linear-gradient(rgba(52,93,132,.045) 1px,transparent 1px),linear-gradient(90deg,rgba(52,93,132,.045) 1px,transparent 1px);background-size:32px 32px;mask-image:linear-gradient(to bottom,rgba(0,0,0,.85),rgba(0,0,0,.22) 58%,rgba(0,0,0,.7));content:""}
.login-page:after{position:absolute;width:130vw;height:260px;top:3%;left:-15vw;border-top:1px solid rgba(69,132,183,.1);border-bottom:1px solid rgba(69,132,183,.08);background:linear-gradient(90deg,transparent,rgba(101,174,222,.075) 25%,rgba(255,255,255,.24) 58%,transparent);transform:rotate(-7deg);content:"";pointer-events:none}
.login-decoration{position:absolute;border-radius:50%;filter:blur(2px);pointer-events:none}.decoration-one{width:620px;height:620px;top:-280px;right:-150px;border:1px solid rgba(69,139,194,.08);background:radial-gradient(circle,rgba(73,145,218,.2),rgba(73,145,218,0) 68%);box-shadow:0 0 0 80px rgba(63,142,202,.025),0 0 0 160px rgba(63,142,202,.018)}.decoration-two{width:520px;height:520px;bottom:-260px;left:-170px;border:1px solid rgba(48,165,147,.07);background:radial-gradient(circle,rgba(59,182,150,.15),rgba(59,182,150,0) 68%);box-shadow:0 0 0 74px rgba(47,164,151,.022)}
.background-scene{position:absolute;inset:0;overflow:hidden;pointer-events:none}.scene-orbit{position:absolute;border:1px solid rgba(62,119,168,.1);border-radius:50%}.orbit-one{width:420px;height:420px;top:-235px;left:7%;box-shadow:0 0 0 46px rgba(52,130,190,.025),0 0 0 92px rgba(52,130,190,.018)}.orbit-two{width:330px;height:330px;right:4%;bottom:-205px;box-shadow:0 0 0 45px rgba(42,153,161,.02)}
.scene-route{position:absolute;display:block;height:1px;background:linear-gradient(90deg,transparent,rgba(49,119,173,.24),transparent);transform-origin:center}.scene-route i{position:absolute;width:6px;height:6px;top:-3px;border:2px solid rgba(49,119,173,.35);border-radius:50%;background:#eef5fa;box-shadow:0 0 0 5px rgba(76,145,198,.07)}.route-one{width:29vw;top:15%;left:-2%;transform:rotate(15deg)}.route-one i:nth-child(1){left:18%}.route-one i:nth-child(2){left:56%}.route-one i:nth-child(3){left:88%}.route-two{width:27vw;right:-3%;bottom:14%;transform:rotate(-14deg)}.route-two i:nth-child(1){left:20%}.route-two i:nth-child(2){left:72%}
.scene-chip{position:absolute;display:flex;min-width:152px;align-items:center;gap:8px;padding:9px 12px;border:1px solid rgba(120,153,183,.18);border-radius:9px;color:#5e7590;background:rgba(255,255,255,.46);box-shadow:0 10px 28px rgba(57,83,111,.045);backdrop-filter:blur(8px)}.scene-chip>span{display:block;width:7px;height:7px;border-radius:50%;background:#4d9bd2;box-shadow:0 0 0 5px rgba(77,155,210,.1)}.scene-chip strong{font-size:11px}.scene-chip small{margin-left:auto;color:#96a7b8;font-size:8px;letter-spacing:.08em}.chip-one{top:7%;left:6%}.chip-two{right:6%;bottom:7%}.chip-two>span{background:#45aa94;box-shadow:0 0 0 5px rgba(69,170,148,.1)}
.scene-caption{position:absolute;display:flex;align-items:center;gap:12px;right:7%;top:8%;color:#71869b}.scene-caption strong{font-size:9px;letter-spacing:.18em}.scene-caption i{width:42px;height:1px;background:rgba(80,124,161,.28)}.scene-caption span{color:#98a8b8;font-size:9px;letter-spacing:.06em}
.login-shell{position:relative;display:grid;width:min(1120px,100%);min-height:650px;grid-template-columns:minmax(0,1.05fr) minmax(430px,.95fr);overflow:hidden;border:1px solid rgba(216,225,236,.9);border-radius:18px;background:#fff;box-shadow:0 28px 80px rgba(33,54,82,.13)}
.brand-panel{position:relative;display:flex;min-width:0;padding:38px 46px 34px;flex-direction:column;overflow:hidden;color:#fff;background:linear-gradient(145deg,#1163a4 0%,#1c78ba 48%,#2b8bc4 100%)}
.brand-panel:before{position:absolute;width:460px;height:460px;right:-250px;bottom:-180px;border:1px solid rgba(255,255,255,.13);border-radius:50%;box-shadow:0 0 0 60px rgba(255,255,255,.035),0 0 0 126px rgba(255,255,255,.028);content:""}
.brand-panel:after{position:absolute;width:220px;height:220px;top:76px;right:-120px;border:1px solid rgba(255,255,255,.1);border-radius:44px;transform:rotate(35deg);content:""}
.brand-top{position:relative;z-index:1;display:flex;align-items:center;gap:12px}.brand-top>div:last-child{display:flex;flex-direction:column;gap:3px}.brand-top strong{font-size:18px;letter-spacing:.03em}.brand-top small{color:rgba(255,255,255,.62);font-size:9px;letter-spacing:.16em}
.brand-mark{display:grid;width:42px;height:42px;padding:9px;grid-template-columns:repeat(3,1fr);align-items:end;gap:3px;border-radius:11px;background:rgba(255,255,255,.15);box-shadow:inset 0 0 0 1px rgba(255,255,255,.2)}
.brand-mark span{border-radius:2px;background:#fff}.brand-mark span:nth-child(1){height:42%;opacity:.65}.brand-mark span:nth-child(2){height:72%;opacity:.8}.brand-mark span:nth-child(3){height:100%}
.brand-content{position:relative;z-index:1;margin:auto 0}.brand-eyebrow{display:inline-flex;margin-bottom:18px;padding:6px 10px;border:1px solid rgba(255,255,255,.2);border-radius:20px;color:rgba(255,255,255,.82);background:rgba(255,255,255,.08);font-size:11px;letter-spacing:.08em}
.brand-content h1{margin:0;font-size:38px;line-height:1.35;letter-spacing:-.025em}.brand-content>p{max-width:470px;margin:18px 0 28px;color:rgba(255,255,255,.72);font-size:14px;line-height:1.8}
.brand-features{display:flex;flex-direction:column;gap:11px}.brand-features article{display:flex;align-items:center;gap:12px;padding:11px 13px;border:1px solid rgba(255,255,255,.11);border-radius:10px;background:rgba(255,255,255,.07);backdrop-filter:blur(4px)}
.brand-features article>span{display:grid;flex:0 0 36px;height:36px;place-items:center;border-radius:9px;color:#dff2ff;background:rgba(255,255,255,.12);font-size:18px}.brand-features article>div{display:flex;flex-direction:column;gap:4px}.brand-features strong{font-size:13px}.brand-features small{color:rgba(255,255,255,.62);font-size:11px}
.brand-footer{position:relative;z-index:1;display:flex;align-items:center;gap:10px;color:rgba(255,255,255,.6);font-size:10px;letter-spacing:.14em}.brand-footer i{width:3px;height:3px;border-radius:50%;background:rgba(255,255,255,.5)}
.login-panel{display:flex;min-width:0;padding:38px 52px 26px;flex-direction:column;background:#fff}.mobile-brand{display:none}.login-form-wrap{width:100%;max-width:400px;margin:auto}
.login-header{margin-bottom:28px}.login-kicker{display:block;margin-bottom:8px;color:#3980bd;font-size:10px;font-weight:700;letter-spacing:.16em}.login-header h2{margin:0;color:#1d2939;font-size:30px;letter-spacing:-.025em}.login-header p{margin:9px 0 0;color:#8490a2;font-size:13px;line-height:1.6}
.login-error{margin-bottom:18px}.login-panel :deep(.el-form-item){margin-bottom:22px}.login-panel :deep(.el-form-item__label){padding-bottom:8px;color:#475467;font-size:13px;font-weight:600}.login-panel :deep(.el-input__wrapper){min-height:46px;border-radius:8px;box-shadow:0 0 0 1px #dce3eb inset}.login-panel :deep(.el-input__wrapper:hover){box-shadow:0 0 0 1px #9ebad4 inset}.login-panel :deep(.el-input__wrapper.is-focus){box-shadow:0 0 0 1px #409eff inset,0 0 0 3px rgba(64,158,255,.1)}.login-panel :deep(.el-input__prefix){font-size:17px}
.caps-hint{position:absolute;right:0;bottom:-19px;color:#d98b18;font-size:11px}.login-options{display:flex;align-items:center;justify-content:space-between;gap:14px;margin:-2px 0 22px}.login-options>span{color:#98a2b3;font-size:11px;text-align:right}
.login-submit{display:flex;width:100%;height:47px;border-radius:8px;font-weight:600;box-shadow:0 8px 18px rgba(47,132,202,.22)}.login-submit :deep(span){display:flex;align-items:center;justify-content:center;gap:8px}.login-submit .el-icon{font-size:16px}
.security-tip{display:flex;align-items:flex-start;gap:10px;margin-top:24px;padding:12px 13px;border:1px solid #e7edf4;border-radius:9px;background:#f8fafc}.security-tip>span{display:grid;flex:0 0 30px;height:30px;place-items:center;border-radius:8px;color:#337db9;background:#e8f3fc}.security-tip p{margin:0;color:#8490a2;font-size:11px;line-height:1.6}.security-tip strong{display:block;color:#536174;font-size:11px}
.login-footer{color:#a1a9b6;font-size:10px;text-align:center}.login-panel :deep(.el-checkbox__label){color:#667085;font-size:12px}
@media(min-width:1500px){.login-page{padding:46px}.login-shell{width:min(1240px,calc(100vw - 160px));min-height:min(740px,calc(100vh - 92px));grid-template-columns:minmax(0,1.08fr) minmax(470px,.92fr)}.brand-panel{padding:42px 54px 38px}.login-panel{padding-right:60px;padding-left:60px}.brand-content h1{font-size:42px}}
@media(max-width:1100px){.scene-chip,.scene-caption{display:none}}
@media(max-width:900px){.login-page{padding:20px}.login-shell{min-height:610px;grid-template-columns:minmax(0,.9fr) minmax(390px,1.1fr)}.brand-panel{padding:32px}.brand-content h1{font-size:31px}.brand-features article{padding:10px}.login-panel{padding:34px 38px 24px}}
@media(max-width:720px){.login-page{padding:0;background:#fff}.background-scene,.login-decoration{display:none}.login-shell{width:100%;min-height:100vh;display:block;border:0;border-radius:0;box-shadow:none}.brand-panel{display:none}.login-panel{min-height:100vh;padding:25px 22px 20px}.mobile-brand{display:flex;align-items:center;gap:10px;color:#1769aa}.mobile-brand .brand-mark{width:36px;height:36px;padding:8px;background:#237bbb}.mobile-brand strong{font-size:17px}.login-form-wrap{margin:auto}.login-header{margin-top:38px}.login-header h2{font-size:28px}.login-footer{margin-top:28px}.login-options{align-items:flex-start}.login-options>span{max-width:190px}}
@media(max-width:420px){.login-panel{padding-right:18px;padding-left:18px}.login-header{margin-bottom:24px}.login-options>span{max-width:160px}.security-tip{margin-top:20px}}
</style>
