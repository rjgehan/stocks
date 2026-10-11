// Checks the built service worker (ARCHITECTURE §22.10), run after `vite build` (`npm run build`):
//  - dist/sw.js exists and precaches the app shell: index.html and the hashed /assets/* (so the app opens offline);
//  - nothing under the server's own paths (/api, /mcp, /ws, /v3, /swagger-ui, /actuator, ...) is precached;
//  - no runtime caching strategy is configured: private API data must never land in a cache;
//  - the navigation denylist that keeps /api, /mcp and /ws away from the offline shell is in the bundle.
// Exits 1 with the reasons on any failure. Usage: node scripts/check-sw.mjs [distDir]
import { existsSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const dist = resolve(process.argv[2] ?? fileURLToPath(new URL('../dist', import.meta.url)))
const swPath = join(dist, 'sw.js')
const failures = []

if (!existsSync(swPath)) {
  console.error(`check-sw: ${swPath} is missing (vite-plugin-pwa did not build the service worker)`)
  process.exit(1)
}
const sw = readFileSync(swPath, 'utf8')

// The injected precache manifest: [{"revision":"…","url":"index.html"}, …] (keys in either order).
const urls = [...sw.matchAll(/url:\s*"([^"]+)"|"url"\s*:\s*"([^"]+)"/g)].map((m) => m[1] ?? m[2])
if (urls.length === 0) failures.push('no precache manifest found in sw.js')
if (!urls.includes('index.html')) failures.push('index.html (the offline shell) is not precached')
if (!urls.some((u) => /^assets\/.+\.js$/.test(u))) failures.push('no hashed /assets/*.js is precached')
const serverPaths = /^\/?(api|mcp|ws|v3|swagger-ui|actuator|oauth2|\.well-known|h2-console)(\/|$)/
for (const u of urls) {
  if (serverPaths.test(u)) failures.push(`server path precached: ${u}`)
}

// Runtime caching (generateSW's runtimeCaching, or a strategy registered by hand) would cache responses.
for (const marker of ['runtimeCaching', 'StaleWhileRevalidate', 'NetworkFirst', 'CacheFirst', 'ExpirationPlugin', 'CacheableResponsePlugin']) {
  if (sw.includes(marker)) failures.push(`runtime caching marker "${marker}" found in sw.js`)
}

// The navigation denylist of src/sw/routes.ts (escaped slashes in the minified regex literals).
for (const path of ['api', 'mcp', 'ws']) {
  if (!new RegExp(`\\^\\\\/${path}\\(`).test(sw)) failures.push(`the navigation denylist for /${path} is missing`)
}

if (failures.length > 0) {
  console.error(`check-sw: ${swPath}\n  - ${failures.join('\n  - ')}`)
  process.exit(1)
}
console.log(`check-sw: ${swPath} OK (${urls.length} precached files, no server paths, no runtime caching)`)
