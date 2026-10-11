/**
 * Mock-mode OAuth consent and Connected apps (§17.9, §22.9). Any `oar_…` request id is a pending request from the
 * claude.ai connector, except ids containing `loopback` (Claude Code on this computer), `dcr` (a dynamically
 * registered client) or starting with `oar_expired` (404). State lives in its own sessionStorage key so the shared
 * mock state needs no new field.
 */
import type { ConnectedApp, OAuthActor, OAuthConsent, PlayerSummary } from '../types'

export interface MockOAuthPlayers {
  me(): PlayerSummary
  /** the signed-in human's ACTIVE AI bots */
  myBots(): PlayerSummary[]
  apiTradingAllowed(): boolean
}

interface Saved {
  apps: ConnectedApp[]
  used: string[]
}

const KEY = 'stocks.mock.oauth.v1'
const ALL = ['market:read', 'account:read', 'trade', 'league:read']
const READ_ONLY = ['market:read', 'account:read', 'league:read']

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const problem = (status: number, code: string, title: string, detail: string): Response =>
  new Response(JSON.stringify({ type: 'about:blank', title, status, code, detail, hint: null, fields: [], retryAfterS: null, requestId: null }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })

function client(requestId: string): { consent: Pick<OAuthConsent, 'client' | 'redirectUri' | 'redirectHost' | 'loopbackRedirect'> } {
  if (requestId.includes('loopback')) {
    return {
      consent: {
        client: { clientId: 'https://claude.ai/oauth/claude-code-client-metadata', name: 'Claude Code', kind: 'CIMD', host: 'claude.ai' },
        redirectUri: 'http://localhost:53682/callback',
        redirectHost: 'localhost',
        loopbackRedirect: true,
      },
    }
  }
  if (requestId.includes('dcr')) {
    return {
      consent: {
        client: { clientId: 'ocl_01J9Y4N2MOCKDCRCLIENT0000AA', name: 'MCP Inspector', kind: 'DCR', host: null },
        redirectUri: 'http://127.0.0.1:6274/oauth/callback',
        redirectHost: '127.0.0.1',
        loopbackRedirect: true,
      },
    }
  }
  return {
    consent: {
      client: { clientId: 'https://claude.ai/oauth/mcp-oauth-client-metadata', name: 'Claude', kind: 'CIMD', host: 'claude.ai' },
      redirectUri: 'https://claude.ai/api/mcp/auth_callback',
      redirectHost: 'claude.ai',
      loopbackRedirect: false,
    },
  }
}

export class MockOAuth {
  private saved: Saved

  constructor(private readonly players: MockOAuthPlayers) {
    this.saved = MockOAuth.load()
  }

  private static load(): Saved {
    try {
      const raw = sessionStorage.getItem(KEY)
      if (raw) return JSON.parse(raw) as Saved
    } catch {
      /* no storage */
    }
    return { apps: [], used: [] }
  }

  private save(): void {
    try {
      sessionStorage.setItem(KEY, JSON.stringify(this.saved))
    } catch {
      /* keep it in memory */
    }
  }

  private actors(): OAuthActor[] {
    const me = this.players.me()
    return [
      ...this.players.myBots().map((p) => ({ player: p, self: false, allowedScopes: ALL })),
      { player: me, self: true, allowedScopes: this.players.apiTradingAllowed() ? ALL : READ_ONLY },
    ]
  }

  private live(requestId: string): boolean {
    return requestId.startsWith('oar_') && !requestId.startsWith('oar_expired') && !this.saved.used.includes(requestId)
  }

  consent(requestId: string): Response {
    if (!this.live(requestId)) return problem(404, 'NOT_FOUND', 'Not found', 'This authorization request expired or was already used.')
    const body: OAuthConsent = {
      requestId,
      ...client(requestId).consent,
      requestedScopes: ['account:read', 'league:read', 'market:read', 'trade'],
      actors: this.actors(),
      expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    }
    return json(body)
  }

  decide(requestId: string, body: unknown): Response {
    if (!this.live(requestId)) return problem(404, 'NOT_FOUND', 'Not found', 'This authorization request expired or was already used.')
    const b = (body ?? {}) as { approve?: boolean | null; actAs?: string | null; scopes?: string[] | null }
    const c = client(requestId).consent
    const iss = encodeURIComponent(window.location.origin)
    if (!b.approve) {
      this.saved.used.push(requestId)
      this.save()
      return json({ redirectUrl: `${c.redirectUri}?error=access_denied&error_description=The+user+denied+the+request.&state=mock-state&iss=${iss}` })
    }
    const actor = this.actors().find((a) => a.player?.handle === b.actAs)
    if (!actor) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'actAs: Choose yourself or one of your active AI bots')
    const allowed = actor.allowedScopes ?? []
    const scopes = b.scopes ?? allowed
    if (!scopes.length || scopes.some((s) => !allowed.includes(s))) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'scopes: not allowed for this player')
    this.saved.used.push(requestId)
    const grantId = `ogr_01J9Y4N2MOCK${String(this.saved.apps.length + this.saved.used.length).padStart(14, '0')}`
    this.saved.apps.unshift({
      grantId,
      client: c.client,
      redirectHost: c.redirectHost,
      actAs: actor.player,
      scopes: [...scopes].sort(),
      createdAt: new Date().toISOString(),
      lastUsedAt: null,
    })
    this.save()
    return json({ redirectUrl: `${c.redirectUri}?code=mock_${grantId.slice(-8)}&state=mock-state&iss=${iss}` })
  }

  list(): Response {
    return json(this.saved.apps)
  }

  disconnect(grantId: string): Response {
    const before = this.saved.apps.length
    this.saved.apps = this.saved.apps.filter((a) => a.grantId !== grantId)
    if (this.saved.apps.length === before) return problem(404, 'NOT_FOUND', 'Not found', 'No such connected app.')
    this.save()
    return new Response(null, { status: 204 })
  }
}
