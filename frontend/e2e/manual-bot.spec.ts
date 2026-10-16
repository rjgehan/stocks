import { expect, test } from '@playwright/test'
import { MOCK, chooseOrderType, open, openTicket, toastWith, viewport } from './support/app'

/**
 * Manual bots (§20.8) at all three viewports, in mock mode and against a real backend: the admin creates a manual
 * bot (no key) and lands on its console, writes instructions, makes a prompt and copies it, pastes a canned AI reply
 * (a market buy and a resting limit buy of AAPL), checks it, unticks the limit row and places the market buy as the
 * bot. Each viewport creates its own bot (`mb_<project>_<n>`).
 */

// several pages per test, and the first visit compiles each route chunk in Vite's dev server
test.describe.configure({ timeout: 120_000 })

const SYMBOL = 'AAPL'

test('a manual bot: prompt out, reply in, one order placed as the bot', async ({ page, context }, testInfo) => {
  const vp = viewport(testInfo)
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])

  // 1. The bid, read from the ticket as trading.spec.ts does: the limit row rests 3% under it (no fat-finger
  //    warning, which starts at 5%; never more than 50% away), one cent apart per viewport.
  await open(page, `/stock/${SYMBOL}`)
  await expect(page.getByRole('heading', { name: SYMBOL, level: 1 })).toBeVisible()
  const ticket = await openTicket(page, vp, SYMBOL, 'Buy')
  await chooseOrderType(ticket, 'Limit')
  const bidLabel = await ticket.getByRole('button', { name: /^Use the bid price/ }).getAttribute('aria-label')
  const bid = Number(bidLabel?.match(/([\d,]+\.\d+)/)?.[1]?.replaceAll(',', ''))
  expect(bid).toBeGreaterThan(0)
  const limit = ((Math.floor(bid * 0.97 * 100) - (['phone', 'tablet', 'desktop'] as const).indexOf(vp)) / 100).toFixed(2)

  // 2. Admin → Players → Add player → AI bot → Manual: no key dialog, straight to the console.
  const handle = `mb_${vp}_${Date.now() % 100_000}`
  await open(page, '/admin/players')
  await page.getByRole('button', { name: 'Add player' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a player' })
  await dialog.getByRole('radiogroup', { name: 'Player kind' }).getByRole('radio', { name: 'AI bot' }).click()
  await dialog.getByLabel('Handle').fill(handle)
  await dialog.getByLabel('Display name').fill('Manual Gem')
  await dialog.getByLabel('Model label').fill('Gemini 3')
  await dialog.getByRole('radio', { name: 'Manual: copy & paste prompts (no key)' }).check()
  await dialog.getByRole('button', { name: 'Create bot' }).click()
  await expect(page).toHaveURL(new RegExp(`/bots/${handle}$`))
  await expect(page.getByRole('dialog', { name: 'Copy this key now' })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Bot settings' })).toBeVisible()

  // 3. Instructions (public, part of every prompt).
  await page.getByLabel('Instructions (public, part of every prompt)').fill('Large caps only; keep at least 20% in cash.')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(toastWith(page, 'Saved')).toBeVisible()

  // 4. New prompt, then Copy prompt: the clipboard holds the prompt.
  await page.getByRole('button', { name: 'New prompt' }).click()
  await expect(page).toHaveURL(/[?&]session=mbs_[0-9A-Z]{26}/)
  const sessionId = new URL(page.url()).searchParams.get('session')!
  await page.getByRole('button', { name: /^(Copy prompt|Copied ✓)$/ }).click()
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()), { timeout: 10_000 }).toContain('I am conducting a test of how well chat bots can trade')
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(sessionId)

  // 5. The AI's answer: prose plus one ```json block for this session.
  const reply = [
    "Here is my plan. AAPL's pullback looks like a fair entry, so I start small.",
    '',
    '```json',
    '{',
    '  "format": "stocks.manual.v1",',
    `  "session": "${sessionId}",`,
    '  "summary": "Bought one AAPL at market and left a limit bid 3% below for a second share.",',
    '  "orders": [',
    `    {"action": "BUY", "symbol": "${SYMBOL}", "type": "MARKET", "qty": "1", "time_in_force": "DAY", "rationale": "A first position after the pullback."},`,
    `    {"action": "BUY", "symbol": "${SYMBOL}", "type": "LIMIT", "qty": "1", "limit_price": "${limit}", "time_in_force": "GTC", "rationale": "A second share only on a further dip."}`,
    '  ]',
    '}',
    '```',
  ].join('\n')
  await page.getByLabel("Paste the AI's reply").fill(reply)

  // 6. Check: both rows pass and are ticked; untick the limit row and place the market buy.
  await page.getByRole('button', { name: 'Check reply' }).click()
  const review = page.getByRole('region', { name: 'Review and place' })
  const row1 = review.getByRole('checkbox', { name: 'Place row 1' })
  const row2 = review.getByRole('checkbox', { name: 'Place row 2' })
  await expect(row1).toBeChecked({ timeout: 15_000 })
  await expect(row2).toBeChecked()
  await expect(review.getByRole('button', { name: `Place 2 orders as @${handle}` })).toBeEnabled()
  await row2.uncheck()
  await review.getByRole('button', { name: `Place 1 order as @${handle}` }).click()
  await expect(review.getByText(/^PLACED · /).first()).toBeVisible({ timeout: 15_000 })
  await expect(review.getByText('Summary posted to the journal')).toBeVisible()
  await expect(row1).toBeDisabled()
  await expect(row2).toBeEnabled()
  await expect(page.getByLabel("Paste the AI's reply")).not.toBeEditable()

  // 7. The session is public: the console lists it, and so does GET /players/{handle}/manual-sessions (prompt and
  //    reply only in the detail). The profile shows the model label from the create dialog.
  await expect(page.getByRole('region', { name: 'Recent sessions' }).getByText('1 placed')).toBeVisible()
  if (!MOCK) {
    const list = await page.request.get(`/api/v1/players/${handle}/manual-sessions`)
    expect(list.status()).toBe(200)
    const body = (await list.json()) as { items: Array<{ sessionId: string; status: string; untrusted: { prompt: string | null }; actions: Array<{ result: { status: string; orderId: string } | null }> }> }
    expect(body.items[0]!.sessionId).toBe(sessionId)
    expect(body.items[0]!.status).toBe('EXECUTED')
    expect(body.items[0]!.untrusted.prompt).toBeNull()
    expect(body.items[0]!.actions[0]!.result?.status).toBe('PLACED')
    expect(body.items[0]!.actions[1]!.result).toBeNull()
  }
  await page.getByRole('link', { name: 'Profile', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/players/${handle}$`))
  await expect(page.getByText('AI · Gemini 3').first()).toBeVisible()

  // 8. History: the placed order (only the market buy) carries a "Manual console" link to its session, which the
  //    "Manual sessions" section shows first, highlighted, with the pasted reply open.
  await open(page, `/players/${handle}/history`)
  const orders = page.getByRole('list', { name: 'Orders' })
  const pill = orders.getByRole('link', { name: 'Manual console: open the session' })
  await expect(pill).toHaveCount(1, { timeout: 15_000 })
  await expect(orders.getByText(`1 sh ${SYMBOL} · Market`)).toBeVisible()
  const sessions = page.getByRole('region', { name: 'Manual sessions' })
  await expect(sessions.getByRole('listitem', { name: `Manual session ${sessionId}` })).toBeVisible()
  await pill.click()
  await expect(page).toHaveURL(new RegExp(`/players/${handle}/history\\?session=${sessionId}$`))
  const card = sessions.getByRole('listitem', { name: `Manual session ${sessionId}` })
  await expect(card).toHaveAttribute('aria-current', 'true')
  await expect(card.getByText('pasted by @', { exact: false })).toBeVisible()
  await expect(card.getByLabel('Reply text')).toContainText("Here is my plan. AAPL's pullback", { timeout: 15_000 })
})
