/**
 * Trade-ticket state and rules (§22.5), kept pure so they are unit-testable:
 *  - Amount ($) forces Market + Day + no extended hours, and needs a fractionable symbol;
 *  - GTC is disabled for Trailing Stop and for Amount;
 *  - extended hours are enabled for Limit only;
 *  - Sell is disabled without a position (no shorting);
 *  - the clientOrderId (`web-<uuid>`) is created when the ticket opens and reused on retry.
 */
import type { OrderType, PlaceOrderRequest, Preferences, Side, TimeInForce } from '@/lib/api/types'
import {
  type Dec,
  add,
  div,
  isZero,
  mul,
  parseUserDecimal,
  roundToTick,
  setScale,
  sign,
} from '@/lib/format/decimal'

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
  note: string
  shareNote: boolean
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
  | { type: 'trailMode'; mode: TrailMode }
  | { type: 'shareNote'; value: boolean }
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
    note: '',
    shareNote: false,
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
    case 'trailMode':
      return { ...s, trailMode: a.mode }
    case 'shareNote':
      return { ...s, shareNote: a.value }
    case 'gfvAck':
      return { ...s, gfvAck: a.value }
    case 'submitted':
      // A new order gets a new idempotency key; quantities are cleared, the order setup is kept.
      return { ...s, qty: '', amount: '', note: '', shareNote: false, gfvAck: false, clientOrderId: a.clientOrderId }
  }
}

/** §11.1: MARKET and TRAILING_STOP are Day only; dollar (notional) orders are Day only too. */
export const gtcAllowed = (s: Pick<TicketState, 'type' | 'qtyMode'>): boolean => s.type !== 'TRAILING_STOP' && s.type !== 'MARKET' && s.qtyMode !== 'AMOUNT'
export const extAllowed = (s: Pick<TicketState, 'type'>): boolean => s.type === 'LIMIT'
export const showsLimit = (t: OrderType): boolean => t === 'LIMIT' || t === 'STOP_LIMIT'
export const showsStop = (t: OrderType): boolean => t === 'STOP' || t === 'STOP_LIMIT'
export const showsTrail = (t: OrderType): boolean => t === 'TRAILING_STOP'

export interface TicketContext {
  fractionable: boolean
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
    extendedHours: extAllowed(s) && s.extendedHours,
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
  if (note) {
    req.rationale = note.slice(0, 1000)
    req.shareNote = s.shareNote
  }
  if (s.gfvAck) req.acknowledge = ['GFV_RISK']
  return req
}

/** Mid price rounded to tick, for the Bid · Mid · Ask · Last chips. */
export function midPrice(bid: Dec, ask: Dec): Dec {
  return roundToTick(div(add(bid, ask), '2', 6, 'HALF_EVEN'))
}

/**
 * Quick quantity chips (§22.5): 25% · 50% · Max. Max = buying power ÷ (ask × 1.02) whole shares for share
 * buys, buying power for amount buys, and the available position for sells. Client-side sizing only; the
 * server re-validates everything.
 */
export function quickQuantity(
  fraction: '0.25' | '0.5' | '1',
  opts: { side: Side; qtyMode: QtyMode; buyingPower: Dec | null; ask: Dec | null; bid: Dec | null; availableQty: Dec | null; fractionable: boolean },
): string | null {
  if (opts.side === 'BUY') {
    if (!opts.buyingPower || sign(opts.buyingPower) <= 0) return null
    const budget = mul(opts.buyingPower, fraction)
    if (opts.qtyMode === 'AMOUNT') return setScale(budget, 2, 'FLOOR')
    if (!opts.ask || isZero(opts.ask)) return null
    return div(budget, mul(opts.ask, '1.02'), 0, 'FLOOR')
  }
  if (!opts.availableQty || sign(opts.availableQty) <= 0) return null
  if (opts.qtyMode === 'AMOUNT') {
    if (!opts.bid) return null
    return setScale(mul(mul(opts.availableQty, opts.bid), fraction), 2, 'FLOOR')
  }
  if (fraction === '1') return opts.availableQty
  return setScale(mul(opts.availableQty, fraction), opts.fractionable ? 5 : 0, 'FLOOR')
}
