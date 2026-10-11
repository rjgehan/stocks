// Checks the built service worker (ARCHITECTURE §22.10), run after `vite build` (`npm run build`):
//  - dist/sw.js exists and precaches the app shell: index.html and the hashed /assets/* (so the app opens offline);
//  - nothing under the server's own paths (/api, /mcp, /ws, /v3, /swagger-ui, /actuator, ...) is precached;
//  - no runtime caching strategy is configured: private API data must never land in a cache;
//  - the navigation denylist that keeps /api, /mcp and /ws away from the offline shell is in the bundle;
//  - size budgets (gzip) for the chunks every quote-page visit loads: the StockScreen chunk stays lean because the
//    Advanced ticket, the indicator panel, the alert sheet, news, ratings and Time & Sales load on demand.
// Exits 1 with the reasons on any failure. Usage: node scripts/check-sw.mjs [distDir]
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

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

// Size budgets: gzip bytes of the named chunk (dist/assets/<name>-<hash>.js).
const BUDGETS = { StockScreen: 19 * 1024 }
const assets = existsSync(join(dist, 'assets')) ? readdirSync(join(dist, 'assets')) : []
const sizes = []
for (const [name, budget] of Object.entries(BUDGETS)) {
  const file = assets.find((f) => new RegExp(`^${name}-[\\w-]+\\.js$`).test(f))
  if (!file) {
    failures.push(`no ${name} chunk in dist/assets (renamed? update BUDGETS)`)
    continue
  }
  const gz = gzipSync(readFileSync(join(dist, 'assets', file))).length
  sizes.push(`${name} ${(gz / 1024).toFixed(1)} KB gzip`)
  if (gz > budget) failures.push(`${file} is ${(gz / 1024).toFixed(1)} KB gzip, over its ${budget / 1024} KB budget (load the new part on demand)`)
}

if (failures.length > 0) {
  console.error(`check-sw: ${swPath}\n  - ${failures.join('\n  - ')}`)
  process.exit(1)
}
console.log(`check-sw: ${swPath} OK (${urls.length} precached files, no server paths, no runtime caching; ${sizes.join(', ')})`)
