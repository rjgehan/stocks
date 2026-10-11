/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { tanstackRouter } from '@tanstack/router-plugin/vite'
import { VitePWA } from 'vite-plugin-pwa'

// ARCHITECTURE §24.6: the dev server proxies the backend paths so the browser sees ONE origin
// (cookies, CSRF and the STOMP handshake behave exactly as in production).
const backend = process.env.STOCKS_BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [
    // Must run before the React plugin (generates src/routeTree.gen.ts from src/routes).
    tanstackRouter({
      target: 'react',
      autoCodeSplitting: true,
      routesDirectory: './src/routes',
      generatedRouteTree: './src/routeTree.gen.ts',
      quoteStyle: 'single',
      semicolons: false,
    }),
    react(),
    tailwindcss(),
    // §22.10 (Phase 2): the service worker (src/sw/sw.ts → dist/sw.js) precaches the app shell only; no runtime
    // caching, so /api, /mcp and /ws never touch a cache (scripts/check-sw.mjs checks the build). The static
    // public/manifest.webmanifest and icons stay as they are (manifest: false). The SPA registers the worker itself
    // (src/features/pwa), never in dev or mock mode.
    VitePWA({
      strategies: 'injectManifest',
      srcDir: 'src/sw',
      filename: 'sw.ts',
      registerType: 'prompt',
      injectRegister: false,
      manifest: false,
      injectManifest: {
        globPatterns: ['index.html', 'theme-init.js', 'assets/**/*.{js,css,woff2,svg,png,webp}', '*.{png,svg,ico}'],
        maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
      },
      devOptions: { enabled: false },
    }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // changeOrigin stays false: the backend sees Host/Origin localhost:5173, which §17.1 allows in dev.
      '/api': { target: backend },
      '/mcp': { target: backend },
      '/v3': { target: backend },
      '/swagger-ui': { target: backend },
      // Phase 2 OAuth (§17.9): the authorization server's endpoints and discovery documents
      '/oauth2': { target: backend },
      '/.well-known': { target: backend },
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
  preview: {
    port: 4173,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    target: 'es2022',
    // Spring Boot serves dist/ from classpath:/static; /assets/** gets an immutable cache header (§24.3).
    assetsDir: 'assets',
    chunkSizeWarningLimit: 600,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
})
