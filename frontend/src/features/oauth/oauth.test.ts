import { beforeEach, describe, expect, it } from 'vitest'
import type { ConnectedApp, OAuthActor, OAuthConsent } from '@/lib/api/types'
import { MockServer } from '@/lib/api/mock/server'
import { approval, blockedReason, clientName, clientOrigin, defaultActor, grantableScopes, orderedScopes, reconcileSelection, safeRedirect } from './scopes'

const player = (handle: string, kind: 'HUMAN' | 'AI') => ({
  playerId: `ply_${handle}`,
  handle,
  displayName: handle,
  kind,
  aiModel: kind === 'AI' ? 'Claude' : null,
  avatarSeed: handle,
  memberSince: '2026-10-01T13:00:00Z',
  resets: 0,
})
const bot: OAuthActor = { player: player('claude', 'AI'), self: false, allowedScopes: ['market:read', 'account:read', 'trade', 'league:read'] }
const me: OAuthActor = { player: player('demo', 'HUMAN'), self: true, allowedScopes: ['market:read', 'account:read', 'league:read'] }
const ALL = ['trade', 'league:read', 'market:read', 'account:read']

describe('act-as consent rules (§17.9.3)', () => {
  it('orders scopes and drops unknown ones (offline_access, openid, admin)', () => {
    expect(orderedScopes(['league:read', 'openid', 'trade', 'offline_access', 'market:read', 'admin', null])).toEqual(['market:read', 'trade', 'league:read'])
  })

  it('grants requested ∩ allowed: a bot may trade, the human only with API trading', () => {
    expect(grantableScopes(ALL, bot)).toEqual(['market:read', 'account:read', 'trade', 'league:read'])
    expect(grantableScopes(ALL, me)).toEqual(['market:read', 'account:read', 'league:read'])
    expect(grantableScopes(['market:read'], bot)).toEqual(['market:read'])
    expect(blockedReason('trade', me)).toMatch(/admin/)
    expect(blockedReason('trade', bot)).toBeNull()
  })

  it('preselects the first bot, else yourself', () => {
    expect(defaultActor([me, bot])).toBe('claude')
    expect(defaultActor([me])).toBe('demo')
    expect(defaultActor([])).toBeNull()
  })

  it('keeps what is still grantable when the actor changes, else selects everything grantable', () => {
    const fromBot = reconcileSelection(new Set(['trade', 'market:read']), ALL, me)
    expect([...fromBot]).toEqual(['market:read'])
    const onlyTrade = reconcileSelection(new Set(['trade']), ALL, me)
    expect([...onlyTrade].sort()).toEqual(['account:read', 'league:read', 'market:read'])
    expect([...reconcileSelection(null, ALL, bot)]).toHaveLength(4)
    expect(approval('claude', new Set(['trade', 'market:read']))).toEqual({ approve: true, actAs: 'claude', scopes: ['market:read', 'trade'] })
  })

  it('names the client and only follows http(s) redirects', () => {
    expect(clientName({ clientId: 'x', name: 'Claude', kind: 'CIMD', host: 'claude.ai' })).toBe('Claude')
    expect(clientOrigin({ clientId: 'x', name: 'Claude', kind: 'CIMD', host: 'claude.ai' })).toBe('claude.ai')
    expect(clientOrigin({ clientId: 'ocl_1', name: 'Inspector', kind: 'DCR', host: null })).toBe('registered dynamically')
    expect(clientName({ clientId: 'x', name: '  ', kind: 'CIMD', host: 'chatgpt.com' })).toBe('chatgpt.com')
    expect(safeRedirect('https://claude.ai/api/mcp/auth_callback?code=a&state=b')).toContain('code=a')
    expect(safeRedirect('http://127.0.0.1:6274/cb?code=a')).toContain('127.0.0.1')
    expect(safeRedirect('javascript:alert(1)')).toBeNull()
    expect(safeRedirect('/relative')).toBeNull()
    expect(safeRedirect(null)).toBeNull()
  })
})

describe('mock OAuth endpoints (§22.9)', () => {
  let server: MockServer
  let csrf = ''
  async function call<T>(method: string, path: string, body?: unknown): Promise<{ status: number; body: T }> {
    if (!csrf) {
      await server.handle(new Request('http://localhost/api/v1/auth/csrf'))
      csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? ''
    }
    const res = await server.handle(
      new Request(`http://localhost/api/v1${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    )
    return { status: res.status, body: (res.status === 204 ? null : await res.json()) as T }
  }

  beforeEach(() => {
    sessionStorage.clear()
    csrf = ''
    server = new MockServer()
  })

  it('serves the §18.3 OAuthConsent shape, approves once, lists and disconnects the app', async () => {
    const consent = await call<OAuthConsent>('GET', '/me/oauth/requests/oar_test1')
    expect(consent.status).toBe(200)
    expect(Object.keys(consent.body).sort()).toEqual(['actors', 'client', 'expiresAt', 'loopbackRedirect', 'redirectHost', 'redirectUri', 'requestId', 'requestedScopes'])
    expect(consent.body.actors?.map((a) => a.player?.handle)).toEqual(['claude', 'chatgpt', 'demo'])

    const decided = await call<{ redirectUrl: string }>('POST', '/me/oauth/requests/oar_test1', { approve: true, actAs: 'claude', scopes: ['market:read', 'trade'] })
    expect(decided.body.redirectUrl).toMatch(/^https:\/\/claude\.ai\/api\/mcp\/auth_callback\?code=/)
    expect((await call('GET', '/me/oauth/requests/oar_test1')).status).toBe(404)

    const apps = await call<ConnectedApp[]>('GET', '/me/connected-apps')
    expect(apps.body).toHaveLength(1)
    expect(Object.keys(apps.body[0]!).sort()).toEqual(['actAs', 'client', 'createdAt', 'grantId', 'lastUsedAt', 'redirectHost', 'scopes'])
    expect(apps.body[0]!.actAs?.handle).toBe('claude')
    expect((await call('DELETE', `/me/connected-apps/${apps.body[0]!.grantId}`)).status).toBe(204)
    expect((await call<ConnectedApp[]>('GET', '/me/connected-apps')).body).toHaveLength(0)
  })

  it('denies, expires and refuses foreign actors', async () => {
    const denied = await call<{ redirectUrl: string }>('POST', '/me/oauth/requests/oar_test2', { approve: false })
    expect(denied.body.redirectUrl).toContain('error=access_denied')
    expect((await call('GET', '/me/oauth/requests/oar_expired_1')).status).toBe(404)
    expect((await call('POST', '/me/oauth/requests/oar_test3', { approve: true, actAs: 'alice' })).status).toBe(400)
    const loopback = await call<OAuthConsent>('GET', '/me/oauth/requests/oar_loopback_1')
    expect(loopback.body.loopbackRedirect).toBe(true)
  })
})
