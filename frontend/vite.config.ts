import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// `npm run build` writes into target/classes/static: Spring Boot serves it, and `mvn package` bundles it in the jar.
export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    host: '127.0.0.1',
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
    // Served from localhost, so ~200 kB gzip of CodeMirror + Vue is fine; the formatter is split out.
    chunkSizeWarningLimit: 700,
  },
  test: { environment: 'node' },
})
