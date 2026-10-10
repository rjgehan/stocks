import { beforeEach, describe, expect, it } from 'vitest'
import type { Leaderboard, Order, PlaceOrderResponse, TransferBatchResult } from '../types'
import { MockServer } from './server'

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
]

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
    const bad = await call<{ valid: boolean; errors: Array<{ code: string; field: string | null }> }>('POST', '/orders/preview', {
      symbol: 'AAPL',
      side: 'BUY',
      type: 'LIMIT',
      qty: '1',
    })
    expect(bad.body.valid).toBe(false)
    expect(bad.body.errors.map((e) => e.code)).toContain('LIMIT_PRICE_REQUIRED')
    const orders = await call<{ items: unknown[] }>('GET', '/orders?status=ALL')
    expect(orders.body.items).toHaveLength(1) // only the seeded NVDA order
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
