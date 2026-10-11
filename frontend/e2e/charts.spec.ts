import { type Page, expect, test } from '@playwright/test'
import { open, viewport } from './support/app'

/**
 * Phase 2 quote page (§22.10) at all three viewports, in mock mode and against the real SIM backend: chart types and
 * indicators (persisted per player), oscillator panes, Time & Sales, analyst ratings and the news tab.
 */

/** Uncaught page errors (a chart that throws while it rebuilds panes must fail the test). */
function collectErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  return errors
}

const chart = (page: Page) => page.locator('[data-chart-type]').first()
const types = (page: Page) => page.getByRole('radiogroup', { name: 'Chart type' })

test('chart types and indicators persist, oscillators get their own panes', async ({ page }) => {
  test.setTimeout(90_000)
  const errors = collectErrors(page)
  await open(page, '/stock/AAPL')
  await expect(page.getByRole('heading', { name: 'AAPL', level: 1 })).toBeVisible()
  await expect(chart(page)).toHaveAttribute('data-oscillators', '0')

  // Indicators: MA (overlay) and RSI + MACD (two lower panes)
  await page.getByRole('button', { name: /Indicators/ }).click()
  const panel = page.getByTestId('indicator-panel')
  await expect(panel).toBeVisible()
  await panel.getByRole('checkbox', { name: 'MA', exact: true }).check()
  await panel.getByRole('checkbox', { name: 'RSI', exact: true }).check()
  await panel.getByRole('checkbox', { name: 'MACD', exact: true }).check()
  await panel.getByRole('spinbutton', { name: 'RSI period' }).fill('10')
  await page.getByRole('dialog', { name: 'Indicators' }).getByRole('button', { name: 'Close', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Indicators' })).toHaveCount(0)
  await expect(chart(page)).toHaveAttribute('data-oscillators', '2')
  // overlays above the candles; each oscillator's values inside its own pane (review finding: all of them sat over
  // the price pane)
  const legend = page.getByTestId('indicator-legend')
  await expect(legend).toContainText('MA5', { timeout: 15_000 })
  await expect(legend).not.toContainText('RSI10')
  const panes = page.getByTestId('indicator-legend-pane')
  await expect(panes).toHaveCount(2)
  await expect(panes.filter({ hasText: 'RSI10' })).toHaveCount(1)
  await expect(panes.filter({ hasText: 'MACD(12,26,9)' })).toHaveCount(1)
  const priceTop = (await chart(page).boundingBox())!.y
  const rsiTop = (await panes.filter({ hasText: 'RSI10' }).boundingBox())!.y
  expect(rsiTop - priceTop).toBeGreaterThan(100)
  await expect(types(page).getByRole('radio', { name: 'Heikin Ashi' })).toHaveAccessibleName('Heikin Ashi')
  await expect(page.getByRole('button', { name: /Indicators/ })).toContainText('3')

  // Chart types
  await types(page).getByRole('radio', { name: 'Heikin Ashi' }).click()
  await expect(chart(page)).toHaveAttribute('data-chart-type', 'heikin')
  await types(page).getByRole('radio', { name: 'Hollow candles' }).click()
  await expect(chart(page)).toHaveAttribute('data-chart-type', 'hollow')

  // A different range rebuilds the data, not the panes; then everything survives a reload
  await page.getByRole('radiogroup', { name: 'Chart range' }).getByRole('radio', { name: '5D', exact: true }).click()
  await expect(page).toHaveURL(/range=5D/)
  await expect(chart(page)).toHaveAttribute('data-oscillators', '2')
  await page.reload()
  await expect(chart(page)).toHaveAttribute('data-chart-type', 'hollow')
  await expect(chart(page)).toHaveAttribute('data-oscillators', '2')
  // the legend shows once the bars have loaded (a cold 5D on the real SIM computes ~1,000 bars)
  await expect(page.getByTestId('indicator-legend-pane').filter({ hasText: 'RSI10' })).toHaveCount(1, { timeout: 20_000 })

  // Turning the oscillators off removes their panes
  await page.getByRole('button', { name: /Indicators/ }).click()
  await page.getByTestId('indicator-panel').getByRole('button', { name: 'Reset to defaults' }).click()
  await page.getByRole('dialog', { name: 'Indicators' }).getByRole('button', { name: 'Close', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Indicators' })).toHaveCount(0)
  await expect(chart(page)).toHaveAttribute('data-oscillators', '0')
  await expect(page.getByTestId('indicator-legend')).toHaveCount(0)
  await expect(page.getByTestId('indicator-legend-pane')).toHaveCount(0)
  // leaving the page tears the chart down cleanly (no draw on a disposed chart)
  await page.goto('/markets')
  await expect(page.getByTestId('screener-card')).toBeVisible()
  await page.waitForTimeout(500)
  expect(errors).toEqual([])
})

test('Time & Sales shows colored prints with glyphs', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  const errors = collectErrors(page)
  await open(page, '/stock/AAPL')
  if (vp !== 'desktop') {
    await page.getByRole('tab', { name: 'Trades' }).click()
    await expect(page).toHaveURL(/tab=TRADES/)
  }
  const tape = page.getByTestId('time-and-sales').filter({ visible: true }).first()
  await tape.scrollIntoViewIfNeeded()
  await expect(tape).toBeVisible()
  // the SIM (and the mock) stream prints: rows with a time, a price and a size
  const rows = tape.locator('li')
  await expect(rows.first()).toBeVisible({ timeout: 15_000 })
  await expect(rows.first()).toHaveText(/^\d{2}:\d{2}:\d{2}\s*[▲▼•]?\s*[\d,]+\.\d{2,4}.*\d/)
  await expect(tape.getByText('SIM', { exact: true })).toBeVisible()
  // the side is never conveyed by color alone
  expect(await rows.locator('.sr-only').first().textContent()).toMatch(/at the ask|at the bid|between|unknown/)
  expect(errors).toEqual([])
})

test('analyst ratings and the news tab', async ({ page }) => {
  await open(page, '/stock/AAPL')
  const ratings = page.getByTestId('analyst-ratings')
  await ratings.scrollIntoViewIfNeeded()
  await expect(ratings).toBeVisible()
  await expect(ratings).toContainText(/Strong buy|Buy|Hold|Sell/)
  await expect(ratings).toContainText(/analysts/)
  await expect(ratings).toContainText('Source:')

  await page.getByRole('tab', { name: 'News' }).click()
  const news = page.getByTestId('news-feed')
  await expect(news).toBeVisible()
  const link = news.getByRole('link').first()
  await expect(link).toHaveAttribute('rel', /noopener/)
  await expect(link).toHaveAttribute('target', '_blank')
  await expect(news.locator('time').first()).toHaveText(/ago|now|[A-Z][a-z]{2} \d+/)

  // an ETF has no ratings: the card is hidden
  await open(page, '/stock/SPY')
  await expect(page.getByRole('heading', { name: 'SPY', level: 1 })).toBeVisible()
  await expect(page.getByTestId('analyst-ratings')).toHaveCount(0)
})

/**
 * Review finding: sr-only labels (one per Time & Sales print, one per news link on phone) are absolutely positioned;
 * with no positioned ancestor they escaped every overflow clip, made the document taller than the viewport, and a
 * wheel over the rail scrolled the whole app off-screen. The shell owns all scrolling: the document never does.
 */
test('the quote page never makes the document scroll past the shell', async ({ page }, testInfo) => {
  const vp = viewport(testInfo)
  const fitsTheViewport = async () => {
    const { scrollHeight, innerHeight } = await page.evaluate(() => ({
      scrollHeight: document.documentElement.scrollHeight,
      innerHeight: window.innerHeight,
    }))
    expect(scrollHeight).toBeLessThanOrEqual(innerHeight + 1)
  }
  await open(page, '/stock/AAPL')
  await expect(page.getByRole('heading', { name: 'AAPL', level: 1 })).toBeVisible()
  // the tape's rows carry the sr-only side labels: desktop shows it beside the ticket, phone and tablet in a tab
  const tapeRows = () => page.getByTestId('time-and-sales').filter({ visible: true }).first().locator('li').first()
  if (vp === 'desktop') await expect(tapeRows()).toBeVisible({ timeout: 15_000 })
  await fitsTheViewport()
  for (const tab of vp === 'desktop' ? ['News'] : ['News', 'Trades']) {
    await page.getByRole('tab', { name: tab }).click()
    await expect(page).toHaveURL(new RegExp(`tab=${tab.toUpperCase()}`))
    if (tab === 'News') await expect(page.getByTestId('news-feed').getByRole('link').first()).toBeVisible({ timeout: 15_000 })
    if (tab === 'Trades') await expect(tapeRows()).toBeVisible({ timeout: 15_000 })
    await fitsTheViewport()
  }
  await page.mouse.move(20, 300)
  await page.mouse.wheel(0, 2_000)
  expect(await page.evaluate(() => window.scrollY)).toBe(0)
  await open(page, '/watchlist')
  await fitsTheViewport()
})
