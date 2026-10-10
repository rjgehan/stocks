import { expect, test } from '@playwright/test'
import { MOCK, open, toastWith } from './support/app'
import { expireRecentAuth } from './support/mock'

/**
 * Admin corporate actions (§14, §21.1, §21.3) at all three viewports: resolve a split the split guard detected
 * (APPLY with confirmed rates, after the @RecentAuth password prompt), and skip a merger. Mock mode seeds both; a
 * real e2e backend has no pending actions, so there only the list is checked.
 */

test('resolve a detected split and skip a merger', async ({ page }) => {
  await open(page, '/admin/corporate-actions')
  await expect(page.getByRole('heading', { name: 'Corporate actions' })).toBeVisible()
  const list = page.getByRole('list', { name: 'Corporate actions' })
  if (!MOCK) {
    // a backend whose CorporateActionQueries is still the Day-0 stub answers 501 (the page says "Not available yet")
    const res = await page.request.get('/api/v1/admin/corporate-actions?status=NEEDS_ADMIN&limit=1')
    test.skip(res.status() === 501, 'This backend does not serve corporate actions yet (501 NOT_IMPLEMENTED)')
    await expect(list.or(page.getByText('Nothing needs you')).first()).toBeVisible()
    return
  }
  await expect(list.getByText('SOFI', { exact: true })).toBeVisible()

  // APPLY needs a recent password confirmation (§17.1): the shell asks once, then the request is retried.
  await expireRecentAuth(page)
  await list.getByRole('button', { name: /^Resolve SOFI/ }).click()
  const dialog = page.getByRole('dialog', { name: 'Resolve SOFI stock split' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('Old shares')).toHaveValue('1')
  await expect(dialog.getByLabel('New shares')).toHaveValue('2')
  await expect(dialog.getByText(/Every 1 SOFI share becomes 2/)).toBeVisible()
  const apply = dialog.getByRole('button', { name: 'Apply 2-for-1' })
  await expect(apply).toBeDisabled() // the audited note is required, and the field says so
  await expect(dialog.getByLabel(/^Note \(required, audited\)/)).toBeVisible()
  await dialog.getByLabel(/^Note/).fill('Confirmed on the issuer press release')
  await apply.click()
  const reauth = page.getByRole('dialog', { name: "Confirm it's you" })
  await expect(reauth).toBeVisible()
  await reauth.getByLabel('Password').fill('correct horse battery')
  await reauth.getByRole('button', { name: 'Confirm' }).click()
  await expect(toastWith(page, 'SOFI 2-for-1 split applied')).toBeVisible()
  await expect(dialog).toBeHidden()
  await expect(list.getByText('SOFI', { exact: true })).toBeHidden()

  // A merger cannot be applied in Phase 1: skip it with a note.
  await list.getByRole('button', { name: /^Resolve SIMB/ }).click()
  const merger = page.getByRole('dialog', { name: 'Resolve SIMB cash merger' })
  await expect(merger.getByRole('radio', { name: 'Apply' })).toBeDisabled()
  await merger.getByLabel(/^Note/).fill('Not a league symbol')
  await merger.getByRole('button', { name: 'Skip it' }).click()
  await expect(toastWith(page, 'SIMB cash merger skipped')).toBeVisible()
  await expect(page.getByText('Nothing needs you')).toBeVisible()

  // Both show under their new status.
  await page.getByRole('radiogroup', { name: 'Corporate action status' }).getByRole('radio', { name: 'Applied' }).click()
  await expect(list.locator('li').filter({ hasText: 'SOFI' }).first()).toContainText('by @demo')
  // the filter is in the URL: a reload keeps it (review fix)
  await expect(page).toHaveURL(/status=APPLIED/)
  await page.reload()
  await expect(page.getByRole('radiogroup', { name: 'Corporate action status' }).getByRole('radio', { name: 'Applied' })).toHaveAttribute('aria-checked', 'true')
  await expect(list.locator('li').filter({ hasText: 'SOFI' }).first()).toBeVisible()
  await page.getByRole('radiogroup', { name: 'Corporate action status' }).getByRole('radio', { name: 'Skipped' }).click()
  await expect(list.getByText('SIMB', { exact: true })).toBeVisible()
})

// Review regression: an accrued dividend is part of the equity, so the balances show it until it is paid
test('the account balances show a dividend receivable', async ({ page }) => {
  test.skip(!MOCK, 'Needs an accrued dividend (mock mode seeds one)')
  await open(page, '/account')
  await expect(page.getByText('Dividends receivable')).toBeVisible()
})
