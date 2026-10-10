import { describe, expect, it } from 'vitest'
import type { Execution, Order, OrderUpdateEvent } from '../api/types'
import { POSITIONS_REFRESH_MS, orderEventToast, throttle } from './useUserEvents'

const order = (over: Partial<Order> = {}): Order => ({ symbol: 'AAPL', side: 'BUY', filledQty: '10', avgFillPrice: '227.38', statusReason: null, ...over }) as Order
const ev = (event: OrderUpdateEvent['event'], o: Order, execution: Partial<Execution> | null = null): OrderUpdateEvent => ({ event, order: o, execution: execution as Execution | null })

describe('orderEventToast (§22.5 Result: live status from /user/queue/orders)', () => {
  it('announces fills with quantity and price from the execution', () => {
    expect(orderEventToast(ev('FILL', order(), { qty: '10', price: '227.3800', principal: '2273.80' }))).toEqual({
      tone: 'up',
      title: 'Filled buy 10 AAPL @ 227.38',
      body: 'Cost $2,273.80',
    })
    expect(orderEventToast(ev('PARTIAL_FILL', order({ side: 'SELL' }), { qty: '0.5', price: '0.4321', principal: '0.22' }))?.title).toBe('Partially filled sell 0.5 AAPL @ 0.4321')
  })

  it('announces cancels with a non-user reason, and expiries', () => {
    expect(orderEventToast(ev('CANCELLED', order()))).toEqual({ tone: 'neutral', title: 'Cancelled: Buy AAPL', body: undefined })
    expect(orderEventToast(ev('CANCELLED', order({ statusReason: 'ADMIN' })))?.body).toBe('Admin')
    expect(orderEventToast(ev('EXPIRED', order({ side: 'SELL' })))?.title).toBe('Expired: Sell AAPL')
  })

  it('stays quiet for bookkeeping events', () => {
    expect(orderEventToast(ev('ACCEPTED', order()))).toBeNull()
    expect(orderEventToast(ev('WORKING', order()))).toBeNull()
    expect(orderEventToast({ event: 'FILL', order: null, execution: null })).toBeNull()
  })
})

// Review regression: positions refetched every 60 s while the NAV moved every ~5 s, so the rows did not add up to it.
describe('positions refresh from account pushes', () => {
  it('refreshes at most once per 5 s', () => {
    const t = throttle(POSITIONS_REFRESH_MS)
    expect(t.due(1_000)).toBe(true)
    expect(t.due(3_000)).toBe(false)
    expect(t.due(5_999)).toBe(false)
    expect(t.due(6_000)).toBe(true)
    expect(t.due(11_000)).toBe(true)
  })
})
