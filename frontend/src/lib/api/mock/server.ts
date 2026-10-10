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
import { MockOAuth } from './oauth'
import {
  type Dec,
  add,
  compare,
  div,
  fromInteger,
  gt,
  gte,
  isDecimal,
  isZero,
  lt,
  lte,
  max,
  min,
  mul,
  negate,
  roundToTick,
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
  CalendarOverride,
  ChartRange,
  Clock,
  CorporateAction,
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
  Rules,
  Run,
  Settings,
  SystemStatus,
  TransferRow,
  Warning,
} from '../types'
import { MOCK_INDEX_SYMBOLS, MOCK_INVITE_CODE, MOCK_ME_BASE, MOCK_PLAYERS, POPULAR_SYMBOLS } from './data'
import { MockMarket, hashString, mulberry32, unitsToDec } from './market'
import { mockRules } from './rules'
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

const DAY_MS = 24 * 60 * MIN
/** ET calendar date (YYYY-MM-DD) of an instant. */
const etDate = (ms: number): string => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'America/New_York' })
const STOP_TYPES = new Set(['STOP', 'STOP_LIMIT', 'TRAILING_STOP'])
const ORDER_TYPE_NAME: Record<string, string> = { MARKET: 'Market', LIMIT: 'Limit', STOP: 'Stop', STOP_LIMIT: 'Stop limit', TRAILING_STOP: 'Trailing stop' }
/** TRAIL_UPDATED is throttled to one every 10 s (§11.5). */
const TRAIL_EVENT_MS = 10_000
const isStopType = (t: string | null | undefined): boolean => t != null && STOP_TYPES.has(t)
/** A triggered STOP_LIMIT behaves as a limit order (§12.7). */
const limitLike = (o: Pick<Order, 'type' | 'triggered'>): boolean => o.type === 'LIMIT' || (o.type === 'STOP_LIMIT' && o.triggered)
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
  /** Jobs the admin ran by hand this session (§21.1 "Run now"): shown as the job's last run. */
  private readonly manualRuns = new Map<string, { runKey: string; finishedAt: string }>()
  readonly market: MockMarket
  private readonly listeners = new Set<Listener>()
  private readonly startedAt = Date.now()
  private loggedIn = true
  private csrfToken: string | null = null
  private s: MockState
  private readonly routes: Array<[string, RegExp, Handler]> = []
  /** §17.9 consent and Connected apps (mock/oauth.ts). */
  private readonly oauth = new MockOAuth({
    me: () => this.mySummary(),
    myBots: () => this.s.others.filter((p) => p.kind === 'AI' && p.status === 'ACTIVE' && p.ownerHandle === this.s.me.handle).map((p) => this.summary(p)),
    apiTradingAllowed: () => this.s.apiTradingAllowed,
  })

  constructor(market = new MockMarket()) {
    this.market = market
    try {
      this.loggedIn = sessionStorage.getItem('stocks.mock.loggedOut') !== '1'
    } catch {
      /* no storage */
    }
    const saved = loadState()
    this.s = saved ?? this.seed()
    this.registerRoutes()
    if (saved) this.resumeEngine()
  }

  /**
   * After a page reload the timers of the previous page are gone: re-arm them, the way the real engine reloads its
   * open orders at boot (§12.9). Pending orders activate, triggered stops go on to fill.
   */
  private resumeEngine(): void {
    for (const o of this.s.orders) {
      if (o.status === 'PENDING') setTimeout(() => this.activate(o.orderId!), 300 + Math.random() * 300)
      else if (o.status === 'WORKING' && o.triggered && o.waitReason === 'LATENCY') setTimeout(() => this.workTriggered(o.orderId!), 300 + Math.random() * 300)
    }
  }

  // ================================================================ seed

  private seed(): MockState {
    const openedAt = iso(this.startedAt - 9 * 24 * 60 * MIN)
    const s: MockState = {
      v: 3,
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
      corporateActions: [],
      liveFeed: [],
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
    this.seedCorporateActionHistory()
    this.seedFeed()
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

  /**
   * Corporate-action history (§14) so the Activity screen and the admin list show every kind: a 2-for-1 split, a
   * 1-for-2 reverse split of a whole-share symbol paid partly as cash in lieu, a symbol change, a paid dividend, an
   * accrued dividend (a receivable that counts in equity until it pays), an upcoming dividend, a detected split that
   * waits for the admin (a corporate-action hold) and a merger the admin must handle manually.
   */
  private seedCorporateActionHistory(): void {
    const s = this.s
    const ago = (days: number, hours = 0): number => this.startedAt - days * DAY_MS + hours * 60 * MIN
    const at = (days: number, hours = 0): string => iso(ago(days, hours))
    const buy = (symbol: string, qty: Dec, price: Dec, when: string) => {
      const o = this.newOrder({ clientOrderId: `web-seed-${symbol.toLowerCase()}`, symbol, side: 'BUY', type: 'LIMIT', qty, limitPrice: price, timeInForce: 'DAY' }, when)
      s.orders.push(o)
      this.pushEvent(o, 'ACCEPTED', when)
      this.pushEvent(o, 'WORKING', when)
      this.fillAt(o, price, qty, when, 'TOUCH')
    }
    const ca = (p: Partial<CorporateAction> & Pick<CorporateAction, 'symbol' | 'type' | 'exDate'>): CorporateAction => {
      const row: CorporateAction = {
        id: publicId('cac'),
        recordDate: null,
        payableDate: null,
        oldRate: null,
        newRate: null,
        cashRate: null,
        newSymbol: null,
        status: 'APPLIED',
        origin: 'PROVIDER',
        detail: null,
        createdAt: iso(Date.parse(`${p.exDate}T12:00:00Z`) - 7 * DAY_MS),
        appliedAt: null,
        resolvedBy: null,
        ...p,
      }
      s.corporateActions.push(row)
      return row
    }

    buy('F', '1', '23.0400', at(9))
    buy('XYZW', '3', '20.4000', at(7))
    buy('SIMX', '10', '0.4012', at(5, 1))

    // F cash dividend: ex-date 6 days ago (1 share entitled), paid 3 days ago (a DIVIDEND ledger row, settled at once).
    const fDiv = ca({ symbol: 'F', type: 'CASH_DIVIDEND', exDate: etDate(ago(6)), recordDate: etDate(ago(6)), payableDate: etDate(ago(3)), cashRate: '0.15', appliedAt: at(6, -1) })
    s.cash = add(s.cash, '0.15')
    s.ledger.push(
      this.ledgerRow({
        at: iso(ago(3) - 4 * 60 * MIN),
        type: 'DIVIDEND',
        symbol: 'F',
        qty: '1',
        price: '0.15',
        amount: '0.15',
        balanceAfter: s.cash,
        memo: `F cash dividend $0.15/share, ex-date ${fDiv.exDate}`,
        by: 'SYSTEM',
        tradeDate: fDiv.payableDate!,
        settleDate: fDiv.payableDate,
        detail: { type: 'DIVIDEND', corporateActionId: fDiv.id, status: 'PAID', qty: '1', cashRate: '0.15', exDate: fDiv.exDate, payableDate: fDiv.payableDate } as unknown as Activity['detail'],
      }),
    )
    // F 2-for-1 (old 1 → new 2): the quantity doubles, the cost is unchanged.
    const fSplit = ca({ symbol: 'F', type: 'FORWARD_SPLIT', exDate: etDate(ago(4)), oldRate: '1', newRate: '2', appliedAt: at(4, -1) })
    const f = s.lots.F!
    s.lots.F = { ...f, qty: '2' }
    s.ledger.push(
      this.eventRow({
        at: fSplit.appliedAt!,
        type: 'SPLIT',
        symbol: 'F',
        qty: '2',
        detail: { type: 'SPLIT', corporateActionId: fSplit.id, oldRate: '1', newRate: '2', qtyBefore: '1', qtyAfter: '2', costBasis: f.costBasis, cashInLieu: '0.00', dustQty: '0', realizedPnl: '0.00' },
      }),
    )
    // XYZW 1-for-2 reverse (old 2 → new 1), whole shares only: 3 → entitled 1.5 → keep 1, cash in lieu for 0.5 at
    // the pre-split close 20.61 × 2 / 1 = 41.22; the relieved cost 20.40 leaves +0.21 realized.
    const xySplit = ca({ symbol: 'XYZW', type: 'REVERSE_SPLIT', exDate: etDate(ago(3)), oldRate: '2', newRate: '1', appliedAt: at(3, -1) })
    const xy = s.lots.XYZW!
    s.lots.XYZW = { ...xy, qty: '1', costBasis: sub(xy.costBasis, '20.40') }
    s.ledger.push(
      this.eventRow({
        at: xySplit.appliedAt!,
        type: 'SPLIT',
        symbol: 'XYZW',
        qty: '1',
        detail: { type: 'SPLIT', corporateActionId: xySplit.id, oldRate: '2', newRate: '1', qtyBefore: '3', qtyAfter: '1', costBasis: s.lots.XYZW.costBasis, cashInLieu: '20.61', dustQty: '0.5', realizedPnl: '0.21' },
      }),
    )
    s.cash = add(s.cash, '20.61')
    s.ledger.push(
      this.ledgerRow({
        at: xySplit.appliedAt!,
        type: 'CASH_IN_LIEU',
        symbol: 'XYZW',
        qty: '0.5',
        price: '41.2200',
        amount: '20.61',
        balanceAfter: s.cash,
        memo: 'Cash in lieu of 0.5 XYZW share (1-for-2 reverse split); realized P&L +$0.21',
        by: 'SYSTEM',
        settleDate: etDate(ago(2)),
      }),
    )
    // SIMX is now SIMA: the position moves to the new symbol; history keeps the old one.
    const rename = ca({ symbol: 'SIMX', type: 'NAME_CHANGE', exDate: etDate(ago(3)), newSymbol: 'SIMA', appliedAt: at(3, -1) })
    s.lots.SIMA = { ...s.lots.SIMX!, symbol: 'SIMA' }
    delete s.lots.SIMX
    s.ledger.push(
      this.eventRow({ at: rename.appliedAt!, type: 'NAME_CHANGE', symbol: 'SIMA', qty: '10', detail: { type: 'NAME_CHANGE', corporateActionId: rename.id, oldSymbol: 'SIMX', newSymbol: 'SIMA', qty: '10' } }),
    )
    // XYZW: ex-date yesterday, pays in 12 days. Until then it is a receivable (planned `DividendDetail`, ACCRUED).
    const xyDiv = ca({ symbol: 'XYZW', type: 'CASH_DIVIDEND', exDate: etDate(ago(1)), recordDate: etDate(ago(1)), payableDate: etDate(ago(-12)), cashRate: '0.25', appliedAt: at(1, -1) })
    s.ledger.push({
      ...this.ledgerRow({
        at: xyDiv.appliedAt!,
        type: 'DIVIDEND',
        symbol: 'XYZW',
        qty: '1',
        price: '0.25',
        amount: '0.25',
        balanceAfter: null,
        by: 'SYSTEM',
        tradeDate: xyDiv.exDate!,
        settleDate: xyDiv.payableDate,
        detail: { type: 'DIVIDEND', corporateActionId: xyDiv.id, status: 'ACCRUED', qty: '1', cashRate: '0.25', exDate: xyDiv.exDate, payableDate: xyDiv.payableDate } as unknown as Activity['detail'],
      }),
      id: `div_${xyDiv.id!.slice(4)}`,
    })
    // Upcoming and pending-review actions (admin list).
    ca({ symbol: 'NVDA', type: 'CASH_DIVIDEND', exDate: etDate(ago(-6)), recordDate: etDate(ago(-6)), payableDate: etDate(ago(-20)), cashRate: '0.01', status: 'PENDING' })
    ca({
      symbol: 'SOFI',
      type: 'FORWARD_SPLIT',
      exDate: etDate(this.startedAt),
      oldRate: '1',
      newRate: '2',
      status: 'NEEDS_ADMIN',
      origin: 'DETECTED',
      detail: "The raw prior close 26.21 and the split-adjusted close 13.11 differ, and no split was reported: inferred 2-for-1. Trading in SOFI is on hold until you resolve it.",
      createdAt: iso(this.startedAt - 5 * 60 * MIN),
    })
    ca({
      symbol: 'SIMB',
      type: 'CASH_MERGER',
      exDate: etDate(ago(-2)),
      cashRate: '1.2500',
      status: 'NEEDS_ADMIN',
      detail: 'Cash merger at $1.25 per share reported by the provider. Mergers are applied manually in Phase 2; skip it to dismiss.',
      createdAt: iso(this.startedAt - 26 * 60 * MIN),
    })
  }

  /** Feed items that are not derived from transfers or trades: joins, a new leader, a closed position. */
  private seedFeed(): void {
    const s = this.s
    const byHandle = (h: string) => s.others.find((p) => p.handle === h)
    const item = (kind: NonNullable<FeedItem['kind']>, handle: string, at: number, symbol: string | null, payload: Partial<NonNullable<FeedItem['payload']>>): void => {
      const p = byHandle(handle)
      if (!p) return
      s.liveFeed.push({
        id: publicId('fde'),
        at: iso(at),
        kind,
        player: this.summary(p),
        symbol,
        payload: { side: null, qty: null, price: null, realizedPnl: null, amount: null, period: null, untrusted: { rationale: null, memo: null }, ...payload },
      })
    }
    const bob = byHandle('bob')
    if (bob) bob.resets = 1
    for (const [i, p] of s.others.entries()) item('JOINED', p.handle, this.startedAt - 9 * DAY_MS + (i + 1) * 17 * MIN, null, {})
    item('NEW_LEADER', 'claude', this.startedAt - 140 * MIN, null, { period: 'WEEK' })
    item('POSITION_CLOSED', 'alice', this.startedAt - 190 * MIN, 'AMD', { side: 'SELL', qty: '2', price: '166.2100', realizedPnl: '12.40' })
    item('RESET', 'bob', this.startedAt - 3 * DAY_MS, null, {})
  }

  // ================================================================ events (fake STOMP broker)

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private publish(destination: string, body: unknown): void {
    for (const l of this.listeners) l(destination, body)
  }

  /** Test hook: forget the last password confirmation, so the next `@RecentAuth` write asks for it (§17.1). */
  expireRecentAuth(): void {
    this.s.reauthAt = 0
    this.persist()
  }

  /** Symbols with a working order: the fake market keeps stepping them so stops and limits work off screen. */
  workingSymbols(): string[] {
    return [...new Set(this.s.orders.filter((o) => o.status === 'WORKING' && o.symbol).map((o) => o.symbol!))]
  }

  /** Called by the fake realtime driver on each market step. */
  onMarketStep(symbol: string): void {
    this.evaluateStops(symbol)
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
    // OAuth connectors (§17.9): the act-as consent and Settings → Connected apps
    r('GET', '/me/oauth/requests/{requestId}', ({ params }) => this.oauth.consent(params[0]!))
    r('POST', '/me/oauth/requests/{requestId}', ({ params, body }) => this.oauth.decide(params[0]!, body))
    r('GET', '/me/connected-apps', () => this.oauth.list())
    r('DELETE', '/me/connected-apps/{grantId}', ({ params }) => this.oauth.disconnect(params[0]!))
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
    r('GET', '/rules', () => json(this.rules()))
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
      const tf = url.searchParams.get('timeframe')
      const timeframe = (['1Min', '5Min', '15Min', '30Min', '1Hour', '1Day', '1Week', '1Month'] as const).find((t) => t.toLowerCase() === tf?.toLowerCase())
      return json(this.market.bars(symbol, range, timeframe))
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
      const types = url.searchParams.getAll('types').flatMap((v) => v.split(',')).map((t) => t.trim().toUpperCase()).filter(Boolean)
      // newest first by occurred-at (§18.2); the seed and corporate actions append out of order
      const rows = this.s.ledger
        .map((a, i) => ({ a, i }))
        .filter(({ a }) => types.length === 0 || (a.type && types.includes(a.type)))
        .sort((x, y) => Date.parse(y.a.at ?? '') - Date.parse(x.a.at ?? '') || y.i - x.i)
        .map(({ a }) => a)
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
        // §21.1: AI players only; a human's keys are personal (the admin must not hold a friend's live-read token)
        if (h === this.s.me.handle || this.other(h)?.kind !== 'AI') {
          return problem(403, 'FORBIDDEN', 'Forbidden', "API keys of a human are personal; the admin issues keys for AI players only.")
        }
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
    r('POST', '/admin/system/jobs/{jobName}/run', ({ params, body }) =>
      this.admin(() => {
        const name = params[0]!
        if (!this.system().jobs?.some((j) => j.name === name)) return problem(404, 'NOT_FOUND', 'Not found', `No job named ${name}.`)
        const runKey = (body as { runKey?: string | null } | null)?.runKey?.trim() || TRADE_DATE
        this.manualRuns.set(name, { runKey, finishedAt: iso(Date.now() + 1_500) })
        this.audit('ADMIN', 'JOB_RUN', 'OK', this.s.me.handle, 'job', name, { runKey })
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
    r('GET', '/admin/calendar/overrides', ({ url }) =>
      this.admin(() => {
        const from = url.searchParams.get('from') ?? '0000-01-01'
        const to = url.searchParams.get('to') ?? '9999-12-31'
        const rows = (this.s.calendarOverrides ?? []).filter((o) => o.date! >= from && o.date! <= to).sort((a, b) => a.date!.localeCompare(b.date!))
        return json(rows)
      }),
    )
    r('POST', '/admin/calendar/overrides', ({ body }) =>
      this.admin(() => {
        const b = (body ?? {}) as { date?: string; closed?: boolean | null; rthCloseAt?: string | null; postCloseAt?: string | null; reason?: string }
        const fields: FieldErr[] = []
        if (!b.date || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) fields.push({ field: 'date', code: 'NotNull', message: 'Pick a date.' })
        if (b.closed == null) fields.push({ field: 'closed', code: 'NotNull', message: 'Closed or early close?' })
        if (!b.reason?.trim()) fields.push({ field: 'reason', code: 'NotBlank', message: 'Give a reason.' })
        if (b.closed === false && !b.rthCloseAt) fields.push({ field: 'rthCloseAt', code: 'NotNull', message: 'An early close needs the regular-session close time.' })
        if (fields.length) return problem(400, 'VALIDATION_FAILED', 'Validation failed', fields[0]!.message, null, fields)
        const row: CalendarOverride = {
          date: b.date!,
          closed: Boolean(b.closed),
          rthCloseAt: b.closed ? null : (b.rthCloseAt ?? null),
          postCloseAt: b.closed ? null : (b.postCloseAt ?? null),
          reason: b.reason!.trim(),
          createdBy: this.s.me.handle,
          createdAt: iso(),
        }
        this.s.calendarOverrides = [...(this.s.calendarOverrides ?? []).filter((o) => o.date !== row.date), row]
        this.audit('ADMIN', 'CALENDAR_OVERRIDE', 'OK', this.s.me.handle, 'calendar', row.date)
        return json(row, 201)
      }, true),
    )
    r('DELETE', '/admin/calendar/overrides/{date}', ({ params }) =>
      this.admin(() => {
        const list = this.s.calendarOverrides ?? []
        if (!list.some((o) => o.date === params[0])) return problem(404, 'NOT_FOUND', 'Not found', `No override on ${params[0]}.`)
        this.s.calendarOverrides = list.filter((o) => o.date !== params[0])
        this.audit('ADMIN', 'CALENDAR_OVERRIDE_DELETE', 'OK', this.s.me.handle, 'calendar', params[0] ?? null)
        return noContent()
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
    r('GET', '/admin/corporate-actions', ({ url }) =>
      this.admin(() => {
        const status = url.searchParams.get('status')?.toUpperCase()
        const rows = this.s.corporateActions
          .filter((c) => !status || c.status === status)
          .sort((a, b) => Date.parse(b.createdAt ?? '') - Date.parse(a.createdAt ?? '') || (b.exDate ?? '').localeCompare(a.exDate ?? ''))
        return json(this.page(rows, url))
      }),
    )
    r('POST', '/admin/corporate-actions/{id}/resolve', ({ params, body }) => this.admin(() => this.resolveCorporateAction(params[0]!, body), true))
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

  /** `GET /rules` (§18.3): the mock accepts every Phase 1 order type (the full §11.1 matrix). */
  private rules(): Rules {
    return mockRules(this.s.settings, iso())
  }

  /** Symbols on a corporate-action hold (§14): a split waiting for the admin with an ex-date today or earlier. */
  private holdSymbols(): string[] {
    const today = etDate(Date.now())
    return this.s.corporateActions
      .filter((c) => c.status === 'NEEDS_ADMIN' && (c.type === 'FORWARD_SPLIT' || c.type === 'REVERSE_SPLIT') && (c.exDate ?? '') <= today)
      .map((c) => c.symbol!)
  }

  /**
   * `POST /admin/corporate-actions/{id}/resolve` (§14, §21.1): APPLY a split with confirmed rates (per-lot quantity,
   * cash in lieu for whole-share symbols, open orders on the symbol cancelled, a SPLIT activity), or SKIP it.
   */
  private resolveCorporateAction(id: string, body: unknown): Response {
    const c = this.s.corporateActions.find((x) => x.id === id)
    if (!c) return problem(404, 'NOT_FOUND', 'Not found', `No corporate action ${id}.`)
    const b = (body ?? {}) as { resolution?: string | null; oldRate?: string | null; newRate?: string | null; note?: string | null }
    const fields: FieldErr[] = []
    const resolution = b.resolution?.toUpperCase()
    if (resolution !== 'APPLY' && resolution !== 'SKIP') fields.push({ field: 'resolution', code: 'NotNull', message: 'Choose apply or skip.' })
    const note = b.note?.trim() ?? ''
    if (note.length < 1 || note.length > 200) fields.push({ field: 'note', code: 'Size', message: 'A note of 1–200 characters is required.' })
    if (resolution === 'APPLY') {
      for (const f of ['oldRate', 'newRate'] as const) {
        const v = b[f]
        if (!v || !isDecimal(v) || sign(v) <= 0) fields.push({ field: f, code: 'Positive', message: 'Enter a positive number of shares.' })
      }
    }
    if (fields.length) return problem(400, 'VALIDATION_FAILED', 'Validation failed', fields[0]!.message, null, fields)
    const split = c.type === 'FORWARD_SPLIT' || c.type === 'REVERSE_SPLIT'
    if (c.status !== 'NEEDS_ADMIN' || (resolution === 'APPLY' && !split)) {
      return problem(
        422,
        'CORPORATE_ACTION_NOT_RESOLVABLE',
        'Not resolvable',
        c.status !== 'NEEDS_ADMIN' ? `This action is already ${String(c.status).toLowerCase().replaceAll('_', ' ')}.` : 'Phase 1 applies forward and reverse splits only; skip this one.',
      )
    }
    const now = iso()
    if (resolution === 'APPLY') {
      const oldRate = b.oldRate!
      const newRate = b.newRate!
      c.oldRate = oldRate
      c.newRate = newRate
      c.type = compare(newRate, oldRate) >= 0 ? 'FORWARD_SPLIT' : 'REVERSE_SPLIT'
      const symbol = c.symbol!
      for (const o of this.s.orders.filter((x) => isOpen(x) && x.symbol === symbol)) this.finish(o, 'CANCELLED', 'CORPORATE_ACTION')
      const lot = this.s.lots[symbol]
      if (lot) {
        // §14 split arithmetic: multiply first, divide last, with an explicit scale
        const entitled = div(mul(lot.qty, newRate), oldRate, 5, 'FLOOR')
        const fractionable = this.market.meta(symbol)?.fractionable ?? true
        const keep = fractionable ? entitled : setScale(entitled, 0, 'FLOOR')
        const cilQty = sub(entitled, keep)
        let cil = '0.00'
        let realized = '0.00'
        let cost = lot.costBasis
        if (sign(cilQty) > 0) {
          const prev = this.market.quote(symbol).prevClose ?? '0'
          cil = setScale(div(mul(mul(cilQty, prev), oldRate), newRate, 10, 'HALF_EVEN'), 2, 'FLOOR')
          const relieved = money(div(mul(lot.costBasis, cilQty), entitled, 10, 'HALF_EVEN'))
          cost = sub(lot.costBasis, relieved)
          realized = sub(cil, relieved)
        }
        const before = lot.qty
        if (sign(keep) > 0) this.s.lots[symbol] = { ...lot, qty: keep, costBasis: cost }
        else delete this.s.lots[symbol]
        this.s.ledger.push(
          this.eventRow({
            at: now,
            type: 'SPLIT',
            symbol,
            qty: keep,
            detail: { type: 'SPLIT', corporateActionId: c.id, oldRate, newRate, qtyBefore: before, qtyAfter: keep, costBasis: cost, cashInLieu: cil, dustQty: cilQty, realizedPnl: realized },
          }),
        )
        if (sign(cil) > 0) {
          this.s.cash = add(this.s.cash, cil)
          this.s.ledger.push(this.ledgerRow({ at: now, type: 'CASH_IN_LIEU', symbol, qty: cilQty, amount: cil, balanceAfter: this.s.cash, memo: `Cash in lieu of ${cilQty} ${symbol} share(s)`, by: 'SYSTEM' }))
        }
        this.notify('CORPORATE_ACTION', `${symbol} split ${newRate}-for-${oldRate}`, `Your ${before} shares are now ${keep}.`, { corporateActionId: c.id })
        this.publish('/user/queue/account', this.account())
      }
      c.status = 'APPLIED'
      c.appliedAt = now
    } else {
      c.status = 'SKIPPED'
    }
    c.resolvedBy = this.s.me.handle
    this.audit('ADMIN', 'CORPORATE_ACTION_RESOLVE', 'OK', this.s.me.handle, 'corporate_action', c.id, { resolution, note })
    return json(c)
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
      tradable: !this.holdSymbols().includes(symbol),
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

  /** Accrued, unpaid dividends (§16.1 receivable): they count in equity from the ex-date until they pay. */
  private receivable(): Dec {
    return this.s.ledger
      .filter((a) => a.type === 'DIVIDEND' && (a.detail as { status?: string } | null)?.status === 'ACCRUED')
      .reduce((acc, a) => add(acc, a.amount ?? '0.00'), '0.00')
  }

  private equity(): Dec {
    return add(add(this.s.cash, this.longValue()), this.receivable())
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
      dividendsReceivable: this.receivable(),
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

  /** An `account_event` row (SPLIT, NAME_CHANGE, …) as an activity: `aev_` id, no cash amount (§18.2). */
  private eventRow(p: Partial<Activity> & Pick<Activity, 'type' | 'at'>): Activity {
    return { ...this.ledgerRow({ by: 'SYSTEM', ...p }), id: publicId('aev') }
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
    // §11.2 checks 15–20 for every Phase 1 type (the mock accepts the full §11.1 matrix)
    const usesLimit = type === 'LIMIT' || type === 'STOP_LIMIT'
    const usesStop = type === 'STOP' || type === 'STOP_LIMIT'
    const usesTrail = type === 'TRAILING_STOP'
    const label = ORDER_TYPE_NAME[type] ?? type
    if (req.limitPrice != null && !usesLimit) err('PRICE_NOT_ALLOWED', 'limitPrice', `${label} orders take no limit price.`)
    if (req.stopPrice != null && !usesStop) err('PRICE_NOT_ALLOWED', 'stopPrice', usesTrail ? "A trailing stop's stop is computed from the trail." : `${label} orders take no stop price.`)
    if ((req.trailAmount != null || req.trailPercent != null) && !usesTrail) err('PRICE_NOT_ALLOWED', req.trailAmount != null ? 'trailAmount' : 'trailPercent', `${label} orders take no trail.`)
    if (usesLimit && req.limitPrice == null) err('LIMIT_PRICE_REQUIRED', 'limitPrice', 'Enter a limit price.')
    if (usesStop && req.stopPrice == null) err('STOP_PRICE_REQUIRED', 'stopPrice', 'Enter a stop price (the trigger).')
    if (usesTrail && (req.trailAmount == null) === (req.trailPercent == null)) {
      err('TRAIL_REQUIRED', req.trailAmount != null ? 'trailPercent' : 'trailAmount', 'Enter a trail in dollars or in percent (one of them).', 'For example 5% trails 5% behind the best price since the order started.')
    }
    if (req.trailPercent != null && (!isDecimal(req.trailPercent) || sign(req.trailPercent) <= 0 || gte(req.trailPercent, '100'))) {
      err('VALIDATION_FAILED', 'trailPercent', 'The trail must be more than 0% and less than 100%.')
    }
    if (req.trailAmount != null && (!isDecimal(req.trailAmount) || sign(req.trailAmount) <= 0 || compare(setScale(req.trailAmount, tickFor(last) === '0.01' ? 2 : 4, 'DOWN'), req.trailAmount) !== 0)) {
      err('INVALID_TICK', 'trailAmount', `The trail must be a positive multiple of $${tickFor(last)}.`)
    }
    if ((type === 'MARKET' || type === 'TRAILING_STOP') && tif === 'GTC') err('TIF_NOT_ALLOWED', 'timeInForce', `${label} orders are Day only.`)
    if (req.extendedHours && type !== 'LIMIT') err('EXT_HOURS_LIMIT_ONLY', 'extendedHours', 'Extended hours need a Limit order.')
    const priceFields: Array<readonly ['limitPrice' | 'stopPrice', Dec | null | undefined]> = [
      ['limitPrice', usesLimit ? req.limitPrice : null],
      ['stopPrice', usesStop ? req.stopPrice : null],
    ]
    for (const [field, price] of priceFields) {
      if (price == null) continue
      if (!isDecimal(price) || sign(price) <= 0) err('INVALID_TICK', field, 'Enter a valid price.')
      else if (compare(setScale(price, tickFor(price) === '0.01' ? 2 : 4, 'DOWN'), price) !== 0) err('INVALID_TICK', field, `Prices must be in steps of $${tickFor(price)}.`)
      else {
        // check 19: a buy stop above the market, a sell stop below it
        if (field === 'stopPrice' && (side === 'BUY' ? lte(price, last) : gte(price, last))) {
          err('STOP_PRICE_WRONG_SIDE', 'stopPrice', side === 'BUY' ? `A buy stop must be above the last price $${setScale(last, 2)}.` : `A sell stop must be below the last price $${setScale(last, 2)}.`)
          continue
        }
        const away = div(sub(price, last), last, 6, 'HALF_UP')
        if (gt(away, '0.5') || lt(away, '-0.5')) err('PRICE_TOO_FAR', field, 'The price is more than 50% away from the last price.')
        else if (gt(away, '0.05') || lt(away, '-0.05')) warnings.push({ code: 'FAT_FINGER', message: `Your ${field === 'stopPrice' ? 'stop' : 'limit'} price is more than 5% away from the last price.` })
      }
    }
    if (side === 'SELL' && !lot) err('SHORT_SELLING_NOT_ALLOWED', 'side', 'You have no position to sell. Short selling is not allowed.')
    if (this.s.orders.filter(isOpen).length >= 50) err('TOO_MANY_OPEN_ORDERS', null, 'You already have 50 open orders.')

    let estimate: Estimate | null = null
    let buyingPowerAfter: Dec | null = null
    if (errors.length === 0) {
      const touch = side === 'BUY' ? ask : bid
      // the trailing stop's starting trigger (§12.7): last ∓ trail, on tick
      const trail = usesTrail ? this.trailDistance(req.trailAmount ?? null, req.trailPercent ?? null, last) : null
      const trailStop = trail ? (side === 'BUY' ? roundToTick(add(last, trail), 'CEILING') : roundToTick(sub(last, trail), 'FLOOR')) : null
      // the estimate: a limit at its price (or better), a stop at its trigger, a market order at the touch
      const refPrice =
        type === 'LIMIT'
          ? side === 'BUY'
            ? min(req.limitPrice!, ask)
            : max(req.limitPrice!, bid)
          : type === 'STOP_LIMIT'
            ? req.limitPrice!
            : type === 'STOP'
              ? req.stopPrice!
              : trailStop ?? touch
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
              : type === 'STOP'
                ? setScale(add(mul(mul(estQty, max(req.stopPrice!, last)), '1.02'), fees.cat ?? '0'), 2, 'CEILING')
                : type === 'TRAILING_STOP'
                  ? setScale(add(mul(mul(estQty, add(last, trail ?? '0')), '1.02'), fees.cat ?? '0'), 2, 'CEILING')
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
    order.waitReason = order.type === 'LIMIT' ? 'LIMIT_NOT_REACHED' : isStopType(order.type) ? 'NOT_TRIGGERED' : null
    if (order.type === 'TRAILING_STOP' && order.symbol) {
      // §12.7: the watermark starts at the first print (the mock's last), the stop trails it
      const last = unitsToDec(this.market.bidAsk(order.symbol)?.last ?? 0)
      order.watermarkPrice = last
      order.stopPrice = this.trailStop(order, last)
    }
    this.pushEvent(order, 'WORKING')
    if (order.type === 'MARKET' || this.marketable(order)) {
      this.fill(order)
    } else {
      this.emitOrder('WORKING', order, null)
      // the opening-gap rule (§12.7): already through the stop at activation triggers at once
      if (isStopType(order.type)) this.evaluateStops(order.symbol!)
    }
    this.persist()
  }

  /** A trailing stop's dollar distance at a reference price: the amount, or ref × pct / 100. */
  private trailDistance(amount: Dec | null, percent: Dec | null, ref: Dec): Dec | null {
    if (amount != null && isDecimal(amount)) return amount
    if (percent != null && isDecimal(percent)) return div(mul(ref, percent), '100', 8, 'HALF_UP')
    return null
  }

  /** §12.7: SELL `TICK_DOWN(hwm − trail)`, BUY `TICK_UP(lwm + trail)`. */
  private trailStop(order: Order, watermark: Dec): Dec {
    const trail = this.trailDistance(order.trailAmount, order.trailPercent, watermark) ?? '0'
    return order.side === 'SELL' ? roundToTick(sub(watermark, trail), 'FLOOR') : roundToTick(add(watermark, trail), 'CEILING')
  }

  /**
   * The mock `TriggerEvaluator` (§12.7) for one symbol, on each market step: trailing watermarks follow the last
   * price (TRAIL_UPDATED at most every 10 s), and a stop triggers when the last trade reaches it (SELL ≤ stop,
   * BUY ≥ stop). A triggered STOP / TRAILING_STOP fills like a market order, a STOP_LIMIT works as a limit.
   */
  private evaluateStops(symbol: string): void {
    const ba = this.market.bidAsk(symbol)
    if (!ba) return
    const last = unitsToDec(ba.last)
    let changed = false
    for (const order of this.s.orders) {
      if (order.symbol !== symbol || order.status !== 'WORKING' || !isStopType(order.type) || order.triggered) continue
      if (order.type === 'TRAILING_STOP') {
        const wm = order.watermarkPrice ?? last
        const next = order.side === 'SELL' ? max(wm, last) : min(wm, last)
        if (compare(next, wm) !== 0 || order.stopPrice == null) {
          order.watermarkPrice = next
          const stop = this.trailStop(order, next)
          if (order.stopPrice == null || compare(stop, order.stopPrice) !== 0) {
            order.stopPrice = stop
            const events = this.s.events[order.orderId!] ?? []
            const lastTrail = events.findLast((e) => e.type === 'TRAIL_UPDATED')
            if (!lastTrail || Date.now() - Date.parse(lastTrail.at ?? '') >= TRAIL_EVENT_MS) {
              this.pushEvent(order, 'TRAIL_UPDATED', iso(), { watermark: next, stopPrice: stop })
            }
            this.emitOrder('TRAIL_UPDATED', order, null)
          }
          changed = true
        }
      }
      const stop = order.stopPrice
      if (!stop) continue
      const through = order.side === 'SELL' ? lte(last, stop) : gte(last, stop)
      if (!through) continue
      const at = iso()
      order.triggered = true
      order.triggeredAt = at
      order.waitReason = 'LATENCY'
      this.pushEvent(order, 'TRIGGERED', at, {
        price: last,
        basis: 'PRINT',
        stopPrice: stop,
        ...(order.type === 'TRAILING_STOP' ? { watermark: order.watermarkPrice } : {}),
        eligibleAt: iso(Date.parse(at) + 400),
      })
      this.emitOrder('TRIGGERED', order, null)
      changed = true
      const id = order.orderId!
      setTimeout(() => this.workTriggered(id), 350 + Math.random() * 250)
    }
    if (changed) this.persist()
  }

  /** After the trigger latency: a STOP / TRAILING_STOP fills at the touch, a STOP_LIMIT works as a limit (§12.7). */
  private workTriggered(orderId: string): void {
    const order = this.s.orders.find((o) => o.orderId === orderId)
    if (!order || !isOpen(order)) return
    if (order.type === 'STOP_LIMIT') {
      order.waitReason = 'LIMIT_NOT_REACHED'
      if (this.marketable(order)) this.fill(order)
      else this.emitOrder('WORKING', order, null)
    } else {
      this.fill(order)
    }
    this.persist()
  }

  private marketable(order: Order): boolean {
    if (!limitLike(order) || !order.limitPrice || !order.symbol) return false
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
    const price = limitLike(order) && order.limitPrice ? (liquidity === 'RESTING' ? order.limitPrice : order.side === 'BUY' ? min(order.limitPrice, touch) : max(order.limitPrice, touch)) : touch
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
    items.push(...this.s.liveFeed.filter((i) => Date.parse(i.at ?? '') <= Date.now()))
    return items.sort((a, b) => Date.parse(b.at ?? '') - Date.parse(a.at ?? ''))
  }

  /**
   * One live league event (§19 `/topic/feed`): a bot or friend trade, published when it becomes visible. Mock mode
   * emits one every 20–45 s while the page is open; e2e calls it through `window.__stocksMock.emitFeed()`.
   */
  emitFeed(kind: 'FILL' | 'POSITION_CLOSED' | 'NEW_LEADER' | 'DEPOSIT' | 'JOINED' | 'RESET' = 'FILL', handle?: string): FeedItem | null {
    const candidates = this.s.others.filter((p) => p.status !== 'DISABLED')
    const p = (handle ? candidates.find((x) => x.handle === handle) : null) ?? candidates[Math.floor(Math.random() * candidates.length)]
    if (!p) return null
    const symbols = ['NVDA', 'AAPL', 'MSFT', 'TSLA', 'AMD', 'SPY', 'META', 'GOOGL']
    const symbol = kind === 'FILL' || kind === 'POSITION_CLOSED' ? (p.top ?? symbols[Math.floor(Math.random() * symbols.length)]!) : null
    const side = kind === 'POSITION_CLOSED' ? 'SELL' : Math.random() > 0.4 ? 'BUY' : 'SELL'
    const price = symbol ? setScale(unitsToDec(this.market.bidAsk(symbol)?.last ?? 0), 2) : null
    const item: FeedItem = {
      id: publicId('fde'),
      at: iso(),
      kind,
      player: this.summary(p),
      symbol,
      payload: {
        side: symbol ? side : null,
        qty: symbol ? String(1 + Math.floor(Math.random() * 3)) : null,
        price,
        realizedPnl: kind === 'POSITION_CLOSED' ? setScale(((Math.random() - 0.35) * 30).toFixed(2), 2) : null,
        amount: kind === 'DEPOSIT' ? '100.00' : null,
        period: kind === 'NEW_LEADER' ? 'TODAY' : null,
        untrusted: {
          rationale: p.kind === 'AI' && symbol ? `${side === 'BUY' ? 'Adding' : 'Trimming'} ${symbol}: ${side === 'BUY' ? 'momentum held above the 20-day average' : 'locking in gains, keeping settled cash'}.` : null,
          memo: kind === 'DEPOSIT' ? 'Weekly top-up' : null,
        },
      },
    }
    this.s.liveFeed.push(item)
    if (this.s.liveFeed.length > 200) this.s.liveFeed.splice(0, this.s.liveFeed.length - 200)
    this.persist()
    this.publish('/topic/feed', item)
    return item
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
    const status: SystemStatus = {
      version: '0.1.0-mock',
      startedAt: iso(this.startedAt),
      db: 'h2',
      engine: { status: this.s.engineHalted ? 'HALTED' : 'RUNNING', queueDepth: 0, lastCommandAt: iso(now - 2_000), haltReason: this.s.engineHalted },
      invariants: { lastRunAt: iso(now - 6 * 60 * MIN), ok: true, failures: [] },
      marketData: {
        profile: 'mock',
        staleSymbols: [],
        corporateActionHolds: this.holdSymbols(),
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
        { name: 'CORPORATE_ACTION_SYNC', lastRunKey: '2026-10-09', status: 'SUCCEEDED', finishedAt: '2026-10-09T06:30:04.000Z', detail: '3 upserted' },
        { name: 'APPLY_CORPORATE_ACTIONS', lastRunKey: '2026-10-09', status: 'SUCCEEDED', finishedAt: '2026-10-09T07:45:02.000Z', detail: '0 applied' },
        { name: 'PAY_DIVIDENDS', lastRunKey: '2026-10-09', status: 'SUCCEEDED', finishedAt: '2026-10-09T10:00:01.000Z', detail: '0 paid' },
        { name: 'SPLIT_GUARD', lastRunKey: '2026-10-09T09:20', status: 'SUCCEEDED', finishedAt: '2026-10-09T13:20:03.000Z', detail: '1 detected (SOFI)' },
      ],
      corporateActionsNeedingAdmin: this.s.corporateActions.filter((c) => c.status === 'NEEDS_ADMIN').length,
    }
    status.jobs = status.jobs!.map((j) => {
      const run = this.manualRuns.get(j.name!)
      if (!run) return j
      const done = Date.parse(run.finishedAt) <= now
      return { ...j, lastRunKey: run.runKey, status: done ? 'SUCCEEDED' : 'RUNNING', finishedAt: done ? run.finishedAt : null, detail: done ? 'Run by the admin' : null }
    })
    return status
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
