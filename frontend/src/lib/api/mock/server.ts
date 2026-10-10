/**
 * In-memory fake of the §18 / §21 REST API for mock mode (§22.9). It answers `Request`s with `Response`s, so the
 * real client code path (openapi-fetch, CSRF, problem mapping) is exercised unchanged, and it publishes the §19
 * user-queue messages through the fake STOMP driver (`realtime.ts`).
 *
 * It models one signed-in account in detail (orders, fills, lots, ledger, unit-value accounting for deposits)
 * plus four other league members as unit-value series, which is enough for every screen: trading, activity,
 * the deposit-aware leaderboard and the whole admin console (players, keys, invites, money, settings, system).
 *
 * NOTE: this is a fake SERVER, so it does arithmetic on money (with the exact decimal helpers). The client
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
  negate,
  setScale,
  sign,
  sub,
  tickFor,
} from '../../format/decimal'
import type {
  Account,
  Activity,
  AdminPlayer,
  ApiKeyCreated,
  Asset,
  AuditRow,
  ChartRange,
  Clock,
  Estimate,
  Execution,
  Fees,
  FeedItem,
  Leaderboard,
  LeaderboardRow,
  Me,
  MoverList,
  Notification,
  Order,
  OrderEvent,
  OrderUpdateEvent,
  PlaceOrderRequest,
  PlayerProfile,
  PlayerSummary,
  Position,
  Preview,
  PublicTrade,
  Run,
  Settings,
  SystemStatus,
  TransferRow,
  Warning,
} from '../types'
import { MOCK_INDEX_SYMBOLS, MOCK_INVITE_CODE, MOCK_ME_BASE, MOCK_PLAYERS, POPULAR_SYMBOLS } from './data'
import { MockMarket, hashString, mulberry32, unitsToDec } from './market'
import { type Lot, type MockOtherPlayer, type MockState, clearState, loadState, saveState } from './state'

type Listener = (destination: string, body: unknown) => void
type Handler = (ctx: Ctx) => Response | Promise<Response>

interface Ctx {
  method: string
  path: string
  url: URL
  body: unknown
  params: string[]
  headers: Headers
}

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const idRng = mulberry32(Date.now() % 100_000)
function publicId(prefix: string): string {
  let s = ''
  for (let i = 0; i < 26; i++) s += CROCKFORD[Math.floor(idRng() * 32)]
  return `${prefix}_${s}`
}
function randomToken(alphabet: string, n: number): string {
  let s = ''
  for (let i = 0; i < n; i++) s += alphabet[Math.floor(Math.random() * alphabet.length)]
  return s
}

interface FieldErr {
  field: string
  code: string
  message: string
}

function problem(status: number, code: string, title: string, detail?: string | null, hint?: string | null, fields: FieldErr[] = []): Response {
  return new Response(
    JSON.stringify({
      type: `https://stocks.gehan.cloud/problems/${code.toLowerCase().replaceAll('_', '-')}`,
      title,
      status,
      code,
      detail: detail ?? null,
      hint: hint ?? null,
      fields,
      retryAfterS: null,
      requestId: publicId('req'),
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const noContent = (status = 204): Response => new Response(null, { status })

const iso = (ms: number = Date.now()): string => new Date(ms).toISOString()
const MIN = 60_000
const RECENT_AUTH_MS = 15 * MIN
const TRADE_DATE = '2026-10-09'
const SETTLE_DATE = '2026-10-13'

/** §13 seed rates (Oct 2026): SEC 0.0000206 × sell principal (CEIL cent), TAF paused, CAT 0.000003/share. */
function estimateFees(side: 'BUY' | 'SELL', qty: Dec, principal: Dec): Fees {
  const sec = side === 'SELL' ? setScale(mul(principal, '0.0000206'), 2, 'CEILING') : '0.00'
  const catExact = mul(qty, '0.000003')
  const cat = lt(catExact, '0.01') ? '0.00' : setScale(catExact, 2, 'HALF_UP')
  return { sec, taf: '0.00', cat, total: add(sec, cat) }
}

function median(xs: Dec[]): Dec | null {
  if (!xs.length) return null
  const sorted = [...xs].sort(compare)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid]! : div(add(sorted[mid - 1]!, sorted[mid]!), '2', 10, 'HALF_EVEN')
}

const floor5 = (d: Dec): Dec => setScale(d, 5, 'FLOOR')
const money = (d: Dec): Dec => setScale(d, 2, 'HALF_EVEN')
const isOpen = (o: Order): boolean => o.status === 'PENDING' || o.status === 'WORKING' || o.status === 'PARTIALLY_FILLED'
const HANDLE_RE = /^[a-z0-9_]{2,32}$/

function defaultSettings(): Settings {
  return {
    'league.defaultStartingCash': '500.00',
    'league.allowSelfReset': false,
    'league.seasonStart': '2026-10-12',
    'league.eligibility.minFills': 5,
    'league.eligibility.minSymbols': 2,
    'social.tradeFeedDelay': 'PT15M',
    'social.holdingsVisibility': 'EOD_WEIGHTS',
    'social.showDepositsInFeed': true,
    'bots.requireRationale': true,
    'league.humanKindExcludesApiTraders': false,
  }
}

export class MockServer {
  readonly market: MockMarket
  private readonly listeners = new Set<Listener>()
  private readonly startedAt = Date.now()
  private loggedIn = true
  private csrfToken: string | null = null
  private s: MockState
  private readonly routes: Array<[string, RegExp, Handler]> = []

  constructor(market = new MockMarket()) {
    this.market = market
    try {
      this.loggedIn = sessionStorage.getItem('stocks.mock.loggedOut') !== '1'
    } catch {
      /* no storage */
    }
    this.s = loadState() ?? this.seed()
    this.registerRoutes()
  }

  // ================================================================ seed

  private seed(): MockState {
    const openedAt = iso(this.startedAt - 9 * 24 * 60 * MIN)
    const s: MockState = {
      v: 2,
      me: { playerId: MOCK_ME_BASE.playerId, handle: 'demo', displayName: 'Demo Player', role: 'ADMIN', accountId: MOCK_ME_BASE.accountId, createdAt: openedAt },
      apiTradingAllowed: true,
      preferences: { theme: 'DARK', upDown: 'GREEN_UP', confirmOrders: true, defaultOrderType: 'LIMIT', defaultTimeInForce: 'DAY', defaultQtyMode: 'SHARES' },
      cash: '500.00',
      nc: '500.00',
      units: '500',
      openedAt,
      dayStartU: '1',
      lots: {},
      orders: [],
      executions: [],
      events: {},
      ledger: [],
      watchlists: [
        { id: 'wlt_01J9Y4N2W1L1ST0000000000AA', name: 'My List', sortOrder: 0, symbols: [...POPULAR_SYMBOLS, 'META', 'AMD'] },
        { id: 'wlt_01J9Y4N2W1L1ST0000000000AB', name: 'Chips', sortOrder: 1, symbols: ['NVDA', 'AMD', 'SIMA'] },
      ],
      others: MOCK_PLAYERS.map((p, i) => ({
        playerId: `ply_01J9Y4N2QK7D3X8M5R6T1V0W${String.fromCharCode(65 + i)}${i}`,
        handle: p.handle,
        displayName: p.displayName,
        kind: p.kind,
        aiModel: p.aiModel,
        role: 'PLAYER',
        status: 'ACTIVE',
        ownerHandle: p.owner,
        accountId: `acc_01J9Y4N2R0S8F6E2W3C9H7K5P${i}`,
        u: p.u,
        units: '500',
        cash: money(mul('500', sub('1', mul('0.6', sub(p.u, '0.9'))))),
        nc: p.nc,
        fills: p.fills,
        symbols: p.symbols,
        top: p.top,
        apiTradingAllowed: p.kind === 'AI',
        resets: 0,
        createdAt: openedAt,
        lastSeenAt: iso(this.startedAt - (i + 1) * 37 * MIN),
      })),
      transfers: [],
      invites: [
        { inviteId: 'inv_01J9Y4N2WELCOME000000000AA', code: MOCK_INVITE_CODE, uses: 0, maxUses: 50, expiresAt: iso(this.startedAt + 30 * 24 * 60 * MIN), revokedAt: null },
      ],
      keys: [],
      settings: defaultSettings(),
      notifications: [],
      audit: [],
      idempotency: {},
      engineHalted: null,
      reauthAt: this.startedAt,
      seq: 1,
    }
    this.s = s
    // Initial funding and transfers (INITIAL, §16.2)
    s.ledger.push(this.ledgerRow({ at: openedAt, type: 'INITIAL_FUNDING', amount: '500.00', balanceAfter: '500.00', memo: 'Starting cash', by: 'SYSTEM' }))
    for (const p of s.others) {
      s.transfers.push(this.transferRow(p.handle, p.accountId, 'INITIAL', '500.00', 'Starting cash', openedAt, null, '0.00', '1'))
    }
    s.transfers.push(this.transferRow('demo', s.me.accountId, 'INITIAL', '500.00', 'Starting cash', openedAt, null, '0.00', '1'))
    // §18.4: the notional buy of $125.00 NVDA, filled 45 minutes ago.
    const filledAt = iso(this.startedAt - 45 * MIN)
    const order = this.newOrder({ clientOrderId: 'web-seed-0001', symbol: 'NVDA', side: 'BUY', type: 'MARKET', notional: '125.00', timeInForce: 'DAY' }, filledAt)
    s.orders.push(order)
    this.pushEvent(order, 'ACCEPTED', filledAt)
    this.pushEvent(order, 'WORKING', filledAt)
    this.fillAt(order, '131.2000', '0.95274', filledAt, 'TOUCH')
    s.dayStartU = this.unitValue()
    // Two bot keys, as the admin would have issued them.
    for (const p of s.others.filter((o) => o.kind === 'AI')) {
      s.keys.push({
        keyId: randomToken('abcdefghijklmnopqrstuvwxyz234567', 12),
        handle: p.handle,
        name: `${p.displayName} agent runner`,
        scopes: ['market:read', 'account:read', 'trade', 'league:read'],
        createdAt: openedAt,
        expiresAt: null,
        lastUsedAt: iso(this.startedAt - 12 * MIN),
        lastUsedIp: '172.18.0.7',
        revokedAt: null,
      })
    }
    this.audit('AUTH', 'LOGIN', 'OK', 'demo', null, null)
    return s
  }


  private persist(): void {
    saveState(this.s)
  }

  // ================================================================ events (fake STOMP broker)

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

  // ================================================================ HTTP

  async handle(request: Request): Promise<Response> {
    const url = new URL(request.url)
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = request.method.toUpperCase()
    await new Promise((r) => setTimeout(r, 40 + Math.random() * 80)) // a little latency

    if (method === 'GET' && path === '/auth/csrf') {
      this.csrfToken = publicId('csrf')
      document.cookie = `XSRF-TOKEN=${this.csrfToken}; path=/; SameSite=Lax`
      return noContent()
    }
    if (method !== 'GET' && (!this.csrfToken || request.headers.get('X-XSRF-TOKEN') !== this.csrfToken)) {
      return problem(403, 'CSRF_INVALID', 'Forbidden', 'Missing or invalid CSRF token.')
    }
    const body: unknown = method === 'GET' || method === 'DELETE' ? null : await request.json().catch(() => null)
    const ctx: Ctx = { method, path, url, body, params: [], headers: request.headers }

    if (method === 'POST' && path === '/auth/login') return this.login(body)
    if (method === 'POST' && path === '/auth/signup') return this.signup(body)
    if (!this.loggedIn) return problem(401, 'UNAUTHENTICATED', 'Unauthenticated', 'Sign in to continue.')

    for (const [m, re, handler] of this.routes) {
      if (m !== method) continue
      const match = re.exec(path)
      if (!match) continue
      ctx.params = match.slice(1).map((p) => decodeURIComponent(p))
      const res = await handler(ctx)
      if (method !== 'GET') this.persist()
      return res
    }
    return problem(501, 'NOT_IMPLEMENTED', 'Not implemented in mock mode', `${method} ${url.pathname}`)
  }

  private route(method: string, pattern: string, handler: Handler): void {
    const re = new RegExp(`^${pattern.replace(/\{[a-zA-Z]+\}/g, '([^/]+)')}$`)
    this.routes.push([method, re, handler])
  }

  private registerRoutes(): void {
    const r = this.route.bind(this)
    // identity
    r('POST', '/auth/logout', () => {
      this.audit('AUTH', 'LOGOUT', 'OK', this.s.me.handle, null, null)
      this.setLoggedIn(false)
      return noContent()
    })
    r('POST', '/auth/reauth', ({ body }) => {
      const pw = (body as { password?: string } | null)?.password
      if (!pw || pw === 'wrong') return problem(401, 'BAD_CREDENTIALS', 'Bad credentials', 'That password is not right.', 'Mock mode accepts any password except "wrong".')
      this.s.reauthAt = Date.now()
      return noContent()
    })
    r('GET', '/me', () => json(this.me()))
    r('PATCH', '/me', ({ body }) => {
      const b = (body ?? {}) as { displayName?: string | null; preferences?: Record<string, unknown> | null }
      if (b.displayName) this.s.me.displayName = b.displayName.slice(0, 64)
      if (b.preferences) {
        const clean = Object.fromEntries(Object.entries(b.preferences).filter(([, v]) => v !== null && v !== undefined))
        this.s.preferences = { ...this.s.preferences, ...clean }
      }
      return json(this.me())
    })
    r('POST', '/me/password', ({ body }) => {
      const b = (body ?? {}) as { currentPassword?: string; newPassword?: string }
      if (!b.currentPassword || b.currentPassword === 'wrong') return problem(401, 'BAD_CREDENTIALS', 'Bad credentials', 'Your current password is not right.')
      if (!b.newPassword || b.newPassword.length < 10) return problem(422, 'WEAK_PASSWORD', 'Weak password', 'Use at least 10 characters.')
      for (const k of this.s.keys) if (k.handle === this.s.me.handle && !k.revokedAt) k.revokedAt = iso()
      return noContent()
    })
    r('GET', '/me/api-keys', () => json(this.s.keys.filter((k) => k.handle === this.s.me.handle).map(({ handle: _h, ...k }) => k)))
    r('POST', '/me/api-keys', ({ body }) => {
      const stale = this.requireRecentAuth()
      if (stale) return stale
      const b = (body ?? {}) as { name?: string; scopes?: string[] | null; expiresInDays?: number | null }
      if (!b.name?.trim()) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'Name the key.', null, [{ field: 'name', code: 'NotBlank', message: 'Name the key.' }])
      const scopes = b.scopes?.length ? b.scopes : ['market:read', 'account:read', 'league:read']
      if (scopes.includes('trade') && !this.s.apiTradingAllowed) return problem(403, 'FORBIDDEN_SCOPE', 'Trade scope not allowed', 'The admin has not enabled API trading for you.')
      return json(this.issueKey(this.s.me.handle, b.name.trim(), scopes, b.expiresInDays ?? null), 201)
    })
    r('DELETE', '/me/api-keys/{keyId}', ({ params }) => {
      const k = this.s.keys.find((x) => x.keyId === params[0] && x.handle === this.s.me.handle)
      if (!k) return problem(404, 'NOT_FOUND', 'Not found')
      k.revokedAt ??= iso()
      return noContent()
    })
    r('GET', '/me/notifications', ({ url }) => {
      const unread = url.searchParams.get('unreadOnly') === 'true'
      const items = this.s.notifications.filter((n) => !unread || !n.readAt).toReversed()
      return json({ items: items.slice(0, Number(url.searchParams.get('limit') ?? 30)), nextCursor: null })
    })
    r('POST', '/me/notifications/read', ({ body }) => {
      const b = (body ?? {}) as { ids?: string[] | null; all?: boolean | null }
      const now = iso()
      for (const n of this.s.notifications) if (b.all || (n.id && b.ids?.includes(n.id))) n.readAt ??= now
      return noContent()
    })

    // market
    r('GET', '/market/clock', () => json(this.clock()))
    r('GET', '/market/indices', () => json(MOCK_INDEX_SYMBOLS.map((s) => this.market.quote(s))))
    r('GET', '/market/movers', ({ url }) => {
      const list = (url.searchParams.get('list') ?? 'GAINERS').toUpperCase() as MoverList
      const limit = Math.min(50, Number(url.searchParams.get('limit') ?? 20))
      return json({ list, asOf: iso(), source: 'mock', items: this.market.movers(list, limit) })
    })
    r('GET', '/quotes', ({ url }) => {
      const symbols = (url.searchParams.get('symbols') ?? '').split(',').map((s) => s.trim().toUpperCase()).filter(Boolean)
      return json({ quotes: symbols.slice(0, 50).map((s) => this.market.quote(s)) })
    })
    r('GET', '/assets/search', ({ url }) => {
      const q = (url.searchParams.get('q') ?? '').trim().toUpperCase()
      const limit = Number(url.searchParams.get('limit') ?? 20)
      const hits = this.market
        .symbols()
        .map((s) => this.asset(s)!)
        .filter((a) => q !== '' && (a.symbol!.startsWith(q) || (a.name ?? '').toUpperCase().includes(q)))
        .sort((a, b) => Number(!a.symbol!.startsWith(q)) - Number(!b.symbol!.startsWith(q)) || a.symbol!.length - b.symbol!.length)
        .slice(0, limit)
      return json(hits.map(({ symbol, name, exchange, type, tradable, fractionable }) => ({ symbol, name, exchange, type, tradable, fractionable })))
    })
    r('GET', '/assets/{symbol}', ({ params }) => {
      const a = this.asset(params[0]!.toUpperCase())
      return a ? json(a) : problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found', `Unknown symbol ${params[0]}.`)
    })
    r('GET', '/bars/{symbol}', ({ params, url }) => {
      const symbol = params[0]!.toUpperCase()
      if (!this.market.has(symbol)) return problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found', `Unknown symbol ${symbol}.`)
      const range = (url.searchParams.get('range') ?? '1D').toUpperCase() as ChartRange
      return json(this.market.bars(symbol, range))
    })
    r('GET', '/news', ({ url }) => json({ items: this.news(url.searchParams.get('symbols') ?? '') }))

    // account
    r('GET', '/account', () => json(this.account()))
    r('GET', '/account/positions', () => json(this.positions()))
    r('GET', '/account/positions/{symbol}', ({ params }) => {
      const p = this.positions().find((x) => x.symbol === params[0]!.toUpperCase())
      return p ? json(p) : problem(404, 'NOT_FOUND', 'No position')
    })
    r('POST', '/account/positions/{symbol}/close', ({ params, body }) => {
      const symbol = params[0]!.toUpperCase()
      const p = this.positions().find((x) => x.symbol === symbol)
      if (!p || !p.availableQty || sign(p.availableQty) <= 0) return problem(422, 'SHORT_SELLING_NOT_ALLOWED', 'No position', `You have no ${symbol} shares available to sell.`)
      const clientOrderId = (body as { clientOrderId?: string } | null)?.clientOrderId ?? null
      return this.place({ clientOrderId, symbol, side: 'SELL', type: 'MARKET', qty: p.availableQty, timeInForce: 'DAY' })
    })
    r('GET', '/account/activities', ({ url }) => {
      const types = (url.searchParams.get('types') ?? '').split(',').map((t) => t.trim().toUpperCase()).filter(Boolean)
      const rows = this.s.ledger.filter((a) => types.length === 0 || (a.type && types.includes(a.type))).toReversed()
      return json(this.page(rows, url))
    })
    r('GET', '/account/history', ({ url }) => json(this.history(url.searchParams.get('range') ?? '1M')))
    r('GET', '/account/runs', () => json([this.run()]))
    r('POST', '/account/reset', () => {
      if (!this.s.settings['league.allowSelfReset']) return problem(422, 'SELF_RESET_DISABLED', 'Self-reset disabled', 'The admin has turned self-reset off.')
      this.resetMe(this.s.settings['league.defaultStartingCash'] ?? '500.00', 'Self reset')
      return json(this.account())
    })

    // orders
    r('GET', '/orders', ({ url }) => {
      const status = (url.searchParams.get('status') ?? 'ALL').toUpperCase()
      const symbol = url.searchParams.get('symbol')?.toUpperCase()
      const items = this.s.orders
        .filter((o) => status === 'ALL' || (status === 'OPEN') === isOpen(o))
        .filter((o) => !symbol || o.symbol === symbol)
        .toReversed()
      return json(this.page(items, url))
    })
    r('DELETE', '/orders', ({ url }) => {
      const symbol = url.searchParams.get('symbol')?.toUpperCase()
      const cancelled = this.s.orders.filter((o) => isOpen(o) && (!symbol || o.symbol === symbol))
      for (const o of cancelled) this.finish(o, 'CANCELLED', 'USER')
      return json({ cancelled })
    })
    r('POST', '/orders/preview', ({ body }) => json(this.preview(body as PlaceOrderRequest)))
    r('POST', '/orders', ({ body }) => this.place(body as PlaceOrderRequest))
    r('GET', '/orders/{orderId}', ({ params }) => {
      const order = this.s.orders.find((o) => o.orderId === params[0])
      if (!order) return problem(404, 'ORDER_NOT_FOUND', 'Order not found')
      return json(this.detail(order))
    })
    r('DELETE', '/orders/{orderId}', ({ params }) => this.cancel(params[0]!, 'USER'))

    // watchlists
    r('GET', '/watchlists', () => json(this.s.watchlists))
    r('POST', '/watchlists', ({ body }) => {
      const b = (body ?? {}) as { name?: string; symbols?: string[] | null }
      const name = b.name?.trim()
      if (!name) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'Name the list.', null, [{ field: 'name', code: 'NotBlank', message: 'Name the list.' }])
      if (this.s.watchlists.length >= 20) return problem(422, 'VALIDATION_FAILED', 'Too many lists', 'You can have up to 20 lists.')
      const symbols = [...new Set((b.symbols ?? []).map((x) => x.toUpperCase()))]
      const bad = symbols.find((x) => !this.market.has(x))
      if (bad) return problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found', `Unknown symbol ${bad}.`)
      const list = { id: publicId('wlt'), name: name.slice(0, 40), sortOrder: this.s.watchlists.length, symbols }
      this.s.watchlists.push(list)
      return json(list, 201)
    })
    r('PUT', '/watchlists/{id}', ({ params, body }) => {
      const list = this.s.watchlists.find((w) => w.id === params[0])
      if (!list) return problem(404, 'NOT_FOUND', 'Watchlist not found')
      const b = (body ?? {}) as { name?: string; symbols?: string[] }
      if (!b.name?.trim() || !Array.isArray(b.symbols)) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'A name and the full symbol list are required.')
      const symbols = [...new Set(b.symbols.map((x) => x.toUpperCase()))]
      const bad = symbols.find((x) => !this.market.has(x))
      if (bad) return problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found', `Unknown symbol ${bad}.`)
      list.name = b.name.trim().slice(0, 40)
      list.symbols = symbols
      return json(list)
    })
    r('DELETE', '/watchlists/{id}', ({ params }) => {
      const at = this.s.watchlists.findIndex((w) => w.id === params[0])
      if (at < 0) return problem(404, 'NOT_FOUND', 'Watchlist not found')
      this.s.watchlists.splice(at, 1)
      this.s.watchlists.forEach((w, i) => (w.sortOrder = i))
      return noContent()
    })
    r('POST', '/watchlists/{id}/symbols', ({ params, body }) => {
      const list = this.s.watchlists.find((w) => w.id === params[0])
      const symbol = String((body as { symbol?: unknown } | null)?.symbol ?? '').toUpperCase()
      if (!list) return problem(404, 'NOT_FOUND', 'Watchlist not found')
      if (!this.market.has(symbol)) return problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found', `Unknown symbol ${symbol}.`)
      list.symbols ??= []
      if (!list.symbols.includes(symbol)) list.symbols.push(symbol)
      return json(list)
    })
    r('DELETE', '/watchlists/{id}/symbols/{symbol}', ({ params }) => {
      const list = this.s.watchlists.find((w) => w.id === params[0])
      if (!list) return problem(404, 'NOT_FOUND', 'Watchlist not found')
      list.symbols = (list.symbols ?? []).filter((x) => x !== params[1]!.toUpperCase())
      return json(list)
    })

    // league
    r('GET', '/leaderboard', ({ url }) => json(this.leaderboard(url.searchParams)))
    r('GET', '/players', () => json([this.mySummary(), ...this.s.others.filter((p) => p.status !== 'DISABLED').map((p) => this.summary(p))]))
    r('GET', '/players/{handle}', ({ params }) => {
      const prof = this.profile(params[0]!.toLowerCase())
      return prof ? json(prof) : problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player @${params[0]}.`)
    })
    r('GET', '/players/{handle}/history', ({ params, url }) => {
      const h = params[0]!.toLowerCase()
      const u = h === this.s.me.handle ? this.unitValue() : this.other(h)?.u
      if (!u) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found')
      const range = url.searchParams.get('range') ?? '1M'
      const points = this.walk(`${h}:${range}`, u, range).map(({ t, v }) => ({ t, unitValue: v, equity: null }))
      return json({ range, points })
    })
    r('GET', '/players/{handle}/trades', ({ params, url }) => {
      const h = params[0]!.toLowerCase()
      if (h !== this.s.me.handle && !this.other(h)) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found')
      return json(this.page(this.publicTrades(h), url))
    })
    r('GET', '/feed', ({ url }) => json(this.page(this.feed(), url)))

    // admin (§21.1): ADMIN role; R routes need a recent password confirmation
    r('GET', '/admin/players', () => this.admin(() => json(this.adminPlayers())))
    r('POST', '/admin/players', ({ body }) => this.admin(() => this.createPlayer(body), true))
    r('GET', '/admin/players/{handle}', ({ params }) => this.admin(() => this.adminDetail(params[0]!.toLowerCase())))
    r('PATCH', '/admin/players/{handle}', ({ params, body }) => this.admin(() => this.patchPlayer(params[0]!.toLowerCase(), body), true))
    r('POST', '/admin/players/{handle}/freeze', ({ params, body }) =>
      this.admin(() => this.setStatus(params[0]!.toLowerCase(), 'FROZEN', body as { reason?: string; revokeKeys?: boolean }), true),
    )
    r('POST', '/admin/players/{handle}/unfreeze', ({ params }) => this.admin(() => this.setStatus(params[0]!.toLowerCase(), 'ACTIVE', null), true))
    r('POST', '/admin/players/{handle}/password', ({ params, body }) =>
      this.admin(() => {
        const pw = (body as { newPassword?: string } | null)?.newPassword ?? ''
        if (pw.length < 10) return problem(422, 'WEAK_PASSWORD', 'Weak password', 'Use at least 10 characters.')
        this.audit('ADMIN', 'PLAYER_PASSWORD', 'OK', this.s.me.handle, 'player', params[0]!)
        return noContent()
      }, true),
    )
    r('POST', '/admin/players/{handle}/reset', ({ params, body }) => this.admin(() => this.resetPlayer(params[0]!.toLowerCase(), body), true))
    r('POST', '/admin/players/{handle}/live-view', ({ params, body }) => this.admin(() => this.liveView(params[0]!.toLowerCase(), body), true))
    r('GET', '/admin/players/{handle}/api-keys', ({ params }) =>
      this.admin(() => json(this.s.keys.filter((k) => k.handle === params[0]!.toLowerCase()).map(({ handle: _h, ...k }) => k))),
    )
    r('POST', '/admin/players/{handle}/api-keys', ({ params, body }) =>
      this.admin(() => {
        const h = params[0]!.toLowerCase()
        if (h !== this.s.me.handle && !this.other(h)) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found')
        const b = (body ?? {}) as { name?: string; scopes?: string[] | null; expiresInDays?: number | null }
        if (!b.name?.trim()) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'Name the key.', null, [{ field: 'name', code: 'NotBlank', message: 'Name the key.' }])
        const scopes = b.scopes?.length ? b.scopes : ['market:read', 'account:read', 'trade', 'league:read']
        this.audit('ADMIN', 'API_KEY_ISSUE', 'OK', this.s.me.handle, 'player', h)
        return json(this.issueKey(h, b.name.trim(), scopes, b.expiresInDays ?? null), 201)
      }, true),
    )
    r('DELETE', '/admin/api-keys/{keyId}', ({ params }) =>
      this.admin(() => {
        const k = this.s.keys.find((x) => x.keyId === params[0])
        if (!k) return problem(404, 'NOT_FOUND', 'Key not found')
        k.revokedAt ??= iso()
        this.audit('ADMIN', 'API_KEY_REVOKE', 'OK', this.s.me.handle, 'api_key', k.keyId)
        return noContent()
      }),
    )
    r('POST', '/admin/cash-transfers/preview', ({ body }) => this.admin(() => this.transferPreview(body)))
    r('POST', '/admin/cash-transfers', ({ body, headers }) => this.admin(() => this.applyTransfer(body, headers.get('Idempotency-Key')), true))
    r('GET', '/admin/cash-transfers', ({ url }) =>
      this.admin(() => {
        const handle = url.searchParams.get('handle')?.toLowerCase()
        const rows = this.s.transfers.filter((t) => !handle || t.handle === handle).toReversed()
        return json(this.page(rows, url))
      }),
    )
    r('GET', '/admin/settings', () => this.admin(() => json(this.s.settings)))
    r('PUT', '/admin/settings', ({ body }) => this.admin(() => this.putSettings(body), true))
    r('GET', '/admin/invites', () => this.admin(() => json(this.s.invites.map(({ code: _c, ...i }) => i).toReversed())))
    r('POST', '/admin/invites', ({ body }) =>
      this.admin(() => {
        const b = (body ?? {}) as { maxUses?: number; expiresInHours?: number }
        const maxUses = Number(b.maxUses)
        const hours = Number(b.expiresInHours)
        const fields: FieldErr[] = []
        if (!Number.isInteger(maxUses) || maxUses < 1 || maxUses > 50) fields.push({ field: 'maxUses', code: 'Range', message: 'Between 1 and 50.' })
        if (!Number.isInteger(hours) || hours < 1 || hours > 720) fields.push({ field: 'expiresInHours', code: 'Range', message: 'Between 1 and 720 hours.' })
        if (fields.length) return problem(400, 'VALIDATION_FAILED', 'Validation failed', fields[0]!.message, null, fields)
        const code = randomToken('abcdefghijkmnpqrstuvwxyz23456789', 10)
        const invite = { inviteId: publicId('inv'), code, uses: 0, maxUses, expiresAt: iso(Date.now() + hours * 60 * MIN), revokedAt: null }
        this.s.invites.push(invite)
        this.audit('ADMIN', 'INVITE_CREATE', 'OK', this.s.me.handle, 'invite', invite.inviteId)
        return json({ inviteId: invite.inviteId, code, url: `${location.origin}/join/${code}`, expiresAt: invite.expiresAt }, 201)
      }),
    )
    r('DELETE', '/admin/invites/{inviteId}', ({ params }) =>
      this.admin(() => {
        const inv = this.s.invites.find((i) => i.inviteId === params[0])
        if (!inv) return problem(404, 'NOT_FOUND', 'Invite not found')
        inv.revokedAt ??= iso()
        return noContent()
      }),
    )
    r('GET', '/admin/audit', ({ url }) =>
      this.admin(() => {
        const cat = url.searchParams.get('category')?.toUpperCase()
        const actor = url.searchParams.get('actor')?.toLowerCase()
        const action = url.searchParams.get('action')?.toUpperCase()
        const rows = this.s.audit
          .filter((a) => (!cat || a.category === cat) && (!actor || a.actor === actor) && (!action || (a.action ?? '').includes(action)))
          .toReversed()
        return json(this.page(rows, url))
      }),
    )
    r('GET', '/admin/system', () => this.admin(() => json(this.system())))
    r('POST', '/admin/system/jobs/{jobName}/run', ({ params }) =>
      this.admin(() => {
        this.audit('ADMIN', 'JOB_RUN', 'OK', this.s.me.handle, 'job', params[0]!)
        return noContent(202)
      }, true),
    )
    r('POST', '/admin/engine/halt', ({ body }) =>
      this.admin(() => {
        const reason = (body as { reason?: string } | null)?.reason?.trim()
        if (!reason) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'Give a reason.')
        this.s.engineHalted = reason
        this.audit('ADMIN', 'ENGINE_HALT', 'OK', this.s.me.handle, 'engine', null)
        return json(this.system())
      }, true),
    )
    r('POST', '/admin/engine/resume', () =>
      this.admin(() => {
        this.s.engineHalted = null
        this.audit('ADMIN', 'ENGINE_RESUME', 'OK', this.s.me.handle, 'engine', null)
        return json(this.system())
      }, true),
    )
    r('DELETE', '/admin/orders/{orderId}', ({ params }) => this.admin(() => this.cancel(params[0]!, 'ADMIN'), true))
  }

  /** ADMIN-only guard; `recent` marks the §21.1 R routes (`@RecentAuth`, 15 min). */
  private admin(fn: () => Response | Promise<Response>, recent = false): Response | Promise<Response> {
    if (this.s.me.role !== 'ADMIN') return problem(403, 'FORBIDDEN', 'Forbidden', 'Admins only.')
    if (recent) {
      const stale = this.requireRecentAuth()
      if (stale) return stale
    }
    return fn()
  }

  private requireRecentAuth(): Response | null {
    if (Date.now() - this.s.reauthAt <= RECENT_AUTH_MS) return null
    return problem(403, 'RECENT_AUTH_REQUIRED', 'Recent authentication required', 'Confirm your password to continue.')
  }

  private page<T>(rows: T[], url: URL): { items: T[]; nextCursor: string | null } {
    const limit = Math.max(1, Math.min(200, Number(url.searchParams.get('limit') ?? 50)))
    const start = Number(url.searchParams.get('cursor') ?? 0) || 0
    const items = rows.slice(start, start + limit)
    return { items, nextCursor: start + limit < rows.length ? String(start + limit) : null }
  }

  private audit(category: string, action: string, outcome: string, actor: string | null, targetType: string | null, targetId: string | null, detail: Record<string, unknown> | null = null): void {
    const row: AuditRow = {
      id: this.s.audit.length + 1,
      occurredAt: iso(),
      category,
      action,
      outcome,
      actor,
      actorKind: actor ? 'HUMAN' : null,
      channel: 'WEB',
      apiKeyId: null,
      mcpClient: null,
      mcpTool: null,
      ip: '127.0.0.1',
      requestId: publicId('req'),
      targetType,
      targetId,
      detail,
    }
    this.s.audit.push(row)
    if (this.s.audit.length > 500) this.s.audit.splice(0, this.s.audit.length - 500)
  }

  // ================================================================ identity

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
    const h = handle?.trim().toLowerCase()
    if (!h || !password || password === 'wrong') {
      this.audit('AUTH', 'LOGIN_FAIL', 'REJECTED', h ?? null, null, null)
      this.persist()
      return problem(401, 'BAD_CREDENTIALS', 'Bad credentials', 'Handle or password is incorrect.', 'Mock mode accepts any password except "wrong".')
    }
    if (this.other(h)?.status === 'DISABLED') return problem(403, 'PLAYER_DISABLED', 'Player disabled', 'This account is disabled.')
    // The mock has one detailed account; signing in as any handle uses it (an admin, so every screen is reachable).
    if (h !== this.s.me.handle && !this.other(h)) {
      this.s.me.handle = h
      this.s.me.displayName = h.charAt(0).toUpperCase() + h.slice(1)
    }
    this.setLoggedIn(true)
    this.s.reauthAt = Date.now()
    this.audit('AUTH', 'LOGIN', 'OK', h, null, null)
    this.persist()
    return json(this.me())
  }

  private signup(body: unknown): Response {
    const b = (body ?? {}) as { inviteCode?: string; handle?: string; displayName?: string; password?: string }
    const code = b.inviteCode?.trim() ?? ''
    const invite = this.s.invites.find((i) => i.code === code)
    if (!invite || invite.revokedAt || invite.uses >= invite.maxUses || (invite.expiresAt && Date.parse(invite.expiresAt) < Date.now())) {
      return problem(422, 'INVITE_INVALID', 'Invite invalid', 'This invite link is invalid, used up or expired.', 'Ask the league admin for a new link.')
    }
    const handle = b.handle?.trim().toLowerCase() ?? ''
    const fields: FieldErr[] = []
    if (!HANDLE_RE.test(handle)) fields.push({ field: 'handle', code: 'Pattern', message: '2–32 lowercase letters, digits or _.' })
    if (!b.displayName?.trim()) fields.push({ field: 'displayName', code: 'NotBlank', message: 'Enter a display name.' })
    if (fields.length) return problem(400, 'VALIDATION_FAILED', 'Validation failed', fields[0]!.message, null, fields)
    if (handle === this.s.me.handle || this.other(handle)) {
      return problem(409, 'HANDLE_TAKEN', 'Handle taken', `@${handle} is already taken.`, null, [{ field: 'handle', code: 'HANDLE_TAKEN', message: `@${handle} is already taken.` }])
    }
    if (!b.password || b.password.length < 10) {
      return problem(422, 'WEAK_PASSWORD', 'Weak password', 'Use at least 10 characters.', null, [{ field: 'password', code: 'WEAK_PASSWORD', message: 'Use at least 10 characters.' }])
    }
    invite.uses += 1
    // The previous identity becomes a regular league member; the new player gets a fresh $500 account.
    const prevU = this.unitValue()
    this.s.others.push({
      playerId: this.s.me.playerId,
      handle: this.s.me.handle,
      displayName: this.s.me.displayName,
      kind: 'HUMAN',
      aiModel: null,
      role: this.s.me.role,
      status: 'ACTIVE',
      ownerHandle: null,
      accountId: this.s.me.accountId,
      u: prevU,
      units: this.s.units,
      cash: this.s.cash,
      nc: this.s.nc,
      fills: this.s.executions.length,
      symbols: new Set(this.s.executions.map((e) => e.symbol)).size,
      top: Object.keys(this.s.lots)[0] ?? null,
      apiTradingAllowed: this.s.apiTradingAllowed,
      resets: 0,
      createdAt: this.s.me.createdAt,
      lastSeenAt: iso(),
    })
    const now = iso()
    this.s.me = { playerId: publicId('ply'), handle, displayName: b.displayName!.trim().slice(0, 64), role: 'PLAYER', accountId: publicId('acc'), createdAt: now }
    const starting = this.s.settings['league.defaultStartingCash'] ?? '500.00'
    this.freshAccount(starting, now)
    this.s.watchlists = [{ id: publicId('wlt'), name: 'My List', sortOrder: 0, symbols: [...POPULAR_SYMBOLS] }]
    this.s.apiTradingAllowed = false
    this.s.notifications = []
    this.s.transfers.push(this.transferRow(handle, this.s.me.accountId, 'INITIAL', starting, 'Starting cash', now, null, '0.00', '1'))
    this.setLoggedIn(true)
    this.s.reauthAt = Date.now()
    this.audit('AUTH', 'SIGNUP', 'OK', handle, 'invite', invite.inviteId)
    this.persist()
    return json(this.me(), 201)
  }

  private freshAccount(starting: Dec, at: string): void {
    this.s.cash = starting
    this.s.nc = starting
    this.s.units = starting
    this.s.dayStartU = '1'
    this.s.openedAt = at
    this.s.lots = {}
    this.s.orders = []
    this.s.executions = []
    this.s.events = {}
    this.s.ledger = [this.ledgerRow({ at, type: 'INITIAL_FUNDING', amount: starting, balanceAfter: starting, memo: 'Starting cash', by: 'SYSTEM' })]
  }

  private me(): Me {
    return {
      ...MOCK_ME_BASE,
      playerId: this.s.me.playerId,
      handle: this.s.me.handle,
      displayName: this.s.me.displayName,
      role: this.s.me.role,
      accountId: this.s.me.accountId,
      createdAt: this.s.me.createdAt,
      preferences: this.s.preferences,
    }
  }

  private issueKey(handle: string, name: string, scopes: string[], expiresInDays: number | null): ApiKeyCreated {
    const keyId = randomToken('abcdefghijklmnopqrstuvwxyz234567', 12)
    const secret = randomToken('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_', 43)
    const token = `stk_${keyId}_${secret}`
    const info = {
      keyId,
      name,
      scopes,
      createdAt: iso(),
      expiresAt: expiresInDays ? iso(Date.now() + expiresInDays * 24 * 60 * MIN) : null,
      lastUsedAt: null,
      lastUsedIp: null,
      revokedAt: null,
    }
    this.s.keys.push({ ...info, handle })
    const origin = location.origin
    return {
      key: info,
      token,
      snippets: {
        claudeCode: `claude mcp add --transport http stocks ${origin}/mcp --header "Authorization: Bearer ${token}"`,
        claudeApi: `"mcp_servers": [{ "type": "url", "url": "${origin}/mcp", "name": "stocks", "authorization_token": "${token}" }]`,
        openaiResponses: `"tools": [{ "type": "mcp", "server_label": "stocks", "server_url": "${origin}/mcp", "headers": { "Authorization": "Bearer ${token}" }, "require_approval": "never" }]`,
        curl: `curl -H "Authorization: Bearer ${token}" ${origin}/api/v1/account`,
      },
    }
  }

  private clock(): Clock {
    const now = Date.now()
    // A friendly fixed countdown: the mock session "closes" 2:14:07 after the page loaded.
    const endsAt = new Date(this.startedAt + ((2 * 60 + 14) * 60 + 7) * 1000)
    return {
      now: iso(now),
      nowEt: new Date(now).toLocaleString('en-US', { timeZone: 'America/New_York' }),
      session: 'REGULAR',
      tradingDate: TRADE_DATE,
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

  // ================================================================ market

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
      liquidityTier: meta.vol > 2 ? 'SMALL' : meta.adv > 10_000_000 ? 'MEGA' : 'MID',
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

  private news(symbols: string) {
    const list = symbols ? symbols.split(',').map((s) => s.trim().toUpperCase()).filter((s) => this.market.has(s)) : ['SPY', 'NVDA', 'AAPL']
    return list.slice(0, 3).flatMap((symbol, i) => {
      const name = this.market.meta(symbol)?.name ?? symbol
      return [0, 1].map((k) => ({
        id: `news_${symbol}_${k}`,
        publishedAt: iso(this.startedAt - (i * 2 + k + 1) * 47 * MIN),
        source: 'Mock Wire',
        url: 'https://example.com/mock-news',
        symbols: [symbol],
        imageUrl: null,
        untrusted: {
          headline: k === 0 ? `${name} shares move as traders weigh the week ahead (mock)` : `What to watch in ${symbol} before the close (mock)`,
          summary: 'Sample headline from mock mode. Real headlines come from the market-data news source when it is available.',
        },
      }))
    })
  }

  // ================================================================ account

  private mark(symbol: string): Dec {
    return unitsToDec(this.market.bidAsk(symbol)?.last ?? 0)
  }

  private longValue(): Dec {
    return Object.values(this.s.lots).reduce((acc, lot) => add(acc, money(mul(lot.qty, this.mark(lot.symbol)))), '0.00')
  }

  private equity(): Dec {
    return add(this.s.cash, this.longValue())
  }

  /** U = E / units (§16.2), scale 10 for display. */
  private unitValue(): Dec {
    return isZero(this.s.units) ? '1' : div(this.equity(), this.s.units, 10, 'HALF_EVEN')
  }

  private positions(): Position[] {
    const lots = Object.values(this.s.lots)
    const equity = this.equity()
    return lots.map((lot) => {
      const quote = this.market.quote(lot.symbol)
      const mark = this.mark(lot.symbol)
      const marketValue = money(mul(lot.qty, mark))
      const unrealized = sub(marketValue, lot.costBasis)
      const reserved = this.s.orders.filter((o) => isOpen(o) && o.side === 'SELL' && o.symbol === lot.symbol).reduce((acc, o) => add(acc, o.reservedQty ?? '0'), '0')
      const prev = quote.prevClose ?? mark
      const dayPnl = money(mul(lot.qty, sub(mark, prev)))
      return {
        symbol: lot.symbol,
        name: this.market.meta(lot.symbol)?.name ?? lot.symbol,
        qty: lot.qty,
        availableQty: setScale(sub(lot.qty, reserved), 5, 'DOWN'),
        avgCost: div(lot.costBasis, lot.qty, 4, 'HALF_EVEN'),
        costBasis: lot.costBasis,
        last: quote.last,
        mark,
        markQuality: 'SIMULATED',
        marketValue,
        unrealizedPnl: unrealized,
        unrealizedReturn: isZero(lot.costBasis) ? '0' : div(unrealized, lot.costBasis, 10, 'HALF_EVEN'),
        dayPnl,
        dayReturn: isZero(prev) ? null : div(sub(mark, prev), prev, 10, 'HALF_EVEN'),
        weight: isZero(equity) ? '0' : div(marketValue, equity, 10, 'HALF_EVEN'),
        fractionable: this.market.meta(lot.symbol)?.fractionable ?? false,
        openedAt: lot.openedAt,
      }
    })
  }

  private openBuyHolds(): Dec {
    return this.s.orders.filter((o) => isOpen(o) && o.side === 'BUY').reduce((acc, o) => add(acc, o.reservedCash ?? '0.00'), '0.00')
  }

  /** Cash from sells today is unsettled until T+1 (§10.2). */
  private unsettled(): Dec {
    return this.s.executions
      .filter((e) => e.side === 'SELL' && e.tradeDate === TRADE_DATE)
      .reduce((acc, e) => add(acc, e.netAmount ?? '0.00'), '0.00')
  }

  private account(): Account {
    const equity = this.equity()
    const longMarketValue = this.longValue()
    const holds = this.openBuyHolds()
    const unsettled = this.unsettled()
    const settled = sub(this.s.cash, unsettled)
    const totalPnl = sub(equity, this.s.nc)
    const u = this.unitValue()
    const dayStart = this.s.dayStartU
    const dayPnl = money(mul(sub(u, dayStart), this.s.units))
    return {
      accountId: this.s.me.accountId,
      runNo: 1,
      type: 'CASH',
      status: 'ACTIVE',
      restriction: 'NONE',
      restrictionUntil: null,
      restrictionReason: null,
      equity,
      longMarketValue,
      dividendsReceivable: '0.00',
      cash: { total: this.s.cash, settled, unsettled, settledAvailable: max('0.00', sub(settled, holds)), withdrawable: max('0.00', settled) },
      openBuyHolds: holds,
      buyingPower: max('0.00', sub(this.s.cash, holds)),
      startingCash: this.s.ledger.find((a) => a.type === 'INITIAL_FUNDING')?.amount ?? '500.00',
      netContributions: this.s.nc,
      totalPnl,
      totalReturn: sub(u, '1'),
      unitValue: u,
      dayPnl,
      dayReturn: isZero(dayStart) ? '0' : sub(div(u, dayStart, 10, 'HALF_EVEN'), '1'),
      gfv: { active: 0, nextExpiry: null, settledCashOnlyAt: 3, liquidationOnlyAt: 4 },
      dayTradesLast5Days: 0,
      openedAt: this.s.openedAt,
      marksQuality: 'SIMULATED',
      asOf: iso(),
    }
  }

  private run(): Run {
    const a = this.account()
    return {
      accountId: a.accountId,
      runNo: 1,
      status: 'ACTIVE',
      openedAt: this.s.openedAt,
      closedAt: null,
      startingCash: a.startingCash,
      finalEquity: null,
      netContributions: this.s.nc,
      return: a.totalReturn,
      pnl: a.totalPnl,
    }
  }

  private ledgerRow(p: Partial<Activity> & Pick<Activity, 'type' | 'at'>): Activity {
    return {
      id: publicId('led'),
      at: p.at,
      type: p.type,
      symbol: p.symbol ?? null,
      side: p.side ?? null,
      qty: p.qty ?? null,
      price: p.price ?? null,
      fees: p.fees ?? null,
      amount: p.amount ?? null,
      balanceAfter: p.balanceAfter ?? null,
      tradeDate: p.tradeDate ?? TRADE_DATE,
      settleDate: p.settleDate ?? null,
      memo: p.memo ?? null,
      by: p.by ?? 'SELF',
      orderId: p.orderId ?? null,
      executionId: p.executionId ?? null,
      transferId: p.transferId ?? null,
      detail: p.detail ?? null,
    }
  }

  /** Synthetic equity / unit-value curve ending at the live values, with the transfers as flows. */
  private history(range: string) {
    const u = this.unitValue()
    const pts = this.walk(`me:${range}`, u, range)
    const spy = this.walk(`spy:${range}`, '1.0123', range, 0.6)
    const points = pts.map((p, i) => {
      const equity = money(mul(p.v, this.s.units))
      return { t: p.t, equity, netContributions: this.s.nc, pnl: sub(equity, this.s.nc), unitValue: p.v, benchmarkIndex: spy[i]?.v ?? null }
    })
    const first = points[0]
    const last = points[points.length - 1]
    const ret = first?.unitValue && last?.unitValue ? sub(div(last.unitValue, first.unitValue, 10, 'HALF_EVEN'), '1') : '0'
    const spyRet = spy[0] && spy[spy.length - 1] ? sub(div(spy[spy.length - 1]!.v, spy[0]!.v, 10, 'HALF_EVEN'), '1') : null
    return {
      range,
      points,
      flows: this.s.transfers
        .filter((t) => t.handle === this.s.me.handle && t.type !== 'INITIAL' && t.status === 'APPLIED')
        .map((t) => ({ t: t.effectiveAt, type: t.type, amount: t.amount, memo: t.memo })),
      summary: {
        return: ret,
        pnl: first && last ? sub(last.pnl ?? '0', first.pnl ?? '0') : '0.00',
        excessVsBenchmark: spyRet ? sub(ret, spyRet) : null,
        maxDrawdown: '0.0210',
      },
    }
  }

  /** A deterministic random walk of `n` points that ends exactly at `end` (unit values, scale 10). */
  private walk(seed: string, end: Dec, range: string, vol = 1): Array<{ t: string; v: Dec }> {
    const spec: Record<string, { n: number; step: number }> = {
      '1D': { n: 78, step: 5 * MIN },
      '1W': { n: 5 * 26, step: 15 * MIN },
      '1M': { n: 22, step: 24 * 60 * MIN },
      '3M': { n: 63, step: 24 * 60 * MIN },
      YTD: { n: 190, step: 24 * 60 * MIN },
      '1Y': { n: 252, step: 24 * 60 * MIN },
      ALL: { n: 30, step: 24 * 60 * MIN },
    }
    const { n, step } = spec[range] ?? spec['1M']!
    const rng = mulberry32(hashString(seed))
    const values: number[] = Array.from({ length: n }, () => 0)
    values[n - 1] = Number(end)
    for (let i = n - 1; i > 0; i--) values[i - 1] = values[i]! * (1 - (rng() - 0.48) * 0.012 * vol)
    const now = Date.now()
    return values.map((v, i) => ({ t: iso(now - (n - 1 - i) * step), v: i === n - 1 ? setScale(end, 10, 'HALF_EVEN') : v.toFixed(10) }))
  }

  private resetMe(starting: Dec, memo: string): void {
    // The new run continues the unit-value series (§16.3): U stays, units = F / U.
    const u = this.unitValue()
    const now = iso()
    for (const o of this.s.orders.filter(isOpen)) this.finish(o, 'CANCELLED', 'ACCOUNT_RESET')
    this.freshAccount(starting, now)
    this.s.units = div(starting, u, 18, 'HALF_EVEN')
    this.s.dayStartU = u
    this.s.ledger.push(this.ledgerRow({ at: now, type: 'RESET', memo, by: 'ADMIN', detail: { type: 'RESET', side: 'OPENED', fromAccountId: null, toAccountId: this.s.me.accountId, runNo: 2, finalEquity: null, startingCash: starting, memo } }))
    this.publish('/user/queue/account', this.account())
  }

  // ================================================================ orders

  private preview(req: PlaceOrderRequest): Preview {
    const account = this.account()
    const bp = account.buyingPower ?? '0.00'
    const errors: NonNullable<Preview['errors']> = []
    const warnings: Warning[] = []
    const err = (code: string, field: string | null, message: string, hint: string | null = null) => errors.push({ code, field, message, hint })
    const meta = this.market.meta(String(req.symbol ?? '').toUpperCase())
    const ba = meta ? this.market.bidAsk(meta.symbol) : null
    if (!meta || !ba) {
      err('SYMBOL_NOT_FOUND', 'symbol', 'Unknown symbol.')
      return { valid: false, errors, warnings, estimate: null, buyingPowerBefore: bp, buyingPowerAfter: null, sessionNote: null }
    }
    const side = req.side ?? 'BUY'
    const type = req.type ?? 'MARKET'
    const tif = req.timeInForce ?? 'DAY'
    const qty = req.qty ?? null
    const notional = req.notional ?? null
    const lot = this.s.lots[meta.symbol]
    const bid = unitsToDec(ba.bid)
    const ask = unitsToDec(ba.ask)
    const last = unitsToDec(ba.last)

    if ((qty == null) === (notional == null)) err('QTY_OR_NOTIONAL_REQUIRED', 'qty', 'Enter a quantity or a dollar amount.')
    if (qty != null && (!isDecimal(qty) || sign(qty) <= 0)) err('INVALID_QTY', 'qty', 'Quantity must be greater than 0.')
    const fractionalQty = qty != null && isDecimal(qty) && sign(qty) > 0 && compare(setScale(qty, 0, 'DOWN'), qty) !== 0
    if (fractionalQty && !meta.fractionable) err('NOT_FRACTIONABLE', 'qty', `${meta.symbol} trades in whole shares only.`)
    else if (fractionalQty && type !== 'MARKET') err('FRACTIONAL_MARKET_ONLY', 'qty', 'Fractional shares need a Market order.')
    if (notional != null) {
      if (!isDecimal(notional) || lt(notional, side === 'BUY' ? '1.00' : '0.01')) err('NOTIONAL_TOO_SMALL', 'notional', 'The minimum dollar order is $1.00.')
      if (type !== 'MARKET') err('FRACTIONAL_MARKET_ONLY', 'type', 'Dollar orders must be Market orders.')
      if (tif !== 'DAY' || req.extendedHours) err('FRACTIONAL_DAY_ONLY', 'timeInForce', 'Dollar orders are Day only, regular hours.')
      if (!meta.fractionable) err('NOT_FRACTIONABLE', 'notional', `${meta.symbol} does not support fractional shares.`)
    }
    if (type === 'MARKET' && tif === 'GTC') err('TIF_NOT_ALLOWED', 'timeInForce', 'Market orders are Day only.')
    if ((type === 'LIMIT' || type === 'STOP_LIMIT') && !req.limitPrice) err('LIMIT_PRICE_REQUIRED', 'limitPrice', 'Enter a limit price.')
    if (type === 'MARKET' && req.limitPrice) err('PRICE_NOT_ALLOWED', 'limitPrice', 'Market orders take no limit price.')
    if ((type === 'STOP' || type === 'STOP_LIMIT') && !req.stopPrice) err('STOP_PRICE_REQUIRED', 'stopPrice', 'Enter a stop price.')
    if (req.extendedHours && type !== 'LIMIT') err('EXT_HOURS_LIMIT_ONLY', 'extendedHours', 'Extended hours need a Limit order.')
    for (const [field, price] of [
      ['limitPrice', req.limitPrice],
      ['stopPrice', req.stopPrice],
    ] as const) {
      if (price == null) continue
      if (!isDecimal(price) || sign(price) <= 0) err('INVALID_TICK', field, 'Enter a valid price.')
      else if (compare(setScale(price, tickFor(price) === '0.01' ? 2 : 4, 'DOWN'), price) !== 0) err('INVALID_TICK', field, `Prices must be in steps of $${tickFor(price)}.`)
      else {
        const away = div(sub(price, last), last, 6, 'HALF_UP')
        if (gt(away, '0.5') || lt(away, '-0.5')) err('PRICE_TOO_FAR', field, 'The price is more than 50% away from the last price.')
        else if (gt(away, '0.05') || lt(away, '-0.05')) warnings.push({ code: 'FAT_FINGER', message: 'Your price is more than 5% away from the last price.' })
      }
    }
    if (side === 'SELL' && !lot) err('SHORT_SELLING_NOT_ALLOWED', 'side', 'You have no position to sell. Short selling is not allowed.')
    if (this.s.orders.filter(isOpen).length >= 50) err('TOO_MANY_OPEN_ORDERS', null, 'You already have 50 open orders.')

    let estimate: Estimate | null = null
    let buyingPowerAfter: Dec | null = null
    if (errors.length === 0) {
      const touch = side === 'BUY' ? ask : bid
      const refPrice = type === 'LIMIT' ? (side === 'BUY' ? min(req.limitPrice!, ask) : max(req.limitPrice!, bid)) : touch
      let estQty: Dec
      if (notional != null) {
        const q0 = floor5(div(notional, refPrice, 8, 'DOWN'))
        estQty = side === 'BUY' ? floor5(div(sub(notional, estimateFees('BUY', q0, '0').cat ?? '0'), refPrice, 8, 'DOWN')) : min(lot?.qty ?? '0', q0)
      } else {
        estQty = qty!
      }
      const principal = money(mul(estQty, refPrice))
      const fees = estimateFees(side, estQty, principal)
      let hold = '0.00'
      if (side === 'BUY') {
        hold =
          notional != null
            ? setScale(notional, 2, 'CEILING')
            : type === 'MARKET'
              ? setScale(add(mul(mul(estQty, ask), '1.02'), fees.cat ?? '0'), 2, 'CEILING')
              : setScale(add(mul(estQty, req.limitPrice!), fees.cat ?? '0'), 2, 'CEILING')
        if (gt(hold, bp)) {
          const maxShares = div(bp, mul(ask, '1.02'), 0, 'FLOOR')
          err(
            'INSUFFICIENT_BUYING_POWER',
            notional != null ? 'notional' : 'qty',
            `This order needs $${hold}; your buying power is $${bp}.`,
            `You can afford ${maxShares} whole shares at $${setScale(ask, 2)}${meta.fractionable ? ', or try a dollar amount' : ''}.`,
          )
        }
        buyingPowerAfter = sub(bp, hold)
      } else {
        const available = this.positions().find((p) => p.symbol === meta.symbol)?.availableQty ?? '0'
        if (gt(estQty, available)) err('INSUFFICIENT_POSITION', notional != null ? 'notional' : 'qty', `You can sell at most ${available} shares.`)
        buyingPowerAfter = bp
      }
      if (qty != null && isDecimal(qty) && gt(qty, fromInteger(900))) {
        warnings.push({ code: 'PARTIAL_FILL_LIKELY', message: 'Quantity exceeds the displayed size; expect partial fills.' })
      }
      estimate = { price: refPrice, qty: estQty, principal, fees, hold, quoteAsOf: iso(), quoteQuality: 'SIMULATED' }
    }
    return {
      valid: errors.length === 0,
      errors,
      warnings,
      estimate,
      buyingPowerBefore: bp,
      buyingPowerAfter: errors.length === 0 ? buyingPowerAfter : null,
      sessionNote: null,
    }
  }

  private newOrder(req: PlaceOrderRequest, at: string = iso()): Order {
    return {
      orderId: publicId('ord'),
      clientOrderId: req.clientOrderId ?? `web-${crypto.randomUUID()}`,
      accountId: this.s.me.accountId,
      symbol: (req.symbol ?? '').toUpperCase(),
      side: req.side ?? 'BUY',
      type: req.type ?? 'MARKET',
      qty: req.qty ?? null,
      notional: req.notional ?? null,
      fractional: req.notional != null || (req.qty != null && compare(setScale(req.qty, 0, 'DOWN'), req.qty) !== 0),
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
      sessionDate: TRADE_DATE,
      receivedAt: at,
      acceptedAt: at,
      eligibleAt: iso(Date.parse(at) + 400),
      activatedAt: null,
      triggeredAt: null,
      expiresAt: (req.timeInForce ?? 'DAY') === 'GTC' ? '2026-12-04T21:00:00.000Z' : iso(this.startedAt + ((2 * 60 + 14) * 60 + 7) * 1000),
      doneAt: null,
      replacesOrderId: null,
      replacedByOrderId: null,
      channel: 'WEB',
      rationale: req.rationale ?? null,
      executions: null,
      events: null,
    }
  }

  private requestHash(req: PlaceOrderRequest): string {
    const { symbol, side, type, qty, notional, limitPrice, stopPrice, timeInForce, extendedHours } = req
    return JSON.stringify([symbol?.toUpperCase(), side, type, qty, notional, limitPrice, stopPrice, timeInForce ?? 'DAY', Boolean(extendedHours)])
  }

  private place(req: PlaceOrderRequest): Response {
    if (this.s.engineHalted) return problem(503, 'ENGINE_HALTED', 'Trading halted', `Trading is paused: ${this.s.engineHalted}`, 'Try again once the admin resumes trading.')
    if (!req.clientOrderId || !/^[A-Za-z0-9._:-]{1,64}$/.test(req.clientOrderId)) return problem(422, 'CLIENT_ORDER_ID_INVALID', 'Invalid clientOrderId')
    const existing = this.s.orders.find((o) => o.clientOrderId === req.clientOrderId)
    if (existing) {
      if (this.requestHash(req) !== this.requestHash({ ...existing, clientOrderId: existing.clientOrderId } as PlaceOrderRequest)) {
        return problem(409, 'CLIENT_ORDER_ID_CONFLICT', 'clientOrderId conflict', 'This clientOrderId was used for a different order.')
      }
      return json({ order: existing, estimate: null, buyingPowerBefore: this.account().buyingPower, buyingPowerAfter: this.account().buyingPower, warnings: [], replayed: true }, 200)
    }
    const preview = this.preview(req)
    if (!preview.valid) {
      const errs = preview.errors ?? []
      const first = errs[0]!
      return problem(
        422,
        first.code ?? 'VALIDATION_FAILED',
        'Order rejected',
        first.message,
        first.hint,
        errs.filter((e) => e.field).map((e) => ({ field: e.field!, code: e.code ?? '', message: e.message ?? '' })),
      )
    }
    const order = this.newOrder(req)
    order.reservedCash = req.side === 'BUY' ? (preview.estimate?.hold ?? '0.00') : '0.00'
    order.reservedQty = req.side === 'SELL' ? (preview.estimate?.qty ?? '0') : '0'
    this.s.orders.push(order)
    this.pushEvent(order, 'ACCEPTED')
    this.emitOrder('ACCEPTED', order, null)
    this.publish('/user/queue/account', this.account())
    setTimeout(() => this.activate(order.orderId!), 450 + Math.random() * 350)
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

  private activate(orderId: string): void {
    const order = this.s.orders.find((o) => o.orderId === orderId)
    if (!order || !isOpen(order)) return
    order.activatedAt = iso()
    order.status = 'WORKING'
    order.waitReason = order.type === 'LIMIT' ? 'LIMIT_NOT_REACHED' : null
    this.pushEvent(order, 'WORKING')
    if (order.type === 'MARKET' || this.marketable(order)) {
      this.fill(order)
    } else {
      this.emitOrder('WORKING', order, null)
    }
    this.persist()
  }

  private marketable(order: Order): boolean {
    if (order.type !== 'LIMIT' || !order.limitPrice || !order.symbol) return false
    const ba = this.market.bidAsk(order.symbol)
    if (!ba) return false
    return order.side === 'BUY' ? compare(order.limitPrice, unitsToDec(ba.ask)) >= 0 : compare(order.limitPrice, unitsToDec(ba.bid)) <= 0
  }

  private sweepRestingLimits(symbol: string): void {
    let changed = false
    for (const order of this.s.orders) {
      if (order.symbol === symbol && order.status === 'WORKING' && this.marketable(order)) {
        this.fill(order, 'RESTING')
        changed = true
      }
    }
    if (changed) this.persist()
  }

  private fill(order: Order, liquidity: 'TOUCH' | 'RESTING' = 'TOUCH'): void {
    const ba = order.symbol ? this.market.bidAsk(order.symbol) : null
    if (!ba) return
    const touch = unitsToDec(order.side === 'BUY' ? ba.ask : ba.bid)
    const price = order.type === 'LIMIT' && order.limitPrice ? (liquidity === 'RESTING' ? order.limitPrice : order.side === 'BUY' ? min(order.limitPrice, touch) : max(order.limitPrice, touch)) : touch
    let qty: Dec
    if (order.notional != null) {
      const q0 = floor5(div(order.notional, price, 8, 'DOWN'))
      qty = order.side === 'BUY' ? floor5(div(sub(order.notional, estimateFees('BUY', q0, '0').cat ?? '0'), price, 8, 'DOWN')) : min(this.s.lots[order.symbol!]?.qty ?? '0', q0)
    } else {
      qty = order.qty!
    }
    if (order.side === 'BUY') {
      // never spend more than the cash the order reserved plus free buying power
      const principal = money(mul(qty, price))
      if (gt(principal, add(this.s.cash, '0'))) {
        this.finish(order, 'CANCELLED', 'INSUFFICIENT_BUYING_POWER_AT_EXECUTION')
        return
      }
    }
    this.fillAt(order, setScale(price, 4), qty, iso(), liquidity)
    this.emitOrder('FILL', order, this.s.executions[this.s.executions.length - 1]!)
    this.publish('/user/queue/account', this.account())
    this.publish('/topic/leaderboard', { asOf: iso() })
  }

  /** Posts an execution (§12.8): ledger row net of fees, lot update, realized P&L, order fields, FILL event. */
  private fillAt(order: Order, price: Dec, qty: Dec, at: string, liquidity: 'TOUCH' | 'RESTING'): void {
    const symbol = order.symbol!
    const side = order.side ?? 'BUY'
    const principal = money(mul(qty, price))
    const fees = estimateFees(side, qty, principal)
    const net = side === 'BUY' ? add(principal, fees.total ?? '0') : sub(principal, fees.total ?? '0')
    const lot: Lot | undefined = this.s.lots[symbol]
    let realized: Dec | null = null
    if (side === 'BUY') {
      this.s.cash = sub(this.s.cash, net)
      if (lot) {
        lot.qty = add(lot.qty, qty)
        lot.costBasis = add(lot.costBasis, net)
      } else {
        this.s.lots[symbol] = { symbol, qty, costBasis: net, openedAt: at }
      }
    } else if (lot) {
      this.s.cash = add(this.s.cash, net)
      const relieved = money(div(mul(lot.costBasis, qty), lot.qty, 6, 'HALF_EVEN'))
      realized = sub(net, relieved)
      lot.qty = sub(lot.qty, qty)
      lot.costBasis = sub(lot.costBasis, relieved)
      if (sign(lot.qty) <= 0) delete this.s.lots[symbol]
    }
    const ba = this.market.bidAsk(symbol)
    const execution: Execution = {
      executionId: publicId('exe'),
      orderId: order.orderId,
      symbol,
      side,
      qty,
      price,
      principal,
      fees,
      netAmount: net,
      realizedPnl: realized,
      liquidity,
      executedAt: at,
      tradeDate: TRADE_DATE,
      settleDate: SETTLE_DATE,
      refQuote: {
        bid: ba ? unitsToDec(ba.bid) : null,
        ask: ba ? unitsToDec(ba.ask) : null,
        bidSize: 300,
        askSize: 200,
        last: ba ? unitsToDec(ba.last) : null,
        quoteAt: at,
        confirmedAt: at,
        source: 'mock',
        quality: 'SIMULATED',
        clamped: false,
      },
    }
    this.s.executions.push(execution)
    this.s.ledger.push(
      this.ledgerRow({
        at,
        type: 'FILL',
        symbol,
        side,
        qty,
        price,
        fees,
        amount: side === 'BUY' ? negate(net) : net,
        balanceAfter: this.s.cash,
        settleDate: SETTLE_DATE,
        orderId: order.orderId,
        executionId: execution.executionId,
      }),
    )
    Object.assign(order, {
      filledQty: qty,
      filledPrincipal: principal,
      avgFillPrice: price,
      fees: fees.total,
      status: 'FILLED',
      waitReason: null,
      reservedCash: '0.00',
      reservedQty: '0',
      doneAt: at,
    } satisfies Partial<Order>)
    this.pushEvent(order, 'FILL', at, { executionId: execution.executionId, qty, price })
    this.notify('FILL', `Filled: ${side === 'BUY' ? 'bought' : 'sold'} ${qty} ${symbol}`, `@ $${setScale(price, 2)} · ${side === 'BUY' ? 'cost' : 'proceeds'} $${principal}`, { orderId: order.orderId }, false)
  }

  private cancel(orderId: string, reason: 'USER' | 'ADMIN'): Response {
    const order = this.s.orders.find((o) => o.orderId === orderId)
    if (!order) return problem(404, 'ORDER_NOT_FOUND', 'Order not found')
    if (order.status === 'CANCELLED') return json(order)
    if (!isOpen(order)) return problem(409, 'ORDER_NOT_CANCELLABLE', 'Order is not cancellable', `This order is already ${String(order.status).toLowerCase()}.`)
    this.finish(order, 'CANCELLED', reason)
    if (reason === 'ADMIN') this.audit('ADMIN', 'ORDER_CANCEL', 'OK', this.s.me.handle, 'order', orderId)
    return json(order)
  }

  private finish(order: Order, status: 'CANCELLED' | 'EXPIRED', reason: string | null): void {
    order.status = status
    order.waitReason = null
    order.statusReason = reason
    order.reservedCash = '0.00'
    order.reservedQty = '0'
    order.doneAt = iso()
    this.pushEvent(order, status, undefined, reason ? { reason } : null)
    this.emitOrder(status, order, null)
    this.publish('/user/queue/account', this.account())
  }

  private pushEvent(order: Order, type: NonNullable<OrderEvent['type']>, at: string = iso(), detail: Record<string, unknown> | null = null): void {
    const list = (this.s.events[order.orderId!] ??= [])
    list.push({ seq: list.length + 1, type, statusAfter: order.status, at, detail })
  }

  private detail(order: Order): Order {
    return {
      ...order,
      executions: this.s.executions.filter((e) => e.orderId === order.orderId),
      events: this.s.events[order.orderId!] ?? [],
    }
  }

  private emitOrder(event: NonNullable<OrderUpdateEvent['event']>, order: Order, execution: Execution | null): void {
    const payload: OrderUpdateEvent = { event, order: { ...order, executions: null, events: null }, execution }
    this.publish('/user/queue/orders', payload)
  }

  private notify(kind: NonNullable<Notification['kind']>, title: string, body: string, payload: Record<string, unknown> | null, push = true): void {
    const n: Notification = { id: publicId('ntf'), kind, title, body, payload, createdAt: iso(), readAt: null }
    this.s.notifications.push(n)
    if (this.s.notifications.length > 100) this.s.notifications.splice(0, this.s.notifications.length - 100)
    if (push) this.publish('/user/queue/notifications', n)
  }

  // ================================================================ league

  private other(handle: string): MockOtherPlayer | undefined {
    return this.s.others.find((p) => p.handle === handle)
  }

  private otherEquity(p: MockOtherPlayer): Dec {
    return money(mul(p.units, p.u))
  }

  private summary(p: MockOtherPlayer): PlayerSummary {
    return { playerId: p.playerId, handle: p.handle, displayName: p.displayName, kind: p.kind, aiModel: p.aiModel, avatarSeed: p.handle, memberSince: p.createdAt, resets: p.resets }
  }

  private mySummary(): PlayerSummary {
    return {
      playerId: this.s.me.playerId,
      handle: this.s.me.handle,
      displayName: this.s.me.displayName,
      kind: 'HUMAN',
      aiModel: null,
      avatarSeed: this.s.me.handle,
      memberSince: this.s.me.createdAt,
      resets: 0,
    }
  }

  /** Period anchor for a player's unit value: deterministic per player and period (ALL = 1, TODAY = session start). */
  private anchor(handle: string, period: string, u: Dec): Dec {
    if (period === 'ALL' || period === 'SEASON') return '1'
    if (handle === this.s.me.handle) return period === 'TODAY' ? this.s.dayStartU : '1'
    const rng = mulberry32(hashString(`${handle}:${period}`))
    const drift = period === 'TODAY' ? 0.006 : period === 'WEEK' ? 0.02 : 0.03
    return div(u, (1 + (rng() - 0.45) * drift).toFixed(6), 10, 'HALF_EVEN')
  }

  private flowsFor(handle: string): Dec {
    return this.s.transfers.filter((t) => t.handle === handle && t.type !== 'INITIAL' && t.status === 'APPLIED' && t.isContribution).reduce((acc, t) => add(acc, t.amount ?? '0'), '0.00')
  }

  private leaderboard(params: URLSearchParams): Leaderboard {
    const kind = (params.get('kind') ?? 'ALL').toUpperCase() as NonNullable<Leaderboard['kind']>
    const metric = (params.get('metric') ?? 'RETURN').toUpperCase() as NonNullable<Leaderboard['metric']>
    const period = (params.get('period') ?? 'WEEK').toUpperCase() as NonNullable<Leaderboard['period']>
    const minFills = this.s.settings['league.eligibility.minFills']
    const minSymbols = this.s.settings['league.eligibility.minSymbols']
    const spyRet = period === 'TODAY' ? '0.0021' : period === 'WEEK' ? '0.0080' : '0.0215'

    type Raw = { summary: PlayerSummary; u: Dec; equity: Dec; nc: Dec; fills: number; symbols: number; top: string | null; apiTrader: boolean }
    const raw: Raw[] = [
      {
        summary: this.mySummary(),
        u: this.unitValue(),
        equity: this.equity(),
        nc: this.s.nc,
        fills: this.s.executions.length,
        symbols: new Set(this.s.executions.map((e) => e.symbol)).size,
        top: Object.values(this.s.lots).sort((a, b) => compare(b.costBasis, a.costBasis))[0]?.symbol ?? null,
        apiTrader: false,
      },
      ...this.s.others
        .filter((p) => p.status !== 'DISABLED')
        .map((p) => ({ summary: this.summary(p), u: p.u, equity: this.otherEquity(p), nc: p.nc, fills: p.fills, symbols: p.symbols, top: p.top, apiTrader: p.kind === 'AI' })),
    ]
    const rows = raw
      .filter((r) => kind === 'ALL' || r.summary.kind === kind)
      .map((r) => {
        const a = this.anchor(r.summary.handle!, period, r.u)
        const ret = sub(div(r.u, a, 10, 'HALF_EVEN'), '1')
        // P&L = [E − NC](b) − [E − NC](a); at the anchor E = units × a (units only change with flows, valued at U)
        const pnlNow = sub(r.equity, r.nc)
        const units = isZero(r.u) ? '0' : div(r.equity, r.u, 10, 'HALF_EVEN')
        const pnlAnchor = sub(money(mul(units, a)), r.nc)
        const pnl = money(period === 'ALL' || period === 'SEASON' ? pnlNow : sub(pnlNow, pnlAnchor))
        const eligible = period === 'TODAY' || (r.fills >= minFills && r.symbols >= minSymbols)
        const spark = this.walk(`${r.summary.handle}:spark:${period}`, r.u, '1M', 0.5).slice(-12).map((p) => p.v)
        const row: LeaderboardRow = {
          rank: null,
          rankChange: null,
          eligible,
          eligibilityNote: eligible ? null : `${Math.min(r.fills, minFills)}/${minFills} trades${r.symbols < minSymbols ? ` · ${r.symbols}/${minSymbols} symbols` : ''}`,
          partialPeriod: false,
          rebased: false,
          player: r.summary,
          apiTrader: r.apiTrader,
          return: ret,
          pnl,
          excessVsBenchmark: sub(ret, spyRet),
          maxDrawdown: '0.0210',
          equity: this.s.settings['social.holdingsVisibility'] === 'HIDDEN' ? null : r.equity,
          netContributions: r.nc,
          flowsInPeriod: this.flowsFor(r.summary.handle!),
          fills: r.fills,
          symbolsTraded: r.symbols,
          winRate: r.fills > 0 ? '0.5500' : null,
          topHolding: r.top,
          spark,
        }
        return row
      })
      .sort((a, b) => Number(b.eligible) - Number(a.eligible) || compare(metric === 'PNL' ? b.pnl! : b.return!, metric === 'PNL' ? a.pnl! : a.return!))
    let rank = 0
    for (const row of rows) {
      if (row.eligible) {
        rank += 1
        row.rank = rank
        row.rankChange = metric === 'RETURN' && kind === 'ALL' ? ((hashString(`${row.player?.handle}:${period}`) % 5) - 2 || null) : null
      }
    }
    const humans = rows.filter((r) => r.player?.kind === 'HUMAN').map((r) => r.return!)
    const ais = rows.filter((r) => r.player?.kind === 'AI').map((r) => r.return!)
    return {
      period,
      metric,
      kind,
      periodStart: period === 'TODAY' ? '2026-10-09T00:00:00.000Z' : '2026-10-05T00:00:00.000Z',
      asOf: iso(),
      benchmark: { symbol: 'SPY', return: spyRet },
      arena: { humansMedianReturn: median(humans), aiMedianReturn: median(ais) },
      rows,
      me: rows.find((r) => r.player?.handle === this.s.me.handle) ?? null,
    }
  }

  private publicTrades(handle: string): PublicTrade[] {
    const delayMs = 15 * MIN
    if (handle === this.s.me.handle) {
      return this.s.executions
        .filter((e) => Date.parse(e.executedAt ?? '') <= Date.now() - delayMs)
        .toReversed()
        .map((e) => ({ executedAt: e.executedAt, symbol: e.symbol, side: e.side, qty: e.qty, price: e.price, realizedPnl: e.realizedPnl, viaApi: false, untrusted: { rationale: null } }))
    }
    const p = this.other(handle)
    if (!p) return []
    const rng = mulberry32(hashString(`${handle}:trades`))
    const symbols = ['NVDA', 'AAPL', 'MSFT', 'TSLA', 'AMD', 'SPY', 'META', 'GOOGL']
    return Array.from({ length: Math.min(p.fills, 12) }, (_, i) => {
      const symbol = (i === 0 && p.top) || symbols[Math.floor(rng() * symbols.length)]!
      const price = this.market.quote(symbol).prevClose ?? '100.00'
      const side = rng() > 0.35 ? ('BUY' as const) : ('SELL' as const)
      return {
        executedAt: iso(this.startedAt - (i + 1) * (95 + Math.floor(rng() * 300)) * MIN),
        symbol,
        side,
        qty: (1 + Math.floor(rng() * 4)).toString(),
        price: setScale(price, 2),
        realizedPnl: side === 'SELL' ? setScale(((rng() - 0.4) * 20).toFixed(2), 2) : null,
        viaApi: p.kind === 'AI',
        untrusted: {
          rationale:
            p.kind === 'AI'
              ? side === 'BUY'
                ? `Adding to ${symbol}: momentum held above the 20-day average; keeps 60% settled cash.`
                : `Trimming ${symbol} after a run-up; locks gains and frees settled cash.`
              : null,
        },
      }
    })
  }

  private profile(handle: string): PlayerProfile | null {
    const mine = handle === this.s.me.handle
    const p = this.other(handle)
    if (!mine && !p) return null
    const summary = mine ? this.mySummary() : this.summary(p!)
    const u = mine ? this.unitValue() : p!.u
    const equity = mine ? this.equity() : this.otherEquity(p!)
    const nc = mine ? this.s.nc : p!.nc
    const board = this.leaderboard(new URLSearchParams({ period: 'ALL' }))
    const stat = (period: string) => {
      const a = this.anchor(handle, period, u)
      const rank = this.leaderboard(new URLSearchParams({ period })).rows?.find((r) => r.player?.handle === handle)?.rank ?? null
      return { return: sub(div(u, a, 10, 'HALF_EVEN'), '1'), pnl: money(mul(sub(u, a), mine ? this.s.units : p!.units)), rank }
    }
    const deposits = this.s.transfers.filter((t) => t.handle === handle && t.type === 'DEPOSIT' && t.status === 'APPLIED')
    const lots = mine ? Object.values(this.s.lots) : []
    const holdings = mine
      ? lots.map((l) => ({ symbol: l.symbol, weight: isZero(equity) ? '0' : div(money(mul(l.qty, this.mark(l.symbol))), equity, 4, 'HALF_EVEN'), qty: null }))
      : [
          { symbol: p!.top ?? 'SPY', weight: '0.4120', qty: null },
          { symbol: 'SPY', weight: '0.1850', qty: null },
        ]
    const row = board.rows?.find((r) => r.player?.handle === handle)
    return {
      player: summary,
      ownerHandle: mine ? null : p!.ownerHandle,
      runNo: 1,
      stats: { TODAY: stat('TODAY'), WEEK: stat('WEEK'), MONTH: stat('MONTH'), YTD: stat('YTD'), ALL: stat('ALL') },
      equity: this.s.settings['social.holdingsVisibility'] === 'HIDDEN' ? null : equity,
      netContributions: nc,
      deposits: { count: deposits.length, total: deposits.reduce((acc, t) => add(acc, t.amount ?? '0'), '0.00') },
      fills: row?.fills ?? 0,
      winRate: row?.winRate ?? null,
      moneyWeightedReturnAll: sub(div(equity, nc, 10, 'HALF_EVEN'), '1'),
      holdingsAsOf: '2026-10-08',
      holdings,
      recentTrades: this.publicTrades(handle).slice(0, 10),
    }
  }

  private feed(): FeedItem[] {
    const items: FeedItem[] = []
    for (const p of this.s.others) {
      for (const t of this.publicTrades(p.handle).slice(0, 3)) {
        items.push({
          id: `feed_${p.handle}_${t.executedAt}`,
          at: t.executedAt,
          kind: 'FILL',
          player: this.summary(p),
          symbol: t.symbol,
          payload: { side: t.side, qty: t.qty, price: t.price, realizedPnl: t.realizedPnl, amount: null, period: null, untrusted: { rationale: t.untrusted?.rationale ?? null, memo: null } },
        })
      }
    }
    if (this.s.settings['social.showDepositsInFeed']) {
      for (const t of this.s.transfers.filter((x) => x.type === 'DEPOSIT' && x.status === 'APPLIED')) {
        const p = t.handle === this.s.me.handle ? this.mySummary() : this.other(t.handle ?? '') ? this.summary(this.other(t.handle!)!) : null
        if (!p) continue
        items.push({ id: `feed_dep_${t.transferId}`, at: t.effectiveAt, kind: 'DEPOSIT', player: p, symbol: null, payload: { side: null, qty: null, price: null, realizedPnl: null, amount: t.amount, period: null, untrusted: { rationale: null, memo: t.memo } } })
      }
    }
    return items.sort((a, b) => Date.parse(b.at ?? '') - Date.parse(a.at ?? ''))
  }

  // ================================================================ admin

  private adminPlayer(handle: string): AdminPlayer | null {
    if (handle === this.s.me.handle) {
      const a = this.account()
      return {
        ...this.mySummary(),
        role: this.s.me.role,
        status: 'ACTIVE',
        ownerHandle: null,
        accountId: this.s.me.accountId,
        runNo: 1,
        equity: a.equity,
        cash: a.cash?.total ?? null,
        netContributions: this.s.nc,
        pnl: a.totalPnl,
        return: a.totalReturn,
        openOrders: this.s.orders.filter(isOpen).length,
        activeKeys: this.s.keys.filter((k) => k.handle === handle && !k.revokedAt).length,
        restriction: 'NONE',
        lastSeenAt: iso(),
        createdAt: this.s.me.createdAt,
      }
    }
    const p = this.other(handle)
    if (!p) return null
    const equity = this.otherEquity(p)
    return {
      ...this.summary(p),
      role: p.role,
      status: p.status,
      ownerHandle: p.ownerHandle,
      accountId: p.accountId,
      runNo: 1 + p.resets,
      equity,
      cash: p.cash,
      netContributions: p.nc,
      pnl: sub(equity, p.nc),
      return: sub(p.u, '1'),
      openOrders: p.kind === 'AI' ? 1 : 0,
      activeKeys: this.s.keys.filter((k) => k.handle === handle && !k.revokedAt).length,
      restriction: p.status === 'FROZEN' ? 'FROZEN' : 'NONE',
      lastSeenAt: p.lastSeenAt,
      createdAt: p.createdAt,
    }
  }

  private adminPlayers(): AdminPlayer[] {
    return [this.s.me.handle, ...this.s.others.map((p) => p.handle)].map((h) => this.adminPlayer(h)!).filter(Boolean)
  }

  private syntheticAccount(p: MockOtherPlayer): Account {
    const equity = this.otherEquity(p)
    return {
      ...this.account(),
      accountId: p.accountId,
      runNo: 1 + p.resets,
      restriction: p.status === 'FROZEN' ? 'FROZEN' : 'NONE',
      equity,
      longMarketValue: sub(equity, p.cash),
      cash: { total: p.cash, settled: p.cash, unsettled: '0.00', settledAvailable: p.cash, withdrawable: p.cash },
      openBuyHolds: '0.00',
      buyingPower: p.cash,
      startingCash: '500.00',
      netContributions: p.nc,
      totalPnl: sub(equity, p.nc),
      totalReturn: sub(p.u, '1'),
      unitValue: p.u,
      dayPnl: '0.00',
      dayReturn: '0',
    }
  }

  private adminDetail(handle: string): Response {
    const player = this.adminPlayer(handle)
    if (!player) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player @${handle}.`)
    const mine = handle === this.s.me.handle
    const p = this.other(handle)
    const balances = mine ? this.account() : this.syntheticAccount(p!)
    const eodHoldings = mine
      ? Object.values(this.s.lots).map((l) => {
          const mark = this.market.quote(l.symbol).prevClose ?? '0'
          return { symbol: l.symbol, qty: l.qty, mark, marketValue: money(mul(l.qty, mark)) }
        })
      : [{ symbol: p!.top ?? 'SPY', qty: '1.5', mark: this.market.quote(p!.top ?? 'SPY').prevClose ?? '0', marketValue: money(mul('1.5', this.market.quote(p!.top ?? 'SPY').prevClose ?? '0')) }]
    return json({
      player,
      apiTradingAllowed: mine ? this.s.apiTradingAllowed : p!.apiTradingAllowed,
      runs: mine ? [this.run()] : [{ accountId: p!.accountId, runNo: 1, status: 'ACTIVE', openedAt: p!.createdAt, closedAt: null, startingCash: '500.00', finalEquity: null, netContributions: p!.nc, return: sub(p!.u, '1'), pnl: sub(this.otherEquity(p!), p!.nc) }],
      balances,
      holdingsAsOf: '2026-10-08',
      eodHoldings,
      transfers: this.s.transfers.filter((t) => t.handle === handle).toReversed(),
      apiKeys: this.s.keys.filter((k) => k.handle === handle).map(({ handle: _h, ...k }) => k),
      recentAudit: this.s.audit.filter((a) => a.targetId === handle || a.actor === handle).toReversed().slice(0, 20),
    })
  }

  private createPlayer(body: unknown): Response {
    const b = (body ?? {}) as { kind?: 'HUMAN' | 'AI'; handle?: string; displayName?: string; password?: string; aiModel?: string; ownerHandle?: string; startingCash?: string; issueKey?: boolean }
    const handle = b.handle?.trim().toLowerCase() ?? ''
    const fields: FieldErr[] = []
    if (b.kind !== 'HUMAN' && b.kind !== 'AI') fields.push({ field: 'kind', code: 'NotNull', message: 'Choose human or AI.' })
    if (!HANDLE_RE.test(handle)) fields.push({ field: 'handle', code: 'Pattern', message: '2–32 lowercase letters, digits or _.' })
    if (!b.displayName?.trim()) fields.push({ field: 'displayName', code: 'NotBlank', message: 'Enter a display name.' })
    if (b.startingCash != null && b.startingCash !== '' && (!isDecimal(b.startingCash) || lt(b.startingCash, '1.00'))) fields.push({ field: 'startingCash', code: 'Min', message: 'At least $1.00.' })
    if (fields.length) return problem(400, 'VALIDATION_FAILED', 'Validation failed', fields[0]!.message, null, fields)
    if (handle === this.s.me.handle || this.other(handle)) return problem(409, 'HANDLE_TAKEN', 'Handle taken', `@${handle} is already taken.`, null, [{ field: 'handle', code: 'HANDLE_TAKEN', message: `@${handle} is already taken.` }])
    if (b.kind === 'HUMAN' && (!b.password || b.password.length < 10)) return problem(422, 'WEAK_PASSWORD', 'Weak password', 'Use at least 10 characters.', null, [{ field: 'password', code: 'WEAK_PASSWORD', message: 'Use at least 10 characters.' }])
    const starting = b.startingCash ? setScale(b.startingCash, 2) : (this.s.settings['league.defaultStartingCash'] ?? '500.00')
    const now = iso()
    const p: MockOtherPlayer = {
      playerId: publicId('ply'),
      handle,
      displayName: b.displayName!.trim(),
      kind: b.kind!,
      aiModel: b.kind === 'AI' ? b.aiModel?.trim() || null : null,
      role: 'PLAYER',
      status: 'ACTIVE',
      ownerHandle: b.kind === 'AI' ? b.ownerHandle?.trim() || this.s.me.handle : null,
      accountId: publicId('acc'),
      u: '1',
      units: starting,
      cash: starting,
      nc: starting,
      fills: 0,
      symbols: 0,
      top: null,
      apiTradingAllowed: b.kind === 'AI',
      resets: 0,
      createdAt: now,
      lastSeenAt: null,
    }
    this.s.others.push(p)
    this.s.transfers.push(this.transferRow(handle, p.accountId, 'INITIAL', starting, 'Starting cash', now, this.s.me.handle, '0.00', '1'))
    const apiKey = b.kind === 'AI' && b.issueKey !== false ? this.issueKey(handle, `${p.displayName} key`, ['market:read', 'account:read', 'trade', 'league:read'], null) : null
    this.audit('ADMIN', 'PLAYER_CREATE', 'OK', this.s.me.handle, 'player', handle, { kind: p.kind })
    return json({ player: this.adminPlayer(handle), apiKey }, 201)
  }

  private patchPlayer(handle: string, body: unknown): Response {
    const b = (body ?? {}) as { displayName?: string | null; aiModel?: string | null; status?: 'ACTIVE' | 'DISABLED' | null; role?: 'PLAYER' | 'ADMIN' | null; apiTradingAllowed?: boolean | null }
    if (handle === this.s.me.handle) {
      if (b.status === 'DISABLED' || b.role === 'PLAYER') return problem(403, 'FORBIDDEN', 'Forbidden', 'You cannot disable or demote yourself.')
      if (b.displayName) this.s.me.displayName = b.displayName
      if (b.apiTradingAllowed != null) this.s.apiTradingAllowed = b.apiTradingAllowed
      return json(this.adminPlayer(handle))
    }
    const p = this.other(handle)
    if (!p) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found')
    if (b.role === 'ADMIN' && p.kind === 'AI') return problem(403, 'FORBIDDEN', 'Forbidden', 'AI players can never be admins.')
    if (b.displayName) p.displayName = b.displayName
    if (b.aiModel !== undefined && b.aiModel !== null && p.kind === 'AI') p.aiModel = b.aiModel
    if (b.status) p.status = b.status
    if (b.role) p.role = b.role
    if (b.apiTradingAllowed != null) p.apiTradingAllowed = b.apiTradingAllowed
    this.audit('ADMIN', 'PLAYER_UPDATE', 'OK', this.s.me.handle, 'player', handle, b as Record<string, unknown>)
    return json(this.adminPlayer(handle))
  }

  private setStatus(handle: string, status: 'FROZEN' | 'ACTIVE', body: { reason?: string; revokeKeys?: boolean } | null): Response {
    if (handle === this.s.me.handle) return problem(403, 'FORBIDDEN', 'Forbidden', 'You cannot freeze yourself.')
    const p = this.other(handle)
    if (!p) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found')
    if (status === 'FROZEN' && !body?.reason?.trim()) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'Give a reason.', null, [{ field: 'reason', code: 'NotBlank', message: 'Give a reason.' }])
    p.status = status
    if (status === 'FROZEN' && body?.revokeKeys) for (const k of this.s.keys) if (k.handle === handle) k.revokedAt ??= iso()
    this.audit('ADMIN', status === 'FROZEN' ? 'PLAYER_FREEZE' : 'PLAYER_UNFREEZE', 'OK', this.s.me.handle, 'player', handle, body ? { reason: body.reason ?? null } : null)
    return json(this.adminPlayer(handle))
  }

  private resetPlayer(handle: string, body: unknown): Response {
    const b = (body ?? {}) as { startingCash?: string | null; memo?: string }
    if (!b.memo?.trim()) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'Add a memo.', null, [{ field: 'memo', code: 'NotBlank', message: 'Add a memo.' }])
    const starting = b.startingCash ? setScale(b.startingCash, 2) : (this.s.settings['league.defaultStartingCash'] ?? '500.00')
    if (handle === this.s.me.handle) {
      this.resetMe(starting, b.memo.trim())
      this.audit('ADMIN', 'PLAYER_RESET', 'OK', this.s.me.handle, 'player', handle)
      return json(this.account())
    }
    const p = this.other(handle)
    if (!p) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found')
    p.units = div(starting, p.u, 18, 'HALF_EVEN')
    p.cash = starting
    p.nc = starting
    p.resets += 1
    this.audit('ADMIN', 'PLAYER_RESET', 'OK', this.s.me.handle, 'player', handle)
    return json(this.syntheticAccount(p))
  }

  private liveView(handle: string, body: unknown): Response {
    const reason = (body as { reason?: string } | null)?.reason?.trim() ?? ''
    if (reason.length < 5) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'Give a reason (5–200 characters).', null, [{ field: 'reason', code: 'Size', message: '5–200 characters.' }])
    const mine = handle === this.s.me.handle
    const p = this.other(handle)
    if (!mine && !p) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found')
    this.audit('ADMIN', 'PLAYER_LIVE_VIEW', 'OK', this.s.me.handle, 'player', handle, { reason })
    if (mine) {
      this.s.ledger.push(this.ledgerRow({ at: iso(), type: 'ADMIN_VIEW', by: 'ADMIN', memo: reason, detail: { type: 'ADMIN_VIEW', admin: this.s.me.handle, reason } }))
    }
    const positions = mine
      ? this.positions()
      : p!.top
        ? [
            {
              ...this.positions()[0],
              symbol: p!.top,
              name: this.market.meta(p!.top)?.name ?? p!.top,
              qty: '1.5',
              availableQty: '1.5',
              avgCost: this.market.quote(p!.top).prevClose,
              costBasis: money(mul('1.5', this.market.quote(p!.top).prevClose ?? '0')),
              last: this.market.quote(p!.top).last,
              mark: this.market.quote(p!.top).last,
              markQuality: 'SIMULATED' as const,
              marketValue: money(mul('1.5', this.market.quote(p!.top).last ?? '0')),
              unrealizedPnl: money(mul('1.5', this.market.quote(p!.top).change ?? '0')),
              unrealizedReturn: this.market.quote(p!.top).changePct,
              dayPnl: money(mul('1.5', this.market.quote(p!.top).change ?? '0')),
              dayReturn: this.market.quote(p!.top).changePct,
              weight: '0.4',
              fractionable: true,
              openedAt: p!.createdAt,
            } satisfies Position,
          ]
        : []
    return json({
      player: mine ? this.mySummary() : this.summary(p!),
      viewedAt: iso(),
      reason,
      positions,
      openOrders: mine ? this.s.orders.filter(isOpen) : [],
    })
  }

  private transferRow(handle: string, accountId: string, type: 'INITIAL' | 'DEPOSIT' | 'WITHDRAWAL' | 'ADJUSTMENT', amount: Dec, memo: string, at: string, by: string | null, equityBefore: Dec | null, u: Dec | null, batchId?: string, contribution = true, status: 'APPLIED' | 'SKIPPED' = 'APPLIED', skipReason: string | null = null): TransferRow {
    return {
      transferId: publicId('xfr'),
      batchId: batchId ?? publicId('xfb'),
      handle,
      accountId,
      type,
      amount,
      isContribution: contribution,
      status,
      skipReason,
      memo,
      effectiveAt: at,
      by,
      equityBefore,
      unitValue: u,
    }
  }

  private parseTransfer(body: unknown): { ok: true; req: TransferInput } | { ok: false; res: Response } {
    const b = (body ?? {}) as Partial<TransferInput> & { target?: { scope?: string; handle?: string; kinds?: string[] } }
    const fields: FieldErr[] = []
    const type = b.type
    if (type !== 'DEPOSIT' && type !== 'WITHDRAWAL' && type !== 'ADJUSTMENT') fields.push({ field: 'type', code: 'NotNull', message: 'Choose deposit, withdrawal or adjustment.' })
    const amount = String(b.amount ?? '')
    if (!isDecimal(amount) || (type === 'ADJUSTMENT' ? isZero(amount) : sign(amount) <= 0)) fields.push({ field: 'amount', code: 'Invalid', message: type === 'ADJUSTMENT' ? 'Enter a non-zero amount.' : 'Enter a positive amount.' })
    else if (setScale(amount, 2, 'DOWN') !== amount && compare(setScale(amount, 2, 'DOWN'), amount) !== 0) fields.push({ field: 'amount', code: 'Scale', message: 'At most 2 decimals.' })
    const memo = (b.memo ?? '').trim()
    if (memo.length < 1 || memo.length > 200) fields.push({ field: 'memo', code: 'Size', message: 'A memo of 1–200 characters is required.' })
    const scope = b.target?.scope
    if (scope !== 'SINGLE' && scope !== 'ALL') fields.push({ field: 'target', code: 'NotNull', message: 'Choose one player or all.' })
    if (scope === 'SINGLE' && !b.target?.handle) fields.push({ field: 'target.handle', code: 'NotBlank', message: 'Choose a player.' })
    if (fields.length) return { ok: false, res: problem(400, 'VALIDATION_FAILED', 'Validation failed', fields[0]!.message, null, fields) }
    const kinds = (b.target?.kinds?.length ? b.target.kinds : ['HUMAN', 'AI']) as Array<'HUMAN' | 'AI'>
    return {
      ok: true,
      req: {
        target: scope === 'SINGLE' ? { scope: 'SINGLE', handle: b.target!.handle!.toLowerCase() } : { scope: 'ALL', kinds },
        type: type!,
        amount: setScale(amount, 2),
        treatAs: b.treatAs ?? 'CONTRIBUTION',
        memo,
        allOrNothing: Boolean(b.allOrNothing),
        acceptDegradedMarks: Boolean(b.acceptDegradedMarks),
      },
    }
  }

  private targets(req: TransferInput): string[] {
    if (req.target.scope === 'SINGLE') return [req.target.handle]
    const kinds = req.target.kinds
    const out: string[] = []
    if (kinds.includes('HUMAN')) out.push(this.s.me.handle)
    for (const p of this.s.others) if (kinds.includes(p.kind) && p.status !== 'DISABLED') out.push(p.handle)
    return out
  }

  private signedAmount(req: TransferInput): Dec {
    return req.type === 'WITHDRAWAL' ? negate(req.amount) : req.amount
  }

  private transferPreview(body: unknown): Response {
    const parsed = this.parseTransfer(body)
    if (!parsed.ok) return parsed.res
    const req = parsed.req
    const handles = this.targets(req)
    if (req.target.scope === 'SINGLE' && handles[0] !== this.s.me.handle && !this.other(handles[0]!)) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player @${handles[0]}.`)
    const signed = this.signedAmount(req)
    const accounts = handles.map((h) => {
      const mine = h === this.s.me.handle
      const p = this.other(h)
      const cash = mine ? this.s.cash : p!.cash
      const withdrawable = mine ? (this.account().cash?.withdrawable ?? '0.00') : p!.cash
      const equityBefore = mine ? this.equity() : this.otherEquity(p!)
      const u = mine ? this.unitValue() : p!.u
      const wouldApply = sign(signed) >= 0 || !gt(negate(signed), withdrawable)
      const warnings: Warning[] = []
      if (lt(equityBefore, '1.00')) warnings.push({ code: 'ACCOUNT_EQUITY_TOO_LOW', message: `Equity is ${equityBefore}; the flow still applies at U = ${setScale(u, 6)}.` })
      const holds = mine ? Object.keys(this.s.lots).length > 0 : Boolean(p!.top)
      return {
        handle: h,
        accountId: mine ? this.s.me.accountId : p!.accountId,
        cash,
        withdrawable,
        equityBefore,
        unitValue: u,
        marksQuality: holds ? ('SIMULATED' as const) : null,
        worstMarkBasis: holds ? 'LIVE' : null,
        wouldApply,
        skipReason: wouldApply ? null : 'INSUFFICIENT_WITHDRAWABLE',
        warnings,
      }
    })
    return json({ asOf: iso(), session: 'REGULAR', accounts })
  }

  private applyTransfer(body: unknown, idempotencyKey: string | null): Response {
    if (!idempotencyKey) return problem(400, 'VALIDATION_FAILED', 'Idempotency-Key required', 'Send an Idempotency-Key header.')
    const parsed = this.parseTransfer(body)
    if (!parsed.ok) return parsed.res
    const req = parsed.req
    const hash = JSON.stringify(req)
    const replay = this.s.idempotency[idempotencyKey]
    if (replay) {
      if (replay.hash !== hash) return problem(409, 'IDEMPOTENCY_KEY_CONFLICT', 'Idempotency conflict', 'This Idempotency-Key was used for a different transfer.')
      return json(replay.body, 201)
    }
    const handles = this.targets(req)
    if (req.target.scope === 'SINGLE' && handles[0] !== this.s.me.handle && !this.other(handles[0]!)) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player @${handles[0]}.`)
    const signed = this.signedAmount(req)
    const contribution = req.type !== 'ADJUSTMENT' || req.treatAs === 'CONTRIBUTION'
    const batchId = publicId('xfb')
    const now = iso()
    const results: Array<Record<string, unknown>> = []
    // all-or-nothing: check first
    const skips = handles.filter((h) => {
      if (sign(signed) >= 0) return false
      const withdrawable = h === this.s.me.handle ? (this.account().cash?.withdrawable ?? '0.00') : this.other(h)!.cash
      return gt(negate(signed), withdrawable)
    })
    if (req.allOrNothing && skips.length) {
      return problem(422, 'INSUFFICIENT_WITHDRAWABLE', 'Transfer not applied', `${skips.map((h) => `@${h}`).join(', ')} cannot cover the withdrawal; nothing was applied (all-or-nothing).`)
    }
    let applied = 0
    for (const h of handles) {
      const mine = h === this.s.me.handle
      const p = this.other(h)
      const equityBefore = mine ? this.equity() : this.otherEquity(p!)
      const u = mine ? this.unitValue() : p!.u
      const accountId = mine ? this.s.me.accountId : p!.accountId
      const displayName = mine ? this.s.me.displayName : p!.displayName
      if (skips.includes(h)) {
        const row = this.transferRow(h, accountId, req.type, signed, req.memo, now, this.s.me.handle, equityBefore, u, batchId, contribution, 'SKIPPED', 'INSUFFICIENT_WITHDRAWABLE')
        this.s.transfers.push(row)
        results.push({ transferId: row.transferId, handle: h, displayName, accountId, status: 'SKIPPED', skipReason: 'INSUFFICIENT_WITHDRAWABLE', amount: signed, equityBefore, unitValue: u, cashAfter: null, marksQuality: 'SIMULATED', rebased: false, warnings: [] })
        continue
      }
      applied += 1
      // §16.2: a contribution flow mints units at the current U, so U (and the return) never moves
      const unitsDelta = contribution && !isZero(u) ? div(signed, u, 18, 'HALF_EVEN') : '0'
      let cashAfter: Dec
      if (mine) {
        this.s.cash = add(this.s.cash, signed)
        if (contribution) this.s.nc = add(this.s.nc, signed)
        this.s.units = add(this.s.units, unitsDelta)
        cashAfter = this.s.cash
      } else {
        p!.cash = add(p!.cash, signed)
        if (contribution) {
          p!.nc = add(p!.nc, signed)
          p!.units = add(p!.units, unitsDelta)
        } else {
          // a P&L correction is performance: equity moves, units do not, so U moves
          p!.u = div(add(this.otherEquity(p!), signed), p!.units, 10, 'HALF_EVEN')
        }
        cashAfter = p!.cash
      }
      const row = this.transferRow(h, accountId, req.type, signed, req.memo, now, this.s.me.handle, equityBefore, u, batchId, contribution)
      this.s.transfers.push(row)
      results.push({ transferId: row.transferId, handle: h, displayName, accountId, status: 'APPLIED', skipReason: null, amount: signed, equityBefore, unitValue: u, cashAfter, marksQuality: 'SIMULATED', rebased: false, warnings: [] })
      if (mine) {
        const type = req.type === 'DEPOSIT' ? 'DEPOSIT' : req.type === 'WITHDRAWAL' ? 'WITHDRAWAL' : 'ADJUSTMENT'
        this.s.ledger.push(this.ledgerRow({ at: now, type, amount: signed, balanceAfter: this.s.cash, memo: req.memo, by: 'ADMIN', transferId: row.transferId, settleDate: TRADE_DATE }))
        const verb = req.type === 'DEPOSIT' ? 'deposited' : req.type === 'WITHDRAWAL' ? 'withdrew' : 'adjusted'
        const amountLabel = `$${setScale(req.type === 'WITHDRAWAL' ? req.amount : signed, 2)}`
        this.notify(type, `Admin ${verb} ${amountLabel}: ${req.memo}`, `Your cash is now $${this.s.cash}. Your return is unchanged: deposits never move it.`, { transferId: row.transferId })
        this.publish('/user/queue/account', this.account())
      }
    }
    this.audit('MONEY', `TRANSFER_${req.type}`, 'OK', this.s.me.handle, req.target.scope === 'SINGLE' ? 'player' : 'league', req.target.scope === 'SINGLE' ? req.target.handle : 'ALL', { amount: req.amount, memo: req.memo, applied })
    this.publish('/topic/leaderboard', { asOf: iso() })
    const result = {
      batchId,
      scope: req.target.scope,
      type: req.type,
      amount: req.amount,
      memo: req.memo,
      createdAt: now,
      applied,
      skipped: handles.length - applied,
      results,
    }
    this.s.idempotency[idempotencyKey] = { hash, body: result }
    return json(result, 201)
  }

  private putSettings(body: unknown): Response {
    const b = (body ?? {}) as Partial<Record<keyof Settings, unknown>>
    const next = { ...this.s.settings }
    for (const [k, v] of Object.entries(b) as Array<[keyof Settings, unknown]>) {
      if (v === null || v === undefined) continue
      if (!(k in next)) return problem(422, 'SETTING_INVALID', 'Unknown setting', `Unknown setting ${k}.`)
      ;(next as Record<string, unknown>)[k] = v
    }
    const cash = next['league.defaultStartingCash']
    if (!cash || !isDecimal(cash) || lt(cash, '1.00')) return problem(422, 'SETTING_INVALID', 'Invalid setting', 'Starting cash must be at least $1.00.', null, [{ field: 'league.defaultStartingCash', code: 'SETTING_INVALID', message: 'At least $1.00.' }])
    if (next['league.eligibility.minFills'] < 0 || next['league.eligibility.minSymbols'] < 0) return problem(422, 'SETTING_INVALID', 'Invalid setting', 'Eligibility minimums cannot be negative.')
    if (!/^P(?=\d|T\d)(?:\d+D)?(?:T(?=\d)(?:\d+H)?(?:\d+M)?(?:\d+S)?)?$/.test(next['social.tradeFeedDelay'] ?? '')) return problem(422, 'SETTING_INVALID', 'Invalid setting', 'Feed delay must be an ISO-8601 duration such as PT15M.', null, [{ field: 'social.tradeFeedDelay', code: 'SETTING_INVALID', message: 'ISO-8601 duration, e.g. PT15M.' }])
    next['league.defaultStartingCash'] = setScale(cash, 2)
    this.s.settings = next
    this.audit('ADMIN', 'SETTINGS_UPDATE', 'OK', this.s.me.handle, 'settings', null, b as Record<string, unknown>)
    return json(next)
  }

  private system(): SystemStatus {
    const now = Date.now()
    return {
      version: '0.1.0-mock',
      startedAt: iso(this.startedAt),
      db: 'h2',
      engine: { status: this.s.engineHalted ? 'HALTED' : 'RUNNING', queueDepth: 0, lastCommandAt: iso(now - 2_000), haltReason: this.s.engineHalted },
      invariants: { lastRunAt: iso(now - 6 * 60 * MIN), ok: true, failures: [] },
      marketData: {
        profile: 'mock',
        staleSymbols: [],
        corporateActionHolds: [],
        providers: [
          { id: 'sim', state: 'LIVE', streamSymbolsUsed: 12, streamSymbolsMax: 1000, restPerMinUsed: 0, restPerMinMax: 0, lastMessageAt: iso(now - 900), capabilities: { quotes: true, bars: true, news: false }, probe: { ok: true } },
          { id: 'alpaca', state: 'DISABLED', streamSymbolsUsed: 0, streamSymbolsMax: 30, restPerMinUsed: 0, restPerMinMax: 180, lastMessageAt: null, capabilities: {}, probe: { skipped: 'mock mode' } },
          { id: 'finnhub', state: 'DISABLED', streamSymbolsUsed: 0, streamSymbolsMax: 50, restPerMinUsed: 0, restPerMinMax: 60, lastMessageAt: null, capabilities: {}, probe: { skipped: 'mock mode' } },
        ],
      },
      jobs: [
        { name: 'EOD_SNAPSHOT', lastRunKey: '2026-10-08', status: 'SUCCEEDED', finishedAt: '2026-10-09T00:20:41.000Z', detail: '5 accounts' },
        { name: 'DAILY_MARK', lastRunKey: '2026-10-08', status: 'SUCCEEDED', finishedAt: '2026-10-08T20:20:12.000Z', detail: null },
        { name: 'ASSET_SYNC', lastRunKey: '2026-10-09', status: 'SUCCEEDED', finishedAt: '2026-10-09T08:00:05.000Z', detail: '11,842 assets' },
        { name: 'INVARIANTS', lastRunKey: '2026-10-09', status: 'SUCCEEDED', finishedAt: '2026-10-09T09:00:01.000Z', detail: 'I1–I9 OK' },
      ],
      corporateActionsNeedingAdmin: 0,
    }
  }

  /** Resets every bit of mock state (Settings → "Reset mock data"). */
  resetAll(): void {
    clearState()
    this.s = this.seed()
    this.persist()
  }
}

interface TransferInput {
  target: { scope: 'SINGLE'; handle: string } | { scope: 'ALL'; kinds: Array<'HUMAN' | 'AI'> }
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'ADJUSTMENT'
  amount: Dec
  treatAs: 'CONTRIBUTION' | 'PNL_CORRECTION'
  memo: string
  allOrNothing: boolean
  acceptDegradedMarks: boolean
}
