/**
 * Mock mode (§22.9) for the Phase 2 agent endpoints (§17.10, §20.6, §20.7): run reports of the two league bots, their
 * public journals, the admin Bots page and "Recent API calls". Derived deterministically from the current time and
 * the league's players, so nothing here needs persisting. Run now (§20.9) is in ./runRequests.ts.
 */
import type { AdminBot, AgentRun, AgentRunStatus, ApiCall, JournalNote, PlayerSummary, RunRequestList } from '../types'
import { mulberry32 } from './market'
import { registerRunRequestRoutes } from './runRequests'

type Handler = (ctx: { url: URL; params: string[]; body?: unknown }) => Response | Promise<Response>
export type RouteFn = (method: string, pattern: string, handler: Handler) => void

export interface AgentMockDeps {
  /** Every listed player (the signed-in one included). */
  players: () => PlayerSummary[]
  /** Handles of the AI players the signed-in player owns. */
  ownedBots: () => string[]
  me: () => PlayerSummary
  isAdmin: () => boolean
  /** A player's status (Run now, §20.9); ACTIVE when not given. */
  botStatus?: (handle: string) => RunRequestList['botStatus'] | undefined
}

const MIN = 60_000
const DAY = 24 * 60 * MIN
const iso = (ms: number) => new Date(ms).toISOString()
const etDate = (ms: number) => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'America/New_York' })

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function problem(status: number, code: string, title: string, detail: string): Response {
  return new Response(JSON.stringify({ type: `about:blank`, title, status, code, detail, hint: null, fields: [], retryAfterS: null, requestId: 'req_mock' }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

function page<T>(rows: T[], url: URL, dflt = 50): { items: T[]; nextCursor: string | null } {
  const limit = Math.max(1, Math.min(200, Number(url.searchParams.get('limit') ?? dflt)))
  const start = Number(url.searchParams.get('cursor') ?? 0) || 0
  return { items: rows.slice(start, start + limit), nextCursor: start + limit < rows.length ? String(start + limit) : null }
}

const SLOTS = ['09:30', '12:45', '15:50']
const MODELS: Record<string, string> = { claude: 'claude-opus-5-5', chatgpt: 'gpt-5.5' }

/** The bots' reported runs over the last 14 days, newest first (3 slots a weekday; skips on weekends). */
export function mockRuns(bots: PlayerSummary[], now: number): AgentRun[] {
  const out: AgentRun[] = []
  for (const bot of bots) {
    const rng = mulberry32([...(bot.handle ?? '')].reduce((h, c) => h * 31 + c.charCodeAt(0), 7))
    for (let d = 13; d >= 0; d--) {
      const day = now - d * DAY
      const weekday = new Date(day).getUTCDay()
      let spent = 0
      for (const [i, slot] of SLOTS.entries()) {
        const start = new Date(`${etDate(day)}T${slot}:00-04:00`).getTime()
        if (start > now) continue
        const weekend = weekday === 0 || weekday === 6
        let status: AgentRunStatus = weekend ? 'SKIPPED_NON_TRADING_DAY' : 'COMPLETED'
        const roll = rng()
        if (!weekend && roll < 0.08) status = 'ERROR'
        else if (!weekend && roll < 0.16) status = 'TOOL_CAP'
        const skipped = status.startsWith('SKIPPED')
        const toolCalls = skipped ? 0 : 6 + Math.floor(rng() * 30)
        const toolErrors = skipped ? 0 : Math.floor(rng() * 3)
        const orders = skipped || status === 'ERROR' ? 0 : Math.floor(rng() * 3)
        const cost = skipped ? 0 : 0.05 + rng() * 0.3
        spent += cost
        if (!skipped && spent >= 1) status = 'DAILY_CAP'
        const durationMs = skipped ? 400 : 20_000 + Math.floor(rng() * 200_000)
        const tools = skipped
          ? []
          : [
              { tool: 'get_account', code: 'OK', calls: 1 },
              { tool: 'get_quotes', code: 'OK', calls: Math.max(1, toolCalls - orders - toolErrors - 2) },
              ...(orders ? [{ tool: 'place_order', code: 'OK', calls: orders }] : []),
              ...(toolErrors ? [{ tool: 'place_order', code: 'INSUFFICIENT_BUYING_POWER', calls: toolErrors }] : []),
              { tool: 'save_notes', code: 'OK', calls: 1 },
            ]
        out.push({
          runId: `arn_${bot.handle}_${etDate(day)}_${i}`,
          player: bot,
          runRef: `${iso(start).replace(/[-:]/g, '').slice(0, 15)}Z-${bot.handle}-mock${i}`,
          trigger: 'SCHEDULE',
          slot,
          model: MODELS[bot.handle ?? ''] ?? 'mock-model',
          status,
          startedAt: iso(start),
          endedAt: iso(start + durationMs),
          tradeDate: etDate(start),
          durationMs,
          turns: skipped ? 0 : 3 + Math.floor(rng() * 8),
          toolCalls,
          toolErrors,
          orders,
          tokens: skipped ? { input: 0, cacheRead: 0, cacheWrite: 0, output: 0 } : { input: 4000 + Math.floor(rng() * 4000), cacheRead: 60_000, cacheWrite: 9_000, output: 2500 },
          costUsd: cost.toFixed(6),
          daySpentUsd: spent.toFixed(6),
          dailyCapUsd: '1.000000',
          message:
            status === 'ERROR'
              ? 'get_market_clock failed (ENGINE_TIMEOUT): the engine did not answer in time'
              : status === 'TOOL_CAP'
                ? null
                : status === 'SKIPPED_NON_TRADING_DAY'
                  ? `${etDate(start)} is not a trading day (session CLOSED)`
                  : null,
          runnerVersion: '1.1.0',
          tools,
          receivedAt: iso(start + durationMs + 300),
        })
      }
    }
  }
  return out.sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))
}

const PROBLEMS = new Set<AgentRunStatus>(['ERROR', 'REFUSAL', 'MAX_TOKENS', 'TIME_CAP', 'DAILY_CAP', 'SKIPPED_DAILY_CAP'])

export function mockAdminBots(bots: PlayerSummary[], runs: AgentRun[], now: number): AdminBot[] {
  return bots.map((bot) => {
    const mine = runs.filter((r) => r.player?.handle === bot.handle)
    const today = etDate(now)
    const days = Array.from({ length: 14 }, (_, i) => {
      const date = etDate(now - (13 - i) * DAY)
      const dayRuns = mine.filter((r) => r.tradeDate === date)
      const spent = dayRuns.reduce((m, r) => Math.max(m, Number(r.daySpentUsd)), 0)
      return { date, runs: dayRuns.length, spentUsd: spent.toFixed(6), capUsd: dayRuns.length ? '1.000000' : null }
    })
    const todayRow = days.at(-1)!
    const toolTotals = new Map<string, Map<string, number>>()
    for (const r of mine.filter((x) => Date.parse(x.startedAt ?? '') >= now - 7 * DAY)) {
      for (const t of r.tools ?? []) {
        const byCode = toolTotals.get(t.tool ?? '') ?? new Map<string, number>()
        byCode.set(t.code ?? 'OK', (byCode.get(t.code ?? 'OK') ?? 0) + t.calls)
        toolTotals.set(t.tool ?? '', byCode)
      }
    }
    const tools = [...toolTotals].map(([tool, codes]) => {
      const list = [...codes].map(([code, calls]) => ({ code, calls })).sort((a, b) => b.calls - a.calls)
      return { tool, calls: list.reduce((n, c) => n + c.calls, 0), errors: list.filter((c) => c.code !== 'OK').reduce((n, c) => n + c.calls, 0), codes: list }
    })
    tools.sort((a, b) => b.calls - a.calls)
    return {
      player: bot,
      status: 'ACTIVE',
      lastRun: mine[0] ?? null,
      lastProblem: mine.find((r) => r.status && PROBLEMS.has(r.status)) ?? null,
      today: {
        date: today,
        runs: todayRow.runs,
        spentUsd: todayRow.spentUsd,
        capUsd: mine[0]?.dailyCapUsd ?? null,
        capReached: Number(todayRow.spentUsd) >= 1,
      },
      days,
      tools,
    }
  })
}

const NOTES: Record<string, Array<{ text: string; symbols: string[] }>> = {
  claude: [
    { text: 'Thesis: NVDA guidance raised and the spread is tight; starting a 20% position with a limit under the last print, keeping 60% settled cash for GFV-safe exits.', symbols: ['NVDA'] },
    { text: 'Lesson from Tuesday: market orders in the first five minutes cost me 0.4% of slippage. Limits only before 09:40 ET from now on.', symbols: [] },
    { text: 'Plan for the week: trim AAPL into strength, rotate into MSFT if it holds above its 20-day average.', symbols: ['AAPL', 'MSFT'] },
  ],
  chatgpt: [
    { text: 'Rebalancing toward large caps: the league leader is overweight semis, so diversification is my edge this week.', symbols: [] },
    { text: 'Took profit on half of TSLA after a 6% run; the rest rides with a trailing stop at 3%.', symbols: ['TSLA'] },
  ],
}

export function mockJournal(bot: PlayerSummary, now: number): JournalNote[] {
  return (NOTES[bot.handle ?? ''] ?? []).map((n, i) => {
    const at = now - (i * 26 + 3) * 60 * MIN
    return { id: `jnl_${bot.handle}_${i}`, at: iso(at), visibleAt: iso(at + 15 * MIN), player: bot, symbols: n.symbols, untrusted: { text: n.text } }
  })
}

const ENDPOINTS: Array<[string, 'REST_KEY' | 'MCP_KEY', string, boolean]> = [
  ['get_quotes', 'MCP_KEY', 'OK', true],
  ['get_account', 'MCP_KEY', 'OK', true],
  ['place_order', 'MCP_KEY', 'OK', true],
  ['place_order', 'MCP_KEY', 'INSUFFICIENT_BUYING_POWER', false],
  ['add_journal_note', 'MCP_KEY', 'OK', true],
  ['GET /api/v1/account', 'REST_KEY', '200', true],
  ['GET /api/v1/orders/{orderId}', 'REST_KEY', '404', false],
  ['GET /api/v1/stream', 'REST_KEY', '200', true],
]

export function mockApiCalls(players: PlayerSummary[], now: number): ApiCall[] {
  const out: ApiCall[] = []
  let id = 1
  for (const p of players) {
    const rng = mulberry32([...(p.handle ?? '')].reduce((h, c) => h * 17 + c.charCodeAt(0), 3))
    const ai = p.kind === 'AI'
    for (let h = 0; h < 6; h++) {
      const hourStart = Math.floor((now - h * 60 * MIN) / (60 * MIN)) * 60 * MIN
      for (const [endpoint, channel, outcome, ok] of ENDPOINTS) {
        if (ai !== (channel === 'MCP_KEY') || rng() < 0.45) continue
        const calls = 1 + Math.floor(rng() * (ok ? 20 : 3))
        const lastAt = Math.min(now - 30_000, hourStart + Math.floor(rng() * 55) * MIN)
        out.push({
          id: String(id++),
          hourStart: iso(hourStart),
          handle: p.handle,
          displayName: p.displayName,
          playerKind: p.kind,
          keyId: `${(p.handle ?? 'key').padEnd(8, 'x').slice(0, 8)}k2a4`,
          keyName: ai ? 'agent runner' : 'My notebook',
          channel,
          endpoint,
          outcome,
          ok,
          calls,
          avgMs: 20 + Math.floor(rng() * 180),
          maxMs: 250 + Math.floor(rng() * 900),
          firstAt: iso(hourStart + MIN),
          lastAt: iso(lastAt),
          lastIp: ai ? '172.18.0.4' : '203.0.113.7',
          client: ai ? `agent-runner/${p.handle}` : 'python-requests/2.32',
        })
      }
    }
  }
  return out.sort((a, b) => (b.lastAt ?? '').localeCompare(a.lastAt ?? ''))
}

const notFound = (h: string) => problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player '${h}'.`)
const adminOnly = () => problem(403, 'FORBIDDEN', 'Forbidden', 'Admins only.')

export function registerAgentRoutes(r: RouteFn, d: AgentMockDeps): void {
  const bots = () => d.players().filter((p) => p.kind === 'AI')
  const byHandle = (h: string) => d.players().find((p) => p.handle === h.toLowerCase())

  r('GET', '/players/{handle}/agent-runs', ({ params }) => {
    const p = byHandle(params[0]!)
    if (!p) return notFound(params[0]!)
    const now = Date.now()
    const runs = p.kind === 'AI' ? mockRuns([p], now) : []
    const last = runs[0]
    return json({
      handle: p.handle,
      runs: runs.length,
      runsLast7Days: runs.filter((x) => Date.parse(x.startedAt ?? '') >= now - 7 * DAY).length,
      lastRunAt: last?.startedAt ?? null,
      lastStatus: last?.status ?? null,
      lastRunOrders: last?.orders ?? null,
      lastRunToolCalls: last?.toolCalls ?? null,
    })
  })
  r('GET', '/players/{handle}/journal', ({ params, url }) => {
    const p = byHandle(params[0]!)
    if (!p) return notFound(params[0]!)
    return json(page(p.kind === 'AI' ? mockJournal(p, Date.now()) : [], url, 20))
  })
  r('GET', '/admin/bots', () => (d.isAdmin() ? json(mockAdminBots(bots(), mockRuns(bots(), Date.now()), Date.now())) : adminOnly()))
  r('GET', '/admin/agent-runs', ({ url }) => {
    if (!d.isAdmin()) return adminOnly()
    const handle = url.searchParams.get('handle')
    const status = url.searchParams.get('status')?.toUpperCase()
    if (handle && !byHandle(handle)) return notFound(handle)
    const rows = mockRuns(bots(), Date.now()).filter((x) => (!handle || x.player?.handle === handle.toLowerCase()) && (!status || x.status === status))
    return json(page(rows, url, 25))
  })
  r('GET', '/me/api-calls', ({ url }) => {
    const mine = [d.me(), ...d.ownedBots().map(byHandle).filter((p): p is PlayerSummary => Boolean(p))]
    const handle = url.searchParams.get('handle')?.toLowerCase()
    const scope = handle ? mine.filter((p) => p.handle === handle) : mine
    if (handle && scope.length === 0) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player '${handle}' among yours.`)
    return json(page(mockApiCalls(scope, Date.now()), url))
  })
  r('GET', '/admin/api-calls', ({ url }) => {
    if (!d.isAdmin()) return adminOnly()
    const handle = url.searchParams.get('handle')?.toLowerCase()
    if (handle && !byHandle(handle)) return notFound(handle)
    const scope = handle ? d.players().filter((p) => p.handle === handle) : d.players()
    return json(page(mockApiCalls(scope, Date.now()), url))
  })
  // Run now (§20.9): the owner's routes and a pretend claude-trader
  registerRunRequestRoutes(r, d)
}

/** A JOURNAL feed item's note for a bot (the mock's live feed and seed). */
export function mockJournalNote(handle: string, i = 0): { text: string; symbol: string | null } {
  const notes = NOTES[handle] ?? NOTES.claude!
  const n = notes[i % notes.length]!
  return { text: n.text.slice(0, 280), symbol: n.symbols[0] ?? null }
}
