<template>
  <div class="page">
    <el-result icon="warning" title="页面不存在" sub-title="您访问的地址不正确，或该页面已被移除">
      <template #extra>
        <el-button type="primary" @click="router.push('/dashboard')">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import {useRouter} from 'vue-router'

const router=useRouter()
</script>
