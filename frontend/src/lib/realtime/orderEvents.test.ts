import { describe, expect, it } from 'vitest'
import type { Execution, Order, OrderUpdateEvent } from '../api/types'
import { orderEventToast } from './useUserEvents'

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
