import type { OAuthActor, OAuthClientInfo } from '@/lib/api/types'

/**
 * The act-as consent's rules (§17.9.3), pure so they are unit-tested: which scopes a connector may get for the chosen
 * player, what is preselected, and the body of the decision. The server re-checks all of it.
 */

/** The four API scopes (§7.1), in the order the consent screen lists them. There is no admin scope (§17.4). */
export const SCOPE_ORDER = ['market:read', 'account:read', 'trade', 'league:read'] as const

export const SCOPE_INFO: Record<string, { label: string; detail: string }> = {
  'market:read': { label: 'Market data', detail: 'Quotes, charts, news, the market clock and the rules' },
  'account:read': { label: 'Read the account', detail: 'Cash, positions, orders, activity and notifications' },
  trade: { label: 'Trade', detail: 'Place, change and cancel orders, close positions, edit watchlists' },
  'league:read': { label: 'League', detail: 'Leaderboard, player profiles and the league feed' },
}

const rank = (s: string) => {
  const i = (SCOPE_ORDER as readonly string[]).indexOf(s)
  return i < 0 ? SCOPE_ORDER.length : i
}

/** Scopes in display order, unknown ones dropped. */
export function orderedScopes(scopes: readonly (string | null | undefined)[] | null | undefined): string[] {
  return [...new Set((scopes ?? []).filter((s): s is string => typeof s === 'string' && s in SCOPE_INFO))].sort((a, b) => rank(a) - rank(b))
}

/** What the connector may get acting as `actor`: requested ∩ the actor's allowed scopes. */
export function grantableScopes(requested: readonly string[], actor: OAuthActor | null | undefined): string[] {
  const allowed = new Set(actor?.allowedScopes ?? [])
  return orderedScopes(requested).filter((s) => allowed.has(s))
}

/** Why a requested scope cannot be granted to `actor` (null when it can). */
export function blockedReason(scope: string, actor: OAuthActor | null | undefined): string | null {
  if (!actor || (actor.allowedScopes ?? []).includes(scope)) return null
  if (scope === 'trade' && actor.self) return 'Trading through apps needs the admin to allow API trading for you.'
  return 'Not available for this player.'
}

/** Which league bot family an app belongs to, from its client host, redirect host or name (null when unknown). */
export type ClientFamily = 'claude' | 'chatgpt'

const CLAUDE_HOSTS = ['claude.ai', 'claude.com', 'anthropic.com']
const CHATGPT_HOSTS = ['chatgpt.com', 'openai.com']
const hostIn = (host: string | null | undefined, list: readonly string[]) => {
  const h = (host ?? '').toLowerCase()
  return h.length > 0 && list.some((d) => h === d || h.endsWith(`.${d}`))
}

export function clientFamily(client: OAuthClientInfo | null | undefined, redirectHost?: string | null): ClientFamily | null {
  const name = (client?.name ?? '').trim().toLowerCase()
  if (hostIn(client?.host, CLAUDE_HOSTS) || hostIn(redirectHost, CLAUDE_HOSTS) || name.startsWith('claude')) return 'claude'
  if (hostIn(client?.host, CHATGPT_HOSTS) || hostIn(redirectHost, CHATGPT_HOSTS) || name.startsWith('chatgpt') || name.startsWith('openai')) return 'chatgpt'
  return null
}

/** True when a bot's model label (else its handle or name) belongs to `family`. */
function botOf(actor: OAuthActor, family: ClientFamily): boolean {
  const p = actor.player
  const label = (p?.aiModel ?? '').toLowerCase()
  const fallback = `${p?.handle ?? ''} ${p?.displayName ?? ''}`.toLowerCase()
  const test = (t: string) => (family === 'claude' ? t.includes('claude') : t.includes('gpt') || t.includes('openai'))
  return label ? test(label) : test(fallback)
}

/**
 * The preselected "Act as" choice (§17.9.3): the one bot of the asking app's family (Claude's connector or Claude
 * Code → the Claude bot, ChatGPT → the ChatGPT bot), so one press of Allow never binds an app to another vendor's
 * bot. The league's own bots, handles `claude` and `chatgpt`, win whatever their model label says (the admin may
 * label them "Opus" or "o3"). An unknown app, or no single matching bot, preselects nothing: the human picks, and
 * Allow waits for it.
 */
export function defaultActor(
  actors: readonly OAuthActor[] | null | undefined,
  client?: OAuthClientInfo | null,
  redirectHost?: string | null,
): string | null {
  const family = clientFamily(client, redirectHost)
  if (!family) return null
  const bots = (actors ?? []).filter((a) => !a.self)
  const named = bots.find((a) => (a.player?.handle ?? '').toLowerCase() === family)
  if (named) return named.player?.handle ?? null
  const matches = bots.filter((a) => botOf(a, family))
  return matches.length === 1 ? (matches[0]!.player?.handle ?? null) : null
}

/**
 * The selection after choosing another actor: keep what is still grantable; when nothing is left (or nothing was
 * chosen yet), select everything the new actor may get.
 */
export function reconcileSelection(prev: ReadonlySet<string> | null, requested: readonly string[], actor: OAuthActor | null | undefined): Set<string> {
  const grantable = grantableScopes(requested, actor)
  const kept = prev ? grantable.filter((s) => prev.has(s)) : []
  return new Set(kept.length ? kept : grantable)
}

/** `POST /me/oauth/requests/{id}` body for an approval. */
export function approval(actAs: string, selected: ReadonlySet<string>): { approve: true; actAs: string; scopes: string[] } {
  return { approve: true, actAs, scopes: orderedScopes([...selected]) }
}

/** "Claude" + "claude.ai", or "registered dynamically" for a DCR client (§17.9.3). */
export function clientOrigin(client: OAuthClientInfo | null | undefined): string {
  if (!client) return ''
  return client.kind === 'CIMD' && client.host ? client.host : 'registered dynamically'
}

/** A friendly name for a client (its own `client_name`, which is untrusted text, clipped). */
export function clientName(client: OAuthClientInfo | null | undefined): string {
  const name = (client?.name ?? '').trim()
  return (name || client?.host || 'An app').slice(0, 60)
}

/**
 * The redirect the server answered, accepted only as an absolute http(s) URL (the server builds it from the
 * client's registered redirect URI; this guards against navigating to anything else, e.g. `javascript:`).
 */
export function safeRedirect(url: string | null | undefined): string | null {
  if (!url) return null
  try {
    const u = new URL(url)
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : null
  } catch {
    return null
  }
}
