import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 加载环境变量
  const env = loadEnv(mode, process.cwd())
  const backendUrl = env.VITE_BACKEND_URL || 'http://localhost:9090'

  return {
    base: '/crmAgent/',
    plugins: [vue(), vueDevTools()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 9173,
      host: true,
      open: true,
      proxy: {
        // SSE 长连接通道
        '/sse': { target: backendUrl, changeOrigin: true },
        // 聊天/检索/联网等业务接口
        '/chat': { target: backendUrl, changeOrigin: true },
        '/rag':  { target: backendUrl, changeOrigin: true },
        '/internet': { target: backendUrl, changeOrigin: true },
        // 媒体/音频子模块
        '/api':  { target: backendUrl, changeOrigin: true },
        // 统一前缀（兼容配置了 VITE_API_BASE_URL 的场景）
        '/crmAgent-api': {
          target: backendUrl,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/crmAgent-api/, ''),
        },
      },
    },
  }
})
