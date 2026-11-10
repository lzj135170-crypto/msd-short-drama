import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发时通过 5173 访问，/api 代理到网关；构建产物由网关静态托管。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
