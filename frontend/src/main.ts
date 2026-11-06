import {createApp} from 'vue';import {createPinia} from 'pinia';import ElementPlus from 'element-plus';import 'element-plus/dist/index.css';import './styles/theme.css';import './styles.css';import './styles/typography.css';import App from './App.vue';import router from './router';import {useAuthStore} from '@/stores/auth';

/** 多标签页同步：任何标签页改写登录态后，其它标签页整页刷新，避免“页面身份”和“请求令牌身份”不一致。 */
const SYNC_KEYS=['token','user','availableRoles','activeRole'];
let syncing=false;
window.addEventListener('storage',event=>{
  if(syncing)return;
  if(event.key!==null&&!SYNC_KEYS.includes(event.key))return;
  if(event.newValue===event.oldValue)return;
  syncing=true;
  location.reload();
});

const app=createApp(App),pinia=createPinia();
app.use(pinia).use(ElementPlus);

async function bootstrap(){
  const auth=useAuthStore(pinia),token=localStorage.getItem('token');
  if(!token)auth.logout();
  else try{await auth.refresh()}catch{if(!localStorage.getItem('token'))auth.logout()}
  app.use(router).mount('#app');
}

void bootstrap();
