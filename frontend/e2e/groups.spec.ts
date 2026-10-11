import { type Locator, type Page, expect, test } from '@playwright/test'
import { MOCK, choose, open, openTicket, toastWith, viewport } from './support/app'
import { nudgePrice } from './support/mock'

/**
 * Group orders (Phase 2, §11.9, §22.5 Advanced) at all three viewports: a Bracket placed from the ticket's Advanced
 * mode, its legs in the orders list (group chips, "Waiting for entry"), the Linked orders card and Cancel group. In mock
 * mode a market-entry bracket also fills, releases its take profit and stop loss, and the stop loss executing cancels
 * the take profit. The ticket offers Advanced only when `GET /rules` lists `orders.groups`; otherwise the tests skip.
 */

const SYMBOL = 'AAPL'

const cents = (n: number): string => n.toFixed(2)

/** Switches the ticket to Advanced (skips when this release does not list group orders) and picks a group type. */
async function advanced(ticket: Locator, type: 'Bracket' | 'OCO' | 'OTO' | 'OTOCO'): Promise<void> {
  const mode = ticket.getByRole('radiogroup', { name: 'Ticket mode' })
  await expect(ticket.getByRole('radiogroup', { name: 'Side' })).toBeVisible()
  await expect(mode)
    .toBeVisible({ timeout: 5_000 })
    .catch(() => undefined)
  test.skip(!(await mode.isVisible()), 'This release does not offer group orders (GET /rules orders.groups)')
  await choose(ticket, 'Ticket mode', 'Advanced')
  await choose(ticket, 'Advanced order type', type)
}

async function fill(ticket: Locator, label: string, value: string): Promise<void> {
  const field = ticket.getByLabel(label, { exact: true })
  await field.fill(value)
  await expect(field).toHaveValue(value)
}

/** Presses Place …, confirms the review sheet, and waits for the toast. */
async function placeGroup(page: Page, ticket: Locator, type: string): Promise<void> {
  const submit = ticket.getByRole('button', { name: `Place ${type} · ${SYMBOL}` })
  await expect(submit).toBeEnabled({ timeout: 10_000 })
  await submit.click()
  const review = page.getByRole('dialog', { name: `Review ${type} · ${SYMBOL}` })
  await expect(review).toBeVisible()
  await expect(review.getByText('Take profit', { exact: true })).toBeVisible()
  await review.getByRole('button', { name: `Confirm ${type}` }).click()
  await expect(review).toBeHidden()
  await expect(toastWith(page, new RegExp(`${type} placed: 1 ${SYMBOL}`))).toBeVisible()
}

test('a bracket waits for its entry, shows its linked orders and cancels as a group', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  await open(page, `/stock/${SYMBOL}`)
  await expect(page.getByRole('heading', { name: SYMBOL, level: 1 })).toBeVisible()
  const ticket = await openTicket(page, vp, SYMBOL, 'Buy')
  await advanced(ticket, 'Bracket')
  await choose(ticket, 'Entry type', 'Limit')
  // the entry starts at the bid: move it 5% under the market so it rests, and bracket it ±10% around the entry
  const entryField = ticket.getByLabel('Entry limit', { exact: true })
  await expect(entryField).not.toHaveValue('')
  const entry = Number(await entryField.inputValue()) * 0.95
  await fill(ticket, 'Entry limit', cents(entry))
  await fill(ticket, 'Take profit', cents(entry * 1.1))
  await fill(ticket, 'Stop loss', cents(entry * 0.9))
  await fill(ticket, 'Shares (every leg)', '1')
  await expect(ticket.getByText('+10.0% from entry')).toBeVisible()
  await expect(ticket.getByText('−10.0% from entry')).toBeVisible()
  const preview = ticket.getByTestId('group-preview')
  await expect(preview.getByText('Risk : reward')).toBeVisible({ timeout: 10_000 })
  await expect(preview.getByText('1 : 1.0')).toBeVisible()
  // a take profit under the entry is refused on its input before anything is sent
  await fill(ticket, 'Take profit', cents(entry * 0.99))
  await expect(ticket.getByText(/take profit .* must be above the entry price/i)).toBeVisible({ timeout: 10_000 })
  await expect(ticket.getByRole('button', { name: `Place Bracket · ${SYMBOL}` })).toBeDisabled()
  await fill(ticket, 'Take profit', cents(entry * 1.1))

  await placeGroup(page, ticket, 'Bracket')

  // the orders list shows each leg with its group chip; the take profit waits for the entry
  await page.goto('/account/orders?status=OPEN')
  const list = page.getByRole('list', { name: 'Orders' })
  const tpRow = list.getByRole('listitem').filter({ hasText: 'Bracket · TP' }).first()
  await expect(tpRow).toBeVisible({ timeout: 10_000 })
  await expect(tpRow).toContainText('Waiting for entry')
  await expect(list.getByRole('listitem').filter({ hasText: 'Bracket · Entry' }).first()).toBeVisible()
  await expect(list.getByRole('listitem').filter({ hasText: 'Bracket · SL' }).first()).toBeVisible()

  // order detail: the linked legs and Cancel group
  await tpRow.getByRole('link').first().click()
  const linked = page.getByRole('region', { name: 'Linked orders' })
  await expect(linked).toBeVisible()
  await expect(linked.getByRole('listitem')).toHaveCount(3)
  await expect(linked.getByText('this order')).toBeVisible()
  await linked.getByRole('button', { name: 'Cancel group' }).click()
  await expect(toastWith(page, 'Bracket cancel sent')).toBeVisible()
  await expect(linked.getByText('Cancelled', { exact: true }).first()).toBeVisible({ timeout: 10_000 })
  await expect(linked.getByRole('button', { name: 'Cancel group' })).toBeHidden()
})

test('a market-entry bracket fills, releases its legs, and the stop loss cancels the take profit', async ({ page }, testInfo) => {
  test.skip(!MOCK, 'Needs a steerable price (mock mode)')
  const vp = viewport(testInfo)
  await open(page, `/stock/${SYMBOL}`)
  await expect(page.getByRole('heading', { name: SYMBOL, level: 1 })).toBeVisible()
  const ticket = await openTicket(page, vp, SYMBOL, 'Buy')
  await advanced(ticket, 'Bracket')
  await choose(ticket, 'Entry type', 'Market')
  await fill(ticket, 'Shares (every leg)', '1')
  await expect(ticket.getByText(/Buys at the market/)).toBeVisible()
  await placeGroup(page, ticket, 'Bracket')

  await page.goto('/account/orders?status=ALL')
  const list = page.getByRole('list', { name: 'Orders' })
  const slRow = list.getByRole('listitem').filter({ hasText: 'Bracket · SL' }).first()
  await expect(slRow).toBeVisible({ timeout: 10_000 })
  // the entry fills; the stop loss is released and arms
  await expect(slRow).toContainText('waiting for the stop price', { timeout: 15_000 })
  await slRow.getByRole('link').first().click()
  const linked = page.getByRole('region', { name: 'Linked orders' })
  await expect(linked).toBeVisible()
  await expect(page.getByText('Released by the entry')).toBeVisible()

  await nudgePrice(page, SYMBOL, -1_500) // −15%: through the stop loss (3% under the market by default)
  await expect(toastWith(page, /Filled sell 1 AAPL @/)).toBeVisible({ timeout: 15_000 })
  await expect(linked.getByText('Completed', { exact: true })).toBeVisible({ timeout: 15_000 })
  const tp = linked.getByRole('listitem').filter({ hasText: 'Take profit' })
  await expect(tp.getByText('Cancelled', { exact: true })).toBeVisible({ timeout: 10_000 })
})
