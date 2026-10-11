import { beforeEach, describe, expect, it } from 'vitest'
import type { GroupPreview, OrderGroup, PlaceOrderGroupResponse } from '../groupTypes'
import type { Order, Quote } from '../types'
import { MockServer } from './server'

/** Mock-mode group orders (§11.9, §22.9): the e2e suite's bracket and OCO flows run on these rules. */

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const cents = (n: number) => n.toFixed(2)

let server: MockServer
let csrf = ''

async function call<T = unknown>(method: string, path: string, body?: unknown): Promise<{ status: number; body: T }> {
  if (!csrf) {
    await server.handle(new Request('http://localhost/api/v1/auth/csrf'))
    csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? ''
  }
  const res = await server.handle(
    new Request(`http://localhost/api/v1${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  )
  return { status: res.status, body: (res.status === 204 ? null : await res.json()) as T }
}

async function lastOf(symbol: string): Promise<number> {
  const q = await call<{ quotes: Quote[] }>('GET', `/quotes?symbols=${symbol}`)
  return Number(q.body.quotes[0]!.last)
}

beforeEach(() => {
  sessionStorage.clear()
  csrf = ''
  server = new MockServer()
})

describe('mock group orders', () => {
  it('a bracket holds its children until the market entry fills, then releases them', async () => {
    const last = await lastOf('AAPL')
    const body = {
      clientOrderId: 'web-grp-1',
      type: 'BRACKET',
      symbol: 'AAPL',
      qty: '1',
      entry: { type: 'MARKET' },
      takeProfit: { limitPrice: cents(last * 1.1) },
      stopLoss: { stopPrice: cents(last * 0.9) },
    }
    const preview = await call<GroupPreview>('POST', '/order-groups/preview', body)
    expect(preview.body.valid).toBe(true)
    expect(preview.body.legs?.map((l) => l.role)).toEqual(['ENTRY', 'TAKE_PROFIT', 'STOP_LOSS'])
    const placed = await call<PlaceOrderGroupResponse>('POST', '/order-groups', body)
    expect(placed.status).toBe(201)
    const g = placed.body.group!
    expect(g.legs?.map((l) => [l.group?.role, l.group?.held])).toEqual([
      ['ENTRY', false],
      ['TAKE_PROFIT', true],
      ['STOP_LOSS', true],
    ])
    expect((await call<PlaceOrderGroupResponse>('POST', '/order-groups', body)).body.replayed).toBe(true)
    await sleep(1_000)
    const after = (await call<OrderGroup>('GET', `/order-groups/${g.groupId}`)).body
    expect(after.legs?.[0]?.status).toBe('FILLED')
    expect(after.legs?.slice(1).every((l) => l.group?.held === false)).toBe(true)
    await sleep(1_000)
    const tp = (await call<Order>('GET', `/orders/${after.legs![1]!.orderId}`)).body
    expect(tp.status).toBe('WORKING')
    expect(tp.events?.map((e) => e.type)).toContain('ACTIVATED')
  }, 10_000)

  it('rejects a bracket whose take profit is not above the entry, naming the leg', async () => {
    const r = await call<{ code: string; fields: { field: string }[] }>('POST', '/order-groups', {
      clientOrderId: 'web-grp-2',
      type: 'BRACKET',
      symbol: 'AAPL',
      qty: '1',
      entry: { type: 'LIMIT', limitPrice: '100.00' },
      takeProfit: { limitPrice: '99.00' },
      stopLoss: { stopPrice: '95.00' },
    })
    expect(r.status).toBe(422)
    expect(r.body.code).toBe('ORDER_GROUP_INVALID')
    expect(r.body.fields[0]!.field).toBe('takeProfit.limitPrice')
  })

  it('cancelling a group cancels its open legs; an unfilled entry takes its children with it', async () => {
    const last = await lastOf('AAPL')
    const placed = await call<PlaceOrderGroupResponse>('POST', '/order-groups', {
      clientOrderId: 'web-grp-3',
      type: 'BRACKET',
      symbol: 'AAPL',
      qty: '1',
      entry: { type: 'LIMIT', limitPrice: cents(last * 0.8) },
      takeProfit: { limitPrice: cents(last * 1.1) },
      stopLoss: { stopPrice: cents(last * 0.7) },
    })
    const g = placed.body.group!
    const cancelled = (await call<OrderGroup>('DELETE', `/order-groups/${g.groupId}`)).body
    expect(cancelled.status).toBe('CANCELLED')
    expect(cancelled.legs?.every((l) => l.status === 'CANCELLED')).toBe(true)
  })
})
