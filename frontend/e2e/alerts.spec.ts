import { type Page, expect, test } from '@playwright/test'
import { type Viewport, choose, open, toastWith, viewport } from './support/app'

/**
 * Phase 2 price alerts and push settings (§19.1, §22.10) at all three viewports, against the mock (default) or a real
 * backend (E2E_BASE_URL; the e2e profile runs the alert monitor on SIM prices and sends pushes in dry-run). Each
 * viewport uses its own thresholds, because the projects share one player against a real backend.
 */

const THRESHOLD: Record<Viewport, string> = { phone: '99991', tablet: '99992', desktop: '99993' }
const shown = (t: string) => `$${Number(t).toLocaleString('en-US')}.00`

async function openAlertSheet(page: Page, symbol: string) {
  await open(page, `/stock/${symbol}`)
  await expect(page.getByRole('heading', { name: symbol, level: 1 })).toBeVisible()
  await page.getByTestId('alert-button').filter({ visible: true }).first().click()
  const sheet = page.getByRole('dialog', { name: `New ${symbol} alert` })
  await expect(sheet).toBeVisible()
  await expect(sheet.getByTestId('alert-live')).toContainText(symbol)
  return sheet
}

test('set alerts from the quote page, see one fire, manage them in the list', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  const far = THRESHOLD[vp]
  const sheet = await openAlertSheet(page, 'AAPL')

  // 1. A price alert that cannot fire yet: at or above a price far above the market.
  await choose(sheet, 'Alert on', 'Price')
  await choose(sheet, 'When it is', 'At or above')
  await sheet.getByLabel('Price', { exact: true }).fill(far)
  await sheet.getByLabel('Note (private)').fill(`e2e ${vp}`)
  await sheet.getByRole('button', { name: 'Create alert' }).click()
  await expect(toastWith(page, `Alert set: AAPL Price ≥ ${shown(far)}`)).toBeVisible()
  const rows = sheet.getByTestId('alert-row')
  await expect(rows.filter({ hasText: `Price ≥ ${shown(far)}` })).toHaveCount(1)

  // 2. One whose condition already holds: the sheet says so, and it fires on the next live price.
  await choose(sheet, 'When it is', 'At or below')
  await sheet.getByLabel('Price', { exact: true }).fill(far)
  await expect(sheet.getByText('The condition already holds')).toBeVisible()
  await sheet.getByRole('button', { name: 'Create alert' }).click()
  await expect(toastWith(page, `AAPL ≤ ${shown(far)}`)).toBeVisible({ timeout: 20_000 })
  await expect(rows.filter({ hasText: `Price ≤ ${shown(far)}` }).filter({ hasText: /Fired/ })).toHaveCount(1, { timeout: 20_000 })
  await sheet.getByRole('button', { name: 'Close' }).click()

  // 3. The Alerts list: pause and resume the waiting one, then delete both.
  await open(page, '/alerts')
  const list = page.getByRole('list', { name: 'Alerts' })
  const waiting = list.getByTestId('alert-row').filter({ hasText: `Price ≥ ${shown(far)}` })
  const fired = list.getByTestId('alert-row').filter({ hasText: `Price ≤ ${shown(far)}` })
  await expect(waiting).toContainText(`e2e ${vp}`)
  await expect(fired).toContainText('Fired')
  await waiting.getByRole('button', { name: 'Pause' }).click()
  await expect(waiting).toContainText('paused')
  await page.getByRole('tab', { name: 'Paused' }).click()
  await expect(list.getByTestId('alert-row').filter({ hasText: `Price ≥ ${shown(far)}` })).toHaveCount(1)
  await page.getByRole('tab', { name: 'All' }).click()
  await waiting.getByRole('button', { name: 'Resume' }).click()
  await expect(waiting).toContainText('active')
  await waiting.getByRole('button', { name: 'Delete alert' }).click()
  await expect(waiting).toHaveCount(0)
  await fired.getByRole('button', { name: 'Delete alert' }).click()
  await expect(fired).toHaveCount(0)
})

test('notification settings: categories and devices', async ({ page }) => {
  await open(page, '/settings/notifications')
  await expect(page.getByRole('heading', { name: 'This device' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'What to send' })).toBeVisible()
  // the league switch round-trips through PATCH /me/push/preferences (tap its label, as a finger would)
  const league = () => page.getByRole('switch', { name: /League events/ })
  const before = await league().isChecked()
  await page.getByText('League events', { exact: true }).click()
  await expect(league()).toBeChecked({ checked: !before })
  await expect(page.getByRole('status').filter({ hasText: /^Saved$/ })).toBeVisible()
  await page.reload()
  await expect(league()).toBeChecked({ checked: !before })
  await page.getByText('League events', { exact: true }).click()
  await expect(league()).toBeChecked({ checked: before })
  await expect(page.getByRole('heading', { name: 'Your devices' })).toBeVisible()
})

test.describe('on an iPhone in Safari', () => {
  test.use({
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
  })

  test('push explains how to install the app instead of offering a broken button', async ({ page }) => {
    await open(page, '/settings/notifications')
    const steps = page.getByTestId('ios-install')
    await expect(steps).toBeVisible()
    await expect(steps).toContainText('Add to Home Screen')
    await expect(steps).toContainText('iOS 16.4')
    await expect(page.getByRole('button', { name: 'Enable on this device' })).toHaveCount(0)
  })
})
