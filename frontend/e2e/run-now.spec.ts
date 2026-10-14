import { type Locator, type Page, expect, test } from '@playwright/test'
import { MOCK, confirmIfAsked, open, signInAs } from './support/app'
import { csrfToken } from './support/clock'

/**
 * "Run now" (§20.9) at all three viewports: the bot's owner or the admin asks for a check-in from the app, and the
 * button follows the request (polled every 5 s) until the claude-trader service reports back.
 *  - mock mode: the seeded bot @claude on its profile; the mock's pretend trader claims the request after 3 s and
 *    reports DONE after 9 s;
 *  - a real backend (`make e2e`): the admin creates a bot, runs it from Admin → Bots and dry-runs it from its
 *    profile, and the test plays the trader with the bot's own key (claim, then finish DONE or FAILED). Until a
 *    trader has asked for the new bot's requests, its button is disabled: nothing would pick a run up.
 * "Run all" from Admin → Bots: a confirmation naming the bots, then a per-bot summary, and each bot's Run now line
 * follows its request. Against a real backend the admin's Run all would ask for every bot a trader served lately (the
 * other tests' bots too), so there the admin only opens the confirmation, and a human of the test's own, the owner of
 * one bot, runs all of their bots: that one only.
 */

// a fresh route chunk per page in Vite's dev server, and up to two 5 s polls per state
test.describe.configure({ timeout: 90_000 })

const POLL = { timeout: 20_000 }

const PASSWORD = 'a long enough password'

/** The admin adds a human player (Admin → Players → Add player). */
async function createHuman(page: Page, handle: string): Promise<void> {
  await open(page, '/admin/players')
  await page.getByRole('button', { name: 'Add player' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a player' })
  await dialog.getByRole('radiogroup', { name: 'Player kind' }).getByRole('radio', { name: 'Human' }).click()
  await dialog.getByLabel('Handle').fill(handle)
  await dialog.getByLabel('Display name').fill('Bot Owner')
  await dialog.getByLabel('Password').fill(PASSWORD)
  await dialog.getByRole('button', { name: 'Create player' }).click()
  await confirmIfAsked(page)
  await expect(dialog).toBeHidden()
}

/** The admin adds an AI bot with an API key, run by `owner` (default: the admin), and returns the key. */
async function createBot(page: Page, handle: string, owner?: string): Promise<string> {
  await open(page, '/admin/players')
  await page.getByRole('button', { name: 'Add player' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a player' })
  await dialog.getByRole('radiogroup', { name: 'Player kind' }).getByRole('radio', { name: 'AI bot' }).click()
  await dialog.getByLabel('Handle').fill(handle)
  await dialog.getByLabel('Display name').fill('Run Now Bot')
  await dialog.getByLabel('Model label').fill('Claude')
  if (owner) await dialog.getByLabel('Owner', { exact: true }).fill(owner)
  await dialog.getByRole('button', { name: 'Create bot' }).click()
  const reveal = page.getByRole('dialog', { name: 'Copy this key now' })
  // the key, or first the password prompt of a recent-auth route (other specs may age the sign-in)
  await expect(reveal.or(page.getByRole('dialog', { name: "Confirm it's you" }))).toBeVisible({ timeout: 15_000 })
  if (!(await reveal.isVisible())) await confirmIfAsked(page)
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

const runAllGroup = (page: Page): Locator => page.getByRole('group', { name: 'Run all bots' })

async function startRunAll(page: Page, dry: boolean): Promise<Locator> {
  const group = runAllGroup(page)
  if (dry) {
    await group.getByRole('button', { name: 'More Run all options' }).click()
    await page.getByRole('dialog', { name: 'Run all options' }).getByRole('button', { name: 'Dry run all (no orders)' }).click()
  } else {
    await group.getByRole('button', { name: 'Run all', exact: true }).click()
  }
  return page.getByRole('dialog', { name: dry ? 'Dry run all bots?' : 'Run all bots now?' })
}

test('Run all from Admin → Bots names the bots, runs the ones a trader serves and sums up per bot', async ({ page }) => {
  test.skip(!MOCK, 'The real-backend Run all is its own test')
  await open(page, '/admin/bots')
  const dialog = await startRunAll(page, false)
  // the mock's pretend trader serves @claude, not @chatgpt (the agent runner's bot)
  await expect(dialog.getByRole('list', { name: 'Bots to run' })).toContainText('@claude')
  await expect(dialog.getByRole('list', { name: 'Bots not run' })).toContainText('@chatgpt: no claude-trader service serves it')
  await expect(dialog).toContainText('They trade for real.')
  await dialog.getByRole('button', { name: 'Run 1 bot' }).click()
  await expect(dialog).toBeHidden()

  const summary = page.getByRole('status', { name: 'Run all result' })
  await expect(summary).toContainText(/Run all at .* ET: 1 requested · 1 skipped/)
  await expect(summary.getByRole('listitem').filter({ hasText: '@claude' })).toContainText('Requested')
  await expect(summary.getByRole('listitem').filter({ hasText: '@chatgpt' })).toContainText("No claude-trader service has asked for @chatgpt's runs")
  // the bot's own card follows the request until the trader reports back
  const card = page.getByRole('list', { name: 'Bots' }).getByRole('listitem', { name: 'Bot claude' })
  const status = runNow(card, 'claude').getByRole('status')
  await expect(status).toContainText(/Requested .* · waiting for the trader/)
  await expect(status).toContainText(/ · done /, POLL)

  // Dry run all: the confirmation says what a dry run does
  const dry = await startRunAll(page, true)
  await expect(dry).toContainText('each bot only says what it would trade')
  await dry.getByRole('button', { name: 'Dry run 1 bot' }).click()
  await expect(summary).toContainText(/Dry run all at .* ET: 1 requested/)
  await expect(status).toContainText(/Dry run requested .* · waiting for the trader/)
  await summary.getByRole('button', { name: 'Dismiss' }).click()
  await expect(summary).toBeHidden()

  // Admin → Players has it too, under the header (each bot's ⋯ menu has Run now)
  await open(page, '/admin/players')
  await expect(runAllGroup(page).getByRole('button', { name: 'Run all', exact: true })).toBeVisible()
})

test('Run all against the real backend: an owner runs all of their bots, and the bot key claims the request', async ({ page, browser }, testInfo) => {
  test.skip(MOCK, 'Needs the real backend: the test claims and finishes the request with the bot key')
  test.setTimeout(150_000) // two players, two browser contexts and the trader's side
  const stamp = `${testInfo.project.name[0]}${Date.now() % 1_000_000}`
  const ownerHandle = `rao${stamp}`
  const handle = `ra${stamp}`
  await createHuman(page, ownerHandle)
  const key = await createBot(page, handle, ownerHandle)
  const auth = { Authorization: `Bearer ${key}`, 'X-Stocks-Client': 'claude-code/trader' }
  // this bot's row: "@ra1234567" then its pill or reason (no \b: "@ra1234567Requested" has no word boundary)
  const mine = (list: Locator) => list.getByRole('listitem').filter({ hasText: new RegExp(`@${handle}(?![a-z0-9_])`) })

  // the admin's confirmation (display only: Cancel; the admin's Run all runs every bot a trader served lately, the
  // other tests' bots too). No trader serves the new bot yet: it is left out, and says why
  await open(page, '/admin/bots')
  let dialog = await startRunAll(page, true)
  await expect(mine(dialog.getByRole('list', { name: 'Bots not run' }))).toHaveText(`@${handle}: no claude-trader service serves it`)
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  // the trader's heartbeat (it sends one instead of a claim while the agent is busy or every slot is in use): a
  // trader serves the bot now
  expect((await page.request.post('/api/v1/agent-runs/requests/heartbeat', { headers: auth })).status()).toBe(204)
  await page.reload()
  dialog = await startRunAll(page, false)
  await expect(mine(dialog.getByRole('list', { name: 'Bots to run' }))).toHaveCount(1)
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  // the owner, on a browser of their own, runs all of their bots: this one only
  const ctx = await browser.newContext({ baseURL: new URL(page.url()).origin })
  try {
    const owner = await ctx.newPage()
    await signInAs(owner, ownerHandle, PASSWORD)
    type RunAll = { dryRun: boolean; requested: number; results: { handle: string; outcome: string; reason: string | null; request: { id: string; dryRun: boolean; requestedBy: string } | null }[] }
    const runAll = async (dry: boolean): Promise<RunAll> => {
      const r = await owner.request.post('/api/v1/me/bots/run-requests', { data: { dryRun: dry }, headers: { 'X-XSRF-TOKEN': await csrfToken(owner) } })
      expect(r.status(), await r.text()).toBe(200)
      return (await r.json()) as RunAll
    }
    const first = await runAll(false)
    expect(first.requested).toBe(1)
    expect(first.results.map((i) => [i.handle, i.outcome])).toEqual([[handle, 'REQUESTED']])
    expect(first.results[0]?.request).toMatchObject({ dryRun: false, requestedBy: ownerHandle })

    // the owner's way to the bot: its profile, with Run now following the request and the link to its console
    await owner.goto(`/players/${handle}`)
    const card = owner.getByRole('region', { name: 'Bot activity' })
    await expect(runNow(card, handle).getByRole('status')).toContainText(/Requested .* · waiting for the trader/)
    await expect(card.getByRole('link', { name: 'Open console' })).toHaveAttribute('href', `/bots/${handle}`)

    // the admin's card of the bot follows it too; the trader's side: claim with the bot's own key, then report
    await page.reload()
    const status = runNow(page.getByRole('list', { name: 'Bots' }).getByRole('listitem', { name: `Bot ${handle}` }), handle).getByRole('status')
    await expect(status).toContainText(new RegExp(`Requested .* by @${ownerHandle} · waiting for the trader`))
    const claim = await page.request.post('/api/v1/agent-runs/requests/claim', { headers: auth })
    expect(claim.status(), await claim.text()).toBe(200)
    const claimed = (await claim.json()) as { id: string; handle: string; dryRun: boolean; status: string }
    expect(claimed).toMatchObject({ id: first.results[0]?.request?.id, handle, dryRun: false, status: 'CLAIMED' })
    await expect(status).toContainText(/picked up .* · running/, POLL)
    const finish = await page.request.post(`/api/v1/agent-runs/requests/${claimed.id}/finish`, { headers: auth, data: { status: 'DONE', message: 'Check-in done in 52 s, 7 turns. Held.' } })
    expect(finish.status(), await finish.text()).toBe(200)
    await expect(status).toContainText('Check-in done in 52 s, 7 turns. Held.', POLL)

    // Dry run all; a second one finds the request on its way
    const dry = await runAll(true)
    expect(dry.results.map((i) => [i.handle, i.outcome, i.request?.dryRun])).toEqual([[handle, 'REQUESTED', true]])
    const again = await runAll(true)
    expect(again.results.map((i) => [i.handle, i.outcome])).toEqual([[handle, 'ALREADY_OPEN']])
    expect(again.requested).toBe(0)
    const dryClaim = await page.request.post('/api/v1/agent-runs/requests/claim', { headers: auth })
    expect(((await dryClaim.json()) as { dryRun: boolean }).dryRun).toBe(true)
  } finally {
    await ctx.close()
  }
})
