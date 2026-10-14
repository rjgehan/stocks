/**
 * Mock mode (§22.9) for "Run now" and "Run all" (§20.9): the owner's routes, with a pretend claude-trader that picks a
 * request up a few seconds after it is made and reports DONE a few seconds later, so the button's states can be seen.
 * It serves every AI bot but @chatgpt (the agent runner's bot), which shows the button disabled and which Run all
 * skips. Requests live in memory (a reload forgets them).
 */
import type { RunAllItem, RunAllResult, RunRequest, RunRequestList } from '../types'
import type { AgentMockDeps, RouteFn } from './agents'

/** The pretend trader: claimed this long after the request, done this long after it. */
export const MOCK_CLAIM_AFTER_MS = 3_000
export const MOCK_DONE_AFTER_MS = 9_000
/** Bots the pretend trader does not serve. */
export const MOCK_UNSERVED = new Set(['chatgpt'])

interface Stored {
  id: string
  handle: string
  requestedBy: string
  requestedAt: number
  dryRun: boolean
}

const iso = (ms: number) => new Date(ms).toISOString()
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function problem(status: number, code: string, title: string, detail: string): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title, status, code, detail, hint: null, fields: [], retryAfterS: null, requestId: 'req_mock' }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

const onItsWay = (h: string, open: RunRequest) => `@${h} already has a run request on its way (requested by @${open.requestedBy}); wait until it reports back.`
const dryRunOf = (body: unknown) => (body as { dryRun?: boolean | null } | null)?.dryRun === true

/** A stored request as the trader would have moved it by `now`. */
export function mockRunRequestAt(s: Stored, now: number): RunRequest {
  const claimed = now - s.requestedAt >= MOCK_CLAIM_AFTER_MS
  const done = now - s.requestedAt >= MOCK_DONE_AFTER_MS
  const took = (MOCK_DONE_AFTER_MS - MOCK_CLAIM_AFTER_MS) / 1000
  return {
    id: s.id,
    handle: s.handle,
    requestedBy: s.requestedBy,
    requestedAt: iso(s.requestedAt),
    dryRun: s.dryRun,
    status: done ? 'DONE' : claimed ? 'CLAIMED' : 'PENDING',
    claimedAt: claimed ? iso(s.requestedAt + MOCK_CLAIM_AFTER_MS) : null,
    finishedAt: done ? iso(s.requestedAt + MOCK_DONE_AFTER_MS) : null,
    message: done
      ? s.dryRun
        ? `Check-in done in ${took} s, 6 turns. Dry run: would buy $40 of NVDA on the guidance raise (mock).`
        : `Check-in done in ${took} s, 6 turns. Held NVDA and MSFT; no orders this time (mock).`
      : null,
  }
}

export function registerRunRequestRoutes(r: RouteFn, d: Pick<AgentMockDeps, 'players' | 'ownedBots' | 'me' | 'isAdmin' | 'botStatus'>): void {
  const store: Stored[] = []
  let seq = 0
  /** The AI player `h` when the caller owns it or is the admin, else the problem to answer. */
  const bot = (h: string): string | Response => {
    const p = d.players().find((x) => x.handle === h.toLowerCase())
    if (!p?.handle) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player '${h}'.`)
    if (p.kind !== 'AI') return problem(403, 'FORBIDDEN', 'Forbidden', `@${p.handle} is not an AI player; only bots can be run.`)
    if (!d.isAdmin() && !d.ownedBots().includes(p.handle)) return problem(403, 'FORBIDDEN', 'Forbidden', `Only @${p.handle}'s owner or the admin can run it.`)
    return p.handle
  }
  const latest = (handle: string, now: number) =>
    store
      .filter((s) => s.handle === handle)
      .sort((a, b) => b.requestedAt - a.requestedAt)
      .map((s) => mockRunRequestAt(s, now))

  r('GET', '/me/bots/{handle}/run-requests', ({ params, url }) => {
    const h = bot(params[0]!)
    if (h instanceof Response) return h
    const limit = Math.max(1, Math.min(50, Number(url.searchParams.get('limit') ?? 10) || 10))
    const now = Date.now()
    const list: RunRequestList = {
      botStatus: d.botStatus?.(h) ?? 'ACTIVE',
      // the pretend trader asked at the start of this minute
      traderSeenAt: MOCK_UNSERVED.has(h) ? null : iso(now - (now % 60_000)),
      asOf: iso(now),
      requests: latest(h, now).slice(0, limit),
    }
    return json(list)
  })
  const openOf = (handle: string, now: number) => latest(handle, now).find((x) => x.status === 'PENDING' || x.status === 'CLAIMED')
  const create = (handle: string, dryRun: boolean, now: number): RunRequest => {
    const s: Stored = { id: `arq_mock${String(++seq).padStart(4, '0')}`, handle, requestedBy: d.me().handle ?? 'demo', requestedAt: now, dryRun }
    store.push(s)
    return mockRunRequestAt(s, now)
  }

  r('POST', '/me/bots/{handle}/run-requests', ({ params, body }) => {
    const h = bot(params[0]!)
    if (h instanceof Response) return h
    const now = Date.now()
    const open = openOf(h, now)
    if (open) return problem(409, 'RUN_REQUEST_CONFLICT', 'Conflict', onItsWay(h, open))
    return json(create(h, dryRunOf(body), now), 201)
  })
  // Run all: every bot the caller may run, by handle, by the per-bot rules plus "a trader serves it"
  r('POST', '/me/bots/run-requests', ({ body }) => {
    const now = Date.now()
    const dryRun = dryRunOf(body)
    const owned = new Set(d.ownedBots())
    const handles = d
      .players()
      .filter((p) => p.kind === 'AI' && p.handle && (d.isAdmin() || owned.has(p.handle)))
      .map((p) => p.handle!)
      .sort()
    const results = handles.map((h): RunAllItem => {
      const status = d.botStatus?.(h) ?? 'ACTIVE'
      if (status !== 'ACTIVE') return { handle: h, outcome: 'SKIPPED', reason: 'NOT_ACTIVE', message: `@${h} is ${status}: it cannot trade, so it cannot be run.`, request: null }
      const open = openOf(h, now)
      if (open) return { handle: h, outcome: 'ALREADY_OPEN', reason: null, message: onItsWay(h, open), request: open }
      if (MOCK_UNSERVED.has(h)) {
        return { handle: h, outcome: 'SKIPPED', reason: 'NO_TRADER', message: `No claude-trader service has asked for @${h}'s runs in the last 5 minutes, so nothing would pick a run up.`, request: null }
      }
      return { handle: h, outcome: 'REQUESTED', reason: null, message: null, request: create(h, dryRun, now) }
    })
    const result: RunAllResult = { dryRun, asOf: iso(now), requested: results.filter((i) => i.outcome === 'REQUESTED').length, results }
    return json(result)
  })
}
