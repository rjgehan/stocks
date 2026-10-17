/**
 * Trade-ticket state and rules (§22.5), kept pure so they are unit-testable:
 *  - Amount ($) forces Market + Day + no extended hours, and needs a fractionable symbol;
 *  - GTC is disabled for Trailing Stop and for Amount;
 *  - extended hours are enabled for Limit only, and never for crypto (it trades 24/7);
 *  - Sell is disabled without a position (no shorting);
 *  - the clientOrderId (`web-<uuid>`) is created when the ticket opens and reused on retry.
 * Crypto pairs (`BTC/USD`) take quantities with up to 5 decimals on every order type.
 */
import type { OrderMatrixRow, OrderType, PlaceOrderRequest, Preferences, Side, TimeInForce } from '@/lib/api/types'
import {
  type Dec,
  add,
  div,
  isDecimal,
  isZero,
  mul,
  parseUserDecimal,
  roundToTick,
  parts,
  roundToTickOf,
  setScale,
  shift,
  sign,
  stripTrailingZeros,
  sub,
  tickFor,
} from '@/lib/format/decimal'
import { formatTrailAmount } from '@/lib/format/money'
import { formatPrice } from '@/lib/format/number'
import { baseAsset } from '@/lib/symbols'

export type QtyMode = 'SHARES' | 'AMOUNT'
export type TrailMode = 'AMOUNT' | 'PERCENT'

export interface TicketState {
  side: Side
  type: OrderType
  qtyMode: QtyMode
  qty: string
  amount: string
  limitPrice: string
  stopPrice: string
  trail: string
  trailMode: TrailMode
  tif: TimeInForce
  extendedHours: boolean
  /** FINRA 5330 (§14, Phase 2): keep this order's prices on ex-dividend dates. */
  doNotReduce: boolean
  /** Public with the order (public mode, §16.7): there is no private note any more. */
  note: string
  gfvAck: boolean
  clientOrderId: string
}

export type TicketAction =
  | { type: 'side'; side: Side }
  | { type: 'orderType'; orderType: OrderType }
  | { type: 'qtyMode'; mode: QtyMode }
  | { type: 'field'; field: 'qty' | 'amount' | 'limitPrice' | 'stopPrice' | 'trail' | 'note'; value: string }
  | { type: 'tif'; tif: TimeInForce }
  | { type: 'extendedHours'; value: boolean }
  | { type: 'doNotReduce'; value: boolean }
  | { type: 'trailMode'; mode: TrailMode }
  | { type: 'gfvAck'; value: boolean }
  | { type: 'submitted'; clientOrderId: string }

export function newClientOrderId(): string {
  return `web-${crypto.randomUUID()}`
}

export function initialTicket(options: { side?: Side; preferences?: Preferences | null; clientOrderId?: string } = {}): TicketState {
  const p = options.preferences
  const base: TicketState = {
    side: options.side ?? 'BUY',
    type: p?.defaultOrderType ?? 'LIMIT',
    qtyMode: 'SHARES',
    qty: '',
    amount: '',
    limitPrice: '',
    stopPrice: '',
    trail: '',
    trailMode: 'AMOUNT',
    tif: (p?.defaultOrderType ?? 'LIMIT') === 'MARKET' ? 'DAY' : (p?.defaultTimeInForce ?? 'DAY'),
    extendedHours: false,
    doNotReduce: false,
    note: '',
    gfvAck: false,
    clientOrderId: options.clientOrderId ?? newClientOrderId(),
  }
  return p?.defaultQtyMode === 'AMOUNT' ? ticketReducer(base, { type: 'qtyMode', mode: 'AMOUNT' }) : base
}

export function ticketReducer(s: TicketState, a: TicketAction): TicketState {
  switch (a.type) {
    case 'side':
      return { ...s, side: a.side, gfvAck: false }
    case 'orderType': {
      const next: TicketState = { ...s, type: a.orderType }
      if (a.orderType !== 'LIMIT') next.extendedHours = false
      if (a.orderType === 'TRAILING_STOP' || a.orderType === 'MARKET') next.tif = 'DAY'
      if (a.orderType !== 'MARKET' && s.qtyMode === 'AMOUNT') next.qtyMode = 'SHARES'
      return next
    }
    case 'qtyMode':
      return a.mode === 'AMOUNT'
        ? { ...s, qtyMode: 'AMOUNT', type: 'MARKET', tif: 'DAY', extendedHours: false }
        : { ...s, qtyMode: 'SHARES' }
    case 'field':
      return { ...s, [a.field]: a.value }
    case 'tif':
      if (a.tif === 'GTC' && !gtcAllowed(s)) return s
      return { ...s, tif: a.tif }
    case 'extendedHours':
      return { ...s, extendedHours: a.value && s.type === 'LIMIT' }
    case 'doNotReduce':
      return { ...s, doNotReduce: a.value }
    case 'trailMode':
      return { ...s, trailMode: a.mode }
    case 'gfvAck':
      return { ...s, gfvAck: a.value }
    case 'submitted':
      // A new order gets a new idempotency key; quantities are cleared, the order setup is kept.
      return { ...s, qty: '', amount: '', note: '', gfvAck: false, clientOrderId: a.clientOrderId }
  }
}

/** §11.1: MARKET and TRAILING_STOP are Day only; dollar (notional) orders are Day only too. */
export const gtcAllowed = (s: Pick<TicketState, 'type' | 'qtyMode'>, row?: Pick<OrderMatrixRow, 'timeInForce'> | null): boolean =>
  s.type !== 'TRAILING_STOP' && s.type !== 'MARKET' && s.qtyMode !== 'AMOUNT' && (!row?.timeInForce || row.timeInForce.includes('GTC'))
export const extAllowed = (s: Pick<TicketState, 'type'>): boolean => s.type === 'LIMIT'
/**
 * FINRA 5330 (§14, Phase 2): the orders a cash dividend reduces on its ex-date (a BUY LIMIT's limit, a SELL STOP's
 * or STOP_LIMIT's prices), which is when "Don't reduce" means something.
 */
export const dividendAdjustable = (s: Pick<TicketState, 'side' | 'type' | 'qtyMode'>): boolean =>
  s.qtyMode === 'SHARES' && (s.side === 'BUY' ? s.type === 'LIMIT' : s.type === 'STOP' || s.type === 'STOP_LIMIT')
export const showsLimit = (t: OrderType): boolean => t === 'LIMIT' || t === 'STOP_LIMIT'
export const showsStop = (t: OrderType): boolean => t === 'STOP' || t === 'STOP_LIMIT'
export const showsTrail = (t: OrderType): boolean => t === 'TRAILING_STOP'
export const isStopType = (t: OrderType | null | undefined): boolean => t === 'STOP' || t === 'STOP_LIMIT' || t === 'TRAILING_STOP'

/** §11.1 order, which the ticket's type picker follows. */
export const ALL_ORDER_TYPES: readonly OrderType[] = ['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP']
/** What a backend that does not publish `GET /rules` (or has not answered yet) is sure to accept (M1). */
export const BASE_ORDER_TYPES: readonly OrderType[] = ['MARKET', 'LIMIT']

/**
 * The order types this release accepts, from `GET /rules` `orders.matrix` (§18.3: only the types in
 * `RulesService.AVAILABLE_ORDER_TYPES`), in §11.1 order. Without the rulebook the ticket offers MARKET and LIMIT.
 */
export function availableOrderTypes(matrix: ReadonlyArray<Pick<OrderMatrixRow, 'type'>> | null | undefined): OrderType[] {
  if (!matrix?.length) return [...BASE_ORDER_TYPES]
  const listed = new Set(matrix.map((r) => r.type).filter((t): t is OrderType => t != null))
  const types = ALL_ORDER_TYPES.filter((t) => listed.has(t))
  return types.length ? types : [...BASE_ORDER_TYPES]
}

/**
 * The stop price a ticket starts with, 1% through the market on the side the server accepts (check 19: a buy stop
 * above the last price, a sell stop below it), on tick. Null without a price.
 */
export function defaultStopPrice(side: Side, last: Dec | null | undefined): Dec | null {
  if (!last || !isDecimal(last) || sign(last) <= 0) return null
  return side === 'BUY' ? roundToTick(mul(last, '1.01'), 'CEILING') : roundToTick(mul(last, '0.99'), 'FLOOR')
}

/**
 * A trail to start with: 1% (percent mode) or 1% of the last price in dollars, on the tick of the last price (the
 * server checks a trail amount against the reference price's tick, §11.2 check 16: $0.43 on a $42.56 stock, never
 * $0.4256).
 */
export function defaultTrail(mode: TrailMode, last: Dec | null | undefined): string {
  if (mode === 'PERCENT') return '1'
  if (!last || !isDecimal(last) || sign(last) <= 0) return ''
  const t = roundToTickOf(mul(last, '0.01'), last)
  return sign(t) > 0 ? t : tickFor(last)
}

/** One step of the trail stepper: a tick of the last price in dollars, a tenth of a percent. */
export function stepTrail(value: string, dir: 1 | -1, mode: TrailMode, last: Dec | null | undefined): string {
  const current = parseUserDecimal(value)
  const reference = last && isDecimal(last) && sign(last) > 0 ? last : '1'
  const unit = mode === 'PERCENT' ? '0.1' : tickFor(reference)
  const next = current ? (dir === 1 ? add(current, unit) : sub(current, unit)) : unit
  return sign(next) > 0 ? (mode === 'PERCENT' ? setScale(next, 1, 'HALF_UP') : roundToTickOf(next, reference)) : unit
}

/**
 * Where a trailing stop would start right now (§12.7, display only): SELL `TICK_DOWN(last − trail)`, BUY
 * `TICK_UP(last + trail)`, with a percent trail taken of the last price. The server owns the real watermark.
 */
export function trailingStopStart(side: Side, last: Dec | null | undefined, trail: string, mode: TrailMode): Dec | null {
  const t = parseUserDecimal(trail)
  if (!t || sign(t) <= 0 || !last || !isDecimal(last) || sign(last) <= 0) return null
  const distance = mode === 'PERCENT' ? div(mul(last, t), '100', 8, 'HALF_UP') : t
  const stop = side === 'SELL' ? roundToTick(sub(last, distance), 'FLOOR') : roundToTick(add(last, distance), 'CEILING')
  return sign(stop) > 0 ? stop : null
}

/** A one-line, plain-English description of what a stop order will do (shown in the ticket and the review sheet). */
export function stopExplanation(o: {
  side: Side
  type: OrderType
  symbol: string
  stopPrice?: Dec | null
  limitPrice?: Dec | null
  trailAmount?: Dec | null
  trailPercent?: Dec | null
}): string | null {
  const buy = o.side === 'BUY'
  const verb = buy ? 'buy' : 'sell'
  const crosses = buy ? 'at or above' : 'at or below'
  switch (o.type) {
    case 'STOP':
      return o.stopPrice ? `When ${o.symbol} trades ${crosses} ${formatPrice(o.stopPrice)}, a market order to ${verb} is sent.` : null
    case 'STOP_LIMIT':
      return o.stopPrice && o.limitPrice
        ? `When ${o.symbol} trades ${crosses} ${formatPrice(o.stopPrice)}, a limit order to ${verb} at ${formatPrice(o.limitPrice)} ${buy ? 'or lower' : 'or higher'} is placed. It may not fill.`
        : null
    case 'TRAILING_STOP': {
      const trail = o.trailAmount ? formatTrailAmount(o.trailAmount) : o.trailPercent ? `${o.trailPercent}%` : null
      if (!trail) return null
      return buy
        ? `The stop follows ${o.symbol}'s lowest price ${trail} above it; when ${o.symbol} rises to the stop, a market order to buy is sent.`
        : `The stop follows ${o.symbol}'s highest price ${trail} below it; when ${o.symbol} falls to the stop, a market order to sell is sent.`
    }
    default:
      return null
  }
}

/**
 * The ticket's stop description, built from what the ticket shows (§22.5: choosing a stop type adds it), so it
 * appears as soon as the stop, limit or trail is set, before a quantity is entered.
 */
export function ticketExplanation(s: Pick<TicketState, 'side' | 'type' | 'stopPrice' | 'limitPrice' | 'trail' | 'trailMode'>, symbol: string): string | null {
  if (!isStopType(s.type)) return null
  const trail = positive(s.trail)
  return stopExplanation({
    side: s.side,
    type: s.type,
    symbol,
    stopPrice: positive(s.stopPrice),
    limitPrice: positive(s.limitPrice),
    trailAmount: s.trailMode === 'AMOUNT' ? trail : null,
    trailPercent: s.trailMode === 'PERCENT' ? trail : null,
  })
}

export interface TicketContext {
  fractionable: boolean
  /** A crypto pair: 24/7, no extended-hours flag, fractional quantities on every order type. */
  crypto?: boolean
  /** Shares the player can sell (0 / null = no position). */
  availableQty: Dec | null
}

export function amountHint(s: TicketState, ctx: TicketContext): string | null {
  if (s.qtyMode === 'AMOUNT' && !ctx.fractionable) return 'Dollar orders need a fractionable symbol.'
  return null
}

export const canSell = (ctx: TicketContext): boolean => ctx.availableQty != null && sign(ctx.availableQty) > 0

const positive = (v: string): Dec | null => {
  const d = parseUserDecimal(v)
  return d !== null && sign(d) > 0 ? d : null
}

/** The `PlaceOrderRequest` for preview/submit, or null while the ticket is incomplete. */
export function buildOrderRequest(s: TicketState, symbol: string, ctx: TicketContext): PlaceOrderRequest | null {
  if (s.side === 'SELL' && !canSell(ctx)) return null
  if (amountHint(s, ctx)) return null
  const qty = s.qtyMode === 'SHARES' ? positive(s.qty) : null
  const notional = s.qtyMode === 'AMOUNT' ? positive(s.amount) : null
  if (!qty && !notional) return null
  const req: PlaceOrderRequest = {
    clientOrderId: s.clientOrderId,
    symbol,
    side: s.side,
    type: s.type,
    timeInForce: gtcAllowed(s) ? s.tif : 'DAY',
    extendedHours: !ctx.crypto && extAllowed(s) && s.extendedHours,
  }
  if (qty) req.qty = qty
  if (notional) req.notional = setScale(notional, 2, 'DOWN')
  if (showsLimit(s.type)) {
    const p = positive(s.limitPrice)
    if (!p) return null
    req.limitPrice = p
  }
  if (showsStop(s.type)) {
    const p = positive(s.stopPrice)
    if (!p) return null
    req.stopPrice = p
  }
  if (showsTrail(s.type)) {
    const t = positive(s.trail)
    if (!t) return null
    if (s.trailMode === 'AMOUNT') req.trailAmount = t
    else req.trailPercent = t
  }
  const note = s.note.trim()
  if (note) req.rationale = note.slice(0, 1000)
  if (s.gfvAck) req.acknowledge = ['GFV_RISK']
  if (s.doNotReduce && dividendAdjustable(s) && !ctx.crypto) req.doNotReduce = true
  return req
}

const QUEUE_WARNINGS = new Set(['QUEUED_FOR_OPEN', 'QUEUED_FOR_EXT_OPEN'])

/**
 * The preview warnings to show next to the estimate: a QUEUED_FOR_OPEN / QUEUED_FOR_EXT_OPEN warning repeats the
 * preview's `sessionNote` ("Order will queue for Mon 09:30 ET"), which the estimate already shows, so it is dropped
 * when the note is there.
 */
export function withoutQueueDuplicates<W extends { code?: string | null }>(warnings: readonly W[], sessionNote: string | null | undefined): W[] {
  return sessionNote ? warnings.filter((w) => !QUEUE_WARNINGS.has(w.code ?? '')) : [...warnings]
}

/** A price that is really there: Alpaca sends bid/ask "0" when IEX has no quote today (weekends, before 08:00 ET). */
const quoted = (v: Dec | null | undefined): Dec | null => (v && sign(v) > 0 ? v : null)

/**
 * The limit price a new ticket starts with: the touch (ask to buy, bid to sell), else the last trade, rounded to the
 * tick. A zero side counts as no quote, so a closed-market ticket never pre-fills 0.00.
 */
export function defaultLimitPrice(side: Side, q: { ask?: Dec | null; bid?: Dec | null; last?: Dec | null }): Dec | null {
  const px = quoted(side === 'BUY' ? q.ask : q.bid) ?? quoted(q.last)
  return px ? roundToTick(px) : null
}

/** Mid price rounded to tick, for the Bid · Mid · Ask · Last chips. */
export function midPrice(bid: Dec, ask: Dec): Dec {
  return roundToTick(div(add(bid, ask), '2', 6, 'HALF_EVEN'))
}

/**
 * Quick quantity chips (§22.5): 25% · 50% · Max. Max = buying power ÷ (ask × 1.02) whole shares for share
 * buys (crypto: units to 5 decimals), buying power for amount buys, and the available position for sells. Client-side sizing only; the
 * server re-validates everything.
 */
export function quickQuantity(
  fraction: '0.25' | '0.5' | '1',
  opts: { side: Side; qtyMode: QtyMode; buyingPower: Dec | null; ask: Dec | null; bid: Dec | null; availableQty: Dec | null; fractionable: boolean; crypto?: boolean },
): string | null {
  if (opts.side === 'BUY') {
    if (!opts.buyingPower || sign(opts.buyingPower) <= 0) return null
    const budget = mul(opts.buyingPower, fraction)
    if (opts.qtyMode === 'AMOUNT') return setScale(budget, 2, 'FLOOR')
    if (!opts.ask || isZero(opts.ask)) return null
    return opts.crypto ? stripTrailingZeros(div(budget, mul(opts.ask, '1.02'), 5, 'FLOOR')) : div(budget, mul(opts.ask, '1.02'), 0, 'FLOOR')
  }
  if (!opts.availableQty || sign(opts.availableQty) <= 0) return null
  if (opts.qtyMode === 'AMOUNT') {
    if (!opts.bid || isZero(opts.bid)) return null
    return setScale(mul(mul(opts.availableQty, opts.bid), fraction), 2, 'FLOOR')
  }
  if (fraction === '1') return opts.availableQty
  return setScale(mul(opts.availableQty, fraction), opts.fractionable ? 5 : 0, 'FLOOR')
}

/** What a quantity counts: "sh" for a stock, the coin for a crypto pair (`BTC/USD` → "BTC"). */
export function qtyUnit(symbol: string, crypto: boolean): string {
  return crypto ? baseAsset(symbol) : 'sh'
}

/**
 * The quantity stepper's step for a crypto pair: the power of ten worth about $100 to $1,000 at the last price
 * (0.001 BTC at $112,000, 1 SOL at $210, 1,000 DOGE at $0.24), never finer than the 5 decimals the server takes.
 * 1 without a price.
 */
export function cryptoQtyStep(last: Dec | null | undefined): Dec {
  if (!last || !isDecimal(last) || sign(last) <= 0) return '1'
  const { int, frac } = parts(last)
  const intDigits = int.replace(/^0+/, '').length
  // price in [10^(d−1), 10^d) → step 10^(3−d); below $1, d is minus the zeros after the point
  const d = intDigits > 0 ? intDigits : -(frac.length - frac.replace(/^0+/, '').length)
  return stripTrailingZeros(shift('1', Math.max(3 - d, -5)))
}
