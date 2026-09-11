<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Folder,FolderAdd} from '@element-plus/icons-vue'
import {api,type Envelope} from '@/api'
import {inFolder,type FolderSelection} from '@/utils/organizationFolder'

const props=defineProps<{domain:'training-plans'|'question-banks';items:{id:number;name:string;folder_id?:number|null}[];modelValue:FolderSelection}>()
const emit=defineEmits<{ 'update:modelValue':[value:FolderSelection];changed:[] }>()
const folders=ref<{id:number;name:string}[]>([]),busy=ref(false),moving=ref(false),selectedIds=ref<number[]>([]),target=ref<number|''>('')
const current=computed(()=>folders.value.find(folder=>folder.id===props.modelValue))
const base=computed(()=>`/organization-folders/${props.domain}`)
const count=(selection:FolderSelection)=>props.items.filter(item=>inFolder(item,selection)).length
async function load(){folders.value=(await api.get<any,Envelope<typeof folders.value>>(base.value)).data}
async function edit(rename=false){
  const folder=rename?current.value:undefined
  if(rename&&!folder)return
  const result=await ElMessageBox.prompt('文件夹名称最多 80 个字符',rename?'重命名文件夹':'新建文件夹',{
    inputValue:folder?.name||'',inputValidator:value=>Boolean(value?.trim())&&value.trim().length<=80||'请输入 1–80 个字符',
    confirmButtonText:'保存',cancelButtonText:'取消'
  }).catch(()=>null)
  if(!result)return
  busy.value=true
  try{
    if(folder)await api.put(`${base.value}/${folder.id}`,{name:result.value.trim()})
    else await api.post(base.value,{name:result.value.trim()})
    await load();ElMessage.success('文件夹已保存')
  }finally{busy.value=false}
}
async function remove(){
  const folder=current.value
  if(!folder)return
  const confirmed=await ElMessageBox.confirm(`删除“${folder.name}”后，其中的内容将移到“未分类”，不会删除内容。`,'删除文件夹',{type:'warning',confirmButtonText:'删除文件夹',cancelButtonText:'取消'}).then(()=>true).catch(()=>false)
  if(!confirmed)return
  busy.value=true
  try{await api.delete(`${base.value}/${folder.id}`);emit('update:modelValue','NONE');await load();emit('changed');ElMessage.success('文件夹已删除，内容已移到未分类')}
  finally{busy.value=false}
}
function openMove(){selectedIds.value=[];target.value=typeof props.modelValue==='number'?props.modelValue:'';moving.value=true}
async function move(){
  if(!selectedIds.value.length)return
  busy.value=true
  try{
    await api.put(`${base.value}/items`,{itemIds:selectedIds.value,folderId:target.value||null})
    moving.value=false;emit('update:modelValue',target.value||'NONE');await load();emit('changed');ElMessage.success('内容已移动')
  }finally{busy.value=false}
}
onMounted(load)
</script>

<template>
  <section class="folder-panel" aria-label="文件夹管理">
    <div class="folder-heading"><strong><el-icon><Folder/></el-icon> 文件夹</strong><div class="folder-actions">
      <el-button :icon="FolderAdd" :disabled="busy" @click="edit()">新建文件夹</el-button>
      <el-button :disabled="busy||!items.length" @click="openMove">移动内容</el-button>
      <el-button v-if="current" :disabled="busy" @click="edit(true)">重命名</el-button>
      <el-button v-if="current" :disabled="busy" type="danger" plain @click="remove">删除文件夹</el-button>
    </div></div>
    <div class="folder-list">
      <button type="button" :class="{active:modelValue==='ALL'}" @click="emit('update:modelValue','ALL')">全部 <small>{{items.length}}</small></button>
      <button type="button" :class="{active:modelValue==='NONE'}" @click="emit('update:modelValue','NONE')">未分类 <small>{{count('NONE')}}</small></button>
      <button v-for="folder in folders" :key="folder.id" type="button" :title="folder.name" :class="{active:modelValue===folder.id}" @click="emit('update:modelValue',folder.id)"><el-icon><Folder/></el-icon><span>{{folder.name}}</span><small>{{count(folder.id)}}</small></button>
    </div>
    <p>新建内容默认进入“未分类”，可通过“移动内容”批量整理。</p>
  </section>
  <el-dialog v-model="moving" title="移动内容到文件夹" width="min(560px,94vw)" :close-on-click-modal="!busy" :show-close="!busy">
    <el-form label-position="top">
      <el-form-item label="选择需要移动的内容（最多 500 项）"><el-select v-model="selectedIds" multiple filterable :multiple-limit="500" placeholder="搜索名称，可多选" style="width:100%"><el-option v-for="item in items" :key="item.id" :value="item.id" :label="item.name"/></el-select></el-form-item>
      <el-form-item label="目标文件夹"><el-select v-model="target" filterable style="width:100%"><el-option label="未分类（移出文件夹）" value=""/><el-option v-for="folder in folders" :key="folder.id" :value="folder.id" :label="folder.name"/></el-select></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="moving=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!selectedIds.length" @click="move">确认移动 {{selectedIds.length}} 项</el-button></template>
  </el-dialog>
</template>

<style scoped>
.folder-panel{margin:16px 0;padding:16px;border:1px solid #e3e9f2;border-radius:10px;background:#fafcff}.folder-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.folder-heading strong{display:flex;gap:6px;align-items:center;color:#344054}.folder-actions{display:flex;gap:8px;flex-wrap:wrap}.folder-actions .el-button{margin:0}.folder-list{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}.folder-list button{display:flex;align-items:center;gap:8px;max-width:300px;border:1px solid #e1e7ef;border-radius:7px;padding:9px 12px;color:#526176;background:white;cursor:pointer}.folder-list button span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.folder-list button.active{border-color:#409eff;background:#ecf5ff;color:#2476c6}.folder-list small{color:#8693a5}.folder-panel p{margin:12px 0 0;color:#8693a5;font-size:12px}
</style>
