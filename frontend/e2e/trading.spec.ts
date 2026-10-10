import { expect, test } from '@playwright/test'
import { MOCK, choose, open, openTicket, searchAndOpen, submitLogin, submitTicket, toastWith, viewport } from './support/app'

/**
 * Phase 1 M1 scenarios (§26) at all three viewports: sign out and back in, search, a $25 dollar-amount market buy
 * that fills into a position, and a resting limit order cancelled from the Orders page. Mock mode by default
 * (`npm run e2e`); against a real backend set E2E_BASE_URL plus E2E_USER / E2E_PASSWORD (`make e2e`).
 */

const SYMBOL = 'AAPL'

test('sign out, then sign back in', async ({ page }) => {
  await open(page, '/settings')
  await page.getByRole('button', { name: /^Sign out/ }).first().click()
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()

  // A protected deep link while signed out goes to /login and comes back after signing in.
  await page.goto('/account/orders')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  if (MOCK) {
    await page.getByLabel('Handle').fill('demo')
    await page.getByLabel('Password').fill('wrong')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('alert')).toContainText(/handle or password|credentials/i)
  }
  await submitLogin(page)
  await expect(page).toHaveURL(/\/account\/orders/)
})

test('search, buy $25 of a stock, and see the position', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  await open(page, vp === 'phone' ? '/watchlist' : '/markets')
  await searchAndOpen(page, vp, 'apple', SYMBOL)

  const ticket = await openTicket(page, vp, SYMBOL, 'Buy')
  await choose(ticket, 'Quantity mode', 'Amount ($)')
  // Dollar orders are market, day, regular hours only (§22.5)
  await expect(ticket.getByRole('radiogroup', { name: 'Order type' }).getByRole('radio', { name: 'Market' })).toHaveAttribute('aria-checked', 'true')
  await ticket.getByLabel('Amount', { exact: true }).fill('25')
  await expect(ticket.getByText(/Est\. shares|≈ [\d.]+ sh/).first()).toBeVisible({ timeout: 10_000 })
  await submitTicket(page, ticket, SYMBOL, 'Buy')

  await expect(toastWith(page, `Buy $25.00 ${SYMBOL}`)).toBeVisible()
  await expect(toastWith(page, new RegExp(`Filled buy [\\d.]+ ${SYMBOL} @`))).toBeVisible({ timeout: 15_000 })

  await page.goto('/account/positions')
  const positions = page.locator('[aria-label="Positions"]:visible')
  await expect(positions.getByText(SYMBOL, { exact: true }).first()).toBeVisible()

  // The fill shows in the activity feed too.
  await page.goto('/account/activity')
  await expect(page.getByRole('list', { name: 'Activity' }).getByText(new RegExp(`Bought .*${SYMBOL}|${SYMBOL}.*Buy`, 'i')).first()).toBeVisible()
})

test('place a resting limit order and cancel it', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  await open(page, `/stock/${SYMBOL}`)
  await expect(page.getByRole('heading', { name: SYMBOL, level: 1 })).toBeVisible()

  const ticket = await openTicket(page, vp, SYMBOL, 'Buy')
  await choose(ticket, 'Order type', 'Limit')
  await choose(ticket, 'Quantity mode', 'Shares')
  // 3% under the bid: it rests (no fat-finger warning, which starts at 5%)
  const bidLabel = await ticket.getByRole('button', { name: /^Use the bid price/ }).getAttribute('aria-label')
  const bid = Number(bidLabel?.match(/([\d,]+\.\d+)/)?.[1]?.replaceAll(',', ''))
  expect(bid).toBeGreaterThan(0)
  const limit = (Math.floor(bid * 0.97 * 100) / 100).toFixed(2)
  await ticket.getByLabel('Limit price', { exact: true }).fill(limit)
  await ticket.getByLabel('Shares', { exact: true }).fill('1')
  await choose(ticket, 'Time in force', 'GTC')
  await submitTicket(page, ticket, SYMBOL, 'Buy')
  await expect(toastWith(page, `Buy 1 ${SYMBOL} Limit ${limit}`)).toBeVisible()

  await page.goto('/account/orders?status=OPEN')
  const orders = page.getByRole('list', { name: 'Orders' })
  const row = orders.getByRole('listitem').filter({ hasText: `${SYMBOL} · 1 sh · Limit ${limit}` })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: `Cancel buy ${SYMBOL} order` }).click()
  await expect(toastWith(page, /Cancel(led|.*sent)/)).toBeVisible()
  await expect(row).toBeHidden({ timeout: 10_000 })

  await page.getByRole('radiogroup', { name: 'Order status' }).getByRole('radio', { name: 'Cancelled' }).click()
  await expect(page.getByRole('list', { name: 'Orders' }).getByRole('listitem').filter({ hasText: `Limit ${limit}` })).toBeVisible()
})
