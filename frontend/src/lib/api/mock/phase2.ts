/**
 * Mock mode (§22.9) for the Phase 2 market endpoints (§15.10, §22.10): Time & Sales (`GET /assets/{symbol}/trades`
 * and `/topic/trades.{SYM}`), analyst recommendation trends, the screener and the trading calendar. Everything is
 * derived from the fake market's random walks, labelled SIMULATED, and lives only in mock builds.
 */
import { type Dec, div, mul, setScale } from '../../format/decimal'
import type { Schemas } from '../contract'
import { MOCK_UNIVERSE } from './data'
import { type MockMarket, hashString, mulberry32 } from './market'

type TimeAndSales = Schemas['TimeAndSales']
type TapePrint = Schemas['TapePrint']
type Recommendations = Schemas['Recommendations']
type ScreenResult = Schemas['ScreenResult']
type ScreenerRow = Schemas['ScreenerRow']
type TradingDay = Schemas['TradingDay']
type Sector = NonNullable<ScreenerRow['sector']>

const SECTORS: Record<string, Sector> = {
  AAPL: 'INFORMATION_TECHNOLOGY', NVDA: 'INFORMATION_TECHNOLOGY', MSFT: 'INFORMATION_TECHNOLOGY', AMD: 'INFORMATION_TECHNOLOGY',
  PLTR: 'INFORMATION_TECHNOLOGY', TSLA: 'CONSUMER_DISCRETIONARY', AMZN: 'CONSUMER_DISCRETIONARY', F: 'CONSUMER_DISCRETIONARY',
  META: 'COMMUNICATION_SERVICES', GOOGL: 'COMMUNICATION_SERVICES', NFLX: 'COMMUNICATION_SERVICES', DIS: 'COMMUNICATION_SERVICES',
  JPM: 'FINANCIALS', COIN: 'FINANCIALS', SOFI: 'FINANCIALS', 'BRK.B': 'FINANCIALS', KO: 'CONSUMER_STAPLES', XYZW: 'INDUSTRIALS',
}

const PRESETS = ['TOP_GAINERS', 'TOP_LOSERS', 'MOST_ACTIVE', 'UNUSUAL_VOLUME', 'LARGE_CAPS_UNDER_50', 'MEGA_CAPS', 'ETFS'] as const
const SORTS = ['SYMBOL', 'PRICE', 'CHANGE_PCT', 'VOLUME', 'AVG_VOLUME', 'REL_VOLUME', 'MARKET_CAP'] as const
type Preset = (typeof PRESETS)[number]
const n = (v: Dec | number | null | undefined): number | null => (v == null ? null : Number(v))
const ge = (v: Dec | number | null | undefined, min: Dec | null): boolean => min == null || (v != null && n(v)! >= Number(min))
const le = (v: Dec | number | null | undefined, max: Dec | null): boolean => max == null || (v != null && n(v)! <= Number(max))
type Sort = (typeof SORTS)[number]

export class Phase2Mock {
  private readonly tapes = new Map<string, TapePrint[]>()
  private seq = 0
  private readonly rng = mulberry32(7)

  constructor(
    private readonly market: MockMarket,
    private readonly now: () => number = Date.now,
  ) {}

  // ------------------------------------------------------------------------------------------- Time & Sales

  private print(symbol: string, at: number): TapePrint | null {
    const q = this.market.quote(symbol)
    if (!q.bid || !q.ask || !q.last) return null
    const r = this.rng()
    const side = r < 0.45 ? 'AT_BID' : r < 0.9 ? 'AT_ASK' : 'BETWEEN'
    const price = side === 'AT_BID' ? q.bid : side === 'AT_ASK' ? q.ask : q.last
    this.seq += 1
    return { id: `m${this.seq}`, t: new Date(at).toISOString(), price, size: 100 * (1 + Math.floor(this.rng() * 8)), side, venue: 'MOCK' }
  }

  /** One new print after the fake market moved `symbol` (the realtime pump calls this). */
  step(symbol: string): TapePrint | null {
    const p = this.print(symbol, this.now())
    if (!p) return null
    const tape = this.tapes.get(symbol) ?? []
    tape.unshift(p)
    if (tape.length > 500) tape.length = 500
    this.tapes.set(symbol, tape)
    return p
  }

  trades(symbol: string, limit: number): TimeAndSales {
    let tape = this.tapes.get(symbol)
    if (!tape && this.market.has(symbol)) {
      // a short back-filled history so the panel is not empty on first open (mock mode only)
      tape = []
      for (let i = 0; i < 40; i++) {
        const p = this.print(symbol, this.now() - (i + 1) * 1_700)
        if (p) tape.push(p)
      }
      this.tapes.set(symbol, tape)
    }
    const known = this.market.has(symbol)
    return { symbol, source: known ? 'SIMULATED' : 'NONE', streaming: known, asOf: new Date(this.now()).toISOString(), prints: (tape ?? []).slice(0, limit) }
  }

  /** The `/topic/trades.{SYM}` message for a new print. */
  tradesMessage(symbol: string, print: TapePrint): TimeAndSales {
    return { symbol, source: 'SIMULATED', streaming: true, asOf: new Date(this.now()).toISOString(), prints: [print] }
  }

  // ------------------------------------------------------------------------------------------- recommendations

  recommendations(symbol: string): Recommendations {
    const meta = this.market.meta(symbol)
    if (!meta || meta.type === 'ETF' || meta.type === 'CRYPTO') return { symbol, source: 'none', asOf: null, periods: [] }
    const rng = mulberry32(hashString(`rec:${symbol}`))
    const analysts = 8 + Math.floor(rng() * 36)
    const tilt = rng()
    const month = new Date(this.now())
    const periods = [0, 1, 2, 3].map((i) => {
      const d = new Date(Date.UTC(month.getUTCFullYear(), month.getUTCMonth() - i, 1))
      const t = Math.min(1, Math.max(0, tilt + 0.08 * (rng() - 0.5)))
      const strongBuy = Math.round(analysts * 0.35 * t)
      const buy = Math.round(analysts * 0.4 * t)
      const sell = Math.round(analysts * 0.12 * (1 - t))
      const strongSell = Math.round(analysts * 0.05 * (1 - t))
      const hold = Math.max(0, analysts - strongBuy - buy - sell - strongSell)
      return { period: d.toISOString().slice(0, 10), strongBuy, buy, hold, sell, strongSell, total: strongBuy + buy + hold + sell + strongSell }
    })
    return { symbol, source: 'simulated', asOf: new Date(this.now()).toISOString(), periods }
  }

  // ------------------------------------------------------------------------------------------- calendar

  /** Weekdays between two dates, 04:00–09:30–16:00–20:00 ET (EDT offsets; mock mode only). */
  calendar(from: string, to: string): TradingDay[] {
    const out: TradingDay[] = []
    const end = Date.parse(`${to}T12:00:00Z`)
    for (let t = Date.parse(`${from}T12:00:00Z`); t <= end && out.length < 400; t += 86_400_000) {
      const d = new Date(t)
      if (d.getUTCDay() === 0 || d.getUTCDay() === 6) continue
      const date = d.toISOString().slice(0, 10)
      out.push({
        date,
        preOpenAt: `${date}T08:00:00.000Z`,
        rthOpenAt: `${date}T13:30:00.000Z`,
        rthCloseAt: `${date}T20:00:00.000Z`,
        postCloseAt: `${date}T00:00:00.000Z`,
        settlementDate: date,
        earlyClose: false,
      })
    }
    return out
  }

  // ------------------------------------------------------------------------------------------- screener

  private rows(): ScreenerRow[] {
    // stocks and ETFs only: crypto pairs are not screened
    return MOCK_UNIVERSE.filter((m) => m.exchange !== 'SIM' && m.type !== 'CRYPTO').map((m) => {
      const q = this.market.quote(m.symbol)
      const avg = Math.round(m.adv)
      const shares = m.marketCap && m.prevClose ? div(m.marketCap, String(m.prevClose / 10_000), 0, 'HALF_EVEN') : null
      const marketCap = shares && q.last ? setScale(mul(shares, q.last), 0, 'HALF_EVEN') : null
      return {
        symbol: m.symbol,
        name: m.name,
        type: m.type,
        exchange: m.exchange,
        sector: m.type === 'ETF' ? null : (SECTORS[m.symbol] ?? null),
        price: q.last,
        change: q.change,
        changePct: q.changePct,
        volume: q.volume,
        volumeIsPartial: false,
        avgVolume: avg,
        relVolume: q.volume != null && avg > 0 ? div(String(q.volume), String(avg), 2, 'HALF_EVEN') : null,
        marketCap,
        quality: 'SIMULATED',
        asOf: q.asOf,
      }
    })
  }

  /** `GET /market/screener` with the server's presets, filters, sort and cursor; null = a validation error field. */
  screen(url: URL): ScreenResult | { field: string } {
    const p = url.searchParams
    const preset = (p.get('preset')?.toUpperCase() ?? null) as Preset | null
    if (preset && !PRESETS.includes(preset)) return { field: 'preset' }
    const num = (k: string): Dec | null | 'bad' => {
      const v = p.get(k)
      if (v == null || v === '') return null
      return /^-?\d+(\.\d+)?$/.test(v) ? v : 'bad'
    }
    const f: Record<string, Dec | null> = {}
    for (const k of ['minPrice', 'maxPrice', 'minMarketCap', 'maxMarketCap', 'minChangePct', 'maxChangePct', 'minVolume', 'minAvgVolume', 'minRelVolume']) {
      const v = num(k)
      if (v === 'bad') return { field: k }
      f[k] = v
    }
    const list = (k: string) => p.getAll(k).flatMap((v) => v.split(',')).map((v) => v.trim().toUpperCase()).filter(Boolean)
    let types = list('types')
    const sectors = list('sectors')
    let sort = (p.get('sort')?.toUpperCase() ?? null) as Sort | null
    if (sort && !SORTS.includes(sort)) return { field: 'sort' }
    let order = p.get('order')?.toUpperCase() ?? null
    const stocks = ['COMMON_STOCK', 'ADR']
    const presetDefaults: Record<Preset, () => void> = {
      TOP_GAINERS: () => ((f.minChangePct ??= '0.000001'), (types = types.length ? types : stocks)),
      TOP_LOSERS: () => ((f.maxChangePct ??= '-0.000001'), (types = types.length ? types : stocks)),
      MOST_ACTIVE: () => (types = types.length ? types : stocks),
      UNUSUAL_VOLUME: () => ((f.minRelVolume ??= '2'), (f.minAvgVolume ??= '500000'), (types = types.length ? types : stocks)),
      LARGE_CAPS_UNDER_50: () => ((f.minMarketCap ??= '10000000000'), (f.maxPrice ??= '50')),
      MEGA_CAPS: () => (f.minMarketCap ??= '200000000000'),
      ETFS: () => (types = types.length ? types : ['ETF']),
    }
    const presetSort: Record<Preset, [Sort, string]> = {
      TOP_GAINERS: ['CHANGE_PCT', 'DESC'], TOP_LOSERS: ['CHANGE_PCT', 'ASC'], MOST_ACTIVE: ['VOLUME', 'DESC'],
      UNUSUAL_VOLUME: ['REL_VOLUME', 'DESC'], LARGE_CAPS_UNDER_50: ['MARKET_CAP', 'DESC'], MEGA_CAPS: ['MARKET_CAP', 'DESC'], ETFS: ['VOLUME', 'DESC'],
    }
    if (preset) presetDefaults[preset]()
    if (!sort) {
      sort = preset ? presetSort[preset][0] : 'MARKET_CAP'
      order ??= preset ? presetSort[preset][1] : 'DESC'
    }
    order ??= sort === 'SYMBOL' ? 'ASC' : 'DESC'
    const all = this.rows()
    const matches = all.filter(
      (r) =>
        ge(r.price, f.minPrice!) && le(r.price, f.maxPrice!) && ge(r.marketCap, f.minMarketCap!) && le(r.marketCap, f.maxMarketCap!) &&
        ge(r.changePct, f.minChangePct!) && le(r.changePct, f.maxChangePct!) && ge(r.volume, f.minVolume!) && ge(r.avgVolume, f.minAvgVolume!) &&
        ge(r.relVolume, f.minRelVolume!) && (sectors.length === 0 || (r.sector != null && sectors.includes(r.sector))) &&
        (types.length === 0 || (r.type != null && types.includes(r.type))),
    )
    const key = (r: ScreenerRow): number | string | null => {
      switch (sort) {
        case 'SYMBOL': return r.symbol
        case 'PRICE': return n(r.price)
        case 'CHANGE_PCT': return n(r.changePct)
        case 'VOLUME': return r.volume
        case 'AVG_VOLUME': return r.avgVolume
        case 'REL_VOLUME': return n(r.relVolume)
        default: return n(r.marketCap)
      }
    }
    const dir = order === 'ASC' ? 1 : -1
    matches.sort((a, b) => {
      const x = key(a)
      const y = key(b)
      if (x == null || y == null) return x == null ? (y == null ? a.symbol!.localeCompare(b.symbol!) : 1) : -1
      const c = x < y ? -1 : x > y ? 1 : 0
      return c !== 0 ? c * dir : a.symbol!.localeCompare(b.symbol!)
    })
    const limit = Math.min(100, Math.max(1, Number(p.get('limit') ?? 25)))
    const cursor = p.get('cursor')
    const offset = cursor ? Number(atob(cursor.replace(/-/g, '+').replace(/_/g, '/')).replace(/^s:/, '')) || 0 : 0
    const page = matches.slice(offset, offset + limit)
    const next = offset + limit < matches.length ? btoa(`s:${offset + limit}`).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_') : null
    return {
      preset,
      sort,
      order: order as 'ASC' | 'DESC',
      total: matches.length,
      items: page,
      nextCursor: next,
      asOf: new Date(this.now()).toISOString(),
      warming: false,
      coverage: { universe: all.length, priced: all.length, withMarketCap: all.filter((r) => r.marketCap).length, withAvgVolume: all.length },
    }
  }
}
