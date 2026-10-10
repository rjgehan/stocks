import { describe, expect, it } from 'vitest'
import { buildOrderRequest, initialTicket, midPrice, quickQuantity, ticketReducer } from './ticketState'

const ctx = { fractionable: true, availableQty: '0.95274' }

describe('trade ticket rules (§22.5)', () => {
  it('Amount forces Market + Day + no extended hours', () => {
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'extendedHours', value: true })
    s = ticketReducer(s, { type: 'tif', tif: 'GTC' })
    expect(s).toMatchObject({ type: 'LIMIT', extendedHours: true, tif: 'GTC' })
    s = ticketReducer(s, { type: 'qtyMode', mode: 'AMOUNT' })
    expect(s).toMatchObject({ qtyMode: 'AMOUNT', type: 'MARKET', tif: 'DAY', extendedHours: false })
    // GTC stays disabled while in Amount mode
    expect(ticketReducer(s, { type: 'tif', tif: 'GTC' }).tif).toBe('DAY')
  })

  it('GTC is disabled for trailing stops; extended hours only for limits', () => {
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'orderType', orderType: 'TRAILING_STOP' })
    expect(ticketReducer(s, { type: 'tif', tif: 'GTC' }).tif).toBe('DAY')
    expect(ticketReducer(s, { type: 'extendedHours', value: true }).extendedHours).toBe(false)
  })

  it('builds a notional market request with the ticket clientOrderId', () => {
    let s = initialTicket({ clientOrderId: 'web-abc' })
    s = ticketReducer(s, { type: 'qtyMode', mode: 'AMOUNT' })
    s = ticketReducer(s, { type: 'field', field: 'amount', value: '$125' })
    expect(buildOrderRequest(s, 'NVDA', ctx)).toEqual({
      clientOrderId: 'web-abc',
      symbol: 'NVDA',
      side: 'BUY',
      type: 'MARKET',
      notional: '125.00',
      timeInForce: 'DAY',
      extendedHours: false,
    })
  })

  it('needs a limit price for limit orders and a position to sell', () => {
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'field', field: 'qty', value: '10' })
    expect(buildOrderRequest(s, 'AAPL', ctx)).toBeNull()
    s = ticketReducer(s, { type: 'field', field: 'limitPrice', value: '227.40' })
    expect(buildOrderRequest(s, 'AAPL', ctx)).toMatchObject({ qty: '10', limitPrice: '227.40', type: 'LIMIT' })
    const sell = ticketReducer(s, { type: 'side', side: 'SELL' })
    expect(buildOrderRequest(sell, 'AAPL', { fractionable: true, availableQty: null })).toBeNull()
  })

  it('adds the note, share flag and GFV acknowledgement', () => {
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'orderType', orderType: 'MARKET' })
    s = ticketReducer(s, { type: 'field', field: 'qty', value: '1' })
    s = ticketReducer(s, { type: 'field', field: 'note', value: '  earnings beat ' })
    s = ticketReducer(s, { type: 'shareNote', value: true })
    s = ticketReducer(s, { type: 'gfvAck', value: true })
    expect(buildOrderRequest(s, 'AAPL', ctx)).toMatchObject({ rationale: 'earnings beat', shareNote: true, acknowledge: ['GFV_RISK'] })
  })

  it('sizes the quick chips without floating point', () => {
    const base = { side: 'BUY' as const, qtyMode: 'SHARES' as const, buyingPower: '375.00', ask: '227.41', bid: '227.39', availableQty: null, fractionable: true }
    expect(quickQuantity('1', base)).toBe('1') // 375 / (227.41 × 1.02) = 1.6…
    expect(quickQuantity('0.5', { ...base, qtyMode: 'AMOUNT' })).toBe('187.50')
    expect(quickQuantity('1', { ...base, side: 'SELL', availableQty: '0.95274' })).toBe('0.95274')
    expect(quickQuantity('0.25', { ...base, side: 'SELL', availableQty: '0.95274' })).toBe('0.23818')
    expect(midPrice('227.39', '227.42')).toBe('227.41')
  })
})
