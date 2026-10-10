import { type APIRequestContext, type Page, expect } from '@playwright/test'

/**
 * advanceClock (§18.5): moves the backend's SIM clock forward through the test-only endpoint
 * `POST /api/v1/test/clock {advance: ISO-8601 duration}` (profile `test` only), then lets the engine sweep
 * and the JobScheduler catch up. Works with the page's session (cookie + CSRF) or with a bot API key.
 */
export async function advanceClock(page: Page, advance: string): Promise<string> {
  const csrf = await csrfToken(page)
  const res = await page.request.post('/api/v1/test/clock', {
    data: { advance },
    headers: { 'X-XSRF-TOKEN': csrf },
  })
  expect(res.status(), await res.text()).toBe(200)
  return ((await res.json()) as { now: string }).now
}

/** Same, authenticated with a bot key (`Authorization: Bearer stk_…`; no CSRF on the key path, §17.1). */
export async function advanceClockWithKey(request: APIRequestContext, apiKey: string, advance: string): Promise<string> {
  const res = await request.post('/api/v1/test/clock', {
    data: { advance },
    headers: { Authorization: `Bearer ${apiKey}` },
  })
  expect(res.status(), await res.text()).toBe(200)
  return ((await res.json()) as { now: string }).now
}

async function csrfToken(page: Page): Promise<string> {
  const find = async () => (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value
  let token = await find()
  if (!token) {
    await page.request.get('/api/v1/auth/csrf')
    token = await find()
  }
  if (!token) throw new Error('No XSRF-TOKEN cookie after GET /api/v1/auth/csrf')
  return token
}
