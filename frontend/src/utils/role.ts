export type Role='EMPLOYEE'|'MENTOR'|'STATION_MANAGER'|'TRAINING_ADMIN'|'ADMIN'|'SUPER_ADMIN'

export const ROLE_LABELS:Record<Role,string>={
  EMPLOYEE:'员工',
  MENTOR:'导师',
  STATION_MANAGER:'服务站负责人',
  TRAINING_ADMIN:'培训管理员',
  ADMIN:'管理员',
  SUPER_ADMIN:'超级管理员'
}

export function roleLabel(role?:string|null){
  if(!role)return '未分配'
  return ROLE_LABELS[role as Role]||role
}
