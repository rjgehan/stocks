import { expect, test } from '@playwright/test'
import { MOCK, open, toastWith } from './support/app'

/**
 * Admin scenarios (§26, §21.3) at all three viewports: deposit to ALL players with a memo (live preview, typed
 * confirmation), the memo in the player's activity and the deposit in the league; inviting a friend; creating an
 * AI player whose key is shown once.
 */

test('deposit to all players with a memo', async ({ page }) => {
  const memo = `e2e bonus ${Date.now() % 100_000}`
  await open(page, '/admin/money')
  await expect(page.getByRole('heading', { name: 'Move money' })).toBeVisible()

  await page.getByRole('radiogroup', { name: 'Target' }).getByRole('radio', { name: 'All players' }).click()
  await page.getByRole('radiogroup', { name: 'Type' }).getByRole('radio', { name: 'Deposit' }).click()
  await page.getByLabel('Amount', { exact: true }).fill('100')
  await page.getByLabel('Memo (players see it)').fill(memo)

  // The live preview lists every affected account before anything is written.
  const preview = page.getByRole('region', { name: 'Preview' })
  await expect(preview.getByText('Applies').first()).toBeVisible({ timeout: 10_000 })
  const applying = await preview.getByText('Applies').count()
  expect(applying).toBeGreaterThan(0)

  const go = page.getByRole('button', { name: new RegExp(`^Deposit \\$100\\.00 × ${applying} players`) })
  await expect(go).toBeEnabled()
  await go.click()
  const dialog = page.getByRole('dialog', { name: 'Deposit $100.00?' })
  await expect(dialog).toBeVisible()
  const confirm = dialog.getByRole('button', { name: 'Deposit now' })
  await expect(confirm).toBeDisabled()
  await dialog.getByLabel('Type 100.00 to confirm').fill('100.00')
  await confirm.click()

  // Against the shared e2e backend another worker may sign a friend up between the preview and the apply, so the
  // batch applies to at least the previewed accounts.
  const applied = /Deposited \$100\.00 · (\d+) applied/
  await expect(toastWith(page, applied)).toBeVisible()
  const count = Number(applied.exec((await toastWith(page, applied).textContent()) ?? '')?.[1] ?? 0)
  expect(count).toBeGreaterThanOrEqual(applying)
  await expect(page.getByRole('region', { name: 'Result' })).toContainText(`${count} applied`)
  await expect(page.getByRole('region', { name: 'Recent transfers' }).getByText(memo).first()).toBeVisible()

  // The admin is also a player: the deposit and its memo show in their own activity, marked "By admin".
  await page.goto('/account/activity')
  const row = page.getByRole('list', { name: 'Activity' }).getByRole('listitem').filter({ hasText: memo }).first()
  await expect(row).toBeVisible()
  await expect(row).toContainText('Deposit')
  await expect(row).toContainText('By admin')

  // Deposits are public in the league (net contributions, "+$100 deposited"), but never move the return.
  if (MOCK) {
    await page.goto('/league?period=WEEK')
    await expect(page.getByText(/\+\$100(\.00)? deposited/).filter({ visible: true }).first()).toBeVisible()
  }
})

test('create an invite; a friend signs up with its link', async ({ page }, testInfo) => {
  await open(page, '/admin/invites')
  await page.getByRole('button', { name: 'Create link' }).click()
  const link = page.getByLabel('Invite link', { exact: true })
  await expect(link).toBeVisible()
  await expect(link).toHaveText(/^https?:\/\/\S+\/(join\/|signup\?invite=)\S+$/)
  await expect(page.getByRole('button', { name: 'Copy Invite link' })).toBeVisible()

  // The link opens the signup form with the code filled in; the friend creates an account and lands in the app
  // with the league's starting cash.
  const url = new URL((await link.textContent())!)
  await page.context().clearCookies()
  await page.goto(url.pathname + url.search)
  await expect(page.getByRole('heading', { name: 'Join the league' })).toBeVisible()
  const handle = `friend${Date.now() % 100_000}`
  await page.getByLabel('Handle').fill(handle)
  await page.getByLabel('Display name').fill('Friend')
  await page.getByLabel('Password', { exact: true }).fill('a long enough password')
  await page.getByLabel('Confirm password').fill('a long enough password')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByRole('navigation', { name: 'Main' }).or(page.getByRole('button', { name: 'Back', exact: true })).first()).toBeVisible({ timeout: 20_000 })
  await page.goto('/account')
  const nc = page.getByTitle('Starting cash plus deposits minus withdrawals')
  await expect(nc).toContainText('Net contributions')
  await expect(nc).toContainText(/\$\d/)
  await page.goto('/settings')
  if (testInfo.project.name !== 'phone') await expect(page.getByText(`Signed in as @${handle}`)).toBeVisible()
})

test('create an AI player and see its API key once', async ({ page }) => {
  test.skip(!MOCK, 'Creates a persistent player; run against a disposable backend only')
  const handle = `bot${Date.now() % 100_000}`
  await open(page, '/admin/players')
  await page.getByRole('button', { name: 'Add player' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a player' })
  await dialog.getByRole('radiogroup', { name: 'Player kind' }).getByRole('radio', { name: 'AI bot' }).click()
  await dialog.getByLabel('Handle').fill(handle)
  await dialog.getByLabel('Display name').fill('E2E Bot')
  await dialog.getByLabel('Model label').fill('Claude')
  await dialog.getByRole('button', { name: 'Create bot' }).click()

  const reveal = page.getByRole('dialog', { name: 'Copy this key now' })
  await expect(reveal).toBeVisible()
  await expect(reveal.getByText(/only time the full key is shown/)).toBeVisible()
  await expect(reveal.getByLabel('API key', { exact: true })).toHaveText(/^stk_[a-z2-7]{12}_[A-Za-z0-9_-]{43}$/)
  const done = reveal.getByRole('button', { name: 'Done' })
  await expect(done).toBeDisabled()
  await reveal.getByLabel('I copied the key').check()
  await done.click()
  await expect(reveal).toBeHidden()
  await expect(page.getByRole('table').or(page.getByRole('list')).getByText(`@${handle}`).first()).toBeVisible()
})

test('add and delete a market calendar override', async ({ page }) => {
  await open(page, '/admin/system')
  const panel = page.getByRole('region', { name: 'Calendar overrides' })
  await expect(panel).toBeVisible()
  // A day well in the future, so the override never touches the session under test.
  const day = new Date(Date.now() + 200 * 86_400_000)
  day.setUTCDate(day.getUTCDate() + ((8 - day.getUTCDay()) % 7)) // next Monday
  const date = day.toISOString().slice(0, 10)
  await panel.getByLabel('Date (ET)').fill(date)
  await panel.getByRole('radiogroup', { name: 'Override kind' }).getByRole('radio', { name: 'Early close' }).click()
  await panel.getByLabel('Regular session closes (ET)').fill('13:00')
  await panel.getByLabel('Reason (audited)').fill('e2e early close')
  await panel.getByRole('button', { name: 'Add override' }).click()
  if (!MOCK) {
    // The e2e profile runs the always-open SIM calendar (§9.3, §23.2), which refuses overrides (400 on `date`);
    // the form must show the server's message instead of failing silently.
    await expect(panel.getByRole('alert')).toContainText(/always-open SIM calendar/)
    return
  }
  const row = panel.getByRole('list', { name: 'Overrides' }).getByRole('listitem').filter({ hasText: 'e2e early close' })
  await expect(row).toBeVisible()
  await expect(row).toContainText('Early close 1:00 PM ET')
  await row.getByRole('button', { name: /^Delete the override/ }).click()
  await page.getByRole('dialog', { name: /^Delete the override/ }).getByRole('button', { name: 'Delete' }).click()
  await expect(row).toBeHidden()
})
