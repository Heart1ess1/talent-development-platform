<script setup lang="ts">
import {ICP_QUERY_URL,ICP_RECORD_NUMBER} from '@/config/compliance'

withDefaults(defineProps<{compact?:boolean}>(),{compact:false})
</script>

<template>
  <footer class="compliance-footer" :class="{compact}">
    <span>© 2026 人才培养平台</span>
    <span aria-hidden="true">·</span>
    <a :href="ICP_QUERY_URL" target="_blank" rel="noopener noreferrer">{{ICP_RECORD_NUMBER}}</a>
  </footer>
</template>

<style scoped>
.compliance-footer{display:flex;align-items:center;justify-content:center;gap:6px;padding:14px 20px;color:#8a96a8;background:transparent;font-size:12px;line-height:1.5;text-align:center}
.compliance-footer.compact{padding:10px 0 0;font-size:10px}
.compliance-footer a{color:inherit;text-decoration:none;transition:color .18s ease}
.compliance-footer a:hover,.compliance-footer a:focus-visible{color:#1769aa;text-decoration:underline;outline:none}
@media(max-width:520px){.compliance-footer{flex-wrap:wrap;padding-right:14px;padding-left:14px}}
</style>
