import { describe, expect, it } from 'vitest'
import { NAVIGATION_DENYLIST, notificationFromPush, safeTarget, shellMayAnswer } from './routes'

const source: string = Object.values(import.meta.glob<string>('./sw.ts', { query: '?raw', import: 'default', eager: true }))[0] ?? ''

describe('service worker routes (§22.10)', () => {
  it('never answers the server paths with the offline shell', () => {
    for (const p of ['/api/v1/account', '/api', '/mcp', '/ws', '/v3/api-docs', '/swagger-ui/index.html', '/oauth2/authorize', '/.well-known/x', '/h2-console', '/actuator/health', '/login/oauth2/code']) {
      expect({ p, shell: shellMayAnswer(p) }).toEqual({ p, shell: false })
    }
    for (const p of ['/', '/stock/AAPL', '/stock/BRK.B', '/settings/notifications', '/alerts', '/apis', '/wsj', '/account/orders/ord_1']) {
      expect({ p, shell: shellMayAnswer(p) }).toEqual({ p, shell: true })
    }
    expect(NAVIGATION_DENYLIST.length).toBeGreaterThanOrEqual(10)
  })

  it('turns a push payload into a notification, and anything else into a generic one', () => {
    expect(notificationFromPush(JSON.stringify({ v: 1, kind: 'PRICE_ALERT', title: 'AAPL ≥ $230.00', body: 'AAPL is at $230.15.', url: '/stock/AAPL', tag: 'alert:alr_1' }))).toEqual({
      title: 'AAPL ≥ $230.00',
      body: 'AAPL is at $230.15.',
      tag: 'alert:alr_1',
      url: '/stock/AAPL',
    })
    expect(notificationFromPush(null).title).toBe('stocks')
    expect(notificationFromPush('not json').body).toBe('You have a new notification.')
    expect(notificationFromPush(JSON.stringify({ title: 'x'.repeat(500) })).title).toHaveLength(120)
  })

  it('opens same-origin paths only', () => {
    const o = 'https://stocks.example.com'
    expect(safeTarget('/stock/AAPL', o)).toBe(`${o}/stock/AAPL`)
    expect(safeTarget('/account/orders/ord_1?x=1', o)).toBe(`${o}/account/orders/ord_1?x=1`)
    for (const bad of ['https://evil.example.com/', '//evil.example.com/x', 'javascript:alert(1)', '/\\evil.example.com', 42, null]) {
      expect({ bad, target: safeTarget(bad, o) }).toEqual({ bad, target: `${o}/` })
    }
  })
})

describe('service worker source: no runtime caching (§22.10)', () => {
  it('imports only precaching and routing', () => {
    const imports = [...source.matchAll(/from '([^']+)'/g)].map((m) => m[1])
    expect(imports.sort()).toEqual(['./routes', 'workbox-precaching', 'workbox-routing'])
  })

  it('registers the navigation shell route and nothing else, and no fetch handler', () => {
    expect(source.match(/registerRoute\(/g)).toHaveLength(1)
    expect(source).toMatch(/registerRoute\(new NavigationRoute\(createHandlerBoundToURL\('\/index\.html'\), \{ denylist: NAVIGATION_DENYLIST \}\)\)/)
    expect(source).not.toMatch(/addEventListener\('fetch'/)
    expect(source).not.toMatch(/caches\.open|CacheFirst|NetworkFirst|StaleWhileRevalidate|runtimeCaching/)
  })
})
