import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// The built app is served by the Spring Boot API from the same origin (see WebConfig.kt), so the
// API is reached at /api/v1 with no CORS. During `npm run dev`, /api is proxied to the backend.
export default defineConfig({
  plugins: [vue()],
  base: '/',
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: { outDir: 'dist', emptyOutDir: true, sourcemap: false, chunkSizeWarningLimit: 700 },
  server: { port: 5173, proxy: { '/api': { target: process.env.API_PROXY_TARGET ?? 'http://localhost:8000', changeOrigin: true } } },
  preview: { port: 4173, proxy: { '/api': { target: process.env.API_PROXY_TARGET ?? 'http://localhost:8000', changeOrigin: true } } },
})
