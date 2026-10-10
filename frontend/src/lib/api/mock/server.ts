/**
 * In-memory fake of the §18 REST API for mock mode (§22.9). It answers `Request`s with `Response`s, so the
 * real client code path (openapi-fetch, CSRF, problem mapping) is exercised unchanged. It is deliberately
 * small: enough for the shell, the watchlist, the quote page and the trade ticket.
 *
 * NOTE: this is a fake SERVER, so it does arithmetic on prices (with the exact decimal helpers). The client
 * proper never computes money (§19).
 */
import {
  type Dec,
  add,
  compare,
  div,
  fromInteger,
  gt,
  isDecimal,
  isZero,
  lt,
  max,
  min,
  mul,
  setScale,
  sign,
  sub,
  tickFor,
} from '../../format/decimal'
import type {
  Account,
  Asset,
  ChartRange,
  Clock,
  Estimate,
  Execution,
  Fees,
  Leaderboard,
  Me,
  Order,
  OrderUpdateEvent,
  PlaceOrderRequest,
  Position,
  Preferences,
  Preview,
  Warning,
} from '../types'
import { MOCK_INDEX_SYMBOLS, MOCK_ME_BASE, MOCK_PLAYERS, POPULAR_SYMBOLS } from './data'
import { MockMarket, mulberry32, unitsToDec } from './market'

type Listener = (destination: string, body: unknown) => void

interface Lot {
  symbol: string
  qty: Dec
  costBasis: Dec
  openedAt: string
}

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const idRng = mulberry32(7)
function publicId(prefix: string): string {
  let s = ''
  for (let i = 0; i < 26; i++) s += CROCKFORD[Math.floor(idRng() * 32)]
  return `${prefix}_${s}`
}

function problem(status: number, code: string, title: string, detail?: string, hint?: string): Response {
  return new Response(
    JSON.stringify({
      type: `/errors/${code}`,
      title,
      status,
      code,
      detail: detail ?? null,
      hint: hint ?? null,
      fields: [],
      retryAfterS: null,
      requestId: publicId('req'),
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const noContent = (): Response => new Response(null, { status: 204 })

/** §13 seed rates (Oct 2026): SEC 0.0000206 × sell principal (CEIL cent), TAF paused, CAT 0.000003/share. */
function estimateFees(side: 'BUY' | 'SELL', qty: Dec, principal: Dec): Fees {
  const sec = side === 'SELL' ? setScale(mul(principal, '0.0000206'), 2, 'CEILING') : '0.00'
  const catExact = mul(qty, '0.000003')
  const cat = lt(catExact, '0.01') ? '0.00' : setScale(catExact, 2, 'HALF_UP')
  return { sec, taf: '0.00', cat, total: add(sec, cat) }
}

export class MockServer {
  readonly market: MockMarket
  private readonly listeners = new Set<Listener>()
  private readonly startedAt = Date.now()
  private loggedIn = true
  private csrfToken: string | null = null
  private preferences: Preferences = {
    theme: 'DARK',
    upDown: 'GREEN_UP',
    confirmOrders: true,
    defaultOrderType: 'LIMIT',
    defaultTimeInForce: 'DAY',
    defaultQtyMode: 'SHARES',
  }
  private cash: Dec = '375.00'
  private readonly lots = new Map<string, Lot>()
  private readonly orders: Order[] = []
  private readonly executions: Execution[] = []
  private readonly watchlists = [
    { id: 'wlt_01J9Y4N2W1L1ST0000000000AA', name: 'My List', sortOrder: 0, symbols: [...POPULAR_SYMBOLS, 'META', 'AMD'] },
    { id: 'wlt_01J9Y4N2W1L1ST0000000000AB', name: 'Chips', sortOrder: 1, symbols: ['NVDA', 'AMD', 'SIMA'] },
  ]

  constructor(market = new MockMarket()) {
    this.market = market
    try {
      this.loggedIn = sessionStorage.getItem('stocks.mock.loggedOut') !== '1'
    } catch {
      /* no storage */
    }
    // §18.4: the bot-style notional buy of $125.00 NVDA, already filled.
    const openedAt = new Date(this.startedAt - 45 * 60_000).toISOString()
    this.lots.set('NVDA', { symbol: 'NVDA', qty: '0.95274', costBasis: '125.00', openedAt })
    const order = this.newOrder({
      clientOrderId: 'web-seed-0001',
      symbol: 'NVDA',
      side: 'BUY',
      type: 'MARKET',
      notional: '125.00',
      timeInForce: 'DAY',
    })
    Object.assign(order, {
      qty: null,
      fractional: true,
      status: 'FILLED',
      waitReason: null,
      filledQty: '0.95274',
      filledPrincipal: '125.00',
      avgFillPrice: '131.2000',
      reservedCash: '0.00',
      receivedAt: openedAt,
      acceptedAt: openedAt,
      eligibleAt: openedAt,
      activatedAt: openedAt,
      doneAt: openedAt,
    } satisfies Partial<Order>)
    this.orders.push(order)
  }

  // ---------------------------------------------------------------- events (fake STOMP broker)

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private publish(destination: string, body: unknown): void {
    for (const l of this.listeners) l(destination, body)
  }

  /** Called by the fake realtime driver on each market step. */
  onMarketStep(symbol: string): void {
    this.sweepRestingLimits(symbol)
  }

  // ---------------------------------------------------------------- HTTP

  async handle(request: Request): Promise<Response> {
    const url = new URL(request.url)
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = request.method.toUpperCase()
    await new Promise((r) => setTimeout(r, 60 + Math.random() * 90)) // a little latency

    if (method === 'GET' && path === '/auth/csrf') {
      this.csrfToken = publicId('csrf')
      document.cookie = `XSRF-TOKEN=${this.csrfToken}; path=/; SameSite=Lax`
      return noContent()
    }
    if (method !== 'GET' && (!this.csrfToken || request.headers.get('X-XSRF-TOKEN') !== this.csrfToken)) {
      return problem(403, 'CSRF_INVALID', 'Forbidden', 'Missing or invalid CSRF token.')
    }
    const body: unknown = method === 'GET' || method === 'DELETE' ? null : await request.json().catch(() => null)

    if (method === 'POST' && path === '/auth/login') return this.login(body)
    if (!this.loggedIn) return problem(401, 'UNAUTHENTICATED', 'Unauthenticated', 'Sign in to continue.')

    const route = `${method} ${path}`
    const param = (re: RegExp): string | null => re.exec(route)?.[1] ?? null
    let p: string | null

    if (route === 'POST /auth/logout') {
      this.setLoggedIn(false)
      return noContent()
    }
    if (route === 'GET /me') return json(this.me())
    if (route === 'PATCH /me') {
      const prefs = (body as { preferences?: Partial<Preferences> } | null)?.preferences
      if (prefs) this.preferences = { ...this.preferences, ...prefs }
      return json(this.me())
    }
    if (route === 'GET /market/clock') return json(this.clock())
    if (route === 'GET /market/indices') return json(MOCK_INDEX_SYMBOLS.map((s) => this.market.quote(s)))
    if (route === 'GET /quotes') {
      const symbols = (url.searchParams.get('symbols') ?? '').split(',').map((s) => s.trim().toUpperCase()).filter(Boolean)
      return json({ quotes: symbols.slice(0, 50).map((s) => this.market.quote(s)) })
    }
    if (route === 'GET /assets/search') {
      const q = (url.searchParams.get('q') ?? '').trim().toUpperCase()
      const hits = this.market
        .symbols()
        .map((s) => this.asset(s)!)
        .filter((a) => q !== '' && (a.symbol.startsWith(q) || a.name.toUpperCase().includes(q)))
        .slice(0, Number(url.searchParams.get('limit') ?? 20))
      return json(hits.map(({ symbol, name, exchange, type, tradable, fractionable }) => ({ symbol, name, exchange, type, tradable, fractionable })))
    }
    if ((p = param(/^GET \/assets\/([A-Za-z0-9.]+)$/)) !== null) {
      const a = this.asset(p.toUpperCase())
      return a ? json(a) : problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found', `Unknown symbol ${p}.`)
    }
    if ((p = param(/^GET \/bars\/([A-Za-z0-9.]+)$/)) !== null) {
      const symbol = p.toUpperCase()
      if (!this.market.has(symbol)) return problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found')
      const range = (url.searchParams.get('range') ?? '1D').toUpperCase() as ChartRange
      return json(this.market.bars(symbol, range))
    }
    if (route === 'GET /account') return json(this.account())
    if (route === 'GET /account/positions') return json(this.positions())
    if (route === 'GET /orders') {
      const status = (url.searchParams.get('status') ?? 'ALL').toUpperCase()
      const items = this.orders
        .filter((o) => status === 'ALL' || (status === 'OPEN') === isOpen(o))
        .toReversed()
      return json({ items, nextCursor: null })
    }
    if (route === 'POST /orders/preview') return json(this.preview(body as PlaceOrderRequest))
    if (route === 'POST /orders') return this.place(body as PlaceOrderRequest)
    if ((p = param(/^(?:GET|DELETE) \/orders\/([A-Za-z0-9_]+)$/)) !== null) {
      const order = this.orders.find((o) => o.orderId === p)
      if (!order) return problem(404, 'ORDER_NOT_FOUND', 'Order not found')
      if (method === 'GET') return json({ ...order, executions: this.executions.filter((e) => e.orderId === order.orderId), events: [] })
      if (!isOpen(order)) return problem(409, 'ORDER_NOT_CANCELLABLE', 'Order is not cancellable')
      this.finish(order, 'CANCELLED', 'USER')
      return json(order)
    }
    if (route === 'GET /watchlists') return json(this.watchlists)
    if ((p = param(/^POST \/watchlists\/([A-Za-z0-9_]+)\/symbols$/)) !== null) {
      const list = this.watchlists.find((w) => w.id === p)
      const symbol = String((body as { symbol?: unknown } | null)?.symbol ?? '').toUpperCase()
      if (!list) return problem(404, 'NOT_FOUND', 'Watchlist not found')
      if (!this.market.has(symbol)) return problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found')
      if (!list.symbols.includes(symbol)) list.symbols.push(symbol)
      return json(list)
    }
    if (route === 'GET /leaderboard') return json(this.leaderboard(url.searchParams))
    return problem(501, 'NOT_IMPLEMENTED', 'Not implemented in mock mode', `${method} ${url.pathname}`)
  }

  // ---------------------------------------------------------------- identity

  private setLoggedIn(v: boolean): void {
    this.loggedIn = v
    try {
      if (v) sessionStorage.removeItem('stocks.mock.loggedOut')
      else sessionStorage.setItem('stocks.mock.loggedOut', '1')
    } catch {
      /* no storage */
    }
  }

  private login(body: unknown): Response {
    const { handle, password } = (body ?? {}) as { handle?: string; password?: string }
    if (!handle || !password || password === 'wrong') {
      return problem(401, 'BAD_CREDENTIALS', 'Bad credentials', 'Handle or password is incorrect.', 'Mock mode accepts any password except "wrong".')
    }
    this.setLoggedIn(true)
    return json(this.me())
  }

  private me(): Me {
    return { ...MOCK_ME_BASE, preferences: this.preferences }
  }

  private clock(): Clock {
    const now = Date.now()
    // A friendly fixed countdown: the mock session "closes" 2:14:07 after the page loaded.
    const endsAt = new Date(this.startedAt + ((2 * 60 + 14) * 60 + 7) * 1000)
    return {
      now: new Date(now).toISOString(),
      nowEt: new Date(now).toLocaleString('en-US', { timeZone: 'America/New_York' }),
      session: 'REGULAR',
      tradingDate: '2026-10-09',
      sessionEndsAt: endsAt.toISOString(),
      nextSession: 'POST',
      nextSessionAt: endsAt.toISOString(),
      nextRegularOpen: '2026-10-12T13:30:00.000Z',
      nextRegularClose: endsAt.toISOString(),
      earlyClose: false,
      upcomingHolidays: [{ date: '2026-11-26', name: 'Thanksgiving Day' }],
      upcomingEarlyCloses: ['2026-11-27'],
      feed: { profile: 'mock', quality: 'SIMULATED', status: 'LIVE', liveIexWindowEt: null },
    }
  }

  // ---------------------------------------------------------------- market

  private asset(symbol: string): Asset | null {
    const meta = this.market.meta(symbol)
    if (!meta) return null
    return {
      symbol,
      name: meta.name,
      exchange: meta.exchange,
      type: meta.type,
      tradable: true,
      fractionable: meta.fractionable,
      shortable: false,
      marginable: false,
      liquidityTier: meta.vol > 2 ? 'SMALL' : 'MEGA',
      status: 'ACTIVE',
      week52High: meta.week52High,
      week52Low: meta.week52Low,
      logoUrl: `/api/v1/assets/${symbol}/logo`,
      fundamentals:
        meta.type === 'ETF'
          ? null
          : {
              marketCap: meta.marketCap,
              peTtm: meta.peTtm,
              epsTtm: null,
              dividendYield: null,
              beta: null,
              sharesOutstanding: null,
              industry: meta.industry,
              website: null,
              source: 'mock',
            },
    }
  }

  // ---------------------------------------------------------------- account

  private positions(): Position[] {
    const marks = [...this.lots.values()].map((lot) => ({ lot, mark: unitsToDec(this.market.bidAsk(lot.symbol)?.last ?? 0) }))
    const longValue = marks.reduce((acc, { lot, mark }) => add(acc, setScale(mul(lot.qty, mark), 2, 'HALF_EVEN')), '0.00')
    const equity = add(this.cash, longValue)
    return marks.map(({ lot, mark }) => {
      const quote = this.market.quote(lot.symbol)
      const marketValue = setScale(mul(lot.qty, mark), 2, 'HALF_EVEN')
      const unrealized = sub(marketValue, lot.costBasis)
      const reserved = this.orders
        .filter((o) => isOpen(o) && o.side === 'SELL' && o.symbol === lot.symbol)
        .reduce((acc, o) => add(acc, o.reservedQty), '0')
      const prev = quote.prevClose ?? mark
      const dayPnl = setScale(mul(lot.qty, sub(mark, prev)), 2, 'HALF_EVEN')
      return {
        symbol: lot.symbol,
        name: this.market.meta(lot.symbol)?.name ?? lot.symbol,
        qty: lot.qty,
        availableQty: setScale(sub(lot.qty, reserved), 5, 'DOWN'),
        avgCost: div(lot.costBasis, lot.qty, 6, 'HALF_EVEN'),
        costBasis: lot.costBasis,
        last: quote.last,
        mark,
        markQuality: 'SIMULATED',
        marketValue,
        unrealizedPnl: unrealized,
        unrealizedReturn: div(unrealized, lot.costBasis, 10, 'HALF_EVEN'),
        dayPnl,
        dayReturn: isZero(prev) ? null : div(sub(mark, prev), prev, 10, 'HALF_EVEN'),
        weight: isZero(equity) ? '0' : div(marketValue, equity, 10, 'HALF_EVEN'),
        fractionable: this.market.meta(lot.symbol)?.fractionable ?? false,
        openedAt: lot.openedAt,
      }
    })
  }

  private openBuyHolds(): Dec {
    return this.orders.filter((o) => isOpen(o) && o.side === 'BUY').reduce((acc, o) => add(acc, o.reservedCash), '0.00')
  }

  private account(): Account {
    const positions = this.positions()
    const longMarketValue = positions.reduce((acc, p) => add(acc, p.marketValue), '0.00')
    const equity = add(this.cash, longMarketValue)
    const startingCash = '500.00'
    const totalPnl = sub(equity, startingCash)
    const holds = this.openBuyHolds()
    const dayPnl = positions.reduce((acc, p) => add(acc, p.dayPnl), '0.00')
    const now = new Date().toISOString()
    return {
      accountId: MOCK_ME_BASE.accountId,
      runNo: 1,
      type: 'CASH',
      status: 'ACTIVE',
      restriction: 'NONE',
      restrictionUntil: null,
      restrictionReason: null,
      equity,
      longMarketValue,
      dividendsReceivable: '0.00',
      cash: { total: this.cash, settled: this.cash, unsettled: '0.00', settledAvailable: sub(this.cash, holds), withdrawable: this.cash },
      openBuyHolds: holds,
      buyingPower: max('0.00', sub(this.cash, holds)),
      startingCash,
      netContributions: startingCash,
      totalPnl,
      totalReturn: div(totalPnl, startingCash, 10, 'HALF_EVEN'),
      unitValue: div(equity, startingCash, 18, 'HALF_EVEN'),
      dayPnl,
      dayReturn: div(dayPnl, sub(equity, dayPnl), 10, 'HALF_EVEN'),
      gfv: { active: 0, nextExpiry: null, settledCashOnlyAt: 3, liquidationOnlyAt: 4 },
      dayTradesLast5Days: 0,
      openedAt: '2026-10-01T13:00:00.000Z',
      marksQuality: 'SIMULATED',
      asOf: now,
    }
  }

  // ---------------------------------------------------------------- orders

  private preview(req: PlaceOrderRequest): Preview {
    const account = this.account()
    const errors: Preview['errors'] = []
    const warnings: Warning[] = []
    const err = (code: string, field: string | null, message: string, hint: string | null = null) =>
      errors.push({ code, field, message, hint })
    const meta = this.market.meta(String(req.symbol ?? '').toUpperCase())
    const ba = meta ? this.market.bidAsk(meta.symbol) : null
    if (!meta || !ba) {
      err('SYMBOL_NOT_FOUND', 'symbol', 'Unknown symbol.')
      return { valid: false, errors, warnings, estimate: null, buyingPowerBefore: account.buyingPower, buyingPowerAfter: null, sessionNote: null }
    }
    const side = req.side
    const type = req.type
    const tif = req.timeInForce ?? 'DAY'
    const qty = req.qty ?? null
    const notional = req.notional ?? null
    const lot = this.lots.get(meta.symbol)
    const bid = unitsToDec(ba.bid)
    const ask = unitsToDec(ba.ask)
    const last = unitsToDec(ba.last)

    if ((qty == null) === (notional == null)) err('QTY_OR_NOTIONAL_REQUIRED', 'qty', 'Enter a quantity or a dollar amount.')
    if (qty != null && (!isDecimal(qty) || sign(qty) <= 0)) err('INVALID_QTY', 'qty', 'Quantity must be greater than 0.')
    const fractionalQty = qty != null && isDecimal(qty) && sign(qty) > 0 && compare(setScale(qty, 0, 'DOWN'), qty) !== 0
    if (fractionalQty && type !== 'MARKET') err('FRACTIONAL_MARKET_ONLY', 'qty', 'Fractional shares need a Market order.')
    if (notional != null) {
      if (!isDecimal(notional) || lt(notional, '1.00')) err('NOTIONAL_TOO_SMALL', 'notional', 'The minimum dollar order is $1.00.')
      if (type !== 'MARKET') err('FRACTIONAL_MARKET_ONLY', 'type', 'Dollar orders must be Market orders.')
      if (tif !== 'DAY') err('FRACTIONAL_DAY_ONLY', 'timeInForce', 'Dollar orders are Day only.')
      if (!meta.fractionable) err('NOT_FRACTIONABLE', 'notional', `${meta.symbol} does not support fractional shares.`)
    }
    if ((type === 'LIMIT' || type === 'STOP_LIMIT') && !req.limitPrice) err('LIMIT_PRICE_REQUIRED', 'limitPrice', 'Enter a limit price.')
    if ((type === 'STOP' || type === 'STOP_LIMIT') && !req.stopPrice) err('STOP_PRICE_REQUIRED', 'stopPrice', 'Enter a stop price.')
    if (type === 'TRAILING_STOP' && !req.trailAmount && !req.trailPercent) err('TRAIL_REQUIRED', 'trailAmount', 'Enter a trail amount or percent.')
    if (type === 'TRAILING_STOP' && tif === 'GTC') err('TIF_NOT_ALLOWED', 'timeInForce', 'Trailing stops are Day only.')
    if (req.extendedHours && type !== 'LIMIT') err('EXT_HOURS_LIMIT_ONLY', 'extendedHours', 'Extended hours need a Limit order.')
    for (const [field, price] of [['limitPrice', req.limitPrice], ['stopPrice', req.stopPrice]] as const) {
      if (price == null) continue
      if (!isDecimal(price) || sign(price) <= 0) err('INVALID_TICK', field, 'Enter a valid price.')
      else if (compare(setScale(price, tickFor(price) === '0.01' ? 2 : 4, 'DOWN'), price) !== 0) {
        err('INVALID_TICK', field, `Prices must be in steps of $${tickFor(price)}.`)
      }
    }
    if (side === 'SELL' && !lot) err('SHORT_SELLING_NOT_ALLOWED', 'side', 'You have no position to sell. Short selling is not allowed.')

    let estimate: Estimate | null = null
    let buyingPowerAfter: Dec | null = null
    if (errors.length === 0) {
      const refPrice =
        type === 'LIMIT' || type === 'STOP_LIMIT'
          ? side === 'BUY'
            ? min(req.limitPrice!, ask)
            : max(req.limitPrice!, bid)
          : type === 'STOP'
            ? req.stopPrice!
            : side === 'BUY'
              ? ask
              : bid
      let estQty: Dec
      if (notional != null) {
        estQty = setScale(div(notional, refPrice, 8, 'DOWN'), 5, 'FLOOR')
      } else {
        estQty = qty!
      }
      const principal = setScale(mul(estQty, refPrice), 2, 'HALF_UP')
      const fees = estimateFees(side, estQty, principal)
      let hold = '0.00'
      if (side === 'BUY') {
        hold =
          notional != null
            ? setScale(notional, 2, 'CEILING')
            : type === 'MARKET'
              ? setScale(add(mul(mul(estQty, ask), '1.02'), fees.cat), 2, 'CEILING')
              : setScale(add(mul(estQty, refPrice), fees.cat), 2, 'CEILING')
        if (gt(hold, account.buyingPower)) {
          const maxShares = div(account.buyingPower, mul(ask, '1.02'), 0, 'FLOOR')
          err(
            'INSUFFICIENT_BUYING_POWER',
            notional != null ? 'notional' : 'qty',
            `Order needs $${hold}; buying power is $${account.buyingPower}.`,
            `Max ${maxShares} whole shares at $${setScale(ask, 2)}. Try a dollar amount instead.`,
          )
        }
        buyingPowerAfter = sub(account.buyingPower, hold)
      } else {
        if (lot && gt(estQty, lot.qty)) err('INSUFFICIENT_POSITION', 'qty', `You can sell at most ${lot.qty} shares.`)
        buyingPowerAfter = account.buyingPower
      }
      if (req.limitPrice && type === 'LIMIT') {
        const away = div(sub(req.limitPrice, last), last, 6, 'HALF_UP')
        if (gt(away, '0.05') || lt(away, '-0.05')) warnings.push({ code: 'FAT_FINGER', message: 'Your limit price is more than 5% away from the last price.' })
      }
      if (side === 'BUY' && qty != null && meta && gt(qty, fromInteger(1000))) {
        warnings.push({ code: 'PARTIAL_FILL_LIKELY', message: 'Quantity exceeds the displayed size; expect partial fills.' })
      }
      estimate = { price: refPrice, qty: estQty, principal, fees, hold, quoteAsOf: new Date().toISOString(), quoteQuality: 'SIMULATED' }
    }
    return {
      valid: errors.length === 0,
      errors,
      warnings,
      estimate,
      buyingPowerBefore: account.buyingPower,
      buyingPowerAfter: errors.length === 0 ? buyingPowerAfter : null,
      sessionNote: null,
    }
  }

  private newOrder(req: PlaceOrderRequest): Order {
    const now = new Date().toISOString()
    return {
      orderId: publicId('ord'),
      clientOrderId: req.clientOrderId ?? `web-${crypto.randomUUID()}`,
      accountId: MOCK_ME_BASE.accountId,
      symbol: req.symbol.toUpperCase(),
      side: req.side,
      type: req.type,
      qty: req.qty ?? null,
      notional: req.notional ?? null,
      fractional: req.notional != null,
      limitPrice: req.limitPrice ?? null,
      stopPrice: req.stopPrice ?? null,
      trailAmount: req.trailAmount ?? null,
      trailPercent: req.trailPercent ?? null,
      watermarkPrice: null,
      collarPrice: null,
      timeInForce: req.timeInForce ?? 'DAY',
      extendedHours: req.extendedHours ?? false,
      status: 'PENDING',
      waitReason: 'LATENCY',
      statusReason: null,
      triggered: false,
      filledQty: '0',
      filledPrincipal: '0.00',
      avgFillPrice: null,
      fees: '0.00',
      reservedCash: '0.00',
      reservedQty: '0',
      sessionDate: '2026-10-09',
      receivedAt: now,
      acceptedAt: now,
      eligibleAt: new Date(Date.now() + 600).toISOString(),
      activatedAt: null,
      triggeredAt: null,
      expiresAt: new Date(this.startedAt + ((2 * 60 + 14) * 60 + 7) * 1000).toISOString(),
      doneAt: null,
      replacesOrderId: null,
      replacedByOrderId: null,
      channel: 'WEB',
      rationale: req.rationale ?? null,
    }
  }

  private place(req: PlaceOrderRequest): Response {
    const existing = this.orders.find((o) => o.clientOrderId === req.clientOrderId)
    const preview = this.preview(req)
    if (existing) {
      return json({ order: existing, estimate: preview.estimate, buyingPowerBefore: preview.buyingPowerBefore, buyingPowerAfter: preview.buyingPowerAfter ?? preview.buyingPowerBefore, warnings: [], replayed: true }, 200)
    }
    if (!preview.valid) {
      const first = preview.errors[0]!
      return new Response(
        JSON.stringify({
          type: `/errors/${first.code}`,
          title: 'Order rejected',
          status: 422,
          code: first.code,
          detail: first.message,
          hint: first.hint,
          fields: preview.errors.filter((e) => e.field).map((e) => ({ field: e.field, code: e.code, message: e.message })),
          retryAfterS: null,
          requestId: publicId('req'),
        }),
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      )
    }
    const order = this.newOrder(req)
    order.reservedCash = req.side === 'BUY' ? (preview.estimate?.hold ?? '0.00') : '0.00'
    order.reservedQty = req.side === 'SELL' ? (preview.estimate?.qty ?? '0') : '0'
    this.orders.push(order)
    this.emitOrder('ACCEPTED', order, null)
    setTimeout(() => this.activate(order), 650 + Math.random() * 300)
    return json(
      {
        order,
        estimate: preview.estimate,
        buyingPowerBefore: preview.buyingPowerBefore,
        buyingPowerAfter: preview.buyingPowerAfter ?? preview.buyingPowerBefore,
        warnings: preview.warnings,
        replayed: false,
      },
      201,
    )
  }

  private activate(order: Order): void {
    if (!isOpen(order)) return
    order.activatedAt = new Date().toISOString()
    if (order.type === 'MARKET' || this.marketable(order)) {
      this.fill(order)
      return
    }
    order.status = 'WORKING'
    order.waitReason = order.type === 'LIMIT' ? 'LIMIT_NOT_REACHED' : 'NOT_TRIGGERED'
    this.emitOrder('WORKING', order, null)
  }

  private marketable(order: Order): boolean {
    if (order.type !== 'LIMIT' || !order.limitPrice) return false
    const ba = this.market.bidAsk(order.symbol)
    if (!ba) return false
    return order.side === 'BUY' ? compare(order.limitPrice, unitsToDec(ba.ask)) >= 0 : compare(order.limitPrice, unitsToDec(ba.bid)) <= 0
  }

  private sweepRestingLimits(symbol: string): void {
    for (const order of this.orders) {
      if (order.symbol === symbol && order.status === 'WORKING' && this.marketable(order)) this.fill(order)
    }
  }

  private fill(order: Order): void {
    const ba = this.market.bidAsk(order.symbol)
    if (!ba) return
    const touch = unitsToDec(order.side === 'BUY' ? ba.ask : ba.bid)
    const price = order.type === 'LIMIT' && order.limitPrice ? (order.side === 'BUY' ? min(order.limitPrice, touch) : max(order.limitPrice, touch)) : touch
    const qty = order.notional != null ? setScale(div(order.notional, price, 8, 'DOWN'), 5, 'FLOOR') : order.qty!
    const principal = setScale(mul(qty, price), 2, 'HALF_UP')
    const fees = estimateFees(order.side, qty, principal)
    const net = order.side === 'BUY' ? add(principal, fees.total) : sub(principal, fees.total)
    const lot = this.lots.get(order.symbol)
    let realized: Dec | null = null
    if (order.side === 'BUY') {
      this.cash = sub(this.cash, net)
      if (lot) {
        lot.qty = add(lot.qty, qty)
        lot.costBasis = add(lot.costBasis, net)
      } else {
        this.lots.set(order.symbol, { symbol: order.symbol, qty, costBasis: net, openedAt: new Date().toISOString() })
      }
    } else if (lot) {
      this.cash = add(this.cash, net)
      const relieved = setScale(div(mul(lot.costBasis, qty), lot.qty, 4, 'HALF_EVEN'), 2, 'HALF_EVEN')
      realized = sub(net, relieved)
      lot.qty = sub(lot.qty, qty)
      lot.costBasis = sub(lot.costBasis, relieved)
      if (sign(lot.qty) <= 0) this.lots.delete(order.symbol)
    }
    const now = new Date().toISOString()
    const execution: Execution = {
      executionId: publicId('exe'),
      orderId: order.orderId,
      symbol: order.symbol,
      side: order.side,
      qty,
      price,
      principal,
      fees,
      netAmount: net,
      realizedPnl: realized,
      liquidity: order.type === 'LIMIT' && order.status === 'WORKING' ? 'RESTING' : 'TOUCH',
      executedAt: now,
      tradeDate: '2026-10-09',
      settleDate: '2026-10-13',
      refQuote: { bid: unitsToDec(ba.bid), ask: unitsToDec(ba.ask), bidSize: 300, askSize: 200, last: unitsToDec(ba.last), quoteAt: now, confirmedAt: now, source: 'mock', quality: 'SIMULATED', clamped: false },
    }
    this.executions.push(execution)
    Object.assign(order, {
      filledQty: qty,
      filledPrincipal: principal,
      avgFillPrice: setScale(price, 4),
      fees: fees.total,
      qty: order.qty ?? null,
    } satisfies Partial<Order>)
    this.finish(order, 'FILLED', null)
    this.emitOrder('FILL', order, execution)
  }

  private finish(order: Order, status: Order['status'], reason: string | null): void {
    order.status = status
    order.waitReason = null
    order.statusReason = reason
    order.reservedCash = '0.00'
    order.reservedQty = '0'
    order.doneAt = new Date().toISOString()
    if (status === 'CANCELLED') this.emitOrder('CANCELLED', order, null)
    this.publish('/user/queue/account', this.account())
  }

  private emitOrder(event: OrderUpdateEvent['event'], order: Order, execution: Execution | null): void {
    const payload: OrderUpdateEvent = { event, order: { ...order }, execution }
    this.publish('/user/queue/orders', payload)
  }

  // ---------------------------------------------------------------- league

  private leaderboard(params: URLSearchParams): Leaderboard {
    const kind = (params.get('kind') ?? 'ALL').toUpperCase() as Leaderboard['kind']
    const metric = (params.get('metric') ?? 'RETURN').toUpperCase() as Leaderboard['metric']
    const period = (params.get('period') ?? 'WEEK').toUpperCase() as Leaderboard['period']
    const rows = MOCK_PLAYERS.filter((p) => kind === 'ALL' || p.kind === kind)
      .slice()
      .sort((a, b) => compare(metric === 'PNL' ? b.pnl : b.ret, metric === 'PNL' ? a.pnl : a.ret))
      .map((p, i) => {
        const eligible = p.fills >= 5 && p.symbols >= 2
        return {
          rank: eligible ? i + 1 : null,
          rankChange: i === 0 ? 1 : i === 2 ? -1 : 0,
          eligible,
          eligibilityNote: eligible ? null : `${p.fills}/5 trades`,
          partialPeriod: false,
          rebased: false,
          player: { playerId: publicId('ply'), handle: p.handle, displayName: p.displayName, kind: p.kind, aiModel: p.aiModel, avatarSeed: null, memberSince: '2026-10-01T13:00:00.000Z', resets: 0 },
          apiTrader: p.kind === 'AI',
          return: p.ret,
          pnl: p.pnl,
          excessVsBenchmark: sub(p.ret, '0.0080'),
          maxDrawdown: '0.0210',
          equity: add('500.00', p.pnl),
          netContributions: '500.00',
          flowsInPeriod: '0.00',
          fills: p.fills,
          symbolsTraded: p.symbols,
          winRate: '0.5500',
          topHolding: p.top,
          spark: ['1.0000', add('1.0000', div(p.ret, '2', 4, 'HALF_UP')), add('1.0000', p.ret)],
        }
      })
    return {
      period,
      metric,
      kind,
      periodStart: '2026-10-05T08:00:00.000Z',
      asOf: new Date().toISOString(),
      benchmark: { symbol: 'SPY', return: '0.0080' },
      arena: { humansMedianReturn: '0.0031', aiMedianReturn: '0.0260' },
      rows,
      me: rows.find((r) => r.player.handle === 'demo') ?? null,
    }
  }
}

function isOpen(o: Order): boolean {
  return o.status === 'PENDING' || o.status === 'WORKING' || o.status === 'PARTIALLY_FILLED'
}
