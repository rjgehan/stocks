import { createHash, randomBytes } from 'node:crypto'
import { type APIRequestContext, type Page, expect, test } from '@playwright/test'
import { MOCK, credentials, open } from './support/app'

/**
 * OAuth 2.1 for the claude.ai / ChatGPT connectors (§17.9) at all three viewports: the act-as consent screen, the
 * redirect back to the app with a one-time code, and Settings → Connected apps with Disconnect.
 *  - mock mode: the consent screen of a claude.ai request (any `oar_…` id), the redirect to claude.ai intercepted;
 *  - real backend (`make e2e`): the whole flow with a dynamically registered loopback client: /oauth2/authorize →
 *    sign in → consent → code → /oauth2/token (PKCE) → `whoami` over /mcp with the JWT → Disconnect → 401.
 */

const LOOPBACK = 'http://127.0.0.1:9/callback'

async function signInIfAsked(page: Page): Promise<void> {
  const signIn = page.getByRole('button', { name: 'Sign in' })
  const consent = page.getByRole('heading', { name: /^Connect / })
  await expect(signIn.or(consent).first()).toBeVisible({ timeout: 20_000 })
  if (await signIn.isVisible()) {
    const creds = credentials()
    test.skip(!creds, 'Real backend without E2E_USER/E2E_PASSWORD')
    await page.getByLabel('Handle').fill(creds!.handle)
    await page.getByLabel('Password').fill(creds!.password)
    await signIn.click()
  }
  await expect(consent).toBeVisible({ timeout: 20_000 })
}

async function disconnect(page: Page, appName: string): Promise<void> {
  await open(page, '/settings/connected-apps')
  const list = page.getByRole('list', { name: 'Connected apps' })
  await expect(list.getByText(appName, { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: `Disconnect ${appName}` }).first().click()
  await page.getByRole('dialog').getByRole('button', { name: 'Disconnect' }).click()
  await expect(page.getByText(`Disconnected ${appName}`).first()).toBeVisible()
}

function pkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString('base64url')
  return { verifier, challenge: createHash('sha256').update(verifier).digest('base64url') }
}

async function mcpWhoami(request: APIRequestContext, token: string) {
  return request.post('/mcp', {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json, text/event-stream', 'Content-Type': 'application/json' },
    data: { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'whoami', arguments: {} } },
  })
}

test('connect an app with the act-as consent, then disconnect it', async ({ page, request }, testInfo) => {
  if (MOCK) {
    await page.route('https://claude.ai/**', (route) => route.fulfill({ contentType: 'text/html', body: '<h1>claude.ai callback</h1>' }))
    await open(page, '/settings')
    await page.goto(`/oauth/consent?request=oar_e2e${Date.now()}`)
    await signInIfAsked(page)
    await expect(page.getByRole('heading', { name: 'Connect Claude' })).toBeVisible()
    await expect(page.getByText('claude.ai', { exact: true }).first()).toBeVisible()
    // act as the bot (preselected), with every requested scope
    const bot = page.getByRole('radio', { name: 'Claude (@claude)' })
    await expect(bot).toBeChecked()
    await expect(page.getByRole('checkbox', { name: /Trade/ })).toBeChecked()
    await page.getByRole('button', { name: 'Allow' }).click()
    await expect(page).toHaveURL(/^https:\/\/claude\.ai\/api\/mcp\/auth_callback\?code=mock_.*&state=mock-state&iss=/)
    await disconnect(page, 'Claude')
    await expect(page.getByText('No connected apps')).toBeVisible()
    return
  }

  // Real backend: a dynamically registered public client with a loopback redirect (like the MCP Inspector). One per
  // viewport project, so the parallel projects never disconnect each other's app. A dozen round trips (register,
  // authorize, sign in, consent, token, MCP, Settings, MCP again), so it gets more than the default 30 s.
  test.setTimeout(90_000)
  const appName = `e2e connector ${testInfo.project.name}`
  const reg = await request.post('/oauth2/register', {
    data: { client_name: appName, redirect_uris: [LOOPBACK], token_endpoint_auth_method: 'none' },
  })
  expect(reg.status()).toBe(201)
  const clientId = ((await reg.json()) as { client_id: string }).client_id
  const resource = ((await (await request.get('/.well-known/oauth-protected-resource/mcp')).json()) as { resource: string }).resource
  const { verifier, challenge } = pkce()
  const state = `e2e-${Date.now()}`
  await page.route('http://127.0.0.1:9/**', (route) => route.fulfill({ contentType: 'text/html', body: '<h1>callback</h1>' }))

  const query = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: LOOPBACK,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
    scope: 'market:read account:read league:read offline_access',
    resource,
  })
  await page.goto(`/oauth2/authorize?${query.toString()}`)
  await signInIfAsked(page)
  await expect(page.getByRole('heading', { name: `Connect ${appName}` })).toBeVisible()
  await expect(page.getByText(/runs on your own computer/)).toBeVisible()
  const me = credentials()!.handle
  await page.getByRole('radio', { name: `Myself (@${me})` }).check()
  await page.getByRole('button', { name: 'Allow' }).click()
  await expect(page).toHaveURL(/^http:\/\/127\.0\.0\.1:9\/callback\?code=/)
  const back = new URL(page.url())
  expect(back.searchParams.get('state')).toBe(state)
  expect(back.searchParams.get('iss')).toBeTruthy()

  const token = await request.post('/oauth2/token', {
    form: { grant_type: 'authorization_code', code: back.searchParams.get('code')!, code_verifier: verifier, client_id: clientId, redirect_uri: LOOPBACK, resource },
  })
  expect(token.status()).toBe(200)
  const tokens = (await token.json()) as { access_token: string; refresh_token: string; scope: string }
  expect(tokens.scope).toBe('account:read league:read market:read')

  const whoami = await mcpWhoami(request, tokens.access_token)
  expect(whoami.status()).toBe(200)
  const body = (await whoami.json()) as { result: { structuredContent: { me: { handle: string; authMethod: string } } } }
  expect(body.result.structuredContent.me.handle).toBe(me)
  expect(body.result.structuredContent.me.authMethod).toBe('OAUTH')

  await disconnect(page, appName)
  expect((await mcpWhoami(request, tokens.access_token)).status()).toBe(401)
})

test('an unknown client gets the error page, never a redirect', async ({ page }) => {
  test.skip(MOCK, 'the authorization endpoint is the backend’s')
  await page.goto('/oauth2/authorize?response_type=code&client_id=ocl_unknown&redirect_uri=https%3A%2F%2Fevil.example%2Fcb')
  await expect(page).toHaveURL(/\/oauth\/error\?error=invalid_client/)
  await expect(page.getByRole('heading', { name: 'Could not connect the app' })).toBeVisible()
})
