/** Money formatting (§22.7): "$1,234.56", "+$1,234.56", "−$12.30"; missing → "--". */
import { type Dec, decimals, isDecimal } from './decimal'
import { MINUS, MISSING, type Maybe, abbreviate, formatDecimal } from './number'

export interface MoneyOptions {
  /** Prefix positives with "+" (P&L, flows). Negatives always get "−". */
  signed?: boolean
  scale?: number
}

export function formatMoney(value: Maybe<Dec>, options: MoneyOptions = {}): string {
  if (value == null || !isDecimal(value)) return MISSING
  const { signed = false, scale = 2 } = options
  const body = formatDecimal(value, { scale, signed })
  if (body.startsWith(MINUS)) return `${MINUS}$${body.slice(1)}`
  if (body.startsWith('+')) return `+$${body.slice(1)}`
  return `$${body}`
}

/** "$3.42T" style for large amounts (market cap). */
export function formatMoneyCompact(value: Maybe<Dec>): string {
  const body = abbreviate(value)
  if (body === MISSING) return MISSING
  return body.startsWith(MINUS) ? `${MINUS}$${body.slice(1)}` : `$${body}`
}

/**
 * A trail amount in dollars (§12.7): cents, unless it really has sub-cent digits (a sub-$1 stock trails on its
 * $0.0001 tick). "0.0500" → "$0.05", "0.0085" → "$0.0085". Never shown as a 4-decimal price.
 */
export function formatTrailAmount(value: Maybe<Dec>): string {
  if (value == null || !isDecimal(value)) return MISSING
  return formatMoney(value, { scale: decimals(value) > 2 ? 4 : 2 })
}
