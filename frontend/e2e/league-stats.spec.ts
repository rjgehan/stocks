import { type Page, expect, test } from '@playwright/test'
import { MOCK, open, viewport } from './support/app'

/**
 * Phase 2 league slice (§16.8–16.11, §22.4) at all three viewports: the race chart with its standings, reactions on
 * feed items (optimistic, one per player, toggled off again, and a bot's reaction arriving live over `/topic/feed` in
 * mock mode), the stats card on a profile, the optional board columns, and the P&L Analysis tab (My Earnings vs SPY
 * and QQQ, the calendar's day and month views, P&L by symbol, your stats).
 */

/** Mock mode only: a bot reacts to the newest feed item through `/topic/feed`. */
async function botReact(page: Page, handle: string, reaction: string): Promise<string | null> {
  await expect.poll(() => page.evaluate(() => Boolean((window as unknown as { stocksMock?: unknown }).stocksMock)), { timeout: 10_000 }).toBe(true)
  return page.evaluate(([h, r]) => (window as unknown as { stocksMock: { botReact(h: string, r: string): string | null } }).stocksMock.botReact(h, r), [handle, reaction] as const)
}

test('the race chart normalizes everyone and highlights a player from the standings', async ({ page }) => {
  await open(page, '/league?view=RACE&period=WEEK')
  const race = page.getByRole('region', { name: 'Race chart' })
  await expect(race).toBeVisible()
  await expect(race.getByText('Humans', { exact: true })).toBeVisible()
  await expect(race.getByText('AI players')).toBeVisible()
  const standings = race.getByRole('list', { name: 'Race standings' })
  const rows = standings.getByRole('listitem')
  if (!MOCK) {
    await expect(rows.first().or(race.getByText('No players in this view yet.'))).toBeVisible()
    return
  }
  await expect(rows).toHaveCount(6)
  await expect(page.getByTestId('race-chart')).toBeVisible()
  await expect(page.getByTestId('race-chart').locator('canvas').first()).toBeVisible()
  const first = standings.getByRole('button', { name: /Highlight/ }).first()
  await first.click()
  await expect(first).toHaveAttribute('aria-pressed', 'true')
  await expect(race.getByRole('button', { name: 'Show all lines' })).toBeEnabled()
  await race.getByRole('button', { name: 'Show all lines' }).click()
  await expect(race.getByRole('button', { name: 'Show all lines' })).toBeDisabled()
  await expect(standings.locator('[aria-pressed="true"]')).toHaveCount(0)

  // the kind filter narrows the race like the board
  await page.getByRole('radiogroup', { name: 'Players' }).getByRole('radio', { name: 'AI' }).click()
  await expect(rows).toHaveCount(2)
  await expect(standings).toContainText('Claude')
  await expect(standings).not.toContainText('Alice')
})

test('players react to feed items, one reaction each, and see others react live', async ({ page }) => {
  await open(page, '/league?view=FEED')
  const feed = page.getByRole('list', { name: 'League feed' })
  if (!MOCK) {
    await expect(feed.or(page.getByText(/Nothing in the feed yet/)).first()).toBeVisible()
    return
  }
  await expect(feed.locator(':scope > li > ul > li').first()).toBeVisible()
  // pin the item by its text: mock mode keeps adding live items at the top
  const text = (await feed.locator(':scope > li > ul > li').first().locator('p').first().textContent()) ?? ''
  const first = feed.locator(':scope > li > ul > li').filter({ hasText: text }).first()
  const reactions = first.getByRole('group', { name: 'Reactions' })
  await reactions.getByRole('button', { name: 'Add a reaction' }).click()
  await page.getByRole('group', { name: 'Reactions to pick' }).getByRole('button', { name: 'Rocket' }).click()
  const mine = reactions.getByRole('button', { name: 'Rocket 1, yours' })
  await expect(mine).toHaveAttribute('aria-pressed', 'true')

  // another emoji moves my one reaction
  await reactions.getByRole('button', { name: 'Add a reaction' }).click()
  await page.getByRole('group', { name: 'Reactions to pick' }).getByRole('button', { name: 'Fire' }).click()
  await expect(reactions.getByRole('button', { name: 'Fire 1, yours' })).toBeVisible()
  await expect(reactions.getByRole('button', { name: /^Rocket/ })).toHaveCount(0)

  // a bot reacts too: pushed on /topic/feed and merged into the same item
  expect(await botReact(page, 'claude', 'FIRE')).not.toBeNull()
  await expect(reactions.getByRole('button', { name: 'Fire 2, yours' })).toBeVisible()

  // tapping mine again removes it; Claude's stays
  await reactions.getByRole('button', { name: 'Fire 2, yours' }).click()
  await expect(reactions.getByRole('button', { name: 'Fire 1' })).toHaveAttribute('aria-pressed', 'false')

  // the server keeps it: leave the feed and come back (a refetch)
  await page.getByRole('radiogroup', { name: 'View' }).getByRole('radio', { name: 'Board' }).first().click()
  await expect(page.getByRole('list', { name: 'League feed' })).toBeHidden()
  await page.getByRole('radiogroup', { name: 'View' }).getByRole('radio', { name: 'Feed' }).first().click()
  await expect(reactions.getByRole('button', { name: 'Fire 1' })).toBeVisible()
})

test('a profile shows league stats with Sharpe, profit factor and rank history', async ({ page }) => {
  await open(page, MOCK ? '/players/claude' : '/league')
  test.skip(!MOCK, 'A fresh e2e league has no other player profile to open')
  const stats = page.getByRole('region', { name: 'League stats' })
  await expect(stats).toBeVisible()
  await expect(stats.getByText('Sharpe', { exact: true })).toBeVisible()
  await expect(stats.getByText('Profit factor', { exact: true })).toBeVisible()
  await expect(stats.getByText('Best trade', { exact: true })).toBeVisible()
  await stats.getByRole('radiogroup', { name: 'Stats period' }).getByRole('radio', { name: 'Week' }).click()
  await expect(stats.getByText(/needs 20 days · 3\/20/)).toBeVisible()
  await stats.getByRole('radiogroup', { name: 'Stats period' }).getByRole('radio', { name: 'All' }).click()
  await expect(stats.getByText(/needs 20 days/)).toHaveCount(0)
  await expect(stats.getByText(/Best and worst trades appear after the 15-minute feed delay/)).toBeVisible()
})

test('the board takes optional stat columns', async ({ page }, testInfo) => {
  await open(page, '/league?period=ALL')
  if (viewport(testInfo) === 'phone') {
    await page.getByLabel('Extra stat on each card').selectOption('SHARPE')
    if (MOCK) await expect(page.getByRole('list', { name: 'Leaderboard' }).getByText(/^Sharpe/).first()).toBeVisible()
    return
  }
  await page.getByRole('button', { name: /^Columns/ }).click()
  await page.getByRole('dialog', { name: 'Leaderboard columns' }).getByLabel('Sharpe ratio').check()
  await page.getByRole('dialog', { name: 'Leaderboard columns' }).getByLabel('Profit factor').check()
  await page.keyboard.press('Escape')
  const table = page.getByRole('table', { name: 'Leaderboard' })
  await expect(table.getByRole('columnheader', { name: 'Sharpe' })).toBeVisible({ timeout: 10_000 })
  await expect(table.getByRole('columnheader', { name: 'PF' })).toBeVisible()
  // remembered per browser
  await page.reload()
  await expect(page.getByRole('table', { name: 'Leaderboard' }).getByRole('columnheader', { name: 'Sharpe' })).toBeVisible({ timeout: 15_000 })
})

test('P&L Analysis: earnings vs SPY and QQQ, the calendar and P&L by symbol', async ({ page }, testInfo) => {
  await open(page, '/account/analysis')
  const earnings = page.getByRole('region', { name: 'My earnings' })
  await expect(earnings).toBeVisible()
  if (MOCK) {
    await expect(earnings.getByText('SPY', { exact: true })).toBeVisible()
    await expect(earnings.getByText('QQQ', { exact: true })).toBeVisible()
  }
  await earnings.getByRole('radiogroup', { name: 'Earnings range' }).getByRole('radio', { name: '3M' }).click()
  await expect(page).toHaveURL(/range=3M/)

  const calendar = page.getByRole('region', { name: 'P&L calendar' })
  await expect(calendar).toBeVisible()
  const grid = calendar.getByRole('table', { name: /Daily P&L/ })
  await expect(grid).toBeVisible()
  await expect(grid.getByRole('columnheader')).toHaveCount(5)
  await expect(calendar.getByText('Month total')).toBeVisible()
  await calendar.getByRole('button', { name: 'Previous month' }).click()
  await expect(page).toHaveURL(/month=\d{4}-\d{2}/)
  await calendar.getByRole('radiogroup', { name: 'Calendar view' }).getByRole('radio', { name: 'Month' }).click()
  await expect(calendar.getByRole('list', { name: /Monthly P&L/ }).getByRole('listitem')).toHaveCount(12)
  await expect(calendar.getByText('Year total')).toBeVisible()

  const bySymbol = page.getByRole('region', { name: 'P&L by symbol' })
  await expect(bySymbol).toBeVisible()
  await expect(page.getByRole('region', { name: 'Your stats' })).toBeVisible()
  if (viewport(testInfo) !== 'phone') {
    // the account tabs list the new section
    await expect(page.getByRole('navigation', { name: 'Account sections' }).getByRole('link', { name: 'Analysis' })).toBeVisible()
  }
})
