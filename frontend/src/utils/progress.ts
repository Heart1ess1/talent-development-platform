export function completionRate(completed:number,total:number){return total<=0?0:Math.round(completed*100/total)}
export function statusLabel(status:string){return ({NOT_SUBMITTED:'未提交',PENDING_REVIEW:'待审核',RETURNED:'已退回',APPROVED:'已通过'} as Record<string,string>)[status]||status}

