/**
 * Number formatting (§22.7). Inputs are server decimal strings; nothing here parses money into `number`.
 * - prices: 2 decimals, or 4 below $1
 * - explicit signs with a real minus (U+2212): "+3.12", "−0.82"
 * - percentages: 2 decimals, computed from FRACTION strings ("0.0123" → "+1.23%")
 * - large numbers abbreviated ("41.2M", "3.42T"); a missing value is "--"
 */
import {
  type Dec,
  type RoundingMode,
  abs,
  fromInteger,
  isDecimal,
  lt,
  parts,
  setScale,
  shift,
  sign,
  stripTrailingZeros,
} from './decimal'

export const MINUS = '−'
export const MISSING = '--'

export type Maybe<T> = T | null | undefined

export function groupThousands(int: string): string {
  return int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export interface FormatDecimalOptions {
  scale: number
  /** Prefix positives with "+" (negatives always get "−"; zero never gets a sign). */
  signed?: boolean
  rounding?: RoundingMode
  group?: boolean
}

export function formatDecimal(value: Maybe<Dec>, options: FormatDecimalOptions): string {
  if (value == null || !isDecimal(value)) return MISSING
  const { scale, signed = false, rounding = 'HALF_UP', group = true } = options
  const rounded = setScale(value, scale, rounding)
  const { negative, int, frac } = parts(rounded)
  const body = (group ? groupThousands(int) : int) + (frac ? `.${frac}` : '')
  if (sign(rounded) === 0) return body
  if (negative) return MINUS + body
  return signed ? `+${body}` : body
}

/** Display scale for a price: 4 decimals below $1, otherwise 2 (§22.7). */
export function priceScale(reference: Maybe<Dec>): 2 | 4 {
  return reference != null && isDecimal(reference) && lt(abs(reference), '1') ? 4 : 2
}

export function formatPrice(value: Maybe<Dec>): string {
  return formatDecimal(value, { scale: priceScale(value) })
}

/** Signed price change; the scale follows the instrument's price (`reference`), not the change itself. */
export function formatChange(change: Maybe<Dec>, reference?: Maybe<Dec>): string {
  return formatDecimal(change, { scale: priceScale(reference ?? null), signed: true })
}

/** A fraction rendered as a percentage: "0.0123" → "+1.23%". */
export function formatPct(fraction: Maybe<Dec>, options: { signed?: boolean; scale?: number } = {}): string {
  if (fraction == null || !isDecimal(fraction)) return MISSING
  const { signed = true, scale = 2 } = options
  return `${formatDecimal(shift(fraction, 2), { scale, signed })}%`
}

/** Quantities keep their significant decimals (≤ 5): "10.00000" → "10", "0.95274" → "0.95274". */
export function formatQty(qty: Maybe<Dec>): string {
  if (qty == null || !isDecimal(qty)) return MISSING
  const { negative, int, frac } = parts(stripTrailingZeros(qty))
  return (negative ? MINUS : '') + groupThousands(int) + (frac ? `.${frac}` : '')
}

export function formatInteger(n: Maybe<number>): string {
  if (n == null || !Number.isFinite(n)) return MISSING
  return formatDecimal(fromInteger(Math.trunc(n)), { scale: 0 })
}

const SUFFIXES: ReadonlyArray<readonly [string, number]> = [
  ['T', 12],
  ['B', 9],
  ['M', 6],
  ['K', 3],
]

/**
 * Abbreviate to 3 significant digits with K/M/B/T: 41_234_567 → "41.2M", "3420000000000" → "3.42T".
 * Values below 1,000 are shown in full. Accepts Dec strings (market cap) or integer counts (volume).
 */
export function abbreviate(value: Maybe<Dec | number>, significant = 3): string {
  if (value == null) return MISSING
  let dec: Dec
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return MISSING
    dec = fromInteger(Math.trunc(value))
  } else if (isDecimal(value)) {
    dec = value
  } else {
    return MISSING
  }
  const magnitude = abs(dec)
  const intDigits = parts(magnitude).int.replace(/^0+/, '').length
  const prefix = sign(dec) < 0 ? MINUS : ''
  for (let i = 0; i < SUFFIXES.length; i++) {
    const [suffix, exp] = SUFFIXES[i]!
    if (intDigits <= exp) continue
    const decimalsNeeded = Math.max(0, significant - (intDigits - exp))
    const rounded = setScale(shift(magnitude, -exp), decimalsNeeded, 'HALF_UP')
    // 999.96M rounds to 1000M: promote to the next suffix ("1B").
    if (parts(rounded).int.length > 3 && i > 0) {
      const [biggerSuffix, biggerExp] = SUFFIXES[i - 1]!
      const promoted = setScale(shift(magnitude, -biggerExp), significant - 1, 'HALF_UP')
      return prefix + stripTrailingZeros(promoted) + biggerSuffix
    }
    return prefix + stripTrailingZeros(rounded) + suffix
  }
  return prefix + formatDecimal(magnitude, { scale: parts(stripTrailingZeros(magnitude)).frac ? 2 : 0 })
}

export type Direction = 'up' | 'down' | 'flat'

export function direction(value: Maybe<Dec>): Direction {
  if (value == null || !isDecimal(value)) return 'flat'
  const s = sign(value)
  return s > 0 ? 'up' : s < 0 ? 'down' : 'flat'
}

/**
 * The direction of a value **as displayed** at `scale` decimals (§22.7): a value that rounds to zero is flat, so a
 * "0.00%" is never red or green and its color always matches its sign.
 */
export function directionAtScale(value: Maybe<Dec>, scale: number): Direction {
  if (value == null || !isDecimal(value)) return 'flat'
  return direction(setScale(value, scale, 'HALF_UP'))
}

/** Direction of a fraction shown by {@link formatPct} (2 decimals of a percent = 4 of the fraction). */
export const pctDirection = (fraction: Maybe<Dec>): Direction => directionAtScale(fraction, 4)

/** Direction of an amount shown by `formatMoney` (cents). */
export const moneyDirection = (value: Maybe<Dec>): Direction => directionAtScale(value, 2)

/** Direction of a price change shown by {@link formatChange} at the instrument's price scale. */
export const changeDirection = (change: Maybe<Dec>, reference?: Maybe<Dec>): Direction =>
  directionAtScale(change, priceScale(reference ?? null))

/** ▲ / ▼ for a direction (§22.7: color is never the only signal). */
export function arrowFor(d: Direction): '▲' | '▼' | '' {
  return d === 'up' ? '▲' : d === 'down' ? '▼' : ''
}

/** Color is never the only signal (§22.7): pair every colored value with ▲/▼. */
export function arrow(value: Maybe<Dec>): '▲' | '▼' | '' {
  return arrowFor(direction(value))
}
