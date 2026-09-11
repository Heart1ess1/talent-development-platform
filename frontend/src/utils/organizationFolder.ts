export type FolderSelection='ALL'|'NONE'|number
export function inFolder(item:{folder_id?:number|null},selection:FolderSelection){
  return selection==='ALL'||(selection==='NONE'?item.folder_id==null:Number(item.folder_id)===selection)
}
