<script setup lang="ts">
import {computed} from 'vue'
const props=withDefaults(defineProps<{modelValue:any;modeKey?:string;endKey?:string;startKey?:string}>(),{modeKey:'scheduleMode',endKey:'endsAt',startKey:'startsAt'})
const emit=defineEmits<{ 'update:modelValue':[value:any] }>()
const mode=computed(()=>props.modelValue[props.modeKey]||'IMMEDIATE')
const defaultEnd=new Date(2000,0,1,23,59,59)
function set(key:string,value:any){emit('update:modelValue',{...props.modelValue,[key]:value})}
</script>
<template>
  <div class="time-window-editor">
    <el-form-item label="时限类型"><el-select filterable :model-value="mode" @update:model-value="set(modeKey,$event)"><el-option label="下发即开始" value="IMMEDIATE"/><el-option label="按基准日期偏移" value="OFFSET"/><el-option label="手动设置起止时间" value="MANUAL"/></el-select></el-form-item>
    <template v-if="mode==='OFFSET'"><el-form-item label="基准日期"><el-date-picker :model-value="modelValue.baseDate" type="date" value-format="YYYY-MM-DD" @update:model-value="set('baseDate',$event)"/></el-form-item><el-form-item label="截止偏移天数"><el-input-number :model-value="modelValue.offsetDays" :min="0" :precision="0" @update:model-value="set('offsetDays',$event)"/></el-form-item></template>
    <template v-else><el-form-item v-if="mode==='MANUAL'" label="开始时间"><el-date-picker :model-value="modelValue[startKey]" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择开始时间" @update:model-value="set(startKey,$event)"/></el-form-item><el-form-item label="截止时间"><el-date-picker :model-value="modelValue[endKey]" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :default-time="defaultEnd" placeholder="选择截止日期或时间" @update:model-value="set(endKey,$event)"/></el-form-item></template>
    <p class="window-note">{{mode==='MANUAL'?'仅在设定的起止时间内允许办理':mode==='OFFSET'?'下发后开始，基准日期加偏移天数当日23:59:59截止':'下发或发布生效时开始，指定的时间为截止时间'}}</p>
  </div>
</template>
<style scoped>.time-window-editor{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:0 16px}.time-window-editor :deep(.el-select),.time-window-editor :deep(.el-date-editor){width:100%}.window-note{grid-column:1/-1;margin:0 0 14px;color:#64748b;font-size:12px;line-height:1.6}</style>
