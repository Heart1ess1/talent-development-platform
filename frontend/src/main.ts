import {createApp} from 'vue';import {createPinia} from 'pinia';import ElementPlus from 'element-plus';import 'element-plus/dist/index.css';import './styles.css';import App from './App.vue';import router from './router';import {useAuthStore} from '@/stores/auth';

const app=createApp(App),pinia=createPinia();
app.use(pinia).use(ElementPlus);

async function bootstrap(){
  const auth=useAuthStore(pinia),token=localStorage.getItem('token');
  if(!token)auth.logout();
  else try{await auth.refresh()}catch{if(!localStorage.getItem('token'))auth.logout()}
  app.use(router).mount('#app');
}

void bootstrap();
