export function localDateTime(value=new Date()){const p=(n:number)=>String(n).padStart(2,'0');return `${value.getFullYear()}-${p(value.getMonth()+1)}-${p(value.getDate())}T${p(value.getHours())}:${p(value.getMinutes())}:${p(value.getSeconds())}`}
export function windowPayload(form:any,modeKey='scheduleMode',endKey='endsAt'){
  const mode=form[modeKey]||'IMMEDIATE'
  let end=form[endKey]
  if(mode==='OFFSET'&&form.baseDate&&Number.isInteger(form.offsetDays)){const date=new Date(`${form.baseDate}T12:00:00`);date.setDate(date.getDate()+form.offsetDays);end=localDateTime(date).slice(0,10)+'T23:59:59'}
  return {scheduleMode:mode,startsAt:mode==='MANUAL'?form.startsAt:localDateTime(),endsAt:end,baseDate:form.baseDate||null,offsetDays:form.offsetDays??null}
}
