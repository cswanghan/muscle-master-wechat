import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiTarget = process.env.API_PROXY ?? 'http://127.0.0.1:8080'

const proxy = {
  '/actuator': { target: apiTarget, changeOrigin: true },
  '/api': { target: apiTarget, changeOrigin: true },
}

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    port: 4173,
    proxy,
  },
})
