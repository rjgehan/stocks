/** Display labels for orders (§11.5: the UI shows `wait_reason` as a sub-label, e.g. "Working · waiting for your price"). */
import type { Order, OrderStatus, OrderType, WaitReason } from '@/lib/api/types'
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
  if (o.waitReason && (o.status === 'PENDING' || o.status === 'WORKING')) return `${status} · ${WAIT_LABEL[o.waitReason]}`
  if (o.status === 'CANCELLED' && o.statusReason) return CANCEL_REASON_LABEL[o.statusReason] ?? `${status} · ${o.statusReason.toLowerCase().replaceAll('_', ' ')}`
  return status
}

/** "10 sh" or "$25.00" */
export function orderSizeLabel(o: Pick<Order, 'notional' | 'qty'>): string {
  return o.notional ? formatMoney(o.notional) : `${formatQty(o.qty)} sh`
}

/** "Buy 10 sh AAPL · Limit 227.40" */
export function orderTitle(o: Order): string {
  const side = o.side === 'SELL' ? 'Sell' : 'Buy'
  const type = o.type ? ORDER_TYPE_LABEL[o.type] : ''
  const price = o.limitPrice ? ` ${formatPrice(o.limitPrice)}` : o.stopPrice ? ` stop ${formatPrice(o.stopPrice)}` : ''
  return `${side} ${orderSizeLabel(o)} ${o.symbol ?? ''} · ${type}${price}`
}

export const CHANNEL_LABEL: Record<string, string> = {
  WEB: 'Web',
  REST_KEY: 'API key',
  MCP_KEY: 'MCP (key)',
  MCP_OAUTH: 'MCP (OAuth)',
  SYSTEM: 'System',
  ADMIN: 'Admin',
}
