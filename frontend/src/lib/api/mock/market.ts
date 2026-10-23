/**
 * A tiny deterministic fake market for mock mode (§22.9): seeded random walks in integer ten-thousandths,
 * rendered as §18.3 `Quote`, `Tick` and `Bars` DTOs with decimal strings. Quality is always SIMULATED.
 * Option contracts (§30, OCC symbols) have no walk of their own: they are priced off their underlying's
 * (`./options`), so stepping or nudging a contract moves its underlying.
 */
import { type Dec, div, fromInteger, shift } from '../../format/decimal'
import { type OptionParts, isOption, optionLabel, parseOption } from '../../options'
import type { Bar, Bars, ChartRange, MoverItem, MoverList, OptionChain, OptionContract, Quote, Tick, Timeframe } from '../types'
import { MOCK_UNIVERSE, type MockSymbol } from './data'
import { blackScholes, chainSymbols, impliedVol, optionBook, yearsTo } from './options'

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
  '5D': { timeframe: '5Min', count: 390, stepMs: 5 * 60_000 },
  '1M': { timeframe: '30Min', count: 286, stepMs: 30 * 60_000 },
  '3M': { timeframe: '1Day', count: 63, stepMs: 86_400_000 },
  '6M': { timeframe: '1Day', count: 126, stepMs: 86_400_000 },
  YTD: { timeframe: '1Day', count: 194, stepMs: 86_400_000 },
  '1Y': { timeframe: '1Day', count: 252, stepMs: 86_400_000 },
  '5Y': { timeframe: '1Week', count: 260, stepMs: 7 * 86_400_000 },
  ALL: { timeframe: '1Month', count: 120, stepMs: 30 * 86_400_000 },
}

/** Crypto trades around the clock: a day is 1,440 minutes. */
const CRYPTO_RANGE_SPEC: Partial<Record<ChartRange, { timeframe: Timeframe; count: number; stepMs: number }>> = {
  '1D': { timeframe: '1Min', count: 1440, stepMs: 60_000 },
  '5D': { timeframe: '5Min', count: 1440, stepMs: 5 * 60_000 },
  '1M': { timeframe: '30Min', count: 1440, stepMs: 30 * 60_000 },
}

const TIMEFRAME_MS: Record<Timeframe, number> = {
  '1Min': 60_000,
  '5Min': 5 * 60_000,
  '15Min': 15 * 60_000,
  '30Min': 30 * 60_000,
  '1Hour': 60 * 60_000,
  '1Day': 86_400_000,
  '1Week': 7 * 86_400_000,
  '1Month': 30 * 86_400_000,
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

  /** A listed symbol: a stock, ETF or pair of the universe, or an unexpired contract on a stock or ETF. */
  has(symbol: string): boolean {
    return this.states.has(symbol) || this.optionState(symbol) !== null
  }

  meta(symbol: string): MockSymbol | undefined {
    const st = this.states.get(symbol)
    if (st) return st.meta
    const o = this.optionState(symbol)
    if (!o) return undefined
    return {
      ...o.underlying.meta,
      symbol,
      name: optionLabel(symbol),
      exchange: 'OPRA',
      type: 'OPTION',
      fractionable: false,
      prevClose: this.optionUnits(symbol, true)?.mid ?? 0,
      openBps: 0,
      marketCap: null,
      peTtm: null,
      industry: null,
      adv: 0,
    }
  }

  /** The underlying's state of an unexpired OCC contract on a stock or ETF of the universe, or null. */
  private optionState(symbol: string): { underlying: SymbolState; parts: OptionParts } | null {
    if (!isOption(symbol)) return null
    const parts = parseOption(symbol)
    const underlying = parts ? this.states.get(parts.underlying) : undefined
    if (!parts || !underlying || underlying.meta.type === 'CRYPTO' || yearsTo(parts.expiration, this.now()) <= 0) return null
    return { underlying, parts }
  }

  /**
   * A contract's book in ten-thousandths off its underlying's last price (`prev`: off the previous close, a day
   * further from expiry, for the change), with the model's delta and volatility.
   */
  private optionUnits(symbol: string, prev = false): { bid: number; ask: number; mid: number; delta: number; iv: number } | null {
    const o = this.optionState(symbol)
    if (!o) return null
    const spot = (prev ? o.underlying.meta.prevClose : o.underlying.last) / UNITS_PER_DOLLAR
    const iv = impliedVol(o.underlying.meta.vol)
    const years = yearsTo(o.parts.expiration, this.now()) + (prev ? 1 / 365 : 0)
    const { price, delta } = blackScholes(o.parts.right, spot, Number(o.parts.strike), years, iv)
    return { ...optionBook(price), delta, iv }
  }

  /** Advance one symbol by one random step (a contract: its underlying). */
  step(symbol: string): void {
    const underlying = this.optionState(symbol)?.underlying.meta.symbol
    if (underlying) return this.step(underlying)
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

  /**
   * Moves a symbol's last price by `bps` basis points at once (mock-mode test hook: e2e drives a stop through its
   * trigger without waiting for the random walk).
   */
  nudge(symbol: string, bps: number): void {
    const underlying = this.optionState(symbol)?.underlying.meta.symbol
    if (underlying) return this.nudge(underlying, bps)
    const s = this.states.get(symbol)
    if (!s) return
    s.last = toTick(Math.round(s.last * (1 + bps / 10_000)))
    s.high = Math.max(s.high, s.last)
    s.low = Math.min(s.low, s.last)
    s.lastAt = this.now()
  }

  /** Executable side prices in units. */
  bidAsk(symbol: string): { bid: number; ask: number; last: number } | null {
    const option = this.optionUnits(symbol)
    if (option) return { bid: option.bid, ask: option.ask, last: option.mid }
    const s = this.states.get(symbol)
    if (!s) return null
    const tick = tickUnits(s.last)
    const half = Math.max(tick, toTick(Math.round(s.last * 0.00005 * s.meta.vol)))
    return { bid: Math.max(tick, s.last - half), ask: s.last + half, last: s.last }
  }

  quote(symbol: string): Quote {
    const s = this.states.get(symbol)
    const nowIso = new Date(this.now()).toISOString()
    const option = s ? null : this.optionQuote(symbol)
    if (option) return option
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

  /** A contract's quote (§30): the model's book, its mid as the last print, the change since yesterday's value. */
  private optionQuote(symbol: string): Quote | null {
    const o = this.optionState(symbol)
    const now = this.optionUnits(symbol)
    const prev = this.optionUnits(symbol, true)
    if (!o || !now || !prev) return null
    const change = unitsToDec(now.mid - prev.mid)
    return {
      symbol,
      bid: unitsToDec(now.bid),
      bidSize: 10 + (hashString(`${symbol}:b`) % 90),
      ask: unitsToDec(now.ask),
      askSize: 10 + (hashString(`${symbol}:a`) % 90),
      last: unitsToDec(now.mid),
      lastSize: 1 + (hashString(symbol) % 9),
      lastAt: new Date(o.underlying.lastAt).toISOString(),
      prevClose: unitsToDec(prev.mid),
      change,
      changePct: prev.mid > 0 ? div(change, unitsToDec(prev.mid), 10, 'HALF_EVEN') : null,
      dayOpen: unitsToDec(prev.mid),
      dayHigh: unitsToDec(Math.max(now.mid, prev.mid)),
      dayLow: unitsToDec(Math.min(now.mid, prev.mid)),
      volume: this.optionActivity(symbol, now.delta).volume,
      volumeIsPartial: false,
      extended: null,
      asOf: new Date(this.now()).toISOString(),
      ageMs: Math.max(0, this.now() - o.underlying.lastAt),
      quality: 'SIMULATED',
      source: 'mock',
      halted: false,
      session: 'REGULAR',
    }
  }

  /** Deterministic volume and open interest, highest near the money (|delta| ≈ 0.5). */
  private optionActivity(symbol: string, delta: number): { volume: number; openInterest: number } {
    const h = hashString(symbol)
    const nearness = 1 - Math.min(1, Math.abs(Math.abs(delta) - 0.5) * 2)
    const openInterest = Math.round((200 + (h % 4000)) * (0.15 + nearness))
    return { volume: Math.round(openInterest * (0.05 + ((h >>> 12) % 40) / 100)), openInterest }
  }

  /**
   * One expiration's chain (§30): 21 strikes around the previous close (so the rows stay put through the day), calls
   * and puts, or null for an unknown underlying or a crypto pair.
   */
  optionChain(underlying: string, expiration: string, enabled: boolean): OptionChain | null {
    const s = this.states.get(underlying)
    if (!s || s.meta.type === 'CRYPTO') return null
    const { calls, puts } = chainSymbols(underlying, expiration, s.meta.prevClose / UNITS_PER_DOLLAR)
    const contract = (symbol: string): OptionContract => {
      const q = this.quote(symbol)
      const u = this.optionUnits(symbol)
      const { volume, openInterest } = this.optionActivity(symbol, u?.delta ?? 0)
      const parts = parseOption(symbol)!
      return {
        symbol,
        underlying,
        expiration,
        right: parts.right,
        strike: parts.strike,
        bid: q.bid,
        bidSize: q.bidSize,
        ask: q.ask,
        askSize: q.askSize,
        mid: q.last,
        last: q.last,
        volume,
        openInterest,
        impliedVolatility: u ? u.iv.toFixed(4) : null,
        delta: u ? u.delta.toFixed(4) : null,
        quoteAt: q.asOf,
        quality: q.quality,
      }
    }
    return {
      underlying,
      expiration,
      enabled,
      feed: 'simulated',
      multiplier: 100,
      underlyingPrice: unitsToDec(s.last),
      asOf: new Date(this.now()).toISOString(),
      calls: calls.map(contract),
      puts: puts.map(contract),
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

  /** Gainers / losers / most active among the stocks (ETFs and crypto excluded), from the live random walks. */
  movers(list: MoverList, limit: number): MoverItem[] {
    const rows = [...this.states.values()]
      .filter((s) => s.meta.type !== 'ETF' && s.meta.type !== 'CRYPTO')
      .map((s) => {
        const q = this.quote(s.meta.symbol)
        return { symbol: s.meta.symbol, name: s.meta.name, price: q.last, change: q.change, changePct: q.changePct, volume: q.volume, move: s.last / s.meta.prevClose }
      })
    if (list === 'GAINERS') rows.sort((a, b) => b.move - a.move)
    else if (list === 'LOSERS') rows.sort((a, b) => a.move - b.move)
    else rows.sort((a, b) => (b.volume ?? 0) - (a.volume ?? 0))
    return rows.slice(0, limit).map(({ move: _move, ...item }) => item)
  }

  /** Bars for a range, at its default interval or at `timeframe` (the same span, ≤ 1000 bars). */
  bars(symbol: string, range: ChartRange, timeframe?: Timeframe | null): Bars {
    const s = this.states.get(symbol)
    // a crypto pair trades 24/7: 1D is the last 24 hours of minutes, 5D the last 5 days, with no session gaps
    const base = s?.meta.type === 'CRYPTO' ? (CRYPTO_RANGE_SPEC[range] ?? RANGE_SPEC[range]) : RANGE_SPEC[range]
    const spec =
      timeframe && timeframe !== base.timeframe
        ? { timeframe, stepMs: TIMEFRAME_MS[timeframe], count: Math.min(1000, Math.max(2, Math.round((base.count * base.stepMs) / TIMEFRAME_MS[timeframe]))) }
        : base
    if (!s) return { symbol, timeframe: spec.timeframe, adjustment: 'SPLIT', bars: [], tailFrom: null, source: 'mock' }
    const rng = mulberry32(hashString(`${symbol}:${range}:${spec.timeframe}`))
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
        v: Math.round((s.meta.adv / (spec.stepMs < 86_400_000 ? (s.meta.type === 'CRYPTO' ? 1440 : 390) * (60_000 / spec.stepMs) : 1)) * (0.4 + rng() * 1.2)),
        vw: null,
      }
    })
    return { symbol, timeframe: spec.timeframe, adjustment: 'SPLIT', bars, tailFrom: null, source: 'mock' }
  }
}
