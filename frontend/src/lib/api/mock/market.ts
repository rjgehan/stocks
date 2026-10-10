/**
 * A tiny deterministic fake market for mock mode (§22.9): seeded random walks in integer ten-thousandths,
 * rendered as §18.3 `Quote`, `Tick` and `Bars` DTOs with decimal strings. Quality is always SIMULATED.
 */
import { type Dec, div, fromInteger, shift } from '../../format/decimal'
import type { Bar, Bars, ChartRange, MoverItem, MoverList, Quote, Tick, Timeframe } from '../types'
import { MOCK_UNIVERSE, type MockSymbol } from './data'

const UNITS_PER_DOLLAR = 10_000

/** Integer ten-thousandths → Dec with 4 decimals ("227.4000"). */
export const unitsToDec = (units: number): Dec => shift(fromInteger(units), -4)

const tickUnits = (price: number): number => (price >= UNITS_PER_DOLLAR ? 100 : 1)
const toTick = (price: number): number => {
  const t = tickUnits(price)
  return Math.max(t, Math.round(price / t) * t)
}

export function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function hashString(s: string): number {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619)
  return h >>> 0
}

function gaussian(rng: () => number): number {
  const u = Math.max(rng(), 1e-12)
  const v = rng()
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v)
}

interface SymbolState {
  meta: MockSymbol
  last: number
  open: number
  high: number
  low: number
  volume: number
  bidSize: number
  askSize: number
  lastSize: number
  lastAt: number
}

const RANGE_SPEC: Record<ChartRange, { timeframe: Timeframe; count: number; stepMs: number }> = {
  '1D': { timeframe: '1Min', count: 390, stepMs: 60_000 },
  '5D': { timeframe: '15Min', count: 130, stepMs: 15 * 60_000 },
  '1M': { timeframe: '1Hour', count: 154, stepMs: 60 * 60_000 },
  '3M': { timeframe: '1Day', count: 63, stepMs: 86_400_000 },
  '6M': { timeframe: '1Day', count: 126, stepMs: 86_400_000 },
  YTD: { timeframe: '1Day', count: 194, stepMs: 86_400_000 },
  '1Y': { timeframe: '1Day', count: 252, stepMs: 86_400_000 },
  '5Y': { timeframe: '1Week', count: 260, stepMs: 7 * 86_400_000 },
  ALL: { timeframe: '1Month', count: 120, stepMs: 30 * 86_400_000 },
}

export class MockMarket {
  private readonly states = new Map<string, SymbolState>()
  private readonly rng: () => number

  constructor(
    seed = 42,
    private readonly now: () => number = Date.now,
  ) {
    this.rng = mulberry32(seed)
    for (const meta of MOCK_UNIVERSE) {
      const open = toTick(Math.round(meta.prevClose * (1 + meta.openBps / 10_000)))
      const state: SymbolState = {
        meta,
        last: open,
        open,
        high: open,
        low: open,
        volume: Math.round(meta.adv * 0.35),
        bidSize: 300,
        askSize: 200,
        lastSize: 100,
        lastAt: this.now(),
      }
      this.states.set(meta.symbol, state)
      for (let i = 0; i < 120; i++) this.step(meta.symbol)
    }
  }

  symbols(): string[] {
    return [...this.states.keys()]
  }

  has(symbol: string): boolean {
    return this.states.has(symbol)
  }

  meta(symbol: string): MockSymbol | undefined {
    return this.states.get(symbol)?.meta
  }

  /** Advance one symbol by one random step. */
  step(symbol: string): void {
    const s = this.states.get(symbol)
    if (!s) return
    const move = s.meta.vol * 0.0006 * gaussian(this.rng)
    s.last = toTick(Math.round(s.last * (1 + move)))
    s.high = Math.max(s.high, s.last)
    s.low = Math.min(s.low, s.last)
    s.lastSize = 100 * (1 + Math.floor(this.rng() * 5))
    s.volume += s.lastSize * (1 + Math.floor(this.rng() * 40))
    s.bidSize = 100 * (1 + Math.floor(this.rng() * 9))
    s.askSize = 100 * (1 + Math.floor(this.rng() * 9))
    s.lastAt = this.now()
  }

  /** Executable side prices in units. */
  bidAsk(symbol: string): { bid: number; ask: number; last: number } | null {
    const s = this.states.get(symbol)
    if (!s) return null
    const tick = tickUnits(s.last)
    const half = Math.max(tick, toTick(Math.round(s.last * 0.00005 * s.meta.vol)))
    return { bid: Math.max(tick, s.last - half), ask: s.last + half, last: s.last }
  }

  quote(symbol: string): Quote {
    const s = this.states.get(symbol)
    const nowIso = new Date(this.now()).toISOString()
    if (!s) {
      return {
        symbol, bid: null, bidSize: null, ask: null, askSize: null, last: null, lastSize: null, lastAt: null,
        prevClose: null, change: null, changePct: null, dayOpen: null, dayHigh: null, dayLow: null, volume: null,
        volumeIsPartial: false, extended: null, asOf: null, ageMs: null, quality: 'NONE', source: 'mock',
        halted: false, session: 'REGULAR',
      }
    }
    const { bid, ask } = this.bidAsk(symbol)!
    const change = unitsToDec(s.last - s.meta.prevClose)
    return {
      symbol,
      bid: unitsToDec(bid),
      bidSize: s.bidSize,
      ask: unitsToDec(ask),
      askSize: s.askSize,
      last: unitsToDec(s.last),
      lastSize: s.lastSize,
      lastAt: new Date(s.lastAt).toISOString(),
      prevClose: unitsToDec(s.meta.prevClose),
      change,
      changePct: div(change, unitsToDec(s.meta.prevClose), 10, 'HALF_EVEN'),
      dayOpen: unitsToDec(s.open),
      dayHigh: unitsToDec(s.high),
      dayLow: unitsToDec(s.low),
      volume: s.volume,
      volumeIsPartial: false,
      extended: null,
      asOf: nowIso,
      ageMs: Math.max(0, this.now() - s.lastAt),
      quality: 'SIMULATED',
      source: 'mock',
      halted: false,
      session: 'REGULAR',
    }
  }

  tick(symbol: string): Tick {
    const q = this.quote(symbol)
    return {
      symbol,
      last: q.last,
      change: q.change,
      changePct: q.changePct,
      extended: null,
      asOf: q.asOf,
      quality: q.quality,
    }
  }

  /** Gainers / losers / most active among the stocks (ETFs excluded), from the live random walks. */
  movers(list: MoverList, limit: number): MoverItem[] {
    const rows = [...this.states.values()]
      .filter((s) => s.meta.type !== 'ETF')
      .map((s) => {
        const q = this.quote(s.meta.symbol)
        return { symbol: s.meta.symbol, name: s.meta.name, price: q.last, change: q.change, changePct: q.changePct, volume: q.volume, move: s.last / s.meta.prevClose }
      })
    if (list === 'GAINERS') rows.sort((a, b) => b.move - a.move)
    else if (list === 'LOSERS') rows.sort((a, b) => a.move - b.move)
    else rows.sort((a, b) => (b.volume ?? 0) - (a.volume ?? 0))
    return rows.slice(0, limit).map(({ move: _move, ...item }) => item)
  }

  bars(symbol: string, range: ChartRange): Bars {
    const s = this.states.get(symbol)
    const spec = RANGE_SPEC[range]
    if (!s) return { symbol, timeframe: spec.timeframe, adjustment: 'SPLIT', bars: [], tailFrom: null, source: 'mock' }
    const rng = mulberry32(hashString(`${symbol}:${range}`))
    const stepVol = s.meta.vol * 0.0009 * Math.sqrt(spec.stepMs / 60_000)
    const closes: number[] = Array.from({ length: spec.count }, () => 0)
    closes[spec.count - 1] = s.last
    for (let i = spec.count - 1; i > 0; i--) {
      // walk backwards from today's price with a gentle upward drift going forward
      closes[i - 1] = toTick(Math.round(closes[i]! * (1 - stepVol * gaussian(rng) - stepVol * 0.04)))
    }
    const end = Math.floor(this.now() / spec.stepMs) * spec.stepMs
    const bars: Bar[] = closes.map((c, i) => {
      const o = i === 0 ? c : closes[i - 1]!
      const wiggle = Math.round(c * stepVol * 0.6 * rng())
      const h = toTick(Math.max(o, c) + wiggle)
      const l = toTick(Math.max(tickUnits(c), Math.min(o, c) - wiggle))
      return {
        t: new Date(end - (spec.count - 1 - i) * spec.stepMs).toISOString(),
        o: unitsToDec(o),
        h: unitsToDec(h),
        l: unitsToDec(l),
        c: unitsToDec(c),
        v: Math.round((s.meta.adv / (spec.stepMs < 86_400_000 ? 390 * (60_000 / spec.stepMs) : 1)) * (0.4 + rng() * 1.2)),
        vw: null,
      }
    })
    return { symbol, timeframe: spec.timeframe, adjustment: 'SPLIT', bars, tailFrom: null, source: 'mock' }
  }
}
