/**
 * Mock mode (§22.9) for the Phase 2 league slice (§16.8–16.11): the race chart, player stats, P&L analysis, the P&L
 * calendar and feed reactions, built from the mock league's board and the demo account. Deterministic per player and
 * period (seeded walks), so screens and e2e specs are stable within a session. Numbers here are display fakes; the
 * real arithmetic lives in the backend.
 */
import type {
  BenchmarkCurve,
  EarningsPoint,
  Execution,
  FeedReaction,
  Leaderboard,
  LeaderboardPeriod,
  LeaderboardRow,
  PlayerStats,
  PnlAnalysis,
  PnlCalendar,
  PnlCalendarCell,
  RaceChart,
  RacePoint,
  RankPoint,
  Reaction,
  Reactor,
  SymbolPnl,
} from '../types'
import { type Dec, add, compare, div, isDecimal, mul, setScale, sub } from '../../format/decimal'
import { hashString, mulberry32 } from './market'
import type { Lot } from './state'

const MIN = 60_000
const DAY = 24 * 60 * MIN
const iso = (ms: number): string => new Date(ms).toISOString()
const money = (d: Dec): Dec => setScale(d, 2, 'HALF_EVEN')
const ret = (n: number): Dec => n.toFixed(10)
const etDate = (ms: number): string => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'America/New_York' })
const isWeekday = (date: string): boolean => {
  const d = new Date(`${date}T12:00:00Z`).getUTCDay()
  return d !== 0 && d !== 6
}

/** Trading days (Mon–Fri ET) strictly before today, newest last, at most `n`. */
function pastTradingDays(nowMs: number, n: number): string[] {
  const out: string[] = []
  for (let k = 1; out.length < n && k < n * 2 + 10; k++) {
    const d = etDate(nowMs - k * DAY)
    if (isWeekday(d) && !out.includes(d)) out.push(d)
  }
  return out.toReversed()
}

/** EOD cut-off (20:00 ET ≈ 00:00Z next day in October) of an ET date, as an instant. */
const cutoff = (date: string): number => Date.parse(`${date}T23:59:00Z`) + MIN

const PERIOD_DAYS: Record<string, number> = { TODAY: 0, WEEK: 3, MONTH: 7, YTD: 25, SEASON: 3, ALL: 25 }

/** A seeded path of `n` returns that ends exactly at `end` (fractions; display only). */
function path(seed: string, n: number, end: number, vol = 0.012): number[] {
  const rng = mulberry32(hashString(seed))
  const out = [0]
  for (let i = 1; i < n; i++) out.push(out[i - 1]! + (rng() - 0.5) * vol)
  const drift = n > 1 ? (end - out[n - 1]!) / (n - 1) : 0
  return out.map((v, i) => (i === 0 ? 0 : i === n - 1 ? end : v + drift * i))
}

/** Up to 30 EOD ranks of a player for a period, ending near the current rank. */
export function mockRanks(handle: string, period: string, current: number | null, players: number, nowMs: number): RankPoint[] {
  if (period === 'TODAY' || current == null) return []
  const days = pastTradingDays(nowMs, period === 'WEEK' ? 3 : 12)
  const rng = mulberry32(hashString(`${handle}:ranks:${period}`))
  let r = current
  const out: RankPoint[] = []
  for (let i = days.length - 1; i >= 0; i--) {
    out.unshift({ date: days[i]!, rank: r, ranked: players })
    if (rng() < 0.45) r = Math.max(1, Math.min(players, r + (rng() < 0.5 ? -1 : 1)))
  }
  return out
}

/** §16.9 race chart from the mock board: each row's line ends at its return; SPY ends at the board's benchmark. */
export function mockRace(board: Leaderboard, nowMs: number): RaceChart {
  const period = board.period ?? 'WEEK'
  const days = PERIOD_DAYS[period] ?? 3
  const times: number[] = period === 'TODAY'
    ? Array.from({ length: 13 }, (_, i) => nowMs - (12 - i) * 15 * MIN)
    : [...pastTradingDays(nowMs, days + 1).map(cutoff), nowMs]
  const players = (board.rows ?? []).filter((r) => r.rank != null).length
  const series = (board.rows ?? []).map((r: LeaderboardRow) => {
    const end = Number(r.return ?? '0')
    const values = path(`${r.player?.handle}:race:${period}`, times.length, end)
    const points: RacePoint[] = times.map((t, i) => ({ t: iso(t), return: ret(values[i]!) }))
    points[points.length - 1] = { t: iso(times[times.length - 1]!), return: r.return }
    return { player: r.player, rank: r.rank, return: r.return, rebased: r.rebased, partialPeriod: r.partialPeriod, points, ranks: mockRanks(r.player?.handle ?? '', period, r.rank, players, nowMs) }
  })
  const spyEnd = Number(board.benchmark?.return ?? '0')
  const spy = path(`spy:race:${period}`, times.length, spyEnd, 0.006)
  return {
    period,
    kind: board.kind,
    periodStart: iso(times[0]!),
    asOf: iso(nowMs),
    benchmark: { symbol: 'SPY', return: board.benchmark?.return ?? null, points: times.map((t, i) => ({ t: iso(t), return: ret(spy[i]!) })) },
    series,
  }
}

/** Mock Sharpe and profit factor for a board row (null below 20 days, as the backend does). */
export function mockRowStats(handle: string, period: string, fills: number): { sharpe: Dec | null; profitFactor: Dec | null } {
  const rng = mulberry32(hashString(`${handle}:stats:${period}`))
  const enough = (PERIOD_DAYS[period] ?? 0) >= 20
  return {
    sharpe: enough ? ((rng() - 0.3) * 3).toFixed(4) : null,
    profitFactor: fills >= 2 ? (0.4 + rng() * 2.6).toFixed(4) : null,
  }
}

/** §16.8 player stats from the board row (return, P&L, rank) plus seeded trade stats. */
export function mockPlayerStats(row: LeaderboardRow, period: LeaderboardPeriod, players: number, nowMs: number, self: boolean, trades: ReadonlyArray<Execution> = [], delayMs = 0): PlayerStats {
  const handle = row.player?.handle ?? '?'
  const rng = mulberry32(hashString(`${handle}:lots:${period}`))
  const days = PERIOD_DAYS[period] ?? 0
  const sells = trades.length ? trades.filter((t) => t.side === 'SELL').length : Math.max(0, Math.floor(row.fills / 2))
  const wins = Math.round(sells * (0.4 + rng() * 0.4))
  const grossProfit = money(String((wins * (5 + rng() * 20)).toFixed(2)))
  const grossLoss = money(String(((sells - wins) * (3 + rng() * 15)).toFixed(2)))
  const lot = (good: boolean) => {
    const closedAt = nowMs - (good ? 2 : 4) * DAY - Math.floor(rng() * 6) * 60 * MIN
    const cost = money(String((40 + rng() * 120).toFixed(2)))
    const pnl = money(String(((good ? 1 : -1) * (2 + rng() * 18)).toFixed(2)))
    return {
      symbol: row.topHolding ?? 'NVDA',
      qty: String(1 + Math.floor(rng() * 3)),
      openedAt: iso(closedAt - (3 + Math.floor(rng() * 40)) * 60 * MIN * 6),
      closedAt: iso(closedAt),
      holdSeconds: 0,
      cost,
      proceeds: add(cost, pnl),
      realizedPnl: pnl,
      return: div(pnl, cost, 10, 'HALF_EVEN'),
    }
  }
  const best = sells ? lot(true) : null
  const worst = sells > 1 ? lot(false) : best
  for (const l of [best, worst]) if (l) l.holdSeconds = Math.round((Date.parse(l.closedAt) - Date.parse(l.openedAt)) / 1000)
  const sharpe = days >= 20 ? (row.sharpe ?? ((rng() - 0.3) * 3).toFixed(4)) : null
  return {
    player: row.player,
    period,
    periodStart: iso(nowMs - Math.max(1, days) * DAY),
    asOf: iso(nowMs),
    return: row.return,
    pnl: row.pnl,
    maxDrawdown: row.maxDrawdown,
    partialPeriod: row.partialPeriod,
    rebased: row.rebased,
    sharpe,
    volatility: sharpe ? (0.12 + rng() * 0.3).toFixed(10) : null,
    dailyReturns: days,
    sharpeMinDays: 20,
    closingSells: sells,
    wins,
    winRate: sells ? (wins / sells).toFixed(10) : null,
    grossProfit,
    grossLoss,
    profitFactor: compare(grossLoss, '0') > 0 ? div(grossProfit, grossLoss, 4, 'HALF_EVEN') : null,
    closedLots: sells + Math.floor(rng() * 3),
    avgHoldSeconds: sells ? Math.round((6 + rng() * 60) * 3600) : null,
    // another player's best and worst trades come after the league's feed delay (none by default, public mode)
    tradesAsOf: iso(self ? nowMs : nowMs - delayMs),
    bestTrade: best,
    worstTrade: worst,
    rank: row.rank,
    rankChange: row.rankChange,
    rankHistory: mockRanks(handle, period, row.rank, players, nowMs),
  }
}

const RANGE_DAYS: Record<string, number> = { '1D': 0, '1W': 5, '1M': 21, '3M': 63, YTD: 190, '1Y': 252, ALL: 7 }

/**
 * §16.10 P&L analysis of an account (the demo account's, or another player's in public mode, seeded by `key`): the
 * earnings curve ends at the account's return since the range start (the mock's unit value against a seeded anchor),
 * SPY and QQQ walks, and P&L by symbol from the lots and fills.
 */
export function mockPnlAnalysis(
  range: string,
  input: { u: Dec; equity: Dec; nc: Dec; openedAtMs: number; lots: ReadonlyArray<Lot>; executions: ReadonlyArray<Pick<Execution, 'side' | 'symbol' | 'realizedPnl' | 'executedAt'>>; mark: (s: string) => Dec; key?: string },
  nowMs: number,
): PnlAnalysis {
  const key = input.key ?? 'demo'
  const days = RANGE_DAYS[range] ?? 21
  const fromMs = Math.max(input.openedAtMs, range === '1D' ? nowMs - 8 * 60 * MIN : nowMs - days * DAY * 1.4)
  const anchorU = range === 'ALL' || fromMs === input.openedAtMs ? 1 : Number(input.u) / (1 + (mulberry32(hashString(`${key}:${range}`))() - 0.45) * 0.03)
  const end = Number(input.u) / anchorU - 1
  const n = range === '1D' ? 20 : Math.max(2, Math.min(days || 6, Math.round((nowMs - fromMs) / DAY)) + 1)
  const times = Array.from({ length: n }, (_, i) => fromMs + ((nowMs - fromMs) * i) / (n - 1))
  const values = path(`${key}:earn:${range}`, n, end)
  const base = Number(input.equity) / (1 + end)
  const points: EarningsPoint[] = times.map((t, i) => ({ t: iso(t), return: ret(values[i]!), pnl: (base * values[i]!).toFixed(2) }))
  const bench = (symbol: string, drift: number): BenchmarkCurve => {
    const v = path(`${symbol}:earn:${range}`, n, drift * Math.max(1, days) / 21, 0.006)
    return { symbol, return: ret(v[n - 1]!), points: times.map((t, i) => ({ t: iso(t), return: ret(v[i]!) })) }
  }
  const bySymbol: SymbolPnl[] = []
  const realizedBy = new Map<string, Dec>()
  for (const e of input.executions) {
    if (e.side === 'SELL' && e.realizedPnl && isDecimal(e.realizedPnl) && Date.parse(e.executedAt ?? '') > fromMs) {
      realizedBy.set(e.symbol ?? '?', add(realizedBy.get(e.symbol ?? '?') ?? '0.00', e.realizedPnl))
    }
  }
  const symbols = new Set([...input.lots.map((l) => l.symbol), ...realizedBy.keys()])
  for (const s of symbols) {
    const lot = input.lots.find((l) => l.symbol === s)
    const unrealized = lot ? money(sub(mul(lot.qty, input.mark(s)), lot.costBasis)) : '0.00'
    const realized = money(realizedBy.get(s) ?? '0.00')
    bySymbol.push({ symbol: s, realized, unrealized, dividends: '0.00', total: money(add(realized, unrealized)), held: Boolean(lot) })
  }
  bySymbol.sort((a, b) => compare(b.total ?? '0', a.total ?? '0'))
  const pnl = bySymbol.reduce((acc, r) => add(acc, r.total ?? '0'), '0.00')
  return {
    range: range as PnlAnalysis['range'],
    from: iso(fromMs),
    asOf: iso(nowMs),
    return: ret(end),
    pnl: money(pnl),
    points: points.map((p, i) => (i === n - 1 ? { ...p, pnl: money(pnl) } : p)),
    benchmarks: [bench('SPY', 0.008), bench('QQQ', 0.011)],
    bySymbol,
    other: '0.00',
  }
}

/** Several mock days as one cell: Σ P&L and flows, chained returns (display fakes). */
function mergeCells(date: string, list: ReadonlyArray<PnlCalendarCell>): PnlCalendarCell {
  let pnl = '0.00'
  let flows = '0.00'
  let growth = 1
  for (const c of list) {
    pnl = add(pnl, c.pnl ?? '0')
    flows = add(flows, c.flows ?? '0')
    growth *= 1 + Number(c.return ?? '0')
  }
  return { date, pnl: money(pnl), return: ret(growth - 1), flows: money(flows), live: list.some((c) => c.live) }
}

/** §16.10 P&L calendar of an account (seeded by `key`, the demo's by default): one cell per trading day since the opening, today live. */
export function mockPnlCalendar(
  view: 'DAY' | 'MONTH',
  month: string,
  year: number,
  /** `weekends`: Saturdays and Sundays are P&L days too (crypto trades 24/7 while it is enabled). */
  input: { openedAtMs: number; dayPnl: Dec; dayReturn: Dec; deposits: ReadonlyArray<{ date: string; amount: Dec }>; key?: string; weekends?: boolean },
  nowMs: number,
): PnlCalendar {
  const today = etDate(nowMs)
  const days: PnlCalendarCell[] = []
  for (let t = input.openedAtMs; t <= nowMs + DAY; t += DAY) {
    const date = etDate(t)
    if ((!input.weekends && !isWeekday(date)) || date > today || days.some((d) => d.date === date)) continue
    const live = date === today
    const rng = mulberry32(hashString(`${input.key ?? 'demo'}:cal:${date}`))
    const r = live ? Number(input.dayReturn) : (rng() - 0.47) * 0.04
    const flows = input.deposits.filter((d) => d.date === date).reduce((acc, d) => add(acc, d.amount), '0.00')
    days.push({ date, pnl: live ? input.dayPnl : (r * 520).toFixed(2), return: ret(r), flows: money(flows), live })
  }
  let from: string
  let to: string
  let cells: PnlCalendarCell[]
  if (view === 'DAY') {
    from = `${month}-01`
    const [y, m] = month.split('-').map(Number) as [number, number]
    to = `${month}-${String(new Date(Date.UTC(y, m, 0)).getUTCDate()).padStart(2, '0')}`
    cells = days.filter((d) => d.date!.startsWith(month))
  } else {
    from = `${year}-01-01`
    to = `${year}-12-31`
    const inYear = days.filter((d) => d.date!.startsWith(String(year)))
    const months = [...new Set(inYear.map((d) => d.date!.slice(0, 7)))]
    cells = months.map((m) => mergeCells(`${m}-01`, inYear.filter((d) => d.date!.startsWith(m))))
  }
  const inRange = days.filter((d) => d.date! >= from && d.date! <= to)
  const total = inRange.length ? mergeCells(from, inRange) : null
  return { view, from, to, asOf: iso(nowMs), cells, total: { pnl: total?.pnl ?? '0.00', return: total?.return ?? null } }
}

/** One player's reaction on a mock feed item. */
export interface MockReaction {
  handle: string
  reaction: Reaction
  at: number
}

const ORDER: Reaction[] = ['ROCKET', 'FIRE', 'GEM', 'CLAP', 'LAUGH', 'SKULL']

/** §16.11 grouping: emojis in the fixed order, players oldest first, unknown players left out. */
export function groupReactions(rows: ReadonlyArray<MockReaction> | undefined, who: (handle: string) => Reactor | null): FeedReaction[] {
  const by = new Map<Reaction, Reactor[]>()
  for (const r of [...(rows ?? [])].sort((a, b) => a.at - b.at)) {
    const p = who(r.handle)
    if (!p) continue
    by.set(r.reaction, [...(by.get(r.reaction) ?? []), p])
  }
  return ORDER.filter((r) => by.has(r)).map((r) => ({ reaction: r, count: by.get(r)!.length, players: by.get(r)! }))
}

/** Sets (or removes, `reaction` null) one player's reaction: one per player per item; the same emoji keeps its time. */
export function setReaction(rows: ReadonlyArray<MockReaction> | undefined, handle: string, reaction: Reaction | null, at: number): MockReaction[] {
  const mine = (rows ?? []).find((r) => r.handle === handle)
  const rest = (rows ?? []).filter((r) => r.handle !== handle)
  if (!reaction) return rest
  return [...rest, { handle, reaction, at: mine && mine.reaction === reaction ? mine.at : at }]
}
