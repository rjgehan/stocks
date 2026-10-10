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

/** The preselected "Act as" choice: the first of your AI bots (the usual reason to connect), else yourself. */
export function defaultActor(actors: readonly OAuthActor[] | null | undefined): string | null {
  const list = actors ?? []
  return (list.find((a) => !a.self) ?? list.find((a) => a.self))?.player?.handle ?? null
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
