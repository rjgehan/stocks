/**
 * Human copy for the stable §7.2 error codes (and the client's own NETWORK_ERROR / UNEXPECTED_RESPONSE). The
 * server's `detail` is usually precise ("Order needs $251.02; buying power is $200.00"), so it is shown under the
 * title when present; the `field` maps an order rejection to the ticket input it belongs to (§22.5 "Server error
 * codes map to inline field errors") when the problem carries no `fields`.
 */
import { formatPrice } from '@/lib/format/number'
import { isApiError } from './problem'

export interface ErrorCopy {
  title: string
  /** The PlaceOrderRequest member the code is about (inline ticket error). */
  field?: 'qty' | 'notional' | 'limitPrice' | 'stopPrice' | 'trailAmount' | 'trailPercent' | 'side' | 'symbol' | 'timeInForce' | 'extendedHours' | 'type' | 'amount'
}

export const ERROR_COPY: Record<string, ErrorCopy> = {
  // 400 / 401 / 403
  VALIDATION_FAILED: { title: 'Please check the highlighted fields' },
  UNAUTHENTICATED: { title: 'Please sign in again' },
  BAD_CREDENTIALS: { title: 'Wrong handle or password' },
  INVALID_API_KEY: { title: 'Invalid API key' },
  API_KEY_REVOKED: { title: 'This API key was revoked' },
  API_KEY_EXPIRED: { title: 'This API key has expired' },
  FORBIDDEN: { title: 'You are not allowed to do that' },
  FORBIDDEN_SCOPE: { title: 'This key does not have the required scope' },
  ADMIN_REQUIRES_SESSION: { title: 'Admin actions need a browser session' },
  RECENT_AUTH_REQUIRED: { title: 'Confirm your password to continue' },
  CSRF_INVALID: { title: 'Your session token expired; please retry' },
  PLAYER_DISABLED: { title: 'This account is disabled' },
  PLAYER_FROZEN: { title: 'Trading is frozen for this account' },
  // 404 / 409
  NOT_FOUND: { title: 'Not found' },
  SYMBOL_NOT_FOUND: { title: 'Unknown symbol', field: 'symbol' },
  ORDER_NOT_FOUND: { title: 'Order not found' },
  PLAYER_NOT_FOUND: { title: 'Player not found' },
  ACCOUNT_NOT_FOUND: { title: 'Account not found' },
  CLIENT_ORDER_ID_CONFLICT: { title: 'That order was already sent with different details' },
  IDEMPOTENCY_KEY_CONFLICT: { title: 'That request was already sent with different details' },
  ORDER_NOT_CANCELLABLE: { title: 'This order can no longer be cancelled' },
  ORDER_NOT_REPLACEABLE: { title: 'This order can no longer be changed' },
  HANDLE_TAKEN: { title: 'That handle is taken' },
  RUN_REQUEST_CONFLICT: { title: 'This bot already has a run on the way' },
  // 422 orders (§11.2)
  CLIENT_ORDER_ID_INVALID: { title: 'Invalid order id; reopen the ticket' },
  RATIONALE_REQUIRED: { title: 'A rationale is required' },
  SYMBOL_NOT_TRADABLE: { title: 'This symbol cannot be traded right now', field: 'symbol' },
  INVALID_QTY: { title: 'Enter a valid quantity', field: 'qty' },
  QTY_OR_NOTIONAL_REQUIRED: { title: 'Enter shares or a dollar amount', field: 'qty' },
  NOT_FRACTIONABLE: { title: 'This symbol does not support fractional shares', field: 'notional' },
  FRACTIONAL_MARKET_ONLY: { title: 'Fractional and dollar orders must be Market orders', field: 'type' },
  FRACTIONAL_DAY_ONLY: { title: 'Fractional and dollar orders are Day only, regular hours', field: 'timeInForce' },
  FRACTIONAL_NOT_REPLACEABLE: { title: 'Fractional orders cannot be changed; cancel and resubmit' },
  NOTIONAL_TOO_SMALL: { title: 'The minimum dollar order is $1.00', field: 'notional' },
  INVALID_TICK: { title: 'Price must be in $0.01 steps ($0.0001 under $1)', field: 'limitPrice' },
  LIMIT_PRICE_REQUIRED: { title: 'Enter a limit price', field: 'limitPrice' },
  STOP_PRICE_REQUIRED: { title: 'Enter a stop price', field: 'stopPrice' },
  TRAIL_REQUIRED: { title: 'Enter a trail amount or percent', field: 'trailAmount' },
  PRICE_NOT_ALLOWED: { title: 'This order type does not take that price' },
  EXT_HOURS_LIMIT_ONLY: { title: 'Extended hours need a Limit order', field: 'extendedHours' },
  TIF_NOT_ALLOWED: { title: 'That time in force is not allowed for this order', field: 'timeInForce' },
  SESSION_NOT_ALLOWED: { title: 'Not allowed in the current session (trailing stops: 9:30–16:00 ET)' },
  STOP_PRICE_WRONG_SIDE: { title: 'The stop price is on the wrong side of the market', field: 'stopPrice' },
  PRICE_TOO_FAR: { title: 'The price is more than 50% from the market', field: 'limitPrice' },
  SHORT_SELLING_NOT_ALLOWED: { title: 'You have no shares to sell (no short selling)', field: 'side' },
  INSUFFICIENT_POSITION: { title: 'You are selling more shares than you can', field: 'qty' },
  INSUFFICIENT_BUYING_POWER: { title: 'Not enough buying power', field: 'qty' },
  ACCOUNT_RESTRICTED: { title: 'Your account is restricted' },
  TOO_MANY_OPEN_ORDERS: { title: 'Too many open orders (50 max)' },
  GFV_ACK_REQUIRED: { title: 'Acknowledge the good-faith violation to continue' },
  MARKET_DATA_UNAVAILABLE: { title: 'No price available for this symbol right now' },
  // 422 other
  INSUFFICIENT_WITHDRAWABLE: { title: 'More than the withdrawable cash', field: 'amount' },
  MARKS_DEGRADED: { title: 'Some positions have degraded prices' },
  SELF_RESET_DISABLED: { title: 'Self-reset is turned off for this league' },
  INVITE_INVALID: { title: 'This invite link is invalid, used up or expired' },
  WEAK_PASSWORD: { title: 'Password must be 10–72 characters' },
  SETTING_INVALID: { title: 'Invalid setting value' },
  CORPORATE_ACTION_NOT_RESOLVABLE: { title: 'This corporate action cannot be resolved' },
  // 429 / 5xx
  RATE_LIMITED: { title: 'Slow down: too many requests' },
  LOGIN_LOCKED: { title: 'Too many attempts; sign-in is locked for a while' },
  INTERNAL_ERROR: { title: 'Something went wrong on the server' },
  NOT_IMPLEMENTED: { title: 'Not available yet' },
  ENGINE_BUSY: { title: 'The trading engine is busy; try again' },
  ENGINE_TIMEOUT: { title: 'The trading engine timed out; try again' },
  ENGINE_HALTED: { title: 'Trading is paused by the admin' },
  PROVIDER_UNAVAILABLE: { title: 'Market data is unavailable right now' },
  NETWORK_ERROR: { title: 'You appear to be offline' },
  UNEXPECTED_RESPONSE: { title: 'Unexpected response from the server' },
}

export interface DescribedError {
  code: string
  title: string
  detail: string | null
  hint: string | null
}

/** Title / detail / hint for any thrown value; the title comes from ERROR_COPY, else the problem title. */
export function describeError(e: unknown): DescribedError {
  if (isApiError(e)) {
    const copy = ERROR_COPY[e.code]
    const detail = e.problem.detail
    let hint = e.hint
    if (e.code === 'RATE_LIMITED' && e.problem.retryAfterS != null) hint = `Try again in ${e.problem.retryAfterS} s.`
    return { code: e.code, title: copy?.title ?? e.problem.title, detail: detail && detail !== copy?.title ? detail : null, hint }
  }
  return { code: 'UNEXPECTED', title: 'Something went wrong', detail: e instanceof Error ? e.message : null, hint: null }
}

/** The ticket field an order error code belongs to, if any. */
export const errorField = (code: string | null | undefined): ErrorCopy['field'] | undefined => (code ? ERROR_COPY[code]?.field : undefined)

/** One line for a toast body: detail, then hint. */
export function errorBody(d: DescribedError): string | undefined {
  const parts = [d.detail, d.hint].filter((p): p is string => Boolean(p))
  return parts.length ? parts.join(' ') : undefined
}

const FIELD_WORDS: Record<string, string> = {
  stopPrice: 'stop price',
  limitPrice: 'limit price',
  trailAmount: 'trail',
  trailPercent: 'trail %',
  qty: 'quantity',
  notional: 'amount',
  timeInForce: 'time in force',
  extendedHours: 'extended hours',
}

/**
 * A server validation message made readable for the ticket: request field names in words and prices at their
 * display scale ("stopPrice 900.0000" → "stop price 900.00").
 */
export function humanizeMessage(message: string): string {
  return message
    .replace(/\b(stopPrice|limitPrice|trailAmount|trailPercent|qty|notional|timeInForce|extendedHours)\b/g, (m) => FIELD_WORDS[m] ?? m)
    .replace(/\b\d+\.\d{3,}\b/g, (n) => formatPrice(n))
}

const NUMBER = '(\\d+(?:\\.\\d+)?)'

/**
 * The ticket's text for a preview warning (§22.5): FAT_FINGER reads "Take profit limit price is 5.0% from the market
 * (11.97)." (leg and field in words, never `limitPrice`), every other warning its message made readable.
 */
export function warningText(w: { code?: string | null; message?: string | null }): string {
  const msg = w.message ?? ''
  if (w.code === 'FAT_FINGER') {
    const m = new RegExp(`^(?:(.+?): )?(.+?) is ${NUMBER}% from the reference price ${NUMBER}\\.?$`).exec(msg)
    if (m) {
      const [, leg, field, pct, ref] = m
      const what = humanizeMessage(field!)
      const subject = leg ? `${leg} ${what}` : what.charAt(0).toUpperCase() + what.slice(1)
      return `${subject} is ${pct}% from the market (${formatPrice(ref!)}).`
    }
  }
  return humanizeMessage(msg)
}

/**
 * The ticket's text for an order validation error (§22.5): friendly copy for the codes players hit with stops and
 * trails, with the market price formatted ("Buy stops must be above the market (256.33)"), never a Java field name
 * or a 4-decimal internal price. Works on the problem's message and on a field's short message ("Above 256.3300").
 */
export function orderErrorText(e: { code?: string | null; message?: string | null }): string {
  const msg = e.message ?? ''
  const ref = new RegExp(`(?:reference|from|^Above|^Below) ${NUMBER}`).exec(msg)?.[1]
  const at = ref ? ` (${formatPrice(ref)})` : ''
  switch (e.code) {
    case 'STOP_PRICE_WRONG_SIDE':
      return /\bbuy\b|^Above/i.test(msg) ? `Buy stops must be above the market${at}.` : `Sell stops must be below the market${at}.`
    case 'PRICE_TOO_FAR':
      return `Too far from the market price${at}.`
    case 'INVALID_TICK': {
      const tick = new RegExp(`(?:on the|multiple of) ${NUMBER}`).exec(msg)?.[1]
      return tick ? `Use steps of $${tick}.` : `${ERROR_COPY.INVALID_TICK!.title}.`
    }
    default:
      return humanizeMessage(msg) || ERROR_COPY[e.code ?? '']?.title || 'Invalid value'
  }
}
