export function avatarUrl(token?:string|null){
  return token?`/api/v1/avatars/${encodeURIComponent(token)}`:''
}

export function nameInitial(name?:string|null){
  const value=String(name||'').trim()
  const characters=Array.from(value)
  return characters[characters.length-1]?.toUpperCase()||'用'
}
