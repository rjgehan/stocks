import { type Locator, type Page, type TestInfo, expect, test } from '@playwright/test'

/**
 * Shared e2e helpers (§26). They work against both targets:
 *  - mock mode (default): Vite with VITE_API_MOCK=1, a fresh in-memory league per browser context, signed in as
 *    the admin "demo" (any password except "wrong");
 *  - a real backend (E2E_BASE_URL, profiles test,e2e): sign in with E2E_USER / E2E_PASSWORD (`make e2e`).
 */
export const MOCK = !process.env.E2E_BASE_URL

export type Viewport = 'phone' | 'tablet' | 'desktop'
export const viewport = (testInfo: TestInfo): Viewport => testInfo.project.name as Viewport

/** Credentials for the login form: anything works in mock mode; the e2e profile's admin otherwise. */
export function credentials(): { handle: string; password: string } | null {
  if (MOCK) return { handle: 'demo', password: 'correct horse battery' }
  const handle = process.env.E2E_USER
  const password = process.env.E2E_PASSWORD
  return handle && password ? { handle, password } : null
}

/**
 * The signed-in app chrome: the main navigation (rail, tab bar), or the Back button of a phone pushed screen
 * (quote page, admin, order detail), which has no tab bar (§22.3).
 */
const appChrome = (page: Page): Locator => page.getByRole('navigation', { name: 'Main' }).or(page.getByRole('button', { name: 'Back', exact: true }))

/** The app is up once either the login form or the app chrome shows (route chunks load on demand). */
async function settled(page: Page): Promise<'login' | 'app'> {
  const login = page.getByRole('button', { name: 'Sign in' })
  await expect(login.or(appChrome(page)).first()).toBeVisible({ timeout: 20_000 })
  return (await login.isVisible()) ? 'login' : 'app'
}

/** Fills and submits the login form (the page must show it). */
export async function submitLogin(page: Page): Promise<void> {
  const creds = credentials()
  test.skip(!creds, 'Real backend without E2E_USER/E2E_PASSWORD')
  await page.getByLabel('Handle').fill(creds!.handle)
  await page.getByLabel('Password').fill(creds!.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(appChrome(page).first()).toBeVisible({ timeout: 20_000 })
}

/** Opens `path`, signing in first when the app asks for it. */
export async function open(page: Page, path: string): Promise<void> {
  await page.goto(path)
  if ((await settled(page)) === 'login') {
    await submitLogin(page)
    await page.goto(path)
    await settled(page)
  }
}

/**
 * Finds a symbol through the search UI and lands on its quote page: the ⌘K bar on desktop, the full-screen
 * `/search` page on tablet and phone (§22.2).
 */
export async function searchAndOpen(page: Page, vp: Viewport, query: string, symbol: string): Promise<void> {
  if (vp === 'desktop') {
    await page.getByRole('combobox', { name: 'Search symbols' }).click()
  } else {
    await page.getByRole('button', { name: 'Search', exact: true }).first().click()
    await expect(page).toHaveURL(/\/search/)
  }
  const box = page.getByRole('combobox', { name: 'Search symbols' })
  await box.fill(query)
  const option = page.getByRole('listbox', { name: 'Search results' }).getByRole('option').filter({ hasText: symbol }).first()
  await expect(option).toBeVisible()
  await option.click()
  await expect(page).toHaveURL(new RegExp(`/stock/${symbol}`))
  await expect(page.getByRole('heading', { name: symbol, level: 1 })).toBeVisible()
}

/**
 * The trade ticket for the open quote page: docked on desktop, a side sheet (tablet) or bottom sheet (phone)
 * opened from the Buy button otherwise. Returns the ticket's container.
 */
export async function openTicket(page: Page, vp: Viewport, symbol: string, side: 'Buy' | 'Sell' = 'Buy'): Promise<Locator> {
  if (vp === 'desktop') {
    const ticket = page.getByRole('complementary', { name: `Trade ${symbol}` })
    await expect(ticket).toBeVisible()
    await ticket.getByRole('radiogroup', { name: 'Side' }).getByRole('radio', { name: side }).click()
    return ticket
  }
  await page.getByRole('button', { name: side, exact: true }).first().click()
  const sheet = page.getByRole('dialog', { name: `Trade ${symbol}` })
  await expect(sheet).toBeVisible()
  return sheet
}

/** Picks one option of a `Segmented` control (radiogroup) inside `scope`. */
export async function choose(scope: Locator, group: string, option: string): Promise<void> {
  const radio = scope.getByRole('radiogroup', { name: group }).getByRole('radio', { name: option, exact: true })
  await radio.click()
  await expect(radio).toHaveAttribute('aria-checked', 'true')
}

/**
 * Submits the ticket: waits for a valid preview (the button is disabled until then, §22.5), presses it, and
 * confirms in the review dialog when order confirmation is on (the default).
 */
export async function submitTicket(page: Page, ticket: Locator, symbol: string, side: 'Buy' | 'Sell' = 'Buy'): Promise<void> {
  const submit = ticket.getByRole('button', { name: `${side} ${symbol}`, exact: true })
  await expect(submit).toBeEnabled({ timeout: 10_000 })
  await submit.click()
  const review = page.getByRole('dialog', { name: `Review ${side.toLowerCase()} ${symbol}` })
  if (await review.isVisible().catch(() => false)) {
    await review.getByRole('button', { name: `Confirm ${side.toLowerCase()}` }).click()
  } else {
    await expect(review.or(page.getByRole('status').filter({ hasText: symbol })).first()).toBeVisible()
    if (await review.isVisible()) await review.getByRole('button', { name: `Confirm ${side.toLowerCase()}` }).click()
  }
  await expect(review).toBeHidden()
}

/** Navigates to an account sub-page through the UI-independent URL (the tab bar and rail are covered elsewhere). */
export async function gotoAccount(page: Page, sub: 'positions' | 'orders' | 'activity' | '' = ''): Promise<void> {
  await page.goto(`/account${sub ? `/${sub}` : ''}`)
  await settled(page)
}

/** A toast (role status/alert) whose text matches. */
export const toastWith = (page: Page, text: string | RegExp): Locator => page.getByRole('status').or(page.getByRole('alert')).filter({ hasText: text }).first()
