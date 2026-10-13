import { beforeEach, describe, expect, it } from 'vitest'
import type { ConnectedApp, OAuthActor, OAuthConsent } from '@/lib/api/types'
import { MockServer } from '@/lib/api/mock/server'
import { approval, blockedReason, clientFamily, clientName, clientOrigin, defaultActor, grantableScopes, orderedScopes, reconcileSelection, safeRedirect } from './scopes'

const player = (handle: string, kind: 'HUMAN' | 'AI', aiModel: string | null = kind === 'AI' ? 'Claude' : null) => ({
  playerId: `ply_${handle}`,
  handle,
  displayName: handle,
  kind,
  aiModel,
  avatarSeed: handle,
  memberSince: '2026-10-01T13:00:00Z',
  resets: 0,
})
const bot: OAuthActor = { player: player('claude', 'AI'), self: false, allowedScopes: ['market:read', 'account:read', 'trade', 'league:read'] }
const me: OAuthActor = { player: player('demo', 'HUMAN'), self: true, allowedScopes: ['market:read', 'account:read', 'league:read'] }
const gpt: OAuthActor = { player: player('chatgpt', 'AI', 'ChatGPT'), self: false, allowedScopes: bot.allowedScopes }
const CLAUDE = { clientId: 'https://claude.ai/oauth/mcp-oauth-client-metadata', name: 'Claude', kind: 'CIMD' as const, host: 'claude.ai' }
const CLAUDE_CODE = { clientId: 'https://claude.ai/oauth/claude-code-client-metadata', name: 'Claude Code', kind: 'CIMD' as const, host: 'claude.ai' }
const CHATGPT = { clientId: 'https://chatgpt.com/oauth/client.json', name: 'ChatGPT', kind: 'CIMD' as const, host: 'chatgpt.com' }
const INSPECTOR = { clientId: 'ocl_1', name: 'MCP Inspector', kind: 'DCR' as const, host: null }
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

  it('preselects the bot of the asking app only (review finding: Claude used to get @chatgpt, the first bot)', () => {
    // the league's real order: the server lists bots by handle, so @chatgpt comes first
    const league = [gpt, bot, me]
    expect(defaultActor(league, CLAUDE, 'claude.ai')).toBe('claude')
    expect(defaultActor(league, CLAUDE_CODE, 'localhost')).toBe('claude')
    expect(defaultActor(league, CHATGPT, 'chatgpt.com')).toBe('chatgpt')
    // a dynamically registered client is known by its redirect host or name
    expect(defaultActor(league, { ...INSPECTOR, name: 'Claude' }, 'claude.ai')).toBe('claude')
    expect(defaultActor(league, { ...INSPECTOR, name: 'x' }, 'chatgpt.com')).toBe('chatgpt')
    // an unknown app, or no single matching bot: nothing preselected
    expect(defaultActor(league, INSPECTOR, '127.0.0.1')).toBeNull()
    expect(defaultActor([me], CLAUDE, 'claude.ai')).toBeNull()
    expect(defaultActor([gpt, me], CLAUDE, 'claude.ai')).toBeNull()
    expect(defaultActor([{ ...bot, player: player('claude1', 'AI') }, { ...bot, player: player('claude2', 'AI') }, me], CLAUDE, 'claude.ai')).toBeNull()
    // the league's bots named claude and chatgpt win, whatever their model label says and however many others match
    expect(defaultActor([bot, { ...bot, player: player('claude2', 'AI') }, me], CLAUDE, 'claude.ai')).toBe('claude')
    const relabeled = [{ ...gpt, player: player('chatgpt', 'AI', 'o3') }, { ...bot, player: player('claude', 'AI', 'Opus 4.5') }, me]
    expect(defaultActor(relabeled, CLAUDE, 'claude.ai')).toBe('claude')
    expect(defaultActor(relabeled, CHATGPT, 'chatgpt.com')).toBe('chatgpt')
    expect(defaultActor([], CLAUDE)).toBeNull()
    // a bot without a model label is matched by its handle; GPT labels count as ChatGPT
    expect(defaultActor([{ ...gpt, player: player('chatgpt', 'AI', null) }, me], CHATGPT)).toBe('chatgpt')
    expect(defaultActor([{ ...gpt, player: player('openbot', 'AI', 'GPT-5') }, me], CHATGPT)).toBe('openbot')
    expect(clientFamily(null, 'evil.claude.ai.example')).toBeNull()
  })

  it('preselects the right league bot for each connector as the server describes it (CIMD and DCR)', () => {
    const league = [gpt, bot, me]
    // claude.ai: its client ID metadata document, or "Register automatically" (DCR, client_name claudeai)
    expect(defaultActor(league, CLAUDE, 'claude.ai')).toBe('claude')
    expect(defaultActor(league, { clientId: 'ocl_01', name: 'claudeai', kind: 'DCR', host: null }, 'claude.ai')).toBe('claude')
    // ChatGPT: its stable or callback-ID document, or DCR with either callback
    expect(defaultActor(league, CHATGPT, 'chatgpt.com')).toBe('chatgpt')
    expect(defaultActor(league, { ...CHATGPT, clientId: 'https://chatgpt.com/oauth/cbk_1/client.json' }, 'chatgpt.com')).toBe('chatgpt')
    expect(defaultActor(league, { clientId: 'ocl_02', name: 'ChatGPT', kind: 'DCR', host: null }, 'chatgpt.com')).toBe('chatgpt')
    expect(defaultActor(league, { clientId: 'ocl_03', name: 'MCP client', kind: 'DCR', host: null }, 'chatgpt.com')).toBe('chatgpt')
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
