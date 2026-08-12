import {defineConfig,loadEnv} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath,URL} from 'node:url'

export default defineConfig(({mode})=>{
  const env=loadEnv(mode,'.','')
  return {
    base:env.VITE_ASSET_BASE||'/',
    plugins:[vue()],
    resolve:{alias:{'@':fileURLToPath(new URL('./src',import.meta.url))}},
    server:{port:5173,proxy:{'/api':{target:'http://localhost:8080'}}}
  }
})
