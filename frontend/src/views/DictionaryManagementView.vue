<script setup lang="ts">
import {computed,onMounted,reactive,ref} from 'vue'
import {Collection,Edit,Plus,Search} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {api,type Envelope} from '@/api'

type DictionaryItem={id:number;value:string;label:string;sortOrder:number;enabled:boolean}
type DictionaryType={code:string;name:string;description:string;coded:boolean;items:DictionaryItem[]}

const types=ref<DictionaryType[]>([])
const activeCode=ref('')
const loading=ref(false)
const saving=ref(false)
const keyword=ref('')
const editorOpen=ref(false)
const editingId=ref<number|null>(null)
const form=reactive({value:'',label:'',sortOrder:0,enabled:true})

const activeType=computed(()=>types.value.find(item=>item.code===activeCode.value)||null)
const filteredItems=computed(()=>{
  const term=keyword.value.trim().toLowerCase()
  const items=activeType.value?.items||[]
  if(!term)return items
  return items.filter(item=>item.label.toLowerCase().includes(term)||item.value.toLowerCase().includes(term))
})
const totalValues=computed(()=>types.value.reduce((total,type)=>total+type.items.length,0))
const enabledValues=computed(()=>types.value.reduce(
  (total,type)=>total+type.items.filter(item=>item.enabled).length,0
))

async function load(preferredCode=activeCode.value){
  loading.value=true
  try{
    const response=await api.get<any,Envelope<DictionaryType[]>>('/dictionaries',{
      params:{includeDisabled:true}
    })
    types.value=response.data
    activeCode.value=types.value.some(item=>item.code===preferredCode)
      ?preferredCode
      :types.value[0]?.code||''
  }finally{
    loading.value=false
  }
}

function openCreate(){
  editingId.value=null
  Object.assign(form,{value:'',label:'',sortOrder:0,enabled:true})
  editorOpen.value=true
}

function openEdit(item:DictionaryItem){
  editingId.value=item.id
  Object.assign(form,item)
  editorOpen.value=true
}

async function save(){
  const type=activeType.value
  if(!type)return
  const label=form.label.trim()
  if(!label)return ElMessage.warning('请填写显示名称')
  if(type.coded&&!form.value.trim())return ElMessage.warning('请填写保存值')
  saving.value=true
  try{
    const payload={
      value:type.coded?form.value.trim():undefined,
      label,
      sortOrder:form.sortOrder,
      enabled:form.enabled
    }
    if(editingId.value){
      await api.put(`/dictionaries/${type.code}/values/${editingId.value}`,payload)
      ElMessage.success('字典值已更新')
    }else{
      await api.post(`/dictionaries/${type.code}/values`,payload)
      ElMessage.success('字典值已新增')
    }
    editorOpen.value=false
    await load(type.code)
  }finally{
    saving.value=false
  }
}

async function changeStatus(item:DictionaryItem){
  const type=activeType.value
  if(!type)return
  try{
    await api.put(`/dictionaries/${type.code}/values/${item.id}`,{
      value:type.coded?item.value:undefined,
      label:item.label,
      sortOrder:item.sortOrder,
      enabled:item.enabled
    })
    ElMessage.success(item.enabled?'字典值已启用':'字典值已停用')
    await load(type.code)
  }catch{
    item.enabled=!item.enabled
  }
}

function selectType(code:string){
  activeCode.value=code
  keyword.value=''
}

onMounted(()=>load())
</script>

<template>
  <div class="dictionary-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">MASTER DATA</span>
        <h1>字典值管理</h1>
        <p>统一维护系统可配置选项。停用后不再出现在新数据选项中，历史记录仍会保留。</p>
      </div>
      <div class="summary-strip">
        <span><strong>{{types.length}}</strong> 个字典</span>
        <span><strong>{{enabledValues}}</strong> / {{totalValues}} 个值启用</span>
      </div>
    </header>

    <div v-loading="loading" class="dictionary-workspace">
      <aside class="type-panel">
        <div class="panel-title">字典分类</div>
        <button
          v-for="type in types"
          :key="type.code"
          type="button"
          :class="['type-option',{active:type.code===activeCode}]"
          @click="selectType(type.code)"
        >
          <span class="type-icon"><el-icon><Collection/></el-icon></span>
          <span class="type-copy">
            <strong>{{type.name}}</strong>
            <small>{{type.items.filter(item=>item.enabled).length}} / {{type.items.length}} 启用</small>
          </span>
        </button>
      </aside>

      <section v-if="activeType" class="value-panel">
        <div class="value-head">
          <div>
            <h2>{{activeType.name}}</h2>
            <p>{{activeType.description}}</p>
          </div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新增字典值</el-button>
        </div>

        <div class="value-toolbar">
          <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索显示名称或保存值"/>
          <span>共 {{filteredItems.length}} 项</span>
        </div>

        <el-table :data="filteredItems" stripe class="value-table">
          <el-table-column prop="label" label="显示名称" min-width="180"/>
          <el-table-column v-if="activeType.coded" prop="value" label="保存值" min-width="180">
            <template #default="{row}"><code>{{row.value}}</code></template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="100" align="center"/>
          <el-table-column label="状态" width="120" align="center">
            <template #default="{row}">
              <el-switch
                v-model="row.enabled"
                inline-prompt
                active-text="启用"
                inactive-text="停用"
                @change="changeStatus(row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="right">
            <template #default="{row}">
              <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无字典值" :image-size="80"/></template>
        </el-table>
      </section>
    </div>

    <el-dialog
      v-model="editorOpen"
      :title="editingId?'编辑字典值':'新增字典值'"
      width="480px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="字典分类">
          <el-input :model-value="activeType?.name" disabled/>
        </el-form-item>
        <el-form-item label="显示名称" required>
          <el-input v-model="form.label" maxlength="64" show-word-limit placeholder="用户看到的名称"/>
        </el-form-item>
        <el-form-item v-if="activeType?.coded" label="保存值" required>
          <el-input
            v-model="form.value"
            :disabled="editingId!==null"
            maxlength="64"
            placeholder="写入业务数据的稳定值"
          />
          <span class="field-hint">创建后不可修改，避免历史数据含义变化。</span>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="排序值">
            <el-input-number v-model="form.sortOrder" :min="-9999" :max="9999"/>
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用"/>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="editorOpen=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dictionary-page{min-height:100%;padding:24px 24px 48px;background:#f5f7fb;color:#172033;box-sizing:border-box}
.page-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:22px}
.eyebrow{display:block;margin-bottom:7px;color:#356bd8;font-size:12px;font-weight:700;letter-spacing:.12em}
.page-heading h1{margin:0;font-size:28px;line-height:1.25}
.page-heading p{margin:8px 0 0;color:#64748b;font-size:14px}
.summary-strip{display:flex;gap:22px;padding:12px 16px;border:1px solid #e6ebf2;border-radius:12px;background:#fff;color:#6b778c;font-size:13px;white-space:nowrap}
.summary-strip strong{color:#245fc5;font-size:18px}
.dictionary-workspace{display:grid;grid-template-columns:250px minmax(0,1fr);min-height:570px;border:1px solid #e3e8f0;border-radius:14px;background:#fff;box-shadow:0 8px 24px rgba(37,54,85,.05);overflow:hidden}
.type-panel{padding:18px 12px;border-right:1px solid #e8ecf2;background:#fbfcfe}
.panel-title{padding:0 10px 10px;color:#8a96a8;font-size:12px;font-weight:700;letter-spacing:.08em}
.type-option{display:flex;align-items:center;gap:11px;width:100%;padding:11px 12px;border:0;border-radius:10px;background:transparent;color:#4b5870;text-align:left;cursor:pointer}
.type-option:hover{background:#f0f4fa}
.type-option.active{background:#eaf2ff;color:#245fc5}
.type-icon{display:grid;width:34px;height:34px;place-items:center;border-radius:9px;background:#eef2f7;font-size:17px}
.type-option.active .type-icon{background:#d9e8ff}
.type-copy{display:flex;min-width:0;flex-direction:column;gap:3px}
.type-copy strong{font-size:14px}
.type-copy small{color:#929eb0;font-size:11px}
.value-panel{min-width:0;padding:24px}
.value-head{display:flex;align-items:center;justify-content:space-between;gap:20px}
.value-head h2{margin:0;font-size:21px}
.value-head p{margin:7px 0 0;color:#7c899c;font-size:13px}
.value-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:22px 0 12px}
.value-toolbar .el-input{max-width:340px}
.value-toolbar span{color:#8a96a8;font-size:12px}
.value-table code{padding:2px 7px;border-radius:5px;background:#f1f4f8;color:#4b5870;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}
.field-hint{margin-top:6px;color:#909bad;font-size:12px}
.form-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}
@media(max-width:800px){
  .dictionary-page{padding:18px 12px 78px}
  .page-heading{align-items:flex-start;flex-direction:column}
  .summary-strip{width:100%;box-sizing:border-box}
  .dictionary-workspace{display:block}
  .type-panel{display:flex;gap:8px;padding:12px;overflow-x:auto;border-right:0;border-bottom:1px solid #e8ecf2}
  .panel-title{display:none}
  .type-option{min-width:155px}
  .value-panel{padding:18px 14px}
  .value-head{align-items:flex-start;flex-direction:column}
  .value-toolbar{align-items:stretch;flex-direction:column}
  .value-toolbar .el-input{max-width:none}
}
</style>
