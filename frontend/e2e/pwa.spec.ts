import { expect, test } from '@playwright/test'
import { MOCK, open } from './support/app'

/**
 * The PWA (§22.10) against the real jar only: mock mode and the Vite dev server register no service worker. The
 * worker precaches the app shell, so the app opens offline, and it never caches or answers an /api request.
 */
test.skip(MOCK, 'The service worker only runs in the production build (make e2e)')

test('the service worker serves the app shell offline and never caches the API', async ({ page, context }) => {
  await open(page, '/watchlist')
  // the first visit installs the worker; it controls the page from the next navigation on
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
  })
  await page.reload()
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true)

  // what the caches hold: the shell, and nothing from /api, /mcp or /ws
  const cached = await page.evaluate(async () => {
    const urls: string[] = []
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) urls.push(new URL(request.url).pathname)
    }
    return urls
  })
  expect(cached).toContain('/index.html')
  expect(cached.some((u) => u.startsWith('/assets/'))).toBe(true)
  expect(cached.filter((u) => /^\/(api|mcp|ws)(\/|$)/.test(u))).toEqual([])

  await context.setOffline(true)
  try {
    // a deep link opens from the precached shell instead of the browser's offline page ...
    await page.goto('/account')
    await expect(page).toHaveTitle(/stocks/i)
    await expect(page.getByRole('alert').filter({ hasText: 'You appear to be offline' })).toBeVisible()
    // ... while the API goes to the network, which fails: nothing is served from a cache
    const api = await page.evaluate(() =>
      fetch('/api/v1/me', { credentials: 'same-origin' }).then(
        (r) => `HTTP ${r.status}`,
        () => 'network error',
      ),
    )
    expect(api).toBe('network error')
  } finally {
    await context.setOffline(false)
  }
  // back online, the app picks up again
  await open(page, '/watchlist')
  await expect(page.getByRole('navigation', { name: 'Main' }).first()).toBeVisible()
})
