import { expect, test } from '@playwright/test'
import { MOCK, open } from './support/app'
import { emitFeed } from './support/mock'

/**
 * League (§22.4) at all three viewports: the Arena card (median human vs median AI, SPY), the formulas behind the
 * board, and the feed sub-view with live `/topic/feed` events (§19). Mock mode publishes a bot trade on demand.
 */

test('the board shows the Arena and explains its formulas', async ({ page }) => {
  await open(page, '/league?period=WEEK')
  const arena = page.getByRole('region', { name: 'Arena' })
  await expect(arena).toBeVisible()
  await expect(arena.getByText('Humans', { exact: true }).first()).toBeVisible()
  await expect(arena.getByText('AI', { exact: true }).first()).toBeVisible()
  await expect(arena.getByText(/leads? by|Dead even|Waiting for both teams|No players yet/)).toBeVisible()

  await arena.getByRole('button', { name: /How arena: humans vs ai is computed/i }).click()
  await expect(page.getByRole('dialog', { name: 'Arena: humans vs AI' })).toContainText('median')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: 'Arena: humans vs AI' })).toBeHidden()

  await page.getByRole('button', { name: 'How the league is scored' }).first().click()
  await expect(page.getByRole('dialog', { name: 'How the league is scored' })).toContainText('U(end) ÷ U(start) − 1')
})

test('the feed shows league events and new ones arrive live', async ({ page }) => {
  await open(page, '/league?view=FEED')
  await expect(page.getByRole('radiogroup', { name: 'Feed filter' })).toBeVisible()
  const feed = page.getByRole('list', { name: 'League feed' })
  if (!MOCK) {
    // a fresh e2e league may have no visible events yet; either way the view renders
    await expect(feed.or(page.getByText(/Nothing in the feed yet/)).first()).toBeVisible()
    return
  }
  await expect(feed).toBeVisible()
  const before = await feed.locator(':scope > li > ul > li').count()

  // A bot trade becomes visible: it is pushed on /topic/feed and slides in at the top, marked New.
  await emitFeed(page, 'FILL', 'claude')
  const fresh = feed.locator(':scope > li > ul > li').filter({ hasText: 'New' }).first()
  await expect(fresh).toBeVisible()
  await expect(fresh).toContainText('Claude')
  await expect(fresh).toContainText(/bought|sold/)
  await expect(feed.locator(':scope > li > ul > li')).toHaveCount(before + 1)

  // Filters narrow the list by kind.
  await page.getByRole('radiogroup', { name: 'Feed filter' }).getByRole('radio', { name: 'Leaders' }).click()
  await expect(feed.getByText(/took the lead/).first()).toBeVisible()
  await expect(feed.getByText(/ bought | sold /)).toHaveCount(0)
})
