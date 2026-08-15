import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiTarget = process.env.API_PROXY ?? 'http://127.0.0.1:8080'

const proxy = {
  '/actuator': { target: apiTarget, changeOrigin: true },
  '/api': { target: apiTarget, changeOrigin: true },
}

function walkthroughIndex() {
  return {
    name: 'walkthrough-index',
    configureServer(server: { middlewares: { use: (fn: (req: { url?: string }, _res: unknown, next: () => void) => void) => void } }) {
      server.middlewares.use((req, _res, next) => {
        const path = (req.url || '').split('?')[0]
        if (path === '/walkthrough' || path === '/walkthrough/') {
          req.url = '/walkthrough/index.html'
        }
        next()
      })
    },
  }
}

export default defineConfig({
  plugins: [vue(), walkthroughIndex()],
  server: {
    port: 5173,
    host: '0.0.0.0',
    allowedHosts: true,
    proxy,
  },
  preview: {
    port: 4173,
    proxy,
  },
})
