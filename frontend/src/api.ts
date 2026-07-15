import axios from 'axios';import {ElMessage} from 'element-plus';
export interface Envelope<T>{code:number;message:string;data:T;requestId:string}
export const api=axios.create({baseURL:'/api/v1',timeout:20000});
api.interceptors.request.use(c=>{const t=localStorage.getItem('token');if(t)c.headers.Authorization=`Bearer ${t}`;c.headers['X-Request-Id']=crypto.randomUUID();return c});
api.interceptors.response.use(r=>r.data,(e)=>{const isLogin=e.config?.url==='/auth/login';const msg=e.response?.data?.message||'请求失败';if(!isLogin)ElMessage.error(msg);if(e.response?.status===401&&!isLogin){localStorage.clear();if(location.pathname!=='/login')location.href='/login'}return Promise.reject(e)});
