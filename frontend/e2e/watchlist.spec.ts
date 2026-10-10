import { type Locator, type Page, expect, test } from '@playwright/test'
import { type Viewport, open, toastWith, viewport } from './support/app'

/** Watchlist (§22.4) at all three viewports: add a symbol from the inline search, reorder it, remove it. */

/** The watchlist panel: the phone Watchlist tab, the tablet drawer, or the desktop left panel. */
async function watchlist(page: Page, vp: Viewport): Promise<Locator> {
  await open(page, '/watchlist')
  if (vp === 'tablet') {
    await page.getByRole('button', { name: 'Watchlist', exact: true }).click()
    await expect(page.getByRole('dialog', { name: 'Watchlist' })).toBeVisible()
  }
  const panel = page.getByRole('region', { name: 'Watchlist' }).filter({ visible: true }).first()
  await expect(panel).toBeVisible()
  return panel
}

/** Symbols in list order, read from the edit-mode "Move X up" buttons. */
const order = (panel: Locator): Promise<string[]> =>
  panel.getByRole('button', { name: /^Move \S+ up$/ }).evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')!.split(' ')[1]!))

test('add, reorder and remove a watchlist symbol', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  const panel = await watchlist(page, vp)
  await expect(panel.getByRole('tab', { name: 'My List' })).toHaveAttribute('aria-selected', 'true')

  // The index strip and the live rows render.
  await expect(panel.getByText('SPY', { exact: true }).first()).toBeVisible()
  await expect(panel.getByRole('link', { name: /^AAPL / })).toBeVisible()

  // Pick a symbol that is not on the list yet.
  const symbol = 'GOOGL'
  await expect(panel.getByRole('link', { name: new RegExp(`^${symbol} `) })).toHaveCount(0)
  await panel.getByRole('button', { name: 'Add symbol' }).click()
  await panel.getByRole('combobox', { name: 'Search symbols' }).fill('alphabet')
  await page.getByRole('listbox', { name: 'Search results' }).getByRole('option').filter({ hasText: symbol }).first().click()
  await expect(toastWith(page, `Added ${symbol}`)).toBeVisible()
  await expect(panel.getByRole('link', { name: new RegExp(`^${symbol} `) })).toBeVisible()
  await panel.getByRole('button', { name: 'Close add symbol' }).click()

  // Edit mode: the new symbol is last; move it up one place, then remove it.
  await panel.getByRole('button', { name: 'List actions' }).click()
  await page.getByRole('dialog', { name: 'List actions' }).getByRole('button', { name: 'Edit symbols' }).click()
  const before = await order(panel)
  expect(before.at(-1)).toBe(symbol)
  await panel.getByRole('button', { name: `Move ${symbol} up` }).click()
  await expect.poll(() => order(panel)).toEqual([...before.slice(0, -2), symbol, before.at(-2)!])

  await panel.getByRole('button', { name: `Remove ${symbol}` }).click()
  await expect(panel.getByRole('button', { name: `Remove ${symbol}` })).toHaveCount(0)
  await panel.getByRole('button', { name: 'Done' }).click()
  await expect(panel.getByRole('link', { name: new RegExp(`^${symbol} `) })).toHaveCount(0)
  await expect(panel.getByRole('link', { name: /^AAPL / })).toBeVisible()
})
