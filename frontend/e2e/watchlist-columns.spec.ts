import { type Locator, expect, test } from '@playwright/test'
import { MOCK, open, viewport } from './support/app'

/**
 * Desktop watchlist columns (§22.10): the column chooser, header sorting, drag-and-drop reordering saved through
 * the watchlists API, and the resizable panel. Phone and tablet keep the §22.4 rows (covered by watchlist.spec.ts).
 */

const rowOrder = (table: Locator): Promise<string[]> => table.locator('[role="row"][data-symbol]').evaluateAll((els) => els.map((e) => e.getAttribute('data-symbol')!))

test('columns, sorting, drag reorder and resize on desktop', async ({ page }, testInfo) => {
  test.skip(viewport(testInfo) !== 'desktop', 'the column table is the desktop watchlist (§22.10)')
  test.setTimeout(90_000)
  await open(page, '/watchlist')
  const panel = page.getByRole('region', { name: 'Watchlist' }).first()
  const table = panel.getByRole('table', { name: 'Watchlist symbols' })
  await expect(table).toBeVisible()
  await expect(table.getByRole('columnheader', { name: /Last/ })).toBeVisible()

  // choose Volume and Bid / Ask; they persist across a reload
  await panel.getByRole('button', { name: 'Columns' }).click()
  const chooser = page.getByTestId('column-chooser')
  await chooser.getByRole('checkbox', { name: 'Volume' }).check()
  await chooser.getByRole('checkbox', { name: 'Bid / Ask' }).check()
  await page.keyboard.press('Escape')
  await expect(table.getByRole('columnheader', { name: /Vol/ })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: /Bid \/ Ask/ })).toBeVisible()
  await expect(table.locator('[role="row"][data-symbol]').first()).toContainText('/')

  // sorting: % change descending, then ascending, then back to list order (drag needs list order)
  const listOrder = await rowOrder(table)
  expect(listOrder.length).toBeGreaterThanOrEqual(3)
  const pct = table.getByRole('columnheader', { name: /Chg %/ })
  await pct.getByRole('button').click()
  await expect(pct).toHaveAttribute('aria-sort', 'descending')
  await expect(panel.getByRole('button', { name: 'Clear the sort to reorder' })).toBeVisible()
  await pct.getByRole('button').click()
  await expect(pct).toHaveAttribute('aria-sort', 'ascending')
  await pct.getByRole('button').click()
  await expect(pct).toHaveAttribute('aria-sort', 'none')
  await expect.poll(() => rowOrder(table)).toEqual(listOrder)

  // drag the third symbol onto the first: saved with PUT /watchlists/{id}, so it survives a reload
  const [a, , c] = listOrder
  // (mock mode answers in the page, so there is no network response to wait for)
  const saved = MOCK ? null : page.waitForResponse((r) => /\/api\/v1\/watchlists\/[^/]+$/.test(r.url()) && r.request().method() === 'PUT')
  await table.locator(`[role="row"][data-symbol="${c}"]`).dragTo(table.locator(`[role="row"][data-symbol="${a}"]`))
  if (saved) expect((await saved).ok()).toBe(true)
  const expected = [c!, ...listOrder.filter((s) => s !== c)]
  await expect.poll(() => rowOrder(table)).toEqual(expected)
  await page.reload()
  await expect(page.getByRole('table', { name: 'Watchlist symbols' })).toBeVisible()
  await expect.poll(() => rowOrder(page.getByRole('table', { name: 'Watchlist symbols' }))).toEqual(expected)
  await expect(page.getByRole('table', { name: 'Watchlist symbols' }).getByRole('columnheader', { name: /Bid \/ Ask/ })).toBeVisible()

  // resize the panel from the keyboard
  const handle = page.getByRole('separator', { name: 'Resize watchlist' })
  const before = Number(await handle.getAttribute('aria-valuenow'))
  await handle.focus()
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowRight')
  await expect(handle).toHaveAttribute('aria-valuenow', String(before + 40))

  // put the list back (the real backend's database is shared by the specs): Edit mode's arrows
  await panel.getByRole('button', { name: 'List actions' }).click()
  await page.getByRole('dialog', { name: 'List actions' }).getByRole('button', { name: 'Edit symbols' }).click()
  const moveDown = panel.getByRole('button', { name: `Move ${c} down` })
  await moveDown.click()
  await moveDown.click()
  await panel.getByRole('button', { name: 'Done' }).click()
  await expect.poll(() => rowOrder(page.getByRole('table', { name: 'Watchlist symbols' }))).toEqual(listOrder)
})
