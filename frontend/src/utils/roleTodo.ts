import axios from 'axios';
import type {Role} from './role';

interface Envelope<T>{code:number;message:string;data:T}
const client=axios.create({baseURL:'/api/v1',timeout:15000});
// 与 api.ts 保持一致：直接把响应体（Envelope）交给调用方，避免多一层 AxiosResponse.data。
client.interceptors.response.use(r=>r.data);
const CACHE_PREFIX='roleTodos:';
const CACHE_TTL=300000;

function requestId(){return globalThis.crypto?.randomUUID?.()??`role-todo-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`}
function headers(token:string){return {Authorization:`Bearer ${token}`,'X-Request-Id':requestId()}}

/** 从 /dashboard 的指标里取出“该身份待处理事项”：管理身份用 pending_actions，员工身份用待办任务 + 待参加考试。 */
export function todoCount(payload:any):number{
  const metrics=payload?.metrics||{};
  if(typeof metrics.pending_actions==='number')return metrics.pending_actions;
  return Number(metrics.pending_tasks||0)+Number(metrics.pending_exams||0);
}

function cacheKey(userId:number){return `${CACHE_PREFIX}${userId}`}
function readCache(userId:number,roles:Role[]):Record<string,number|null>|null{
  try{
    const raw=sessionStorage.getItem(cacheKey(userId));
    if(!raw)return null;
    const parsed=JSON.parse(raw) as {ts:number;roles:Role[];counts:Record<string,number|null>};
    if(!parsed?.ts||Date.now()-parsed.ts>CACHE_TTL)return null;
    if(JSON.stringify(parsed.roles)!==JSON.stringify(roles))return null;
    return parsed.counts;
  }catch{return null}
}
function writeCache(userId:number,roles:Role[],counts:Record<string,number|null>){
  try{sessionStorage.setItem(cacheKey(userId),JSON.stringify({ts:Date.now(),roles,counts}))}catch{}
}

/** 清除待办缓存：传 userId 只清该账号，不传则清掉全部账号（退出登录时用）。 */
export function clearRoleTodos(userId?:number){
  try{
    if(userId!==undefined){sessionStorage.removeItem(cacheKey(userId));return}
    for(let i=sessionStorage.length-1;i>=0;i--){
      const key=sessionStorage.key(i);
      if(key?.startsWith(CACHE_PREFIX))sessionStorage.removeItem(key);
    }
  }catch{}
}

/**
 * 逐个身份取待办数：用 /auth/switch-role 换取一次性令牌，再以该令牌读取 /dashboard，令牌用完即弃、不写 localStorage。
 * 不改后端接口的前提只能这样做；取不到的身份返回 null（界面只显示名称，不报错）。
 */
export async function loadRoleTodos(token:string,roles:Role[],activeRole:Role|null,userId:number):Promise<Record<string,number|null>>{
  const cached=readCache(userId,roles);
  if(cached)return cached;
  const counts:Record<string,number|null>={};
  for(const role of roles){
    try{
      const roleToken=role===activeRole
        ?token
        :(await client.post<any,Envelope<{token:string}>>('/auth/switch-role',{role},{headers:headers(token)})).data.token;
      const payload=(await client.get<any,Envelope<any>>('/dashboard',{headers:headers(roleToken)})).data;
      counts[role]=todoCount(payload);
    }catch{counts[role]=null}
  }
  writeCache(userId,roles,counts);
  return counts;
}
