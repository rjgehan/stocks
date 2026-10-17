import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest'
import type { Activity, Leaderboard, Order, PlaceOrderResponse, PlayerProfile, PublicOrder, PublicPortfolio, Rules, Settings, TransferBatchResult } from '../types'
import { MockServer, durationMs } from './server'
import { roundToTick } from '../../format/decimal'

/**
 * The mock backend (§22.9) must answer with the exact §18.3 / §21.1 shapes, so screens built against it work
 * unchanged against the real API. These tests pin the member sets of the core DTOs (the §18.4 golden sample for
 * `Order`) and the money flows the e2e suite relies on.
 */

const ORDER_KEYS = [
  'orderId', 'clientOrderId', 'accountId', 'symbol', 'side', 'type', 'qty', 'notional', 'fractional', 'limitPrice', 'stopPrice',
  'trailAmount', 'trailPercent', 'watermarkPrice', 'collarPrice', 'timeInForce', 'extendedHours', 'status', 'waitReason', 'statusReason',
  'triggered', 'filledQty', 'filledPrincipal', 'avgFillPrice', 'fees', 'reservedCash', 'reservedQty', 'sessionDate', 'receivedAt',
  'acceptedAt', 'eligibleAt', 'activatedAt', 'triggeredAt', 'expiresAt', 'doneAt', 'replacesOrderId', 'replacedByOrderId', 'channel',
  'rationale', 'executions', 'events',
  // Phase 2 (§11.9 group, §14 doNotReduce, §11.10 cancelRequestedAt)
  'group', 'doNotReduce', 'cancelRequestedAt',
]
const ACCOUNT_KEYS = [
  'accountId', 'runNo', 'type', 'status', 'restriction', 'restrictionUntil', 'restrictionReason', 'equity', 'longMarketValue',
  'dividendsReceivable', 'cash', 'openBuyHolds', 'buyingPower', 'startingCash', 'netContributions', 'totalPnl', 'totalReturn', 'unitValue',
  'dayPnl', 'dayReturn', 'gfv', 'dayTradesLast5Days', 'openedAt', 'marksQuality', 'asOf',
]
const POSITION_KEYS = [
  'symbol', 'name', 'qty', 'availableQty', 'avgCost', 'costBasis', 'last', 'mark', 'markQuality', 'marketValue', 'unrealizedPnl',
  'unrealizedReturn', 'dayPnl', 'dayReturn', 'weight', 'fractionable', 'openedAt',
]
const QUOTE_KEYS = [
  'symbol', 'bid', 'bidSize', 'ask', 'askSize', 'last', 'lastSize', 'lastAt', 'prevClose', 'change', 'changePct', 'dayOpen', 'dayHigh',
  'dayLow', 'volume', 'volumeIsPartial', 'extended', 'asOf', 'ageMs', 'quality', 'source', 'halted', 'session',
]
const ACTIVITY_KEYS = [
  'id', 'at', 'type', 'symbol', 'side', 'qty', 'price', 'fees', 'amount', 'balanceAfter', 'tradeDate', 'settleDate', 'memo', 'by',
  'orderId', 'executionId', 'transferId', 'detail',
]
const LEADERBOARD_ROW_KEYS = [
  'rank', 'rankChange', 'eligible', 'eligibilityNote', 'partialPeriod', 'rebased', 'player', 'apiTrader', 'return', 'pnl',
  'excessVsBenchmark', 'maxDrawdown', 'equity', 'netContributions', 'flowsInPeriod', 'fills', 'symbolsTraded', 'winRate', 'topHolding', 'spark',
  // Phase 2 (§16.8)
  'sharpe', 'profitFactor',
  // today and all-time, whatever the period
  'dayReturn', 'dayPnl', 'totalReturn', 'totalPnl',
]

// public mode (§16.7): exactly the generated schema's members (the type checks below fail when the backend adds one)
const PUBLIC_PORTFOLIO_KEYS = [
  'player', 'runNo', 'asOf', 'marksQuality', 'equity', 'longMarketValue', 'dividendsReceivable', 'cash', 'settledCash', 'unsettledCash',
  'buyingPower', 'openBuyHolds', 'netContributions', 'totalPnl', 'totalReturn', 'dayPnl', 'dayReturn', 'unrealizedPnl', 'restriction',
  'restrictionUntil', 'gfvActive', 'positions', 'openOrders',
] as const satisfies ReadonlyArray<keyof PublicPortfolio>
const PUBLIC_ORDER_KEYS = [
  'orderId', 'runNo', 'symbol', 'side', 'type', 'qty', 'notional', 'fractional', 'limitPrice', 'stopPrice', 'trailAmount', 'trailPercent',
  'timeInForce', 'extendedHours', 'status', 'waitReason', 'statusReason', 'filledQty', 'avgFillPrice', 'fees', 'receivedAt', 'expiresAt',
  'doneAt', 'replacesOrderId', 'replacedByOrderId', 'group', 'channel', 'viaApi', 'manualSessionId', 'fills', 'untrusted',
] as const satisfies ReadonlyArray<keyof PublicOrder>
const PUBLIC_FILL_KEYS = ['executionId', 'executedAt', 'qty', 'price', 'fees', 'realizedPnl'] as const

const keys = (o: unknown) => Object.keys(o as object).sort()
const DECIMAL = /^-?\d+(\.\d+)?$/
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

let server: MockServer
let csrf = ''

async function call<T = unknown>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<{ status: number; body: T }> {
  if (!csrf) {
    await server.handle(new Request('http://localhost/api/v1/auth/csrf'))
    csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? ''
  }
  const res = await server.handle(
    new Request(`http://localhost/api/v1${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  )
  return { status: res.status, body: (res.status === 204 ? null : await res.json()) as T }
}

beforeEach(() => {
  sessionStorage.clear()
  csrf = ''
  server = new MockServer()
})

describe('mock server: §18.3 shapes', () => {
  it('Quote, Account and Position carry exactly the §18.3 members, decimals as strings', async () => {
    const quotes = await call<{ quotes: Array<Record<string, unknown>> }>('GET', '/quotes?symbols=AAPL,NOPE')
    expect(quotes.body.quotes).toHaveLength(2)
    expect(keys(quotes.body.quotes[0])).toEqual([...QUOTE_KEYS].sort())
    expect(quotes.body.quotes[0]!.last).toMatch(DECIMAL)

    const account = await call<Record<string, unknown>>('GET', '/account')
    expect(keys(account.body)).toEqual([...ACCOUNT_KEYS].sort())
    expect(keys(account.body.cash)).toEqual(['settled', 'settledAvailable', 'total', 'unsettled', 'withdrawable'])
    for (const k of ['equity', 'buyingPower', 'netContributions', 'unitValue', 'dayPnl']) expect(account.body[k]).toMatch(DECIMAL)

    const positions = await call<Array<Record<string, unknown>>>('GET', '/account/positions')
    expect(positions.body.length).toBeGreaterThan(0) // the §18.4 seed: $125 of NVDA
    expect(keys(positions.body[0])).toEqual([...POSITION_KEYS].sort())
  })

  it('a dollar-amount market buy returns the §18.4 PlaceOrderResponse and fills into a position', async () => {
    const before = await call<{ buyingPower: string }>('GET', '/account')
    const placed = await call<PlaceOrderResponse>('POST', '/orders', {
      clientOrderId: 'web-test-0001',
      symbol: 'AAPL',
      side: 'BUY',
      type: 'MARKET',
      notional: '25.00',
      timeInForce: 'DAY',
      extendedHours: false,
    })
    expect(placed.status).toBe(201)
    expect(keys(placed.body)).toEqual(['buyingPowerAfter', 'buyingPowerBefore', 'estimate', 'order', 'replayed', 'warnings'])
    expect(keys(placed.body.order)).toEqual([...ORDER_KEYS].sort())
    expect(keys(placed.body.estimate)).toEqual(['fees', 'hold', 'price', 'principal', 'qty', 'quoteAsOf', 'quoteQuality'])
    expect(placed.body.order).toMatchObject({ status: 'PENDING', waitReason: 'LATENCY', notional: '25.00', qty: null, fractional: true, channel: 'WEB', executions: null, events: null })
    expect(placed.body.buyingPowerBefore).toBe(before.body.buyingPower)

    // Replaying the same clientOrderId returns the same order with 200 and replayed=true (§11.7).
    const replay = await call<PlaceOrderResponse>('POST', '/orders', { clientOrderId: 'web-test-0001', symbol: 'AAPL', side: 'BUY', type: 'MARKET', notional: '25.00' })
    expect(replay.status).toBe(200)
    expect(replay.body.replayed).toBe(true)
    expect(replay.body.order!.orderId).toBe(placed.body.order!.orderId)

    await sleep(1_200) // latency, then the fill
    const order = await call<Order>('GET', `/orders/${placed.body.order!.orderId}`)
    expect(order.body.status).toBe('FILLED')
    expect(order.body.executions).toHaveLength(1)
    expect(order.body.events!.map((e) => e.type)).toContain('FILL')
    const positions = await call<Array<{ symbol: string }>>('GET', '/account/positions')
    expect(positions.body.map((p) => p.symbol)).toContain('AAPL')
    const acts = await call<{ items: Array<Record<string, unknown>> }>('GET', '/account/activities?limit=5')
    expect(keys(acts.body.items[0])).toEqual([...ACTIVITY_KEYS].sort())
    expect(acts.body.items[0]).toMatchObject({ type: 'FILL', symbol: 'AAPL', side: 'BUY' })
  }, 10_000)

  it('previews never write and report §7.2 codes for invalid input', async () => {
    const seeded = (await call<{ items: unknown[] }>('GET', '/orders?status=ALL')).body.items.length
    const bad = await call<{ valid: boolean; errors: Array<{ code: string; field: string | null }> }>('POST', '/orders/preview', {
      symbol: 'AAPL',
      side: 'BUY',
      type: 'LIMIT',
      qty: '1',
    })
    expect(bad.body.valid).toBe(false)
    expect(bad.body.errors.map((e) => e.code)).toContain('LIMIT_PRICE_REQUIRED')
    const orders = await call<{ items: unknown[] }>('GET', '/orders?status=ALL')
    expect(orders.body.items).toHaveLength(seeded) // only the seeded orders (NVDA and the corporate-action history)
  })

  it('cancels a resting limit order', async () => {
    const q = await call<{ quotes: Array<{ bid: string }> }>('GET', '/quotes?symbols=AAPL')
    const limit = (Math.floor(Number(q.body.quotes[0]!.bid) * 0.9 * 100) / 100).toFixed(2)
    const placed = await call<PlaceOrderResponse>('POST', '/orders', { clientOrderId: 'web-test-0002', symbol: 'AAPL', side: 'BUY', type: 'LIMIT', qty: '1', limitPrice: limit, timeInForce: 'GTC' })
    expect(placed.status).toBe(201)
    await sleep(1_000)
    const open = await call<{ items: Array<{ orderId: string; status: string }> }>('GET', '/orders?status=OPEN')
    expect(open.body.items.map((o) => o.orderId)).toContain(placed.body.order!.orderId)
    const cancelled = await call<{ status: string }>('DELETE', `/orders/${placed.body.order!.orderId}`)
    expect(cancelled.body.status).toBe('CANCELLED')
    // §11.6: cancelling a CANCELLED order is idempotent; any other terminal order is 409.
    const again = await call<{ status: string }>('DELETE', `/orders/${placed.body.order!.orderId}`)
    expect(again.status).toBe(200)
    expect(again.body.status).toBe('CANCELLED')
    const filled = (await call<{ items: Array<{ orderId: string; status: string }> }>('GET', '/orders?status=CLOSED')).body.items.find((o) => o.status === 'FILLED')!
    const refused = await call<{ code: string }>('DELETE', `/orders/${filled.orderId}`)
    expect(refused.status).toBe(409)
    expect(refused.body.code).toBe('ORDER_NOT_CANCELLABLE')
  }, 10_000)
})

describe('mock server: deposit-aware money (§16.2, §21.2)', () => {
  it('a deposit to ALL previews every account, needs an Idempotency-Key, and leaves unit values unchanged', async () => {
    const body = { target: { scope: 'ALL', kinds: ['HUMAN', 'AI'] }, type: 'DEPOSIT', amount: '100.00', memo: 'Halloween bonus' }
    const board0 = await call<Leaderboard>('GET', '/leaderboard?period=ALL')
    const before = await call<{ unitValue: string; netContributions: string; cash: { total: string } }>('GET', '/account')

    const preview = await call<{ accounts: Array<{ handle: string; wouldApply: boolean }> }>('POST', '/admin/cash-transfers/preview', body)
    expect(preview.status).toBe(200)
    expect(preview.body.accounts.length).toBe(board0.body.rows!.length)
    expect(preview.body.accounts.every((a) => a.wouldApply)).toBe(true)

    const applied = await call<TransferBatchResult>('POST', '/admin/cash-transfers', body, { 'Idempotency-Key': 'test-key-1' })
    expect(applied.status).toBe(201)
    expect(applied.body).toMatchObject({ scope: 'ALL', type: 'DEPOSIT', amount: '100.00', memo: 'Halloween bonus', skipped: 0 })
    expect(applied.body.applied).toBe(preview.body.accounts.length)

    const after = await call<{ unitValue: string; netContributions: string; cash: { total: string } }>('GET', '/account')
    expect(after.body.unitValue).toBe(before.body.unitValue)
    expect(Number(after.body.netContributions) - Number(before.body.netContributions)).toBeCloseTo(100, 6)
    expect(Number(after.body.cash.total) - Number(before.body.cash.total)).toBeCloseTo(100, 6)

    // The same key replays without a second deposit; a different body under it is a conflict (§17.6).
    const replay = await call<TransferBatchResult>('POST', '/admin/cash-transfers', body, { 'Idempotency-Key': 'test-key-1' })
    expect(replay.body.batchId).toBe(applied.body.batchId)
    const conflict = await call<{ code: string }>('POST', '/admin/cash-transfers', { ...body, amount: '5.00' }, { 'Idempotency-Key': 'test-key-1' })
    expect(conflict.status).toBe(409)
    expect(conflict.body.code).toBe('IDEMPOTENCY_KEY_CONFLICT')

    // The leaderboard is deposit-aware: returns unchanged, net contributions up, flows shown.
    const board1 = await call<Leaderboard>('GET', '/leaderboard?period=ALL')
    expect(keys(board1.body.rows![0])).toEqual([...LEADERBOARD_ROW_KEYS].sort())
    for (const r of board1.body.rows!) {
      const old = board0.body.rows!.find((o) => o.player?.handle === r.player?.handle)!
      expect(Number(r.return)).toBeCloseTo(Number(old.return), 3)
      expect(Number(r.netContributions) - Number(old.netContributions)).toBeCloseTo(100, 6)
      expect(Number(r.flowsInPeriod)).toBeGreaterThanOrEqual(100)
    }

    // The memo reaches the player's activity, marked as done by the admin.
    const acts = await call<{ items: Array<{ type: string; memo: string | null; by: string }> }>('GET', '/account/activities?limit=5')
    expect(acts.body.items[0]).toMatchObject({ type: 'DEPOSIT', memo: 'Halloween bonus', by: 'ADMIN' })
  })

  it('skips a withdrawal larger than the withdrawable cash', async () => {
    const res = await call<TransferBatchResult>(
      'POST',
      '/admin/cash-transfers',
      { target: { scope: 'SINGLE', handle: 'alice' }, type: 'WITHDRAWAL', amount: '100000.00', memo: 'too much' },
      { 'Idempotency-Key': 'test-key-2' },
    )
    expect(res.body.skipped).toBe(1)
    expect(res.body.results![0]).toMatchObject({ handle: 'alice', status: 'SKIPPED', skipReason: 'INSUFFICIENT_WITHDRAWABLE' })
  })
})

describe('mock server: auth', () => {
  it('rejects unsafe requests without the CSRF header and supports logout / login', async () => {
    const res = await server.handle(new Request('http://localhost/api/v1/auth/logout', { method: 'POST' }))
    expect(res.status).toBe(403)
    expect(((await res.json()) as { code: string }).code).toBe('CSRF_INVALID')

    expect((await call('POST', '/auth/logout')).status).toBe(204)
    expect((await call<{ code: string }>('GET', '/account')).body.code).toBe('UNAUTHENTICATED')
    expect((await call<{ code: string }>('POST', '/auth/login', { handle: 'demo', password: 'wrong' })).body.code).toBe('BAD_CREDENTIALS')
    const me = await call<{ handle: string; role: string }>('POST', '/auth/login', { handle: 'Demo', password: 'long enough password' })
    expect(me.status).toBe(200)
    expect(me.body).toMatchObject({ handle: 'demo', role: 'ADMIN' })
  })

  it('signs up with an invite code and rejects a bad one', async () => {
    const bad = await call<{ code: string }>('POST', '/auth/signup', { inviteCode: 'nope', handle: 'zed', displayName: 'Zed', password: 'long enough password' })
    expect(bad.body.code).toBe('INVITE_INVALID')
    const invite = await call<{ code: string; url: string }>('POST', '/admin/invites', { maxUses: 1, expiresInHours: 24 })
    expect(invite.status).toBe(201)
    const ok = await call<{ handle: string }>('POST', '/auth/signup', { inviteCode: invite.body.code, handle: 'zed', displayName: 'Zed', password: 'long enough password' })
    expect(ok.status).toBe(201)
    expect(ok.body.handle).toBe('zed')
  })
})

const lastOf = async (symbol: string) => (await call<{ quotes: Array<{ last: string }> }>('GET', `/quotes?symbols=${symbol}`)).body.quotes[0]!.last
const cents = (n: number) => (Math.round(n * 100) / 100).toFixed(2)
const preview = async (body: Record<string, unknown>) =>
  (await call<{ valid: boolean; errors: Array<{ code: string; field: string | null }> }>('POST', '/orders/preview', { symbol: 'AAPL', side: 'BUY', qty: '1', ...body })).body

describe('mock server: stop orders (§11.1, §12.7)', () => {

  it('validates stop prices, trails and TIF per type', async () => {
    const last = Number(await lastOf('AAPL'))
    expect((await preview({ type: 'STOP' })).errors.map((e) => e.code)).toContain('STOP_PRICE_REQUIRED')
    expect((await preview({ type: 'STOP', stopPrice: cents(last * 0.98) })).errors).toEqual([expect.objectContaining({ code: 'STOP_PRICE_WRONG_SIDE', field: 'stopPrice' })])
    expect((await preview({ type: 'STOP_LIMIT', stopPrice: cents(last * 1.02) })).errors.map((e) => e.code)).toContain('LIMIT_PRICE_REQUIRED')
    expect((await preview({ type: 'TRAILING_STOP' })).errors).toEqual([expect.objectContaining({ code: 'TRAIL_REQUIRED', field: 'trailAmount' })])
    expect((await preview({ type: 'TRAILING_STOP', trailPercent: '2', timeInForce: 'GTC' })).errors.map((e) => e.code)).toEqual(['TIF_NOT_ALLOWED'])
    expect((await preview({ type: 'TRAILING_STOP', trailPercent: '2', stopPrice: '1.00' })).errors.map((e) => e.code)).toContain('PRICE_NOT_ALLOWED')
    const ok = await preview({ type: 'STOP', stopPrice: cents(last * 1.02), timeInForce: 'GTC' })
    expect(ok.valid).toBe(true)
  })

  it('a buy stop waits NOT_TRIGGERED, triggers on a print through the stop, then fills', async () => {
    const last = Number(await lastOf('AAPL'))
    const stop = cents(last * 1.01)
    const placed = await call<PlaceOrderResponse>('POST', '/orders', { clientOrderId: 'web-stop-1', symbol: 'AAPL', side: 'BUY', type: 'STOP', qty: '1', stopPrice: stop, timeInForce: 'DAY' })
    expect(placed.status).toBe(201)
    await sleep(1_000)
    const id = placed.body.order!.orderId!
    let o = (await call<Order>('GET', `/orders/${id}`)).body
    expect(o).toMatchObject({ status: 'WORKING', waitReason: 'NOT_TRIGGERED', triggered: false, stopPrice: stop })
    server.market.nudge('AAPL', 300)
    server.onMarketStep('AAPL')
    o = (await call<Order>('GET', `/orders/${id}`)).body
    expect(o.triggered).toBe(true)
    const trig = o.events!.find((e) => e.type === 'TRIGGERED')!
    expect(trig.detail).toMatchObject({ basis: 'PRINT', stopPrice: stop })
    await sleep(800)
    o = (await call<Order>('GET', `/orders/${id}`)).body
    expect(o.status).toBe('FILLED')
    expect(o.events!.map((e) => e.type)).toEqual(['ACCEPTED', 'WORKING', 'TRIGGERED', 'FILL'])
  }, 10_000)

  it('a sell trailing stop follows the high-water mark and triggers on the pullback', async () => {
    const placed = await call<PlaceOrderResponse>('POST', '/orders', { clientOrderId: 'web-trail-1', symbol: 'F', side: 'SELL', type: 'TRAILING_STOP', qty: '2', trailPercent: '2', timeInForce: 'DAY' })
    expect(placed.status).toBe(201)
    await sleep(1_000)
    const id = placed.body.order!.orderId!
    const o1 = (await call<Order>('GET', `/orders/${id}`)).body
    expect(o1.watermarkPrice).not.toBeNull()
    const stop1 = Number(o1.stopPrice)
    expect(stop1).toBeLessThan(Number(o1.watermarkPrice))
    server.market.nudge('F', 100) // +1%: the watermark and the stop move up
    server.onMarketStep('F')
    const o2 = (await call<Order>('GET', `/orders/${id}`)).body
    expect(Number(o2.watermarkPrice)).toBeGreaterThan(Number(o1.watermarkPrice))
    expect(Number(o2.stopPrice)).toBeGreaterThan(stop1)
    expect(o2.events!.map((e) => e.type)).toContain('TRAIL_UPDATED')
    server.market.nudge('F', -300) // −3%: through the trailing stop
    server.onMarketStep('F')
    await sleep(800)
    const o3 = (await call<Order>('GET', `/orders/${id}`)).body
    expect(o3).toMatchObject({ status: 'FILLED', triggered: true })
  }, 10_000)
})

describe('mock server: public mode (§16.7)', () => {
  it('pins PublicPortfolio and PublicOrder to the generated schema', () => {
    expectTypeOf<Exclude<keyof PublicPortfolio, (typeof PUBLIC_PORTFOLIO_KEYS)[number]>>().toEqualTypeOf<never>()
    expectTypeOf<Exclude<keyof PublicOrder, (typeof PUBLIC_ORDER_KEYS)[number]>>().toEqualTypeOf<never>()
  })

  it("serves another player's live portfolio with the exact members, positions and read-only open orders", async () => {
    const res = await call<PublicPortfolio>('GET', '/players/claude/portfolio')
    expect(res.status).toBe(200)
    expect(keys(res.body)).toEqual([...PUBLIC_PORTFOLIO_KEYS].sort())
    const p = res.body
    expect(p.player?.handle).toBe('claude')
    expect(p.runNo).toBe(1)
    expect(p.positions!.length).toBeGreaterThanOrEqual(2)
    expect(p.positions![0]!.symbol).toBe('NVDA') // the board's top holding is the largest position
    for (const k of ['equity', 'cash', 'buyingPower', 'openBuyHolds', 'unrealizedPnl'] as const) expect(p[k]).toMatch(DECIMAL)
    const open = p.openOrders!
    expect(open.length).toBeGreaterThanOrEqual(2)
    expect(keys(open[0])).toEqual([...PUBLIC_ORDER_KEYS].sort())
    // a bracket whose entry has not filled: its exits are held, waiting for the parent
    const held = open.find((o) => o.group?.held)!
    expect(held).toMatchObject({ waitReason: 'AWAITING_PARENT', group: { type: 'BRACKET', held: true } })
    expect(held.group!.parentOrderId).toBe(open.find((o) => o.group?.role === 'ENTRY')!.orderId)
    expect(open.every((o) => o.untrusted?.rationale)).toBe(true) // bots explain every order
  })

  it("serves a player's orders newest first with fills, filters and pages, and their sanitized activity", async () => {
    const all = await call<{ items: PublicOrder[]; nextCursor: string | null }>('GET', '/players/alice/orders?limit=3')
    expect(all.body.items).toHaveLength(3)
    expect(all.body.nextCursor).not.toBeNull()
    const times = all.body.items.map((o) => Date.parse(o.receivedAt!))
    expect(times).toEqual([...times].sort((a, b) => b - a))
    const closed = await call<{ items: PublicOrder[] }>('GET', '/players/alice/orders?status=CLOSED&limit=50')
    const filled = closed.body.items.filter((o) => o.status === 'FILLED')
    expect(filled.length).toBeGreaterThanOrEqual(3)
    expect(keys(filled[0]!.fills![0])).toEqual([...PUBLIC_FILL_KEYS].sort())
    expect(closed.body.items.some((o) => o.status === 'CANCELLED')).toBe(true)
    const aapl = await call<{ items: PublicOrder[] }>('GET', '/players/alice/orders?symbol=aapl')
    expect(aapl.body.items.length).toBeGreaterThan(0)
    expect(aapl.body.items.every((o) => o.symbol === 'AAPL')).toBe(true)

    const acts = await call<{ items: Activity[] }>('GET', '/players/alice/activities?limit=50')
    const types = acts.body.items.map((a) => a.type)
    expect(types).toContain('INITIAL_FUNDING')
    expect(types).toContain('FILL')
    expect(types.at(-1)).toBe('INITIAL_FUNDING')
    expect(acts.body.items.every((a) => a.memo === null || a.by === 'SYSTEM')).toBe(true)

    // the signed-in player's own ledger, as others see it: no "admin viewed" rows, no admin memos
    const mine = await call<{ items: Activity[] }>('GET', '/players/demo/activities?limit=200')
    expect(mine.body.items.some((a) => a.type === 'ADMIN_VIEW')).toBe(false)
    expect(mine.body.items.every((a) => a.memo === null || a.by === 'SYSTEM')).toBe(true)
  })

  it('analysis and calendar answer for any listed player; a disabled one is only on the admin twin', async () => {
    expect((await call('GET', '/players/chatgpt/pnl-analysis?range=1M')).status).toBe(200)
    expect((await call('GET', '/players/chatgpt/pnl-calendar?view=MONTH&year=2026')).status).toBe(200)
    expect((await call('PATCH', '/admin/players/bob', { status: 'DISABLED' })).status).toBe(200)
    expect((await call<{ code: string }>('GET', '/players/bob/portfolio')).body.code).toBe('PLAYER_NOT_FOUND')
    const twin = await call<PublicPortfolio>('GET', '/admin/players/bob/portfolio')
    expect(twin.status).toBe(200)
    expect(twin.body.positions!.length).toBeGreaterThan(0)
  })

  it('has no feed delay or holdings visibility by default: equity and holdings with qty are public', async () => {
    const settings = await call<Settings>('GET', '/admin/settings')
    expect(settings.body['social.tradeFeedDelay']).toBe('PT0S')
    expect(settings.body).not.toHaveProperty('social.holdingsVisibility')
    const rules = await call<Rules>('GET', '/rules')
    expect(rules.body.social).toEqual({ tradeFeedDelay: 'PT0S', visibility: 'PUBLIC' })
    expect(rules.body.fairness).toHaveLength(6)
    const board = await call<Leaderboard>('GET', '/leaderboard?period=ALL')
    expect(board.body.rows!.every((r) => r.equity !== null)).toBe(true)
    const profile = await call<PlayerProfile>('GET', '/players/claude')
    expect(profile.body.equity).toMatch(DECIMAL)
    expect(profile.body.holdings!.every((h) => h.qty !== null)).toBe(true)
    expect(durationMs('PT15M')).toBe(15 * 60_000)
    expect(durationMs('P1D')).toBe(24 * 60 * 60_000)
    expect(durationMs('PT0S')).toBe(0)
  })
})

type PreviewBody = { valid: boolean; errors: Array<{ code: string; field: string | null }>; estimate: { fees: { total: string } } | null }
/** The touch, on the price tick (a marketable buy limit). */
const ask = async (symbol: string) => roundToTick((await call<{ quotes: Array<{ ask: string }> }>('GET', `/quotes?symbols=${symbol}`)).body.quotes[0]!.ask, 'CEILING')

describe('mock server: crypto (§29)', () => {
  it('serves the pairs on dash paths with canonical BTC/USD symbols and 24 h of 1D bars', async () => {
    const asset = await call<Record<string, unknown>>('GET', '/assets/BTC-USD')
    expect(asset.status).toBe(200)
    expect(asset.body).toMatchObject({ symbol: 'BTC/USD', type: 'CRYPTO', exchange: 'CRYPTO', fractionable: true, tradable: true, fundamentals: null })
    expect(asset.body.logoUrl).toBe('/api/v1/assets/BTC-USD/logo')
    const bars = await call<{ symbol: string; bars: Array<{ t: string }> }>('GET', '/bars/btc-usd?range=1D')
    expect(bars.body.symbol).toBe('BTC/USD')
    expect(bars.body.bars).toHaveLength(1440)
    expect(Date.parse(bars.body.bars.at(-1)!.t) - Date.parse(bars.body.bars[0]!.t)).toBe(1439 * 60_000)
    const quotes = await call<{ quotes: Array<{ symbol: string; last: string }> }>('GET', '/quotes?symbols=BTC-USD,DOGE/USD')
    expect(quotes.body.quotes.map((q) => q.symbol)).toEqual(['BTC/USD', 'DOGE/USD'])
    expect(quotes.body.quotes[1]!.last).toMatch(/^0\.\d{4}$/)
    const search = await call<Array<{ symbol: string; type: string }>>('GET', '/assets/search?q=BTC')
    expect(search.body[0]).toMatchObject({ symbol: 'BTC/USD', type: 'CRYPTO' })
  })

  it('takes 5-decimal quantities on every order type, ignores extendedHours and charges no fees', async () => {
    const px = await ask('ETH/USD')
    const limit = await call<PreviewBody>('POST', '/orders/preview', { symbol: 'ETH/USD', side: 'BUY', type: 'LIMIT', qty: '0.01234', limitPrice: px, timeInForce: 'GTC', extendedHours: true })
    expect(limit.body.errors).toEqual([])
    expect(limit.body.valid).toBe(true)
    expect(limit.body.estimate!.fees.total).toBe('0.00')
    const tooFine = await call<PreviewBody>('POST', '/orders/preview', { symbol: 'ETH-USD', side: 'BUY', type: 'LIMIT', qty: '0.000001', limitPrice: px })
    expect(tooFine.body.errors.map((e) => e.code)).toContain('INVALID_QTY')
    // the same fractional limit on a stock still needs a Market order
    const stock = await call<PreviewBody>('POST', '/orders/preview', { symbol: 'AAPL', side: 'BUY', type: 'LIMIT', qty: '0.5', limitPrice: await ask('AAPL') })
    expect(stock.body.errors.map((e) => e.code)).toContain('FRACTIONAL_MARKET_ONLY')
  })

  it('publishes crypto in the clock and the rules, and refuses crypto buys (not sells) while it is off', async () => {
    const clock = await call<{ crypto: { enabled: boolean; pairs: string[] } }>('GET', '/market/clock')
    expect(clock.body.crypto.enabled).toBe(true)
    expect(clock.body.crypto.pairs).toHaveLength(12)
    expect((await call<Rules>('GET', '/rules')).body.crypto).toMatchObject({ enabled: true })
    expect((await call<Settings>('GET', '/admin/settings')).body['crypto.enabled']).toBe(true)

    expect((await call('PUT', '/admin/settings', { 'crypto.enabled': 'yes' })).status).toBe(422)
    const off = await call<Settings>('PUT', '/admin/settings', { 'crypto.enabled': false })
    expect(off.status).toBe(200)
    expect(off.body['crypto.enabled']).toBe(false)
    expect((await call<{ crypto: { enabled: boolean } }>('GET', '/market/clock')).body.crypto.enabled).toBe(false)
    const buy = await call<PreviewBody>('POST', '/orders/preview', { symbol: 'BTC/USD', side: 'BUY', type: 'MARKET', notional: '10.00' })
    expect(buy.body.errors.map((e) => e.code)).toContain('SYMBOL_NOT_TRADABLE')
    const sell = await call<PreviewBody>('POST', '/orders/preview', { symbol: 'BTC/USD', side: 'SELL', type: 'MARKET', qty: '0.001' })
    expect(sell.body.errors.map((e) => e.code)).not.toContain('SYMBOL_NOT_TRADABLE')
  })
})
