/** Display labels for orders (§11.5: the UI shows `wait_reason` as a sub-label, e.g. "Working · waiting for your price"). */
import type { Order, OrderEvent, OrderStatus, OrderType, WaitReason } from '@/lib/api/types'
import { type Dec, isDecimal } from '@/lib/format/decimal'
import type { PillTone } from '@/components/ui/Pill'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'

export const ORDER_TYPE_LABEL: Record<OrderType, string> = {
  MARKET: 'Market',
  LIMIT: 'Limit',
  STOP: 'Stop',
  STOP_LIMIT: 'Stop Limit',
  TRAILING_STOP: 'Trailing Stop',
}

export const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: 'Pending',
  WORKING: 'Working',
  PARTIALLY_FILLED: 'Partially filled',
  FILLED: 'Filled',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  REPLACED: 'Replaced',
  REJECTED: 'Rejected',
}

export const STATUS_TONE: Record<OrderStatus, PillTone> = {
  PENDING: 'warn',
  WORKING: 'accent',
  PARTIALLY_FILLED: 'accent',
  FILLED: 'up',
  CANCELLED: 'flat',
  EXPIRED: 'flat',
  REPLACED: 'flat',
  REJECTED: 'down',
}

export const WAIT_LABEL: Record<WaitReason, string> = {
  LATENCY: 'sending to market',
  QUEUED_FOR_OPEN: 'queued for the 9:30 ET open',
  QUEUED_FOR_EXT_OPEN: 'queued for 4:00 ET pre-market',
  SESSION_CLOSED: 'paused until the session opens',
  NOT_TRIGGERED: 'waiting for the stop price',
  AWAITING_QUOTE: 'waiting for a fresh quote',
  HALTED: 'trading halted',
  CORPORATE_ACTION_HOLD: 'corporate-action hold',
  BEYOND_COLLAR: 'price moved beyond the 5% collar',
  LIMIT_NOT_REACHED: 'waiting for your price',
}

export const CANCEL_REASON_LABEL: Record<string, string> = {
  USER: 'Cancelled by you',
  ADMIN: 'Cancelled by the admin',
  PLAYER_FROZEN: 'Cancelled: account frozen',
  PLAYER_DISABLED: 'Cancelled: account disabled',
  ACCOUNT_RESET: 'Cancelled by an account reset',
  CORPORATE_ACTION: 'Cancelled by a corporate action',
  INSUFFICIENT_BUYING_POWER_AT_EXECUTION: 'Cancelled: not enough buying power at execution',
  DUST: 'Cancelled: the amount was below the minimum share size',
  REPLACED_BY_USER: 'Replaced by a new order',
}

/** "Working · waiting for your price" */
export function statusLine(o: Order): string {
  const status = o.status ? STATUS_LABEL[o.status] : 'Unknown'
  if (o.triggered && (o.status === 'PENDING' || o.status === 'WORKING')) {
    return `${status} · triggered${o.waitReason && o.waitReason !== 'NOT_TRIGGERED' ? `, ${WAIT_LABEL[o.waitReason]}` : ''}`
  }
  if (o.waitReason && (o.status === 'PENDING' || o.status === 'WORKING')) return `${status} · ${WAIT_LABEL[o.waitReason]}`
  if (o.status === 'CANCELLED' && o.statusReason) return CANCEL_REASON_LABEL[o.statusReason] ?? `${status} · ${o.statusReason.toLowerCase().replaceAll('_', ' ')}`
  return status
}

/** "10 sh" or "$25.00" */
export function orderSizeLabel(o: Pick<Order, 'notional' | 'qty'>): string {
  return o.notional ? formatMoney(o.notional) : `${formatQty(o.qty)} sh`
}

/** "$1.50" or "2.5%": a trailing stop's trail. */
export function trailLabel(o: Pick<Order, 'trailAmount' | 'trailPercent'>): string | null {
  if (o.trailAmount) return `$${formatPrice(o.trailAmount)}`
  if (o.trailPercent) return `${o.trailPercent}%`
  return null
}

/**
 * The prices that define an order, after its type label: "227.40" (Limit), "230.00" (Stop), "230.00 / 231.00"
 * (Stop Limit: stop / limit), "$1.50 trail" or "2% trail" (Trailing Stop). Empty for Market.
 */
export function orderPriceLabel(o: Pick<Order, 'type' | 'limitPrice' | 'stopPrice' | 'trailAmount' | 'trailPercent'>): string {
  switch (o.type) {
    case 'LIMIT':
      return o.limitPrice ? formatPrice(o.limitPrice) : ''
    case 'STOP':
      return o.stopPrice ? formatPrice(o.stopPrice) : ''
    case 'STOP_LIMIT':
      return [o.stopPrice, o.limitPrice].filter(Boolean).map((p) => formatPrice(p)).join(' / ')
    case 'TRAILING_STOP': {
      const trail = trailLabel(o)
      return trail ? `${trail} trail` : ''
    }
    default:
      return ''
  }
}

/** "Buy 10 sh AAPL · Limit 227.40", "Sell 2 sh F · Trailing Stop 2% trail" */
export function orderTitle(o: Order): string {
  const side = o.side === 'SELL' ? 'Sell' : 'Buy'
  const type = o.type ? ORDER_TYPE_LABEL[o.type] : ''
  const price = orderPriceLabel(o)
  return `${side} ${orderSizeLabel(o)} ${o.symbol ?? ''} · ${type}${price ? ` ${price}` : ''}`
}

/** A stop order that has not triggered yet: the ticket, list and detail show its live stop (§12.7). */
export const isArmedStop = (o: Pick<Order, 'type' | 'triggered' | 'status'>): boolean =>
  (o.type === 'STOP' || o.type === 'STOP_LIMIT' || o.type === 'TRAILING_STOP') && !o.triggered && (o.status === 'PENDING' || o.status === 'WORKING')

/** What triggered a stop (`TRIGGERED` event `detail.basis`, §12.7). */
export const TRIGGER_BASIS_LABEL: Record<string, string> = {
  PRINT: 'last trade',
  QUOTE: 'quote (no recent trade)',
  OPENING_QUOTE: 'opening gap',
}

export const CHANNEL_LABEL: Record<string, string> = {
  WEB: 'Web',
  REST_KEY: 'API key',
  MCP_KEY: 'MCP (key)',
  MCP_OAUTH: 'MCP (OAuth)',
  SYSTEM: 'System',
  ADMIN: 'Admin',
}

const str = (v: unknown): Dec | null => (typeof v === 'string' && isDecimal(v) ? v : null)

/** The second line of a timeline event: what the engine recorded (`OrderEvent.detail`, §11.5, §12.7). */
export function eventDetailLine(ev: OrderEvent): string | null {
  const d = ev.detail ?? {}
  switch (ev.type) {
    case 'TRIGGERED': {
      const price = str(d.price)
      const basis = typeof d.basis === 'string' ? (TRIGGER_BASIS_LABEL[d.basis] ?? d.basis.toLowerCase()) : null
      const stop = str(d.stopPrice)
      const parts = [price ? `at ${formatPrice(price)}` : null, basis ? `on the ${basis}` : null, stop ? `stop ${formatPrice(stop)}` : null].filter(Boolean)
      return parts.length ? parts.join(' · ') : null
    }
    case 'TRAIL_UPDATED': {
      const stop = str(d.stopPrice)
      const wm = str(d.watermark)
      return stop ? `Stop moved to ${formatPrice(stop)}${wm ? ` (watermark ${formatPrice(wm)})` : ''}` : null
    }
    case 'FILL':
    case 'PARTIAL_FILL': {
      const qty = str(d.qty)
      const price = str(d.price)
      return qty && price ? `${formatQty(qty)} @ ${formatPrice(price)}` : null
    }
    default:
      return typeof d.reason === 'string' ? d.reason.toLowerCase().replaceAll('_', ' ') : null
  }
}

