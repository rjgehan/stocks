import { describe, expect, it } from 'vitest'
import {
  availableOrderTypes,
  buildOrderRequest,
  defaultLimitPrice,
  defaultStopPrice,
  defaultTrail,
  gtcAllowed,
  initialTicket,
  midPrice,
  quickQuantity,
  stepTrail,
  stopExplanation,
  ticketReducer,
  trailingStopStart,
  withoutQueueDuplicates,
} from './ticketState'

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

  // Review regression: Alpaca sends bid/ask "0" when IEX has no quote (weekends, before 08:00 ET); the ticket pre-filled
  // a 0.00 limit and sized "Amount" sells at a zero bid.
  it('treats a zero bid or ask as no quote', () => {
    expect(defaultLimitPrice('BUY', { ask: '0', bid: '0', last: '255.01' })).toBe('255.01')
    expect(defaultLimitPrice('SELL', { ask: '0', bid: '0', last: '255.01' })).toBe('255.01')
    expect(defaultLimitPrice('BUY', { ask: '227.415', bid: '227.39', last: '227.40' })).toBe('227.42')
    expect(defaultLimitPrice('BUY', { ask: null, bid: null, last: null })).toBeNull()
    expect(defaultLimitPrice('BUY', { ask: '0', bid: '0', last: '0' })).toBeNull()
    const sell = { side: 'SELL' as const, qtyMode: 'AMOUNT' as const, buyingPower: '0', ask: '0', bid: '0', availableQty: '2', fractionable: true }
    expect(quickQuantity('1', sell)).toBeNull()
  })

  // Review regression: closed-market previews showed the queue notice twice (sessionNote + QUEUED_FOR_OPEN warning).
  it('drops the queue warning when the session note says the same', () => {
    const warnings = [
      { code: 'QUEUED_FOR_OPEN', message: 'Order will queue for the Mon 09:30 ET open.' },
      { code: 'FAT_FINGER', message: 'limitPrice is 7.0% from the reference price 227.41.' },
    ]
    expect(withoutQueueDuplicates(warnings, 'Order will queue for Mon 09:30 ET').map((w) => w.code)).toEqual(['FAT_FINGER'])
    expect(withoutQueueDuplicates(warnings, null).map((w) => w.code)).toEqual(['QUEUED_FOR_OPEN', 'FAT_FINGER'])
    expect(withoutQueueDuplicates([{ code: 'QUEUED_FOR_EXT_OPEN', message: 'x' }], 'Order will queue for 04:00 ET')).toEqual([])
  })
})

describe('stop tickets (§11.1, §12.7)', () => {
  it('offers the order types the rulebook publishes, in §11.1 order', () => {
    expect(availableOrderTypes(null)).toEqual(['MARKET', 'LIMIT'])
    expect(availableOrderTypes([{ type: 'LIMIT' }, { type: 'MARKET' }])).toEqual(['MARKET', 'LIMIT'])
    expect(availableOrderTypes([{ type: 'TRAILING_STOP' }, { type: 'MARKET' }, { type: 'STOP' }, { type: 'LIMIT' }, { type: 'STOP_LIMIT' }])).toEqual(['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP'])
  })

  it('GTC follows the order matrix, and trailing stops are Day only', () => {
    expect(gtcAllowed({ type: 'STOP', qtyMode: 'SHARES' }, { timeInForce: ['DAY', 'GTC'] })).toBe(true)
    expect(gtcAllowed({ type: 'STOP', qtyMode: 'SHARES' }, { timeInForce: ['DAY'] })).toBe(false)
    expect(gtcAllowed({ type: 'TRAILING_STOP', qtyMode: 'SHARES' }, null)).toBe(false)
  })

  it('defaults a stop 1% through the market on the accepted side, on tick', () => {
    expect(defaultStopPrice('BUY', '227.40')).toBe('229.68')
    expect(defaultStopPrice('SELL', '227.40')).toBe('225.12')
    expect(defaultStopPrice('SELL', '0.4389')).toBe('0.4345')
    expect(defaultStopPrice('BUY', null)).toBeNull()
  })

  it('builds stop, stop-limit and trailing requests with only the fields each type takes', () => {
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'orderType', orderType: 'STOP_LIMIT' })
    s = ticketReducer(s, { type: 'field', field: 'qty', value: '2' })
    s = ticketReducer(s, { type: 'field', field: 'stopPrice', value: '230' })
    expect(buildOrderRequest(s, 'AAPL', ctx)).toBeNull() // the limit is still missing
    s = ticketReducer(s, { type: 'field', field: 'limitPrice', value: '231.00' })
    expect(buildOrderRequest(s, 'AAPL', ctx)).toMatchObject({ type: 'STOP_LIMIT', stopPrice: '230', limitPrice: '231.00', qty: '2' })

    let t = ticketReducer(initialTicket({ clientOrderId: 'web-2' }), { type: 'tif', tif: 'GTC' })
    t = ticketReducer(t, { type: 'orderType', orderType: 'TRAILING_STOP' })
    t = ticketReducer(t, { type: 'trailMode', mode: 'PERCENT' })
    t = ticketReducer(t, { type: 'field', field: 'trail', value: '2.5' })
    t = ticketReducer(t, { type: 'field', field: 'qty', value: '1' })
    t = ticketReducer(t, { type: 'side', side: 'SELL' })
    const req = buildOrderRequest(t, 'AAPL', { fractionable: true, availableQty: '3' })
    expect(req).toMatchObject({ type: 'TRAILING_STOP', trailPercent: '2.5', timeInForce: 'DAY', side: 'SELL' })
    expect(req).not.toHaveProperty('trailAmount')
    expect(req).not.toHaveProperty('stopPrice')
    expect(req).not.toHaveProperty('limitPrice')
  })

  it('shows where a trailing stop would start, and steps the trail', () => {
    expect(trailingStopStart('SELL', '227.40', '2.5', 'PERCENT')).toBe('221.71')
    expect(trailingStopStart('BUY', '227.40', '1.00', 'AMOUNT')).toBe('228.40')
    expect(trailingStopStart('SELL', '227.40', '', 'AMOUNT')).toBeNull()
    expect(stepTrail('1', 1, 'PERCENT', '227.40')).toBe('1.1')
    expect(stepTrail('0.01', -1, 'AMOUNT', '227.40')).toBe('0.01')
    expect(defaultTrail('AMOUNT', '227.40')).toBe('2.27')
  })

  it('describes what each stop does', () => {
    expect(stopExplanation({ side: 'SELL', type: 'STOP', symbol: 'AAPL', stopPrice: '220.00' })).toBe('When AAPL trades at or below 220.00, a market order to sell is sent.')
    expect(stopExplanation({ side: 'BUY', type: 'STOP_LIMIT', symbol: 'AAPL', stopPrice: '230.00', limitPrice: '231.00' })).toMatch(/limit order to buy at 231.00 or lower/)
    expect(stopExplanation({ side: 'SELL', type: 'TRAILING_STOP', symbol: 'AAPL', trailPercent: '2' })).toMatch(/highest price 2% below/)
    expect(stopExplanation({ side: 'BUY', type: 'LIMIT', symbol: 'AAPL', limitPrice: '1' })).toBeNull()
  })
})
