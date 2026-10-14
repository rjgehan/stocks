/** Display helpers for manual-bot sessions (§20.8): issue lines, result chips, row sizes and the place button. */
import type { ManualAction, ManualActionResult, ManualIssue, ManualResultStatus, ManualSession, ManualSessionStatus } from '@/lib/api/types'
import type { PillTone } from '@/components/ui/Pill'
import { ORDER_TYPE_LABEL, STATUS_LABEL, orderPriceLabel } from '@/features/account/orderLabels'
import { formatMoney } from '@/lib/format/money'
import { formatQty } from '@/lib/format/number'

export const SESSION_STATUS_LABEL: Record<ManualSessionStatus, string> = { PROMPTED: 'Prompted', REPLIED: 'Replied', EXECUTED: 'Executed' }
export const SESSION_STATUS_TONE: Record<ManualSessionStatus, PillTone> = { PROMPTED: 'neutral', REPLIED: 'accent', EXECUTED: 'up' }

const RESULT_TONE: Record<ManualResultStatus, PillTone> = { PLACED: 'up', REPLAYED: 'up', CANCELLED: 'flat', CANCEL_REQUESTED: 'warn', FAILED: 'down' }

/** A row the server will not place again (§20.8 execute step 4): its stored result is final. */
export const isDone = (a: Pick<ManualAction, 'result'>): boolean => {
  const s = a.result?.status
  return s === 'PLACED' || s === 'REPLAYED' || s === 'CANCELLED' || s === 'CANCEL_REQUESTED'
}

/** A row the owner can tick: executable and not placed yet (a FAILED row can be retried). */
export const isTickable = (a: Pick<ManualAction, 'executable' | 'result'>): boolean => a.executable && !isDone(a)

/** Errors first, then warnings, each group in the server's order. */
export function sortIssues(issues: ManualIssue[] | null | undefined): ManualIssue[] {
  const list = issues ?? []
  return [...list.filter((i) => i.severity === 'ERROR'), ...list.filter((i) => i.severity !== 'ERROR')]
}

export const hasError = (issues: ManualIssue[] | null | undefined): boolean => (issues ?? []).some((i) => i.severity === 'ERROR')

/** "Line 14, col 7 · JSON_SYNTAX · message" (the location only when the issue has one). */
export function issueLine(i: ManualIssue): string {
  const where = i.line != null ? `Line ${i.line}${i.column != null ? `, col ${i.column}` : ''}` : null
  return [where, i.code, i.message].filter((p): p is string => Boolean(p)).join(' · ')
}

/** "PLACED · Working", "REPLAYED", "CANCELLED", "CANCEL REQUESTED", "FAILED · INSUFFICIENT_BUYING_POWER: …". */
export function resultLabel(r: ManualActionResult): string {
  switch (r.status) {
    case 'PLACED':
      return r.orderStatus ? `PLACED · ${STATUS_LABEL[r.orderStatus]}` : 'PLACED'
    case 'CANCEL_REQUESTED':
      return 'CANCEL REQUESTED'
    case 'FAILED':
      return `FAILED · ${[r.code, r.message].filter(Boolean).join(': ')}`
    default:
      return r.status ?? ''
  }
}

export const resultTone = (r: ManualActionResult): PillTone => (r.status ? RESULT_TONE[r.status] : 'neutral')

/** "10 sh" or "$250.00"; a CANCEL has no size. */
export function rowSize(a: Pick<ManualAction, 'action' | 'qty' | 'notional'>): string {
  if (a.action === 'CANCEL') return ''
  if (a.notional) return formatMoney(a.notional)
  return a.qty ? `${formatQty(a.qty)} sh` : ''
}

type RowOrder = Pick<ManualAction, 'action' | 'type' | 'limitPrice' | 'stopPrice' | 'trailAmount' | 'trailPercent'>

/** "412.50" (Limit), "230.00 / 231.00" (Stop Limit: stop / limit), "5% trail": the row's prices, as on the Orders page. */
export const rowPrices = (a: RowOrder): string => (a.action === 'CANCEL' ? '' : orderPriceLabel(a))

export const rowType = (a: Pick<ManualAction, 'action' | 'type'>): string => (a.action === 'CANCEL' ? '' : a.type ? ORDER_TYPE_LABEL[a.type] : '')

/** "Limit 412.50", "Market", "Trailing Stop 5% trail": the type with its prices. */
export const rowOrder = (a: RowOrder): string => [rowType(a), rowPrices(a)].filter(Boolean).join(' ')

/** "DAY", "GTC · Ext": time in force plus the extended-hours flag. */
export const rowTif = (a: Pick<ManualAction, 'action' | 'timeInForce' | 'extendedHours'>): string =>
  a.action === 'CANCEL' ? '' : `${a.timeInForce ?? 'DAY'}${a.extendedHours ? ' · Ext' : ''}`

/** Rows placed (or replayed) by the session, for "n placed". */
export const placedCount = (s: Pick<ManualSession, 'actions'>): number => (s.actions ?? []).filter((a) => a.result?.status === 'PLACED' || a.result?.status === 'REPLAYED').length

/** The journal outcome of an executed session: posted, skipped (with the server's message) or nothing yet. */
export function journalLine(s: Pick<ManualSession, 'journalNoteId' | 'issues' | 'executedAt'>): string | null {
  if (s.journalNoteId) return 'Summary posted to the journal'
  const skipped = (s.issues ?? []).find((i) => i.code === 'JOURNAL_SKIPPED')
  if (skipped?.message) return skipped.message
  return null
}

/**
 * The place button (§20.8): "Place N orders as @bot" ("Place remaining" once the session placed orders), "Post summary
 * only" when nothing is ticked but a summary waits for the journal, otherwise disabled.
 */
export function placeButton(ticked: number, s: Pick<ManualSession, 'executedAt' | 'journalNoteId' | 'untrusted'>, handle: string): { label: string; enabled: boolean } {
  if (ticked > 0) return { label: s.executedAt ? 'Place remaining' : `Place ${ticked} order${ticked === 1 ? '' : 's'} as @${handle}`, enabled: true }
  if (s.untrusted?.summary && !s.journalNoteId) return { label: 'Post summary only', enabled: true }
  return { label: `Place orders as @${handle}`, enabled: false }
}
