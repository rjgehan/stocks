import { type Page, expect, test } from '@playwright/test'
import { open, viewport } from './support/app'

/**
 * Screener (§22.10) at all three viewports, in mock mode and against the real SIM backend: the entry points, presets,
 * filters in the URL, sorting, and opening a result. On the SIM the first screen computes the statistics in the
 * background (market caps arrive a few seconds later).
 */

const results = (page: Page) => page.getByTestId('screener-results')

/** The `%` change cells (table) or pills (cards) of the results, as numbers. */
async function changes(page: Page): Promise<number[]> {
  const texts = await results(page).getByText(/^[+−]?\d+\.\d{2}%$/).allTextContents()
  return texts.map((t) => Number(t.replace('−', '-').replace('%', '')))
}

test('presets, filters and sorting', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))

  // entry points: the desktop rail, the Markets card elsewhere
  if (vp === 'desktop') {
    await open(page, '/watchlist')
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Screener' }).click()
  } else {
    await open(page, '/markets')
    await page.getByTestId('screener-card').click()
  }
  await expect(page).toHaveURL(/\/screener/)
  await expect(results(page)).toBeVisible({ timeout: 15_000 })

  // Top gainers: every change is positive
  const presets = page.getByRole('radiogroup', { name: 'Preset screens' })
  await presets.getByRole('radio', { name: 'Top gainers' }).click()
  await expect(page).toHaveURL(/preset=TOP_GAINERS/)
  await expect(presets.getByRole('radio', { name: 'Top gainers' })).toHaveAttribute('aria-checked', 'true')
  await expect.poll(async () => {
    const c = await changes(page)
    return c.length > 0 && c.every((x) => x > 0)
  }, { timeout: 15_000 }).toBe(true)

  // Large caps under $50: needs market caps (the SIM computes them on the first screen)
  await presets.getByRole('radio', { name: 'Large caps under $50' }).click()
  await expect(page).toHaveURL(/preset=LARGE_CAPS_UNDER_50/)
  await expect(results(page).getByRole('link').first()).toBeVisible({ timeout: 30_000 })

  // a filter goes to the URL; ETFs only
  await page.getByRole('combobox', { name: 'Type' }).selectOption({ label: 'ETFs' })
  await expect(page).toHaveURL(/type=ETF/)
  await presets.getByRole('radio', { name: 'Large caps under $50' }).click() // clear the preset (ETFs have no cap)
  await expect(page).not.toHaveURL(/preset=/)
  await expect(results(page).getByRole('link').first()).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('screener-footer')).toContainText('symbols priced')

  // sorting by a header (table layouts)
  if (vp !== 'phone') {
    const priceHeader = results(page).getByRole('columnheader', { name: /Price/ })
    await priceHeader.getByRole('button').click()
    await expect(page).toHaveURL(/sort=PRICE/)
    await expect(priceHeader).toHaveAttribute('aria-sort', 'descending')
    await priceHeader.getByRole('button').click()
    await expect(priceHeader).toHaveAttribute('aria-sort', 'ascending')
  }

  // a result opens its quote page
  const first = results(page).locator('a[data-symbol]').first()
  const symbol = (await first.getAttribute('data-symbol'))!
  await first.click()
  await expect(page).toHaveURL(new RegExp(`/stock/${symbol.replace('.', '\\.')}`))
  expect(errors).toEqual([])
})
