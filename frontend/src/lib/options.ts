/**
 * Option contract symbols (§30): the OCC spelling ROOT (1–6 letters) + YYMMDD + C|P + strike × 1000 (8 digits), e.g.
 * `AAPL261120C00250000` = the AAPL 2026-11-20 $250 call. Pure helpers, like `symbols.ts` for crypto pairs. A contract
 * covers 100 shares: the API's dollar amounts (principal, marketValue, costBasis, P&L) already include the × 100,
 * its prices (bid, ask, last, avgCost, limitPrice, fill prices) are per share.
 */
import { type Dec, compare, isDecimal, max, setScale, sign, stripTrailingZeros, sub } from './format/decimal'
import { formatDecimal, formatQty } from './format/number'

/** Shares per contract. */
export const MULTIPLIER = 100

export type OptionRight = 'CALL' | 'PUT'

export interface OptionParts {
  underlying: string
  /** YYYY-MM-DD */
  expiration: string
  right: OptionRight
  /** Per-share strike, trailing zeros stripped ("250", "252.5"). */
  strike: Dec
}

const OCC = /^([A-Z]{1,6})(\d{2})(\d{2})(\d{2})([CP])(\d{8})$/
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const

/** True for an OCC option symbol (`AAPL261120C00250000`), upper case. */
export function isOption(symbol: string | null | undefined): boolean {
  return symbol != null && OCC.test(symbol)
}

/** The parts of an OCC symbol, or null when it is not one. */
export function parseOption(symbol: string | null | undefined): OptionParts | null {
  const m = symbol ? OCC.exec(symbol) : null
  if (!m) return null
  const [, underlying, yy, mm, dd, cp, strike] = m
  const month = Number(mm)
  const day = Number(dd)
  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  return {
    underlying: underlying!,
    expiration: `20${yy}-${mm}-${dd}`,
    right: cp === 'C' ? 'CALL' : 'PUT',
    strike: stripTrailingZeros(setScale(`${Number(strike!.slice(0, 5))}.${strike!.slice(5)}`, 3)),
  }
}

/** The OCC symbol of a contract; the strike must have at most 3 decimals. */
export function formatOption(parts: OptionParts): string {
  const [yyyy, mm, dd] = parts.expiration.split('-')
  const thousandths = setScale(parts.strike, 3, 'HALF_UP').replace('.', '')
  return `${parts.underlying.toUpperCase()}${yyyy!.slice(2)}${mm}${dd}${parts.right === 'CALL' ? 'C' : 'P'}${thousandths.padStart(8, '0')}`
}

/** The underlying of an OCC symbol; any other symbol is returned as is. */
export function underlyingOf(symbol: string): string {
  return parseOption(symbol)?.underlying ?? symbol
}

/** "AAPL 20 Nov 26 $250 Call"; any other symbol is returned as is. */
export function optionLabel(symbol: string | null | undefined): string {
  const o = parseOption(symbol)
  if (!o) return symbol ?? ''
  const [yyyy, mm, dd] = o.expiration.split('-')
  return `${o.underlying} ${Number(dd)} ${MONTHS[Number(mm) - 1]} ${yyyy!.slice(2)} $${o.strike} ${o.right === 'CALL' ? 'Call' : 'Put'}`
}

/** "AAPL 250C 11/20" (tables and chips); any other symbol is returned as is. */
export function optionShortLabel(symbol: string | null | undefined): string {
  const o = parseOption(symbol)
  if (!o) return symbol ?? ''
  const [, mm, dd] = o.expiration.split('-')
  return `${o.underlying} ${o.strike}${o.right === 'CALL' ? 'C' : 'P'} ${mm}/${dd}`
}

/** Per-share intrinsic value at an underlying price: max(0, S − K) for a call, max(0, K − S) for a put. */
export function intrinsicValue(right: OptionRight, strike: Dec, underlyingPrice: Dec): Dec {
  if (!isDecimal(strike) || !isDecimal(underlyingPrice)) return '0'
  return max('0', right === 'CALL' ? sub(underlyingPrice, strike) : sub(strike, underlyingPrice))
}

/** True when the contract is in the money at the underlying price (intrinsic value above zero). */
export function inTheMoney(right: OptionRight, strike: Dec, underlyingPrice: Dec | null | undefined): boolean {
  return underlyingPrice != null && isDecimal(underlyingPrice) && intrinsicValue(right, strike, underlyingPrice) !== '0'
}

/** "1 contract", "3 contracts". */
export function contractsLabel(qty: Dec | null | undefined): string {
  return `${formatQty(qty)} ${qty != null && isDecimal(qty) && compare(qty, '1') === 0 ? 'contract' : 'contracts'}`
}

/** An option price per share, always in cents ("0.45", never "0.4500"). */
export function formatOptionPrice(value: Dec | null | undefined): string {
  return formatDecimal(value, { scale: 2 })
}

/** How an expired contract settled (§30): "Cash-settled at $0.62" at its intrinsic value, or "Expired worthless" at 0. */
export function expiryLabel(price: Dec | null | undefined): string {
  return price != null && isDecimal(price) && sign(price) > 0 ? `Cash-settled at $${formatOptionPrice(price)}` : 'Expired worthless'
}

/**
 * An option expiration (§30): the SYSTEM sell the backend records at the close on expiration day (clientOrderId
 * `expiry:<OCC>` where the order carries it; a public order has only the channel).
 */
export function isExpiryOrder(o: { symbol?: string | null; side?: string | null; channel?: string | null; clientOrderId?: string | null }): boolean {
  if (!isOption(o.symbol) || o.side !== 'SELL') return false
  return o.clientOrderId != null ? o.clientOrderId.startsWith('expiry:') : o.channel === 'SYSTEM'
}

/** Calendar days from `today` (YYYY-MM-DD) to an expiration (YYYY-MM-DD); 0 on expiration day. */
export function daysToExpiry(expiration: string, today: string): number {
  return Math.round((Date.parse(`${expiration}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000)
}
