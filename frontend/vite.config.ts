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
        // 统一前缀 → 转发时剥掉前缀
        '/crmAgent-api': {
          target: backendUrl,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/crmAgent-api/, ''),
        },
      },
    },
  }
})
