import { expect, test } from '@playwright/test'
import { MOCK, chooseOrderType, choose, confirmIfAsked, open, openTicket, signInAs, submitTicket, toastWith, viewport } from './support/app'

/**
 * Public mode (§16.7) at all three viewports: every player's live portfolio, order history, account activity and
 * P&L analysis are visible to everyone.
 *  - mock mode: a seeded bot's Portfolio (holdings, cash, buying power, open orders), History (orders with a status
 *    and a note; Activity with the starting cash) and Analysis, and a board without hidden equity;
 *  - a real backend (`make e2e`): a new player places a resting limit buy with a note, and the admin, in another
 *    browser context, sees the open order and the note on that player's Portfolio and History.
 */

// two browser contexts and several route chunks per test
test.describe.configure({ timeout: 90_000 })

const PASSWORD = 'a long enough password'

test("a bot's Portfolio, History and Analysis are public", async ({ page }, testInfo) => {
  test.skip(!MOCK, 'The league members are seeded in mock mode; the real-backend test below covers a live order')
  const vp = viewport(testInfo)
  await open(page, '/players/claude')
  const tabs = page.getByRole('navigation', { name: 'Profile sections' })
  await expect(tabs.getByRole('link', { name: 'Overview' })).toHaveAttribute('data-status', 'active')
  await expect(page.getByRole('heading', { name: 'Top holdings (live)' })).toBeVisible()

  await tabs.getByRole('link', { name: 'Portfolio' }).click()
  await expect(page).toHaveURL(/\/players\/claude\/portfolio$/)
  const summary = page.getByLabel('Portfolio summary')
  await expect(summary).toContainText('Cash')
  await expect(summary).toContainText('Buying power')
  // a table from tablet up, cards on phones
  const holdings = vp === 'phone' ? page.getByRole('list', { name: 'Holdings' }) : page.getByRole('table', { name: 'Holdings' })
  await expect(holdings.getByRole('link', { name: 'NVDA' }).first()).toBeVisible()
  const openOrders = page.getByRole('list', { name: 'Open orders' })
  await expect(openOrders.getByRole('listitem').first()).toBeVisible()
  await expect(openOrders.getByText(/^Bracket · (stop loss|take profit) · held$/).first()).toBeVisible()
  await expect(openOrders.getByRole('button', { name: /cancel/i })).toHaveCount(0)

  await tabs.getByRole('link', { name: 'History' }).click()
  const orders = page.getByRole('list', { name: 'Orders' })
  const filled = orders.getByRole('button').filter({ hasText: 'Filled' }).first()
  await expect(filled).toBeVisible()
  await expect(filled).toContainText('“') // the bot's note
  await filled.click()
  await expect(page.getByRole('table', { name: 'Fills' }).first()).toBeVisible()
  await page.getByRole('radiogroup', { name: 'Order status' }).getByRole('radio', { name: 'Open' }).click()
  await expect(page).toHaveURL(/status=OPEN/)
  await expect(orders.getByText('Working').first()).toBeVisible()
  await expect(orders.getByText('Filled', { exact: true })).toHaveCount(0)
  // the tab stays active whatever the search
  await expect(tabs.getByRole('link', { name: 'History' })).toHaveAttribute('data-status', 'active')

  await page.getByRole('radiogroup', { name: 'History view' }).getByRole('radio', { name: 'Activity' }).click()
  const activity = page.getByRole('list', { name: 'Activity' })
  await expect(activity.getByText('Starting cash').first()).toBeVisible()
  await expect(activity.getByRole('link')).toHaveCount(0)

  await tabs.getByRole('link', { name: 'Analysis' }).click()
  await expect(page.getByRole('heading', { name: 'Earnings' })).toBeVisible()
  await expect(page.getByText('@claude', { exact: true }).first()).toBeVisible()

  // equity is public: the board never says "hidden"
  await page.goto('/league?period=ALL')
  await expect(page.getByRole('table', { name: 'Leaderboard' }).or(page.getByRole('list', { name: 'Leaderboard' })).first()).toBeVisible()
  await expect(page.getByText('hidden', { exact: true })).toHaveCount(0)
})

test("another player's working order and note are public at once", async ({ page, browser }, testInfo) => {
  test.skip(MOCK, 'Needs a real backend: a second player and a second browser context')
  const vp = viewport(testInfo)
  const handle = `pub${vp[0]}${Date.now() % 1_000_000}`
  const note = `E2E public note ${handle}: <b>bid</b> under the market.`

  // the admin creates the player (§21.1)
  await open(page, '/admin/players')
  await page.getByRole('button', { name: 'Add player' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a player' })
  await dialog.getByRole('radiogroup', { name: 'Player kind' }).getByRole('radio', { name: 'Human' }).click()
  await dialog.getByLabel('Handle').fill(handle)
  await dialog.getByLabel('Display name').fill('Public Player')
  await dialog.getByLabel('Password').fill(PASSWORD)
  await dialog.getByRole('button', { name: 'Create player' }).click()
  await confirmIfAsked(page)
  await expect(dialog).toBeHidden()

  // the player, in a browser context of their own, places a resting limit buy with a note
  const { viewport: size, isMobile, hasTouch } = testInfo.project.use
  const ctx = await browser.newContext({ baseURL: new URL(page.url()).origin, viewport: size, isMobile, hasTouch })
  let limit = ''
  try {
    const player = await ctx.newPage()
    await signInAs(player, handle, PASSWORD)
    await player.goto('/stock/AAPL')
    await expect(player.getByRole('heading', { name: 'AAPL', level: 1 })).toBeVisible()
    const ticket = await openTicket(player, vp, 'AAPL', 'Buy')
    await chooseOrderType(ticket, 'Limit')
    await choose(ticket, 'Quantity mode', 'Shares')
    // about 3% under the bid: it rests (the fat-finger warning starts at 5%)
    const bidLabel = await ticket.getByRole('button', { name: /^Use the bid price/ }).getAttribute('aria-label')
    const bid = Number(bidLabel?.match(/([\d,]+\.\d+)/)?.[1]?.replaceAll(',', ''))
    expect(bid).toBeGreaterThan(0)
    limit = (Math.floor(bid * 0.97 * 100) / 100).toFixed(2)
    await ticket.getByLabel('Limit price', { exact: true }).fill(limit)
    await ticket.getByLabel('Shares', { exact: true }).fill('1')
    await ticket.getByRole('button', { name: '+ Add note' }).click()
    await expect(ticket.getByText('Notes are public: every player sees them with the order.')).toBeVisible()
    await ticket.getByLabel(/^Note/).fill(note)
    await submitTicket(player, ticket, 'AAPL', 'Buy')
    await expect(toastWith(player, `Buy 1 AAPL Limit ${limit}`)).toBeVisible()
  } finally {
    await ctx.close()
  }

  // the admin (any player would do) sees the working order and the note, live
  await page.goto(`/players/${handle}/portfolio`)
  const row = page.getByRole('list', { name: 'Open orders' }).getByRole('listitem').filter({ hasText: `AAPL · Limit ${limit}` })
  await expect(row).toBeVisible({ timeout: 15_000 })
  await expect(row).toContainText(note) // untrusted text, shown as written
  await expect(row.getByRole('button')).toHaveCount(0)
  await expect(page.getByLabel('Portfolio summary')).toContainText('Open buy holds')

  await page.getByRole('navigation', { name: 'Profile sections' }).getByRole('link', { name: 'History' }).click()
  const orders = page.getByRole('list', { name: 'Orders' })
  const order = orders.getByRole('button').filter({ hasText: `AAPL · Limit ${limit}` })
  await expect(order).toContainText('Working')
  await expect(order).toContainText('E2E public note')
  await page.getByRole('radiogroup', { name: 'History view' }).getByRole('radio', { name: 'Activity' }).click()
  await expect(page.getByRole('list', { name: 'Activity' }).getByText('Starting cash').first()).toBeVisible()
})
