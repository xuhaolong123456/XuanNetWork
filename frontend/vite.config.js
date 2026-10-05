import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiTarget = process.env.API_TARGET || 'http://127.0.0.1:9090'
const host = process.env.HOST || '127.0.0.1'

export default defineConfig({
  plugins: [vue()],
  server: {
    host,
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        timeout: 30000,
        proxyTimeout: 30000
      }
    }
  }
})
