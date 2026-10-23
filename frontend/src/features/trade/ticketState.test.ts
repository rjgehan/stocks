import { describe, expect, it } from 'vitest'
import {
  availableOrderTypes,
  buildOrderRequest,
  cryptoQtyStep,
  defaultLimitPrice,
  defaultStopPrice,
  defaultTrail,
  dividendAdjustable,
  gtcAllowed,
  initialTicket,
  midPrice,
  qtyUnit,
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

  it('adds the note (always public, so no share flag) and the GFV acknowledgement', () => {
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'orderType', orderType: 'MARKET' })
    s = ticketReducer(s, { type: 'field', field: 'qty', value: '1' })
    s = ticketReducer(s, { type: 'field', field: 'note', value: '  earnings beat ' })
    s = ticketReducer(s, { type: 'gfvAck', value: true })
    const req = buildOrderRequest(s, 'AAPL', ctx)
    expect(req).toMatchObject({ rationale: 'earnings beat', acknowledge: ['GFV_RISK'] })
    expect(req).not.toHaveProperty('shareNote')
  })

  it("sends Don't reduce only for the orders a dividend reduces (FINRA 5330)", () => {
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'field', field: 'qty', value: '2' })
    s = ticketReducer(s, { type: 'field', field: 'limitPrice', value: '100.00' })
    s = ticketReducer(s, { type: 'field', field: 'stopPrice', value: '95.00' })
    expect(buildOrderRequest(s, 'AAPL', ctx)).not.toHaveProperty('doNotReduce')
    s = ticketReducer(s, { type: 'doNotReduce', value: true })
    expect(dividendAdjustable(s)).toBe(true)
    expect(buildOrderRequest(s, 'AAPL', ctx)).toMatchObject({ type: 'LIMIT', side: 'BUY', doNotReduce: true })
    // a SELL LIMIT and a BUY STOP are never reduced, so the flag is left out
    expect(buildOrderRequest(ticketReducer(s, { type: 'side', side: 'SELL' }), 'AAPL', { fractionable: true, availableQty: '5' })).not.toHaveProperty('doNotReduce')
    expect(buildOrderRequest(ticketReducer(s, { type: 'orderType', orderType: 'STOP' }), 'AAPL', ctx)).not.toHaveProperty('doNotReduce')
    // SELL STOP and STOP_LIMIT are
    const sellStop = ticketReducer(ticketReducer(s, { type: 'side', side: 'SELL' }), { type: 'orderType', orderType: 'STOP_LIMIT' })
    expect(buildOrderRequest(sellStop, 'AAPL', { fractionable: true, availableQty: '5' })).toMatchObject({ doNotReduce: true })
    expect(dividendAdjustable(ticketReducer(s, { type: 'qtyMode', mode: 'AMOUNT' }))).toBe(false)
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

  it('trades an option in whole contracts on Market or Limit in cents, Day only, never extended hours or dollars', () => {
    const occ = 'AAPL261120C00250000'
    const option = { fractionable: false, availableQty: '2', option: true }
    expect(availableOrderTypes([{ type: 'MARKET' }, { type: 'LIMIT' }, { type: 'STOP' }], true)).toEqual(['MARKET', 'LIMIT'])
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'extendedHours', value: true })
    s = ticketReducer(s, { type: 'tif', tif: 'GTC' })
    s = ticketReducer(s, { type: 'field', field: 'qty', value: '2' })
    s = ticketReducer(s, { type: 'field', field: 'limitPrice', value: '3.25' })
    expect(buildOrderRequest(s, occ, option)).toMatchObject({ symbol: occ, type: 'LIMIT', qty: '2', limitPrice: '3.25', timeInForce: 'DAY', extendedHours: false })
    // the same ticket on a stock keeps GTC and the flag
    expect(buildOrderRequest(s, 'AAPL', ctx)).toMatchObject({ timeInForce: 'GTC', extendedHours: true })
    expect(buildOrderRequest(ticketReducer(s, { type: 'field', field: 'limitPrice', value: '3.255' }), occ, option)).toBeNull()
    expect(buildOrderRequest(ticketReducer(s, { type: 'field', field: 'qty', value: '1.5' }), occ, option)).toBeNull()
    expect(buildOrderRequest(ticketReducer(s, { type: 'orderType', orderType: 'STOP' }), occ, option)).toBeNull()
    const amount = ticketReducer(ticketReducer(s, { type: 'qtyMode', mode: 'AMOUNT' }), { type: 'field', field: 'amount', value: '500' })
    expect(buildOrderRequest(amount, occ, option)).toBeNull()
    // sell to close up to the held contracts
    expect(buildOrderRequest(ticketReducer(s, { type: 'side', side: 'SELL' }), occ, { ...option, availableQty: null })).toBeNull()
    expect(qtyUnit(occ, false, true)).toBe('contracts')
    expect(defaultLimitPrice('BUY', { ask: '0.4550', bid: '0.40' }, true)).toBe('0.46')
    expect(midPrice('0.40', '0.47', true)).toBe('0.44')
  })

  it('sizes option quick chips in whole contracts of 100 shares', () => {
    const base = { side: 'BUY' as const, qtyMode: 'SHARES' as const, buyingPower: '1000.00', ask: '3.20', bid: '3.10', availableQty: null, fractionable: false, option: true }
    expect(quickQuantity('1', base)).toBe('3') // 1000 / (3.20 × 100 × 1.02) = 3.06…
    expect(quickQuantity('0.25', base)).toBe('0')
    expect(quickQuantity('0.5', { ...base, side: 'SELL', availableQty: '5' })).toBe('2')
  })

  it('trades crypto in fractional units on every order type, never with the extended-hours flag', () => {
    const crypto = { fractionable: true, availableQty: '0.5', crypto: true }
    let s = initialTicket({ clientOrderId: 'web-1' })
    s = ticketReducer(s, { type: 'extendedHours', value: true })
    s = ticketReducer(s, { type: 'tif', tif: 'GTC' })
    s = ticketReducer(s, { type: 'field', field: 'qty', value: '0.01234' })
    s = ticketReducer(s, { type: 'field', field: 'limitPrice', value: '112000.00' })
    expect(buildOrderRequest(s, 'BTC/USD', crypto)).toMatchObject({ symbol: 'BTC/USD', type: 'LIMIT', qty: '0.01234', timeInForce: 'GTC', extendedHours: false })
    // the same ticket on a stock keeps the flag
    expect(buildOrderRequest(s, 'AAPL', ctx)).toMatchObject({ extendedHours: true })
    const stop = ticketReducer(ticketReducer(s, { type: 'orderType', orderType: 'STOP' }), { type: 'field', field: 'stopPrice', value: '110000.00' })
    expect(buildOrderRequest(stop, 'BTC/USD', crypto)).toMatchObject({ type: 'STOP', qty: '0.01234', stopPrice: '110000.00' })
    expect(qtyUnit('BTC/USD', true)).toBe('BTC')
    expect(qtyUnit('AAPL', false)).toBe('sh')
  })

  it('sizes crypto quick chips to 5 decimals and steps by about $100 to $1,000', () => {
    const base = { side: 'BUY' as const, qtyMode: 'SHARES' as const, buyingPower: '500000.00', ask: '112000.00', bid: '111990.00', availableQty: null, fractionable: true, crypto: true }
    expect(quickQuantity('1', base)).toBe('4.37675') // 500000 / (112000 × 1.02) = 4.376750…
    expect(quickQuantity('0.25', { ...base, ask: '0.2400' })).toBe('510620.91503')
    expect(quickQuantity('1', { ...base, crypto: false })).toBe('4')
    expect(quickQuantity('0.5', { ...base, side: 'SELL', availableQty: '0.00003' })).toBe('0.00001')
    expect(cryptoQtyStep('112000.00')).toBe('0.001')
    expect(cryptoQtyStep('4200.00')).toBe('0.1')
    expect(cryptoQtyStep('210.00')).toBe('1')
    expect(cryptoQtyStep('2.6000')).toBe('100')
    expect(cryptoQtyStep('0.2400')).toBe('1000')
    expect(cryptoQtyStep(null)).toBe('1')
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

  // Review regression: the $ trail is on the tick of the LAST price (check 16), not of the trail's own size
  it('keeps a dollar trail on the tick of the last price for stocks under $100', () => {
    expect(defaultTrail('AMOUNT', '42.56')).toBe('0.43')
    expect(defaultTrail('AMOUNT', '9.87')).toBe('0.10')
    expect(defaultTrail('AMOUNT', '0.8537')).toBe('0.0085')
    expect(stepTrail('0.05', 1, 'AMOUNT', '42.56')).toBe('0.06')
    expect(stepTrail('0.4256', 1, 'AMOUNT', '42.56')).toBe('0.44')
    expect(stepTrail('0.10', -1, 'AMOUNT', '9.87')).toBe('0.09')
    expect(stepTrail('0.0085', 1, 'AMOUNT', '0.8537')).toBe('0.0086')
  })

  it('describes what each stop does', () => {
    expect(stopExplanation({ side: 'SELL', type: 'STOP', symbol: 'AAPL', stopPrice: '220.00' })).toBe('When AAPL trades at or below 220.00, a market order to sell is sent.')
    expect(stopExplanation({ side: 'BUY', type: 'STOP_LIMIT', symbol: 'AAPL', stopPrice: '230.00', limitPrice: '231.00' })).toMatch(/limit order to buy at 231.00 or lower/)
    expect(stopExplanation({ side: 'SELL', type: 'TRAILING_STOP', symbol: 'AAPL', trailPercent: '2' })).toMatch(/highest price 2% below/)
    expect(stopExplanation({ side: 'BUY', type: 'LIMIT', symbol: 'AAPL', limitPrice: '1' })).toBeNull()
  })
})

describe('order labels for stops', async () => {
  const { eventDetailLine, orderPriceLabel, statusLine } = await import('@/features/account/orderLabels')
  it('prints each type with its defining prices', () => {
    expect(orderPriceLabel({ type: 'STOP', stopPrice: '230.0000', limitPrice: null, trailAmount: null, trailPercent: null })).toBe('230.00')
    expect(orderPriceLabel({ type: 'STOP_LIMIT', stopPrice: '230.00', limitPrice: '231.00', trailAmount: null, trailPercent: null })).toBe('230.00 / 231.00')
    expect(orderPriceLabel({ type: 'TRAILING_STOP', stopPrice: '221.70', limitPrice: null, trailAmount: null, trailPercent: '2.5' })).toBe('2.5% trail')
    expect(orderPriceLabel({ type: 'TRAILING_STOP', stopPrice: null, limitPrice: null, trailAmount: '1.50', trailPercent: null })).toBe('$1.50 trail')
    expect(orderPriceLabel({ type: 'MARKET', stopPrice: null, limitPrice: null, trailAmount: null, trailPercent: null })).toBe('')
  })

  // Review regression: the description shows with the pre-filled prices, before a quantity is entered
  it('explains a stop from the ticket state, before a quantity', async () => {
    const { initialTicket: init, ticketExplanation, ticketReducer: reduce } = await import('./ticketState')
    let t = reduce(init({ side: 'SELL' }), { type: 'orderType', orderType: 'STOP' })
    expect(ticketExplanation(t, 'SIMG')).toBeNull()
    t = reduce(t, { type: 'field', field: 'stopPrice', value: '22.77' })
    expect(t.qty).toBe('')
    expect(ticketExplanation(t, 'SIMG')).toBe('When SIMG trades at or below 22.77, a market order to sell is sent.')
    t = reduce(reduce(t, { type: 'orderType', orderType: 'TRAILING_STOP' }), { type: 'field', field: 'trail', value: '0.43' })
    expect(ticketExplanation(t, 'SIMG')).toMatch(/highest price \$0\.43 below/)
    expect(ticketExplanation(reduce(t, { type: 'orderType', orderType: 'LIMIT' }), 'SIMG')).toBeNull()
  })

  // Review regression: a trail amount is money, not a 4-decimal sub-dollar price
  it('formats trail amounts as money, keeping a real sub-cent trail', () => {
    expect(orderPriceLabel({ type: 'TRAILING_STOP', stopPrice: null, limitPrice: null, trailAmount: '0.0500', trailPercent: null })).toBe('$0.05 trail')
    expect(orderPriceLabel({ type: 'TRAILING_STOP', stopPrice: null, limitPrice: null, trailAmount: '0.0085', trailPercent: null })).toBe('$0.0085 trail')
    expect(stopExplanation({ side: 'SELL', type: 'TRAILING_STOP', symbol: 'SIMF', trailAmount: '0.0500' })).toMatch(/highest price \$0\.05 below/)
  })

  it('explains TRIGGERED and TRAIL_UPDATED events and the triggered status', () => {
    expect(eventDetailLine({ seq: 3, type: 'TRIGGERED', statusAfter: 'WORKING', at: null, detail: { price: '230.0500', basis: 'PRINT', stopPrice: '230.00' } })).toBe('at 230.05 · on the last trade · stop 230.00')
    expect(eventDetailLine({ seq: 4, type: 'TRAIL_UPDATED', statusAfter: 'WORKING', at: null, detail: { watermark: '235.10', stopPrice: '229.22' } })).toBe('Stop moved to 229.22 (watermark 235.10)')
    expect(eventDetailLine({ seq: 5, type: 'CANCELLED', statusAfter: 'CANCELLED', at: null, detail: { reason: 'CORPORATE_ACTION' } })).toBe('corporate action')
    expect(statusLine({ status: 'WORKING', waitReason: 'NOT_TRIGGERED', triggered: false } as never)).toBe('Working · waiting for the stop price')
    expect(statusLine({ status: 'WORKING', waitReason: 'LIMIT_NOT_REACHED', triggered: true } as never)).toBe('Working · triggered, waiting for your price')
  })
})
