import { type Locator, type Page, expect, test } from '@playwright/test'
import { MOCK, open } from './support/app'

/**
 * "Run now" (§20.9) at all three viewports: the bot's owner or the admin asks for a check-in from the app, and the
 * button follows the request (polled every 5 s) until the claude-trader service reports back.
 *  - mock mode: the seeded bot @claude on its profile; the mock's pretend trader claims the request after 3 s and
 *    reports DONE after 9 s;
 *  - a real backend (`make e2e`): the admin creates a bot, runs it from Admin → Bots and dry-runs it from its
 *    profile, and the test plays the trader with the bot's own key (claim, then finish DONE or FAILED). Until a
 *    trader has asked for the new bot's requests, its button is disabled: nothing would pick a run up.
 */

// a fresh route chunk per page in Vite's dev server, and up to two 5 s polls per state
test.describe.configure({ timeout: 90_000 })

const POLL = { timeout: 20_000 }

async function createBot(page: Page, handle: string): Promise<string> {
  await open(page, '/admin/players')
  await page.getByRole('button', { name: 'Add player' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a player' })
  await dialog.getByRole('radiogroup', { name: 'Player kind' }).getByRole('radio', { name: 'AI bot' }).click()
  await dialog.getByLabel('Handle').fill(handle)
  await dialog.getByLabel('Display name').fill('Run Now Bot')
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

const runNow = (scope: Locator, handle: string): Locator => scope.getByRole('group', { name: `Run @${handle} now` })

async function dryRun(page: Page, group: Locator, handle: string): Promise<void> {
  await group.getByRole('button', { name: 'More run options' }).click()
  const menu = page.getByRole('dialog', { name: `Run options for @${handle}` })
  await expect(menu.getByText(/runs @.+'s normal check-in on the server .* within about a minute/i)).toBeVisible()
  await menu.getByRole('button', { name: 'Dry run (no orders)' }).click()
}

test('the owner runs a bot now and follows the request until the trader reports back', async ({ page }) => {
  test.skip(!MOCK, 'The real-backend flow is the next test')
  await open(page, '/players/claude')
  const group = runNow(page.getByRole('region', { name: 'Bot activity' }), 'claude')
  const run = group.getByRole('button', { name: 'Run now' })
  await expect(run).toHaveAttribute('title', /within about a minute/)
  await run.click()
  const status = group.getByRole('status')
  await expect(status).toContainText(/Requested .* · waiting for the trader/)
  await expect(run).toBeDisabled()
  await expect(status).toContainText(/picked up .* · (running|done)/, POLL)
  await expect(status).toContainText(/ · done /, POLL)
  await expect(status).toContainText('Check-in done in 6 s, 6 turns.')
  await expect(run).toBeEnabled()

  await dryRun(page, group, 'claude')
  await expect(status).toContainText(/Dry run requested .* · waiting for the trader/)
})

test('Run now against the real backend: the trader claims the request with the bot key and reports back', async ({ page }, testInfo) => {
  test.skip(MOCK, 'Needs the real backend: the test claims and finishes the request with the bot key')
  const handle = `rn${testInfo.project.name[0]}${Date.now() % 1_000_000}`
  const key = await createBot(page, handle)
  const auth = { Authorization: `Bearer ${key}`, 'X-Stocks-Client': 'claude-code/trader' }

  await open(page, '/admin/bots')
  const card = page.getByRole('list', { name: 'Bots' }).getByRole('listitem', { name: `Bot ${handle}` })
  const group = runNow(card, handle)
  const status = group.getByRole('status')
  // no trader serves the new bot yet
  await expect(status).toContainText(`No claude-trader service has asked for @${handle}'s runs`)
  await expect(group.getByRole('button', { name: 'Run now' })).toBeDisabled()

  // the trader's minute poll: nothing to claim yet, but now the app knows a trader serves the bot
  expect((await page.request.post('/api/v1/agent-runs/requests/claim', { headers: auth })).status()).toBe(204)
  await page.reload()
  await expect(group.getByRole('button', { name: 'Run now' })).toBeEnabled()
  await group.getByRole('button', { name: 'Run now' }).click()
  await expect(status).toContainText(/Requested .* · waiting for the trader/)
  await expect(group.getByRole('button', { name: 'Run now' })).toBeDisabled()

  // the trader's side (tools/claude-trader): claim with the bot's own key, then report
  const claim = await page.request.post('/api/v1/agent-runs/requests/claim', { headers: auth })
  expect(claim.status(), await claim.text()).toBe(200)
  const claimed = (await claim.json()) as { id: string; handle: string; requestedBy: string; dryRun: boolean; status: string }
  expect(claimed).toMatchObject({ handle, requestedBy: process.env.E2E_USER ?? claimed.requestedBy, dryRun: false, status: 'CLAIMED' })
  await expect(status).toContainText(/picked up .* · running/, POLL)
  const finish = await page.request.post(`/api/v1/agent-runs/requests/${claimed.id}/finish`, {
    headers: auth,
    data: { status: 'DONE', message: 'Check-in done in 1 min 4 s, 9 turns. Bought 1 NVDA.' },
  })
  expect(finish.status(), await finish.text()).toBe(200)
  await expect(status).toContainText(/picked up .* · done /, POLL)
  await expect(status).toContainText('Check-in done in 1 min 4 s, 9 turns. Bought 1 NVDA.')

  // the profile: the same request, then a dry run that fails
  await page.goto(`/players/${handle}`)
  const onProfile = runNow(page.getByRole('region', { name: 'Bot activity' }), handle)
  await expect(onProfile.getByRole('status')).toContainText('Bought 1 NVDA.')
  await dryRun(page, onProfile, handle)
  await expect(onProfile.getByRole('status')).toContainText(/Dry run requested .* · waiting for the trader/)
  const second = await page.request.post('/api/v1/agent-runs/requests/claim', { headers: auth })
  expect(second.status()).toBe(200)
  const dry = (await second.json()) as { id: string; dryRun: boolean }
  expect(dry.dryRun).toBe(true)
  await page.request.post(`/api/v1/agent-runs/requests/${dry.id}/finish`, { headers: auth, data: { status: 'FAILED', message: 'claude exited with code 1' } })
  await expect(onProfile.getByRole('status')).toContainText(/ · failed /, POLL)
  await expect(onProfile.getByRole('status')).toContainText('claude exited with code 1')
  await expect(onProfile.getByText('Failed', { exact: true })).toBeVisible()
  expect((await page.request.post('/api/v1/agent-runs/requests/claim', { headers: auth })).status()).toBe(204)
})
