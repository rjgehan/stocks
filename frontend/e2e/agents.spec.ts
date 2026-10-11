import { type Page, expect, test } from '@playwright/test'
import { MOCK, open } from './support/app'
import { emitFeed } from './support/mock'

/**
 * Phase 2 agents (§17.10, §20.6, §20.7) at all three viewports:
 *  - mock mode: the seeded league bots' Admin → Bots cards (spend vs cap, runs), a bot profile's Bot activity and
 *    journal, journal items in the feed (filter, live), and Recent API calls (Mine / Everyone);
 *  - a real backend (`make e2e`): the main flow end to end: the admin creates a bot, the bot reports a run and writes
 *    a journal note with its own key (as the agent runner does), and the admin sees the run under Admin → Bots, the
 *    bot's public Bot activity, the note held back by the feed delay, and the bot's calls in Recent API calls.
 */

// several pages per test, and the first visit compiles each route chunk in Vite's dev server
test.describe.configure({ timeout: 90_000 })

async function createBot(page: Page, handle: string): Promise<string> {
  await open(page, '/admin/players')
  await page.getByRole('button', { name: 'Add player' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a player' })
  await dialog.getByRole('radiogroup', { name: 'Player kind' }).getByRole('radio', { name: 'AI bot' }).click()
  await dialog.getByLabel('Handle').fill(handle)
  await dialog.getByLabel('Display name').fill('Runner Bot')
  await dialog.getByLabel('Model label').fill('Claude')
  await dialog.getByRole('button', { name: 'Create bot' }).click()
  const reveal = page.getByRole('dialog', { name: 'Copy this key now' })
  const field = reveal.getByLabel('API key', { exact: true })
  await expect(field).toHaveText(/^stk_[a-z2-7]{12}_[A-Za-z0-9_-]{43}$/)
  const key = (await field.textContent())!.trim()
  await reveal.getByLabel('I copied the key').check()
  await reveal.getByRole('button', { name: 'Done' }).click()
  await expect(reveal).toBeHidden()
  return key
}

/** The first frames of `GET /api/v1/stream` up to `event: hello`, then the stream is closed (§19.1). */
async function streamHello(url: string, auth: Record<string, string>) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 20_000)
  try {
    const res = await fetch(url, { headers: auth, signal: ctrl.signal })
    let text = ''
    if (res.body) {
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      while (!text.includes('event: hello')) {
        const { value, done } = await reader.read()
        if (done) break
        text += decoder.decode(value, { stream: true })
      }
    }
    return { res, text }
  } finally {
    clearTimeout(timer)
    ctrl.abort()
  }
}

test('a bot reports its run and writes a journal note; the admin sees both', async ({ page, baseURL }) => {
  test.skip(MOCK, 'Needs the real backend: the bot calls the API with its own key')
  const handle = `runner${Date.now() % 1_000_000}`
  const key = await createBot(page, handle)
  const auth = { Authorization: `Bearer ${key}` }

  // §19.1: the bot's key opens the SSE stream, unbuffered and uncacheable for proxies such as Cloudflare; the
  // admin's browser session cannot (it has /ws).
  const { res: stream, text: hello } = await streamHello(`${baseURL}/api/v1/stream?topics=session,orders,feed`, auth)
  expect(stream.status).toBe(200)
  expect(stream.headers.get('content-type')).toMatch(/^text\/event-stream/)
  expect(stream.headers.get('cache-control')).toBe('no-store, no-transform')
  expect(stream.headers.get('x-accel-buffering')).toBe('no')
  expect(hello).toMatch(/^retry: 3000\n\n/)
  expect(hello).toContain('"topics":["SESSION","ORDERS","FEED"]')
  expect((await page.request.get('/api/v1/stream?topics=session')).status()).toBe(403)

  // The agent runner's report (§20.6), with the bot's own key, timed on the server's (SIM) clock.
  const clock = await page.request.get('/api/v1/market/clock', { headers: auth })
  expect(clock.status()).toBe(200)
  const serverNow = Date.parse(((await clock.json()) as { now: string }).now)
  const started = new Date(serverNow - 95_000).toISOString()
  const report = await page.request.post('/api/v1/agent-runs', {
    headers: auth,
    data: {
      runRef: `e2e-${handle}-run1`,
      trigger: 'SCHEDULE',
      slot: '09:45',
      model: 'claude-opus-5-5',
      status: 'completed',
      startedAt: started,
      endedAt: new Date(Date.parse(started) + 94_000).toISOString(),
      turns: 5,
      toolCalls: 7,
      toolErrors: 1,
      orders: 1,
      tokens: { input: 5210, cacheRead: 96000, cacheWrite: 12400, output: 4100 },
      costUsd: '0.1834',
      daySpentUsd: '0.1834',
      dailyCapUsd: '1.00',
      message: null,
      runnerVersion: 'e2e',
      tools: [
        { tool: 'get_quotes', code: 'OK', calls: 5 },
        { tool: 'place_order', code: 'OK', calls: 1 },
        { tool: 'place_order', code: 'RATE_LIMITED', calls: 1 },
      ],
    },
  })
  expect(report.status(), await report.text()).toBe(201)
  // A journal note (§20.7): returned at once, public only after the feed delay.
  const note = await page.request.post('/api/v1/journal', { headers: auth, data: { text: 'E2E thesis: <b>starter</b> AAPL position.', symbols: ['AAPL'] } })
  expect(note.status(), await note.text()).toBe(201)
  expect(((await note.json()) as { note: { untrusted: { text: string } } }).note.untrusted.text).toBe('E2E thesis: <b>starter</b> AAPL position.')

  await open(page, '/admin/bots')
  const card = page.getByRole('list', { name: 'Bots' }).getByRole('listitem', { name: `Bot ${handle}` })
  await expect(card).toBeVisible()
  await expect(card).toContainText('Completed')
  await expect(card.getByRole('meter', { name: `Spend today for ${handle}` })).toHaveAttribute('aria-valuetext', '$0.18 of $1.00')
  await card.getByText(/Tool calls, last 7 days/).click()
  await expect(card).toContainText('RATE_LIMITED × 1')
  await expect(page.getByRole('list', { name: 'Agent runs' }).getByText(`@${handle}`).first()).toBeVisible()

  await page.goto(`/players/${handle}`)
  const activity = page.getByRole('region', { name: 'Bot activity' })
  await expect(activity).toContainText('1 run')
  await expect(activity).toContainText('Completed · 7 tool calls · 1 order')
  await expect(activity).not.toContainText('$')
  await expect(page.getByRole('region', { name: 'Journal' })).toContainText('No journal notes yet')

  await page.goto('/settings/api-calls')
  await page.getByRole('radiogroup', { name: 'Whose calls' }).getByRole('radio', { name: 'Everyone' }).click()
  await page.getByLabel('Player').fill(handle)
  const calls = page.getByRole('list', { name: 'API calls' })
  await expect(calls.getByText('POST /api/v1/agent-runs')).toBeVisible()
  await expect(calls.getByText('POST /api/v1/journal')).toBeVisible()
})

test('Admin → Bots shows each bot against its daily cap, with its runs', async ({ page }) => {
  await open(page, '/admin/bots')
  await expect(page.getByRole('link', { name: 'Bots' }).first()).toBeVisible()
  const runs = page.getByRole('region', { name: 'Runs' })
  await expect(runs).toBeVisible()
  if (!MOCK) return // a fresh e2e league: the main-flow test covers the real cards
  const claude = page.getByRole('list', { name: 'Bots' }).getByRole('listitem', { name: 'Bot claude' })
  await expect(claude).toBeVisible()
  await expect(claude.getByRole('meter', { name: 'Spend today for claude' })).toHaveAttribute('aria-valuetext', /^\$\d+\.\d\d of \$1\.00$/)
  await expect(claude.getByText('Last run')).toBeVisible()
  await expect(claude.getByRole('img', { name: /Daily spend of claude/ })).toBeVisible()
  await claude.getByText(/Tool calls, last 7 days/).click()
  await expect(claude.getByText('get_quotes')).toBeVisible()

  await runs.getByLabel('Bot').selectOption('chatgpt')
  const list = page.getByRole('list', { name: 'Agent runs' })
  await expect(list.getByText('@chatgpt').first()).toBeVisible()
  await expect(list.getByText('@claude')).toHaveCount(0)
  await runs.getByLabel('Status').selectOption('SKIPPED_NON_TRADING_DAY')
  await expect(list.getByText('Skipped: market closed').first()).toBeVisible()
})

test("a bot's profile shows its activity and its public journal; notes reach the feed", async ({ page }) => {
  test.skip(!MOCK, 'The league bots are seeded in mock mode; the main-flow test covers the real profile')
  await open(page, '/players/claude')
  const activity = page.getByRole('region', { name: 'Bot activity' })
  await expect(activity).toBeVisible()
  await expect(activity.getByText('Last 7 days')).toBeVisible()
  const journal = page.getByRole('region', { name: 'Journal' })
  await expect(journal.getByRole('list', { name: 'Journal notes' }).getByRole('listitem').first()).toContainText('Thesis: NVDA guidance raised')
  // human players have neither
  await page.goto('/players/alice')
  await expect(page.getByRole('heading', { name: 'Alice', level: 1 })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Bot activity' })).toHaveCount(0)

  await page.goto('/league?view=FEED')
  const feed = page.getByRole('list', { name: 'League feed' })
  await page.getByRole('radiogroup', { name: 'Feed filter' }).getByRole('radio', { name: 'Journal' }).click()
  await expect(feed.getByText('wrote in their journal').first()).toBeVisible()
  await expect(feed.getByText(/ bought | sold /)).toHaveCount(0)
  await emitFeed(page, 'JOURNAL', 'chatgpt')
  const fresh = feed.locator(':scope > li > ul > li').filter({ hasText: 'New' }).first()
  await expect(fresh).toContainText('ChatGPT')
  await expect(fresh).toContainText('wrote in their journal')
})

test('Settings → Recent API calls lists my keys and my bots; the admin can see everyone', async ({ page }) => {
  await open(page, '/settings')
  await page.getByRole('link', { name: /Recent API calls/ }).click()
  await expect(page).toHaveURL(/\/settings\/api-calls/)
  const list = page.getByRole('list', { name: 'API calls' })
  if (!MOCK) {
    await expect(list.or(page.getByText('No API calls yet')).first()).toBeVisible()
    return
  }
  await expect(list.getByRole('listitem').first()).toBeVisible()
  await expect(list.getByText('@alice')).toHaveCount(0)
  await page.getByLabel('Player (you or one of your bots)').fill('claude')
  await expect(list.getByText('MCP').first()).toBeVisible()
  await page.getByLabel('Player (you or one of your bots)').fill('alice')
  await expect(page.getByText('No player @alice among yours')).toBeVisible()
  await page.getByRole('radiogroup', { name: 'Whose calls' }).getByRole('radio', { name: 'Everyone' }).click()
  await expect(list.getByText('@alice').first()).toBeVisible()
})
