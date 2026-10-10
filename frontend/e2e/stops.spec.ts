import { expect, test } from '@playwright/test'
import { MOCK, choose, chooseOrderType, offersOrderType, open, openTicket, submitTicket, toastWith, viewport } from './support/app'
import { nudgePrice } from './support/mock'

/**
 * Stop orders (§11.1, §12.7, §22.5) at all three viewports. The ticket offers the types `GET /rules` publishes, so
 * against a backend that does not accept stops yet these tests skip. Mock mode drives the price through the trigger
 * (`window.stocksMock.nudge`); a real backend's SIM price cannot be steered, so there the armed stop is cancelled.
 */

const SYMBOL = 'AAPL'

test('a buy stop waits for its price, then triggers and fills', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  await open(page, `/stock/${SYMBOL}`)
  await expect(page.getByRole('heading', { name: SYMBOL, level: 1 })).toBeVisible()
  const ticket = await openTicket(page, vp, SYMBOL, 'Buy')
  test.skip(!(await offersOrderType(ticket, 'Stop')), 'This release does not accept stop orders yet (GET /rules)')

  await chooseOrderType(ticket, 'Stop')
  await choose(ticket, 'Quantity mode', 'Shares')
  // The ticket starts the stop 1% above the market (a buy stop must be above it, §11.2 check 19).
  const stopField = ticket.getByLabel('Stop price', { exact: true })
  await expect(stopField).not.toHaveValue('')
  const stop = await stopField.inputValue()
  await ticket.getByLabel('Shares', { exact: true }).fill('1')
  await expect(ticket.getByText(`When ${SYMBOL} trades at or above ${stop}, a market order to buy is sent.`)).toBeVisible()
  await expect(ticket.getByText('Est. price at trigger')).toBeVisible({ timeout: 10_000 })
  // Trailing stops and market orders are Day only; a plain stop may be GTC.
  await expect(ticket.getByRole('radiogroup', { name: 'Time in force' }).getByRole('radio', { name: 'GTC' })).toBeEnabled()

  await submitTicket(page, ticket, SYMBOL, 'Buy')
  await expect(toastWith(page, `Buy 1 ${SYMBOL} Stop ${stop}`)).toBeVisible()

  // Open orders show the stop and why it waits.
  await page.goto('/account/orders?status=OPEN')
  const row = page.getByRole('list', { name: 'Orders' }).getByRole('listitem').filter({ hasText: `${SYMBOL} · 1 sh · Stop ${stop}` }).first()
  await expect(row).toBeVisible()
  await expect(row).toContainText('waiting for the stop price', { timeout: 10_000 })
  // phones show the status in the row's second line; the status pills appear from tablet up (review fix: they used
  // to render anyway and squeeze the row text down to a word)
  const statusPill = row.getByText(/^(Pending|Working)$/).last()
  if (vp === 'phone') await expect(statusPill).toBeHidden()
  else await expect(statusPill).toBeVisible()
  await row.getByRole('link').first().click()
  const panel = page.getByRole('region', { name: 'Stop' })
  await expect(panel).toBeVisible()
  await expect(panel.getByText('Armed')).toBeVisible()

  if (MOCK) {
    await nudgePrice(page, SYMBOL, 300) // +3%: through the stop
    await expect(toastWith(page, `Stop triggered: Buy 1 ${SYMBOL}`)).toBeVisible()
    await expect(toastWith(page, new RegExp(`Filled buy 1 ${SYMBOL} @`))).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('Stop triggered').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(/on the last trade/).first()).toBeVisible()
    await expect(panel.getByText('Triggered', { exact: true }).first()).toBeVisible()
  } else {
    await page.getByRole('button', { name: 'Cancel order' }).click()
    await expect(toastWith(page, /Cancel(led|.*sent)/)).toBeVisible()
    await expect(panel.getByText('Not triggered')).toBeVisible({ timeout: 10_000 })
  }
})

test('a sell trailing stop follows the high and triggers on the pullback', async ({ page }, testInfo) => {
  test.skip(!MOCK, 'Needs a whole-share position and a steerable price (mock mode)')
  const vp = viewport(testInfo)
  // The mock account holds 2 F (bought before a 2-for-1 split).
  await open(page, '/stock/F')
  await expect(page.getByRole('heading', { name: 'F', level: 1 })).toBeVisible()
  const ticket = await openTicket(page, vp, 'F', 'Sell')
  await chooseOrderType(ticket, 'Trailing Stop')
  await choose(ticket, 'Trail type', '%')
  await ticket.getByLabel('Trail', { exact: true }).fill('2')
  await ticket.getByLabel('Shares', { exact: true }).fill('2')
  await expect(ticket.getByText(/follows F's highest price 2% below it/)).toBeVisible()
  // Trailing stops are Day only (§11.1).
  await expect(ticket.getByRole('radiogroup', { name: 'Time in force' }).getByRole('radio', { name: 'GTC' })).toBeDisabled()
  await submitTicket(page, ticket, 'F', 'Sell')
  await expect(toastWith(page, 'Sell 2 F Trailing Stop 2% trail')).toBeVisible()

  await page.goto('/account/orders?status=OPEN')
  const row = page.getByRole('list', { name: 'Orders' }).getByRole('listitem').filter({ hasText: 'F · 2 sh · Trailing Stop 2% trail' }).first()
  await expect(row).toContainText(/stop \d+\.\d{2}/, { timeout: 10_000 })
  await row.getByRole('link').first().click()
  const panel = page.getByRole('region', { name: 'Stop' })
  const watermark = panel.getByText('Highest price (watermark)').locator('xpath=following-sibling::dd')
  await expect(watermark).toHaveText(/\d+\.\d{2}/)
  const before = Number((await watermark.textContent())?.replaceAll(',', '').match(/[\d.]+/)?.[0])

  await nudgePrice(page, 'F', 150) // a new high: the watermark and the stop follow it up
  await expect.poll(async () => Number((await watermark.textContent())?.replaceAll(',', '').match(/[\d.]+/)?.[0]), { timeout: 10_000 }).toBeGreaterThan(before)

  await nudgePrice(page, 'F', -400) // −4%: through the trailing stop
  await expect(toastWith(page, 'Stop triggered: Sell 2 F')).toBeVisible()
  await expect(toastWith(page, /Filled sell 2 F @/)).toBeVisible({ timeout: 10_000 })
})

// Review regression: the $ trail defaults to the tick of the stock's price ($0.12 on a $12 stock, never $0.1203), so
// the default ticket is valid and the order is accepted, on the real backend too.
test('a dollar trailing stop with the default trail is valid on a stock under $100', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  const symbol = 'F'
  await open(page, `/stock/${symbol}`)
  await expect(page.getByRole('heading', { name: symbol, level: 1 })).toBeVisible()
  const ticket = await openTicket(page, vp, symbol, 'Buy')
  test.skip(!(await offersOrderType(ticket, 'Trailing Stop')), 'This release does not accept trailing stops yet (GET /rules)')
  await chooseOrderType(ticket, 'Trailing Stop')
  await choose(ticket, 'Quantity mode', 'Shares')
  await choose(ticket, 'Trail type', '$')
  const trail = ticket.getByLabel('Trail', { exact: true })
  await expect(trail).toHaveValue(/^\d+\.\d{2}$/)
  const amount = await trail.inputValue()
  // the description shows before a quantity is entered
  await expect(ticket.getByText(new RegExp(`follows ${symbol}'s lowest price \\$${amount.replace('.', '\\.')} above it`))).toBeVisible()
  await ticket.getByLabel('Shares', { exact: true }).fill('1')
  await expect(ticket.getByText(/is not on the/)).toHaveCount(0)
  await submitTicket(page, ticket, symbol, 'Buy')
  await expect(toastWith(page, `Buy 1 ${symbol} Trailing Stop $${amount} trail`)).toBeVisible()
  if (!MOCK) {
    // leave nothing open on the shared backend
    await page.goto('/account/orders?status=OPEN')
    const row = page.getByRole('list', { name: 'Orders' }).getByRole('listitem').filter({ hasText: `${symbol} · 1 sh · Trailing Stop $${amount} trail` }).first()
    await row.getByRole('button', { name: `Cancel buy ${symbol} order` }).click()
    await expect(toastWith(page, /Cancel(led|.*sent)/)).toBeVisible()
  }
})
