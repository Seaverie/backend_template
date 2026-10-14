import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// During `npm run dev`, /api is proxied to the FastAPI backend, so no CORS setup is needed.
export default defineConfig({
  plugins: [vue()],
  base: './',
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: { '/api': { target: process.env.API_PROXY_TARGET ?? 'http://localhost:8000', changeOrigin: true } },
  },
})
