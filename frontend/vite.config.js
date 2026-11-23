import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端跑在 5173，后端 HTTP 在 8080、WebSocket 在 9001。
// 这里把 /api 代理到 8080，前端代码里就能直接写相对路径 /api/xxx，
// 不用在每个文件里拼后端地址，也不需要后端开 CORS。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
