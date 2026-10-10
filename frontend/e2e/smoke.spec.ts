import { type Locator, type Page, expect, test } from '@playwright/test'

/**
 * M0 smoke: the SPA shell renders on phone, tablet and desktop. Against mock mode the demo player is
 * already signed in; against a real backend set E2E_USER / E2E_PASSWORD (dev seed) to sign in first.
 * The §26 trading scenarios (notional buy → fill toast → GFV ack, admin deposit, bot order + advanceClock)
 * are written in Phase 1 against profiles test,e2e (`make e2e` passes the e2e profile's admin).
 */

/**
 * Signs in through the login page when the app shows it (real backend); a no-op in mock mode. Waits until the
 * app has settled on either the login page or `signedIn` (the session check is asynchronous, and a dev server
 * compiles routes on demand), so a slow first render never skips the login.
 */
async function signInIfNeeded(page: Page, signedIn: Locator) {
  const login = page.getByRole('button', { name: 'Sign in' })
  await expect(login.or(signedIn).first()).toBeVisible({ timeout: 15_000 })
  if (!(await login.isVisible())) return
  const user = process.env.E2E_USER
  const password = process.env.E2E_PASSWORD
  test.skip(!user || !password, 'Real backend without E2E_USER/E2E_PASSWORD: only the login page was checked')
  await page.getByLabel('Handle').fill(user!)
  await page.getByLabel('Password').fill(password!)
  await login.click()
}

test('the shell renders for this viewport', async ({ page }, testInfo) => {
  await page.goto('/')
  await signInIfNeeded(page, page.getByRole('navigation', { name: 'Main' }))

  if (testInfo.project.name === 'phone') {
    // Root screen with the bottom tab bar (§22.3)
    await expect(page).toHaveURL(/\/watchlist/)
    const tabs = page.getByRole('navigation', { name: 'Main' })
    for (const label of ['Watchlist', 'Markets', 'League', 'Account', 'Menu']) {
      await expect(tabs.getByRole('link', { name: label })).toBeVisible()
    }
  } else {
    await expect(page).toHaveURL(/\/stock\/[A-Z]+/)
    await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'League' })).toBeVisible()
    await expect(page.getByText('Paper', { exact: true }).first()).toBeVisible()
  }
})

test('the quote page shows the ticket', async ({ page }, testInfo) => {
  await page.goto('/stock/AAPL')
  await signInIfNeeded(page, page.getByRole('heading', { name: 'AAPL', level: 1 }))
  await expect(page.getByRole('heading', { name: 'AAPL', level: 1 })).toBeVisible()
  if (testInfo.project.name === 'desktop') {
    await expect(page.getByRole('button', { name: 'Buy AAPL' })).toBeVisible()
  } else {
    await page.getByRole('button', { name: 'Buy', exact: true }).first().click()
    await expect(page.getByRole('dialog', { name: 'Trade AAPL' })).toBeVisible()
  }
})
