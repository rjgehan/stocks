/**
 * Display rules for corporate actions (§14, §21.1 `CorporateAction`). Rates are `old_rate → new_rate` shares: a 2:1
 * forward split is old 1 / new 2, a 1-for-8 reverse split is old 8 / new 1. Nothing here divides the rates (§14 rule 1:
 * a ratio such as 1/3 does not terminate); labels are built from the rate strings themselves.
 */
import type { CorporateAction, CorporateActionStatus, CorporateActionType } from '@/lib/api/types'
import type { PillTone } from '@/components/ui/Pill'
import { type Dec, compare, isDecimal, sign, stripTrailingZeros } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'

export const CA_TYPE_LABEL: Record<CorporateActionType, string> = {
  FORWARD_SPLIT: 'Stock split',
  REVERSE_SPLIT: 'Reverse split',
  CASH_DIVIDEND: 'Cash dividend',
  NAME_CHANGE: 'Symbol change',
  CASH_MERGER: 'Cash merger',
  STOCK_MERGER: 'Stock merger',
  SPIN_OFF: 'Spin-off',
  DELISTING: 'Delisting',
}

export const CA_STATUS_LABEL: Record<CorporateActionStatus, string> = {
  PENDING: 'Upcoming',
  APPLIED: 'Applied',
  NEEDS_ADMIN: 'Needs you',
  SKIPPED: 'Skipped',
}

export const CA_STATUS_TONE: Record<CorporateActionStatus, PillTone> = {
  PENDING: 'accent',
  APPLIED: 'up',
  NEEDS_ADMIN: 'warn',
  SKIPPED: 'flat',
}

export const CA_ORIGIN_LABEL: Record<string, string> = {
  PROVIDER: 'Reported by the data provider',
  MANUAL: 'Entered by an admin',
  DETECTED: 'Detected from adjusted prices (split guard)',
}

const n = (d: Dec | null | undefined): string => (d && isDecimal(d) ? stripTrailingZeros(d) : '?')

/** Valid rates for a resolve: two positive decimals (at most 6 decimals each, as the server stores them). */
export function validRate(v: string): boolean {
  return /^\d{1,9}(\.\d{1,6})?$/.test(v.trim()) && sign(v.trim()) > 0
}

/**
 * "2-for-1" for a forward split (old 1 → new 2), "1-for-8" for a reverse split (old 8 → new 1), "3-for-2" (old 2 →
 * new 3): new-for-old, the way splits are announced.
 */
export function splitRatio(oldRate: Dec | null | undefined, newRate: Dec | null | undefined): string {
  return `${n(newRate)}-for-${n(oldRate)}`
}

/** Forward when the new share count is larger, reverse when smaller, null when equal or unknown. */
export function splitKind(oldRate: string, newRate: string): 'FORWARD_SPLIT' | 'REVERSE_SPLIT' | null {
  if (!validRate(oldRate) || !validRate(newRate)) return null
  const c = compare(newRate.trim(), oldRate.trim())
  return c > 0 ? 'FORWARD_SPLIT' : c < 0 ? 'REVERSE_SPLIT' : null
}

export const isSplit = (t: CorporateActionType | null | undefined): boolean => t === 'FORWARD_SPLIT' || t === 'REVERSE_SPLIT'

/** One-line summary: "2-for-1 split", "$0.51 per share", "SIMX → SIMA", "Cash merger at $1.25 per share". */
export function caSummary(c: Pick<CorporateAction, 'type' | 'oldRate' | 'newRate' | 'cashRate' | 'symbol' | 'newSymbol'>): string {
  switch (c.type) {
    case 'FORWARD_SPLIT':
    case 'REVERSE_SPLIT':
      return `${splitRatio(c.oldRate, c.newRate)} ${c.type === 'REVERSE_SPLIT' ? 'reverse split' : 'split'}`
    case 'CASH_DIVIDEND':
      return `${formatMoney(c.cashRate, { scale: cashScale(c.cashRate) })} per share`
    case 'NAME_CHANGE':
      return `${c.symbol ?? '?'} → ${c.newSymbol ?? '?'}`
    case 'CASH_MERGER':
      return c.cashRate ? `Cash merger at ${formatMoney(c.cashRate, { scale: cashScale(c.cashRate) })} per share` : 'Cash merger'
    default:
      return c.type ? CA_TYPE_LABEL[c.type] : 'Corporate action'
  }
}

/** Dividend rates often have 3–4 decimals ($0.0125); show them all, at least cents. */
function cashScale(rate: Dec | null | undefined): number {
  if (!rate || !isDecimal(rate)) return 2
  const frac = stripTrailingZeros(rate).split('.')[1] ?? ''
  return Math.min(6, Math.max(2, frac.length))
}

/** What applying a split with these rates does to one holding, in words (the resolve dialog). */
export function splitEffect(symbol: string, oldRate: string, newRate: string): string | null {
  const kind = splitKind(oldRate, newRate)
  if (!kind) return null
  const o = stripTrailingZeros(oldRate.trim())
  const nw = stripTrailingZeros(newRate.trim())
  return `Every ${o} ${symbol} share${o === '1' ? '' : 's'} become${o === '1' ? 's' : ''} ${nw} (${nw}-for-${o} ${kind === 'REVERSE_SPLIT' ? 'reverse split' : 'split'}). Cost basis is unchanged; fractions of a whole-share symbol are paid as cash in lieu.`
}
