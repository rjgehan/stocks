/**
 * Exact string-decimal arithmetic for DISPLAY and ticket-input purposes only (ARCHITECTURE §22.1, §22.6).
 *
 * Money, prices and quantities arrive from the server as decimal strings ("227.4000") and are never parsed
 * into a JS `number` for arithmetic. These helpers work on BigInt-scaled integers, so "0.1" + "0.2" is
 * exactly "0.3". The server stays the source of truth: the client never computes NAV, P&L or buying power
 * (§19); this module formats, compares, rounds to tick and sizes ticket inputs.
 */

/** A decimal carried as a string, exactly as on the wire (§18.3 `Dec`). */
export type Dec = string

export type RoundingMode = 'HALF_UP' | 'HALF_EVEN' | 'FLOOR' | 'CEILING' | 'DOWN' | 'UP'

interface Parsed {
  /** value = unscaled / 10^scale */
  readonly unscaled: bigint
  readonly scale: number
}

const DECIMAL_RE = /^([+-]?)(\d*)(?:\.(\d*))?$/

/** True when `value` is a plain-notation decimal string ("12", "-0.5", ".25", "3."). */
export function isDecimal(value: unknown): value is Dec {
  if (typeof value !== 'string') return false
  const m = DECIMAL_RE.exec(value.trim())
  return m !== null && ((m[2] ?? '') !== '' || (m[3] ?? '') !== '')
}

function parse(value: Dec): Parsed {
  const m = DECIMAL_RE.exec(value.trim())
  const intPart = m?.[2] ?? ''
  const fracPart = m?.[3] ?? ''
  if (!m || (intPart === '' && fracPart === '')) throw new TypeError(`Not a decimal string: "${value}"`)
  const magnitude = BigInt((intPart || '0') + fracPart)
  return { unscaled: m[1] === '-' ? -magnitude : magnitude, scale: fracPart.length }
}

const pow10 = (n: number): bigint => 10n ** BigInt(n)

function render(p: Parsed): Dec {
  const negative = p.unscaled < 0n
  const digits = (negative ? -p.unscaled : p.unscaled).toString()
  if (p.scale === 0) return (negative && digits !== '0' ? '-' : '') + digits
  const padded = digits.padStart(p.scale + 1, '0')
  const out = `${padded.slice(0, -p.scale)}.${padded.slice(-p.scale)}`
  return negative ? `-${out}` : out
}

function align(a: Parsed, b: Parsed): [bigint, bigint, number] {
  const scale = Math.max(a.scale, b.scale)
  return [a.unscaled * pow10(scale - a.scale), b.unscaled * pow10(scale - b.scale), scale]
}

/** Integer division of num/den rounded with `mode` (den ≠ 0). */
function divRound(num: bigint, den: bigint, mode: RoundingMode): bigint {
  const q = num / den // truncates toward zero
  const r = num % den
  if (r === 0n) return q
  const negative = num < 0n !== den < 0n
  const away = negative ? q - 1n : q + 1n
  const twiceR = 2n * (r < 0n ? -r : r)
  const absDen = den < 0n ? -den : den
  switch (mode) {
    case 'DOWN':
      return q
    case 'UP':
      return away
    case 'FLOOR':
      return negative ? away : q
    case 'CEILING':
      return negative ? q : away
    case 'HALF_UP':
      return twiceR >= absDen ? away : q
    case 'HALF_EVEN':
      if (twiceR > absDen) return away
      if (twiceR < absDen) return q
      return q % 2n === 0n ? q : away
  }
}

export function compare(a: Dec, b: Dec): -1 | 0 | 1 {
  const [x, y] = align(parse(a), parse(b))
  return x < y ? -1 : x > y ? 1 : 0
}

export const eq = (a: Dec, b: Dec): boolean => compare(a, b) === 0
export const lt = (a: Dec, b: Dec): boolean => compare(a, b) < 0
export const gt = (a: Dec, b: Dec): boolean => compare(a, b) > 0
export const lte = (a: Dec, b: Dec): boolean => compare(a, b) <= 0
export const gte = (a: Dec, b: Dec): boolean => compare(a, b) >= 0

export function sign(a: Dec): -1 | 0 | 1 {
  const { unscaled } = parse(a)
  return unscaled < 0n ? -1 : unscaled > 0n ? 1 : 0
}

export const isZero = (a: Dec): boolean => sign(a) === 0

export function add(a: Dec, b: Dec): Dec {
  const [x, y, scale] = align(parse(a), parse(b))
  return render({ unscaled: x + y, scale })
}

export function sub(a: Dec, b: Dec): Dec {
  const [x, y, scale] = align(parse(a), parse(b))
  return render({ unscaled: x - y, scale })
}

export function mul(a: Dec, b: Dec): Dec {
  const x = parse(a)
  const y = parse(b)
  return render({ unscaled: x.unscaled * y.unscaled, scale: x.scale + y.scale })
}

/** a ÷ b at exactly `scale` decimals. Like Java's BigDecimal, every division names a scale and a mode. */
export function div(a: Dec, b: Dec, scale: number, mode: RoundingMode): Dec {
  const x = parse(a)
  const y = parse(b)
  if (y.unscaled === 0n) throw new RangeError('Division by zero')
  // a/b = (x.u / 10^x.s) / (y.u / 10^y.s); scaled by 10^scale.
  const num = x.unscaled * pow10(y.scale + scale)
  const den = y.unscaled * pow10(x.scale)
  return render({ unscaled: divRound(num, den, mode), scale })
}

export function negate(a: Dec): Dec {
  const p = parse(a)
  return render({ unscaled: -p.unscaled, scale: p.scale })
}

export function abs(a: Dec): Dec {
  const p = parse(a)
  return render({ unscaled: p.unscaled < 0n ? -p.unscaled : p.unscaled, scale: p.scale })
}

export const min = (a: Dec, b: Dec): Dec => (compare(a, b) <= 0 ? a : b)
export const max = (a: Dec, b: Dec): Dec => (compare(a, b) >= 0 ? a : b)

/** Multiply by 10^n (n may be negative): shift("0.0123", 2) === "1.23". Exact. */
export function shift(a: Dec, n: number): Dec {
  const p = parse(a)
  const scale = p.scale - n
  if (scale >= 0) return render({ unscaled: p.unscaled, scale })
  return render({ unscaled: p.unscaled * pow10(-scale), scale: 0 })
}

/** Round (or pad) to exactly `scale` decimals. */
export function setScale(a: Dec, scale: number, mode: RoundingMode = 'HALF_UP'): Dec {
  const p = parse(a)
  if (scale >= p.scale) return render({ unscaled: p.unscaled * pow10(scale - p.scale), scale })
  return render({ unscaled: divRound(p.unscaled, pow10(p.scale - scale), mode), scale })
}

/** Canonical form without insignificant zeros: "227.4000" → "227.4", "-0.0" → "0". */
export function stripTrailingZeros(a: Dec): Dec {
  let { unscaled, scale } = parse(a)
  while (scale > 0 && unscaled % 10n === 0n) {
    unscaled /= 10n
    scale -= 1
  }
  return render({ unscaled, scale })
}

/** Number of significant decimals actually present after stripping trailing zeros. */
export function decimals(a: Dec): number {
  return parse(stripTrailingZeros(a)).scale
}

/** Split into sign and absolute integer/fraction digit strings (used by the formatters). */
export function parts(a: Dec): { negative: boolean; int: string; frac: string } {
  const s = render(parse(a))
  const negative = s.startsWith('-')
  const body = negative ? s.slice(1) : s
  const [int = '0', frac = ''] = body.split('.')
  return { negative, int, frac }
}

/** Convert an exact integer `number` (share sizes, volumes, counts) to a Dec. */
export function fromInteger(n: number): Dec {
  if (!Number.isSafeInteger(n)) throw new RangeError(`Not a safe integer: ${n}`)
  return String(n)
}

/**
 * Reg NMS tick (§6.2): $0.01 at or above $1.00, $0.0001 below. `reference` is the price being quoted.
 */
export function tickFor(reference: Dec): Dec {
  return lt(abs(reference), '1') ? '0.0001' : '0.01'
}

/** Round a price to its tick (HALF_UP by default; TICK_DOWN/TICK_UP use DOWN/UP). */
export function roundToTick(price: Dec, mode: RoundingMode = 'HALF_UP'): Dec {
  return setScale(price, tickFor(price) === '0.01' ? 2 : 4, mode)
}

/**
 * Round a value to the tick of a reference price (§11.2 check 16): a trail amount is checked against the tick of the
 * price it trails ($0.01 at or above $1), not against its own size, so a $0.43 trail on a $42.56 stock has 2 decimals.
 */
export function roundToTickOf(value: Dec, reference: Dec, mode: RoundingMode = 'HALF_UP'): Dec {
  return setScale(value, tickFor(reference) === '0.01' ? 2 : 4, mode)
}

/** Normalise user-typed input ("1,234.5", " $12 ") into a Dec, or null if it is not a number. */
export function parseUserDecimal(input: string): Dec | null {
  const cleaned = input.replace(/[\s,$]/g, '').replace(/−/g, '-')
  if (!isDecimal(cleaned)) return null
  return render(parse(cleaned))
}
