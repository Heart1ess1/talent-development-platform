import {defineStore} from 'pinia';
import {api,type Envelope} from '@/api';
import type {Role} from '@/utils/role';
export interface User{id:number;username:string;displayName:string;role:Role;mustChangePassword:boolean;securityVersion:number;permissions:string[];dataScope:'SELF'|'MENTORED'|'STATION'|'AREA'|'ALL'|'NONE';avatarToken?:string|null}
interface AuthPayload{token:string;user:User;availableRoles?:Role[];activeRole?:Role}
function storedUser(){return JSON.parse(localStorage.getItem('user')||'null') as User|null}
/** 旧版本浏览器里可能没有 availableRoles，回退为当前单一角色，切换器因此不会出现。 */
function storedRoles():Role[]{const raw=localStorage.getItem('availableRoles');if(raw){try{const parsed=JSON.parse(raw) as Role[];if(Array.isArray(parsed)&&parsed.length)return parsed}catch{}}const user=storedUser();return user?[user.role]:[]}
function storedActiveRole():Role|null{return (localStorage.getItem('activeRole') as Role|null)||storedUser()?.role||null}
export const useAuthStore=defineStore('auth',{
  state:()=>({user:storedUser(),availableRoles:storedRoles(),activeRole:storedActiveRole()}),
  getters:{
    can:s=>(permission:string)=>!!s.user?.permissions?.includes(permission),
    multiRole:s=>s.availableRoles.length>1
  },
  actions:{
    apply(token:string|null,payload:AuthPayload){
      const roles:Role[]=payload.availableRoles?.length?payload.availableRoles:[payload.user.role];
      const active:Role=payload.activeRole||payload.user.role;
      if(token)localStorage.setItem('token',token);
      localStorage.setItem('user',JSON.stringify(payload.user));
      localStorage.setItem('availableRoles',JSON.stringify(roles));
      localStorage.setItem('activeRole',active);
      this.user=payload.user;this.availableRoles=roles;this.activeRole=active;
    },
    async login(username:string,password:string){
      const r=await api.post<any,Envelope<AuthPayload>>('/auth/login',{username,password});
      this.apply(r.data.token,r.data);
    },
    logout(){localStorage.clear();this.user=null;this.availableRoles=[];this.activeRole=null},
    async refresh(){
      const r=await api.get<any,Envelope<User&{availableRoles?:Role[]}>>('/auth/me');
      this.apply(null,{token:'',user:r.data,availableRoles:r.data.availableRoles,activeRole:r.data.role});
    },
    /** 切换身份：直接采用切换响应（已含新令牌、用户权限与角色集合），避免再发一次 /auth/me。 */
    async switchRole(role:Role){
      const r=await api.post<any,Envelope<AuthPayload>>('/auth/switch-role',{role});
      this.apply(r.data.token,r.data);
    },
    async changePassword(oldPassword:string,newPassword:string){
      const r=await api.post<any,Envelope<AuthPayload>>('/auth/change-password',{oldPassword,newPassword});
      this.apply(r.data.token,{token:r.data.token,user:r.data.user,availableRoles:this.availableRoles,activeRole:r.data.user.role});
    }
  }
});
