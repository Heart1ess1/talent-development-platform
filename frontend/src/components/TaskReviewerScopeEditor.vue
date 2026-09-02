<script setup lang="ts">
import {computed} from 'vue'
import {Plus,View} from '@element-plus/icons-vue'
import type {ReviewerScopeDraft,ReviewerScopeMode} from '@/types/taskReviewerScope'

const props=withDefaults(defineProps<{
  mode:ReviewerScopeMode
  modelValue:ReviewerScopeDraft[]
  reviewerOptions:any[]
  batches:any[]
  businessUnits:any[]
  classOptions:any[]
  preview?:any
  disabled?:boolean
  previewable?:boolean
}>(),{previewable:true,disabled:false})
const emit=defineEmits<{
  'update:mode':[value:ReviewerScopeMode]
  'update:modelValue':[value:ReviewerScopeDraft[]]
  preview:[]
}>()

const scopes=computed({get:()=>props.modelValue||[],set:value=>emit('update:modelValue',value)})
const selectedMode=computed({get:()=>props.mode,set:value=>changeMode(value)})
const coverageValid=computed(()=>!props.preview||(!Number(props.preview.uncoveredEmployees)&&!Number(props.preview.overlappingEmployees)))
const hasLocked=computed(()=>scopes.value.some(item=>item.locked))

function emptyScope():ReviewerScopeDraft{return {id:null,batchId:null,businessUnitId:null,classId:null,reviewerIds:[]}}
function changeMode(mode:ReviewerScopeMode){
  if(props.disabled)return
  emit('update:mode',mode)
  if(mode==='NONE')scopes.value=[]
  else if(mode==='UNIFORM'){
    const current=scopes.value.length===1?scopes.value[0]!:emptyScope()
    scopes.value=[{...current,batchId:null,businessUnitId:null,classId:null}]
  }else if(!scopes.value.length||scopes.value.every(item=>!item.batchId&&!item.businessUnitId&&!item.classId))scopes.value=[emptyScope()]
}
function updateScope(index:number,key:keyof ReviewerScopeDraft,value:any){
  const next=scopes.value.map((item,i)=>i===index?{...item,[key]:value}:item)
  scopes.value=next
}
function addScope(){scopes.value=[...scopes.value,emptyScope()]}
function removeScope(index:number){if(scopes.value[index]?.locked)return;scopes.value=scopes.value.filter((_,i)=>i!==index)}
function coverage(index:number){return props.preview?.scopes?.find((item:any)=>Number(item.index)===index)?.coveredEmployees??scopes.value[index]?.coveredEmployees}
function roleLabel(role:string){return ({MENTOR:'导师',STATION_MANAGER:'服务站负责人',TRAINING_ADMIN:'培训管理员',ADMIN:'管理员',SUPER_ADMIN:'超级管理员'} as Record<string,string>)[role]||role}
</script>

<template>
  <div class="scope-editor">
    <div class="mode-row">
      <el-radio-group v-model="selectedMode" :disabled="disabled||hasLocked">
        <el-radio-button value="NONE">暂不配置</el-radio-button>
        <el-radio-button value="UNIFORM">统一评分人</el-radio-button>
        <el-radio-button value="SCOPED">按范围配置</el-radio-button>
      </el-radio-group>
      <el-button v-if="previewable&&mode!=='NONE'" :icon="View" :disabled="disabled" @click="emit('preview')">预览覆盖</el-button>
    </div>

    <p v-if="mode==='NONE'" class="hint">员工可正常接收和提交任务，提交后显示“待分配评分人”。</p>
    <div v-else-if="mode==='UNIFORM'" class="uniform-row">
      <el-select :model-value="scopes[0]?.reviewerIds||[]" multiple filterable collapse-tags collapse-tags-tooltip
        :disabled="disabled||scopes[0]?.locked" placeholder="选择负责全部员工的评分人"
        @update:model-value="updateScope(0,'reviewerIds',$event)">
        <el-option v-for="item in reviewerOptions" :key="item.id" :label="`${item.display_name}（${roleLabel(item.role)}）`" :value="item.id"/>
      </el-select>
      <el-tag v-if="scopes[0]?.locked" type="warning">已锁定</el-tag>
    </div>
    <div v-else class="scope-table-shell">
      <el-table :data="scopes" size="small" empty-text="请新增评分范围">
        <el-table-column label="批次" min-width="135"><template #default="{row,$index}"><el-select :model-value="row.batchId" clearable filterable :disabled="disabled||row.locked" placeholder="全部批次" @update:model-value="updateScope($index,'batchId',$event??null)"><el-option v-for="item in batches" :key="item.id" :label="item.name" :value="item.id"/></el-select></template></el-table-column>
        <el-table-column label="板块" min-width="135"><template #default="{row,$index}"><el-select :model-value="row.businessUnitId" clearable filterable :disabled="disabled||row.locked" placeholder="全部板块" @update:model-value="updateScope($index,'businessUnitId',$event??null)"><el-option v-for="item in businessUnits" :key="item.id" :label="item.name" :value="item.id"/></el-select></template></el-table-column>
        <el-table-column label="班级" min-width="135"><template #default="{row,$index}"><el-select :model-value="row.classId" clearable filterable :disabled="disabled||row.locked" placeholder="全部班级" @update:model-value="updateScope($index,'classId',$event??null)"><el-option v-for="item in classOptions" :key="item.id" :label="item.label" :value="item.id"/></el-select></template></el-table-column>
        <el-table-column label="评分人" min-width="230"><template #default="{row,$index}"><el-select :model-value="row.reviewerIds" multiple filterable collapse-tags collapse-tags-tooltip :disabled="disabled||row.locked" placeholder="至少选择一人" @update:model-value="updateScope($index,'reviewerIds',$event)"><el-option v-for="item in reviewerOptions" :key="item.id" :label="`${item.display_name}（${roleLabel(item.role)}）`" :value="item.id"/></el-select></template></el-table-column>
        <el-table-column label="预计覆盖" width="95" align="center"><template #default="{$index}">{{coverage($index)??'--'}} 人</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{row}"><el-tag v-if="row.locked" type="warning">已锁定</el-tag><el-tag v-else type="info">可编辑</el-tag></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{row,$index}"><el-button link type="danger" :disabled="disabled||row.locked" @click="removeScope($index)">删除</el-button></template></el-table-column>
      </el-table>
      <el-button class="add-button" :icon="Plus" :disabled="disabled" @click="addScope">新增范围</el-button>
    </div>

    <div v-if="preview&&mode!=='NONE'" class="coverage" :class="coverageValid?'valid':'invalid'">
      <strong>已覆盖 {{preview.coveredEmployees}} / {{preview.targetEmployees}} 人</strong>
      <span v-if="preview.uncoveredEmployees">未覆盖 {{preview.uncoveredEmployees}} 人</span>
      <span v-if="preview.overlappingEmployees">重叠 {{preview.overlappingEmployees}} 人</span>
      <div v-if="preview.uncovered?.length" class="employee-errors">未覆盖：{{preview.uncovered.map((item:any)=>`${item.employeeName}（${item.employeeNo}）`).join('、')}}</div>
      <div v-if="preview.overlapping?.length" class="employee-errors">范围冲突：{{preview.overlapping.map((item:any)=>`${item.employeeName}（${item.employeeNo}）`).join('、')}}</div>
    </div>
  </div>
</template>

<style scoped>
.scope-editor{display:grid;gap:12px}.mode-row{display:flex;align-items:center;justify-content:space-between;gap:12px}.hint{margin:0;color:#8490a3;font-size:12px}.uniform-row{display:flex;align-items:center;gap:10px}.uniform-row .el-select{flex:1}.scope-table-shell{overflow:hidden;border:1px solid #e4e9f0;border-radius:8px;background:#fff}.scope-table-shell :deep(.el-select){width:100%}.add-button{margin:10px}.coverage{display:flex;gap:16px;flex-wrap:wrap;padding:10px 12px;border-radius:7px;font-size:12px}.coverage.valid{color:#257a57;background:#edf9f3}.coverage.invalid{color:#b54747;background:#fff1f1}.employee-errors{flex-basis:100%;line-height:1.6}.mode-row :deep(.el-radio-button__inner){padding:8px 14px}@media(max-width:720px){.mode-row{align-items:flex-start;flex-direction:column}.mode-row :deep(.el-radio-group){display:flex;flex-wrap:wrap}.uniform-row{align-items:stretch;flex-direction:column}}
</style>
