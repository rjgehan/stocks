import { type Page, expect } from '@playwright/test'

/**
 * Mock-mode test hooks (`window.stocksMock`, set by `lib/api/mock/index.ts` in mock builds only): move a price
 * through a stop, publish a league feed event, expire the recent password confirmation. Never available against a
 * real backend; specs guard their use with `MOCK`.
 */
interface Hooks {
  nudge(symbol: string, bps: number): void
  last(symbol: string): string | null
  emitFeed(kind?: string, handle?: string): string | null
  expireRecentAuth(): void
}

async function hooks(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => Boolean((window as unknown as { stocksMock?: Hooks }).stocksMock)), { timeout: 10_000 }).toBe(true)
}

/** Moves `symbol`'s last price by `bps` basis points and runs the mock trigger evaluator on it. */
export async function nudgePrice(page: Page, symbol: string, bps: number): Promise<void> {
  await hooks(page)
  await page.evaluate(([s, b]) => (window as unknown as { stocksMock: Hooks }).stocksMock.nudge(s as string, b as number), [symbol, bps] as const)
}

/** Publishes one `/topic/feed` item (§19) and returns its id. */
export async function emitFeed(page: Page, kind: string, handle: string): Promise<string> {
  await hooks(page)
  const id = await page.evaluate(([k, h]) => (window as unknown as { stocksMock: Hooks }).stocksMock.emitFeed(k, h), [kind, handle] as const)
  expect(id).not.toBeNull()
  return id!
}

/** The next `@RecentAuth` write asks for the password again. */
export async function expireRecentAuth(page: Page): Promise<void> {
  await hooks(page)
  await page.evaluate(() => (window as unknown as { stocksMock: Hooks }).stocksMock.expireRecentAuth())
}
