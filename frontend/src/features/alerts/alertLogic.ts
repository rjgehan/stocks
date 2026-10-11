/**
 * Price-alert form logic (§19.1, §22.10), pure and unit-tested. Thresholds travel as decimal strings: a price for
 * PRICE_*, a FRACTION for CHANGE_PCT_* (like `Quote.changePct`), while the form shows a change in PERCENT. The
 * conversions are exact (`shift`), never through `number`.
 */
import { type Dec, compare, div, gte, isDecimal, lte, mul, parseUserDecimal, roundToTick, shift, sign, stripTrailingZeros, sub } from '@/lib/format/decimal'
import { formatPct, formatPrice } from '@/lib/format/number'
import type { Alert, AlertCondition, Quote } from '@/lib/api/types'

export const CONDITIONS: ReadonlyArray<{ value: AlertCondition; label: string; short: string }> = [
  { value: 'PRICE_GTE', label: 'Price at or above', short: 'Price ≥' },
  { value: 'PRICE_LTE', label: 'Price at or below', short: 'Price ≤' },
  { value: 'CHANGE_PCT_GTE', label: 'Day change at or above', short: 'Day change ≥' },
  { value: 'CHANGE_PCT_LTE', label: 'Day change at or below', short: 'Day change ≤' },
]

export const isPrice = (c: AlertCondition): boolean => c === 'PRICE_GTE' || c === 'PRICE_LTE'
export const isAbove = (c: AlertCondition): boolean => c === 'PRICE_GTE' || c === 'CHANGE_PCT_GTE'

/** The limits of §19.1 (the server checks them too). */
export const MAX_PRICE = '1000000'
export const NOTE_MAX = 140

/** The form's text → the wire threshold (a price, or a fraction from a percent); null when not a number. */
export function toWireThreshold(condition: AlertCondition, input: string): Dec | null {
  const d = parseUserDecimal(input.replace('%', ''))
  if (d === null) return null
  return isPrice(condition) ? stripTrailingZeros(d) : stripTrailingZeros(shift(d, -2))
}

/** The wire threshold → the form's text: "230.00", or a percent "5" from "0.050000". */
export function fromWireThreshold(condition: AlertCondition, threshold: Dec | null | undefined): string {
  if (threshold == null || !isDecimal(threshold)) return ''
  if (isPrice(condition)) {
    const s = stripTrailingZeros(threshold)
    return s.includes('.') && s.split('.')[1]!.length >= 2 ? s : roundToTick(s)
  }
  return stripTrailingZeros(shift(threshold, 2))
}

const decimalsOf = (d: Dec): number => (stripTrailingZeros(d).split('.')[1] ?? '').length

/** Why the form's threshold is not acceptable, or null. */
export function thresholdError(condition: AlertCondition, input: string): string | null {
  if (input.trim() === '') return isPrice(condition) ? 'Enter a price.' : 'Enter a percentage.'
  const wire = toWireThreshold(condition, input)
  if (wire === null) return 'Enter a number.'
  if (isPrice(condition)) {
    if (sign(wire) <= 0) return 'The price must be above $0.'
    if (gte(wire, MAX_PRICE)) return 'The price must be below $1,000,000.'
    if (decimalsOf(wire) > 4) return 'Use at most 4 decimals.'
    return null
  }
  if (lte(wire, '-1')) return 'A stock cannot fall 100% or more.'
  if (compare(wire, '10') > 0) return 'At most +1000%.'
  if (decimalsOf(wire) > 6) return 'Use at most 4 decimals of a percent.'
  return null
}

/** What the alert compares against right now (display only; the server decides on live data). */
export interface LiveView {
  price: Dec | null
  changePct: Dec | null
}

/**
 * The live line of a quote: in pre-market and after-hours the extended line (its change measured from the
 * previous regular close), otherwise the last price and the day's change.
 */
export function liveView(quote: Quote | null | undefined): LiveView {
  if (!quote) return { price: null, changePct: null }
  const ext = quote.extended
  if (ext?.price && (ext.session === 'PRE' || ext.session === 'POST')) {
    if (ext.session === 'PRE') return { price: ext.price, changePct: ext.changePct ?? null }
    const prev = quote.prevClose
    const pct = prev && isDecimal(prev) && sign(prev) > 0 ? div(sub(ext.price, prev), prev, 10, 'HALF_EVEN') : null
    return { price: ext.price, changePct: pct }
  }
  return { price: quote.last ?? null, changePct: quote.changePct ?? null }
}

/**
 * A sensible starting threshold that does not fire at once: 5% above or below the live price (on tick) for price
 * alerts, ±5% for change alerts.
 */
export function defaultInput(condition: AlertCondition, live: LiveView): string {
  if (!isPrice(condition)) return isAbove(condition) ? '5' : '-5'
  if (!live.price || !isDecimal(live.price)) return ''
  return roundToTick(mul(live.price, isAbove(condition) ? '1.05' : '0.95'), isAbove(condition) ? 'UP' : 'DOWN')
}

/** True when the condition already holds on the live line: the alert would fire on the next live update. */
export function wouldFireNow(condition: AlertCondition, wire: Dec | null, live: LiveView): boolean {
  if (wire === null) return false
  const value = isPrice(condition) ? live.price : live.changePct
  if (value == null || !isDecimal(value)) return false
  return isAbove(condition) ? gte(value, wire) : lte(value, wire)
}

/** "Price ≥ $230.00", "Day change ≤ −3.00%". */
export function describeCondition(condition: AlertCondition | null | undefined, threshold: Dec | null | undefined): string {
  const c = CONDITIONS.find((x) => x.value === condition)
  if (!c) return 'Alert'
  return `${c.short} ${isPrice(c.value) ? `$${formatPrice(threshold)}` : formatPct(threshold)}`
}

export type ExpiryPreset = 'NEVER' | 'TODAY' | 'WEEK' | 'MONTH'

export const EXPIRY_PRESETS: ReadonlyArray<{ value: ExpiryPreset; label: string }> = [
  { value: 'NEVER', label: 'Never' },
  { value: 'TODAY', label: 'End of today' },
  { value: 'WEEK', label: '1 week' },
  { value: 'MONTH', label: '1 month' },
]

const etParts = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York',
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  timeZoneName: 'shortOffset',
})

/** The ET offset in minutes at `d` ("GMT-4" → −240). */
function etOffsetMinutes(d: Date): number {
  const name = etParts.formatToParts(d).find((p) => p.type === 'timeZoneName')?.value ?? 'GMT-5'
  const m = /GMT([+-])(\d{1,2})(?::(\d{2}))?/.exec(name)
  if (!m) return 0
  const minutes = Number(m[2]) * 60 + Number(m[3] ?? 0)
  return m[1] === '-' ? -minutes : minutes
}

/** 23:59:59 ET on the ET calendar day of `now` (DST-correct). */
export function endOfDayEt(now: Date): Date {
  const parts = etParts.formatToParts(now)
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value)
  const localMidnightUtc = Date.UTC(get('year'), get('month') - 1, get('day'), 23, 59, 59)
  // the offset at that wall time (not at `now`): correct across a DST change during the day
  const guess = new Date(localMidnightUtc - etOffsetMinutes(now) * 60_000)
  return new Date(localMidnightUtc - etOffsetMinutes(guess) * 60_000)
}

/** The `expiresAt` of a preset, as an ISO instant (null = never). */
export function expiryFor(preset: ExpiryPreset, now: Date): string | null {
  switch (preset) {
    case 'NEVER':
      return null
    case 'TODAY':
      return endOfDayEt(now).toISOString()
    case 'WEEK':
      return new Date(now.getTime() + 7 * 86_400_000).toISOString()
    case 'MONTH':
      return new Date(now.getTime() + 30 * 86_400_000).toISOString()
  }
}

/** Status order of the Alerts list (§18.2): ACTIVE, PAUSED, TRIGGERED, EXPIRED. */
const STATUS_ORDER: Record<string, number> = { ACTIVE: 0, PAUSED: 1, TRIGGERED: 2, EXPIRED: 3 }

export function sortAlerts(list: readonly Alert[]): Alert[] {
  return [...list].sort(
    (a, b) =>
      (STATUS_ORDER[a.status ?? ''] ?? 9) - (STATUS_ORDER[b.status ?? ''] ?? 9) || (b.createdAt ?? '').localeCompare(a.createdAt ?? ''),
  )
}

/** A short status line: "Active", "Active · waiting to re-arm", "Fired 2× · last $230.15", "Paused", "Expired". */
export function statusLine(a: Alert): string {
  switch (a.status) {
    case 'ACTIVE':
      if (a.repeat && !a.armed) return `Fired ${a.triggerCount}× · re-arms after the price moves back`
      return a.repeat && a.triggerCount > 0 ? `Watching again · fired ${a.triggerCount}×` : 'Watching the live price'
    case 'PAUSED':
      return 'Paused'
    case 'TRIGGERED':
      return a.lastTriggerPrice ? `Fired at $${formatPrice(a.lastTriggerPrice)}` : 'Fired'
    case 'EXPIRED':
      return 'Expired'
    default:
      return ''
  }
}
