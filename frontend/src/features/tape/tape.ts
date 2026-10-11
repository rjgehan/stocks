/**
 * Time & Sales logic (§22.10), pure: merging REST and STOMP batches by print id, and formatting a print. Prices are
 * shown as traded (sub-penny prints keep their digits, at least 2 decimals), never rounded to the tick.
 */
import { decimals, isDecimal, stripTrailingZeros } from '@/lib/format/decimal'
import { formatDecimal } from '@/lib/format/number'

export interface Print {
  id: string | null
  t: string | null
  price: string | null
  size: number | null
  side: 'AT_ASK' | 'AT_BID' | 'BETWEEN' | 'UNKNOWN' | null
  venue: string | null
}

export const MAX_PRINTS = 200

/** Newest first by trade time (stable for equal times: newer batches first), deduplicated by id, at most `max`. */
export function mergePrints(current: readonly Print[], incoming: readonly Print[], max = MAX_PRINTS): Print[] {
  const seen = new Set<string>()
  const all: Print[] = []
  for (const p of [...incoming, ...current]) {
    if (!p.id || !p.t || seen.has(p.id)) continue
    seen.add(p.id)
    all.push(p)
  }
  // a stable sort keeps the batch order (newest first) for prints at the same time
  all.sort((a, b) => Date.parse(b.t!) - Date.parse(a.t!))
  return all.length > max ? all.slice(0, max) : all
}

/** A print's price as traded: at least 2 decimals, up to 4 (sub-penny prints), never rounded below that. */
export function formatPrintPrice(price: string | null | undefined): string {
  if (price == null || !isDecimal(price)) return '--'
  const scale = Math.min(4, Math.max(2, decimals(stripTrailingZeros(price))))
  return formatDecimal(price, { scale })
}

const hms = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })

/** "14:31:07" in ET. */
export function formatPrintTime(t: string | null | undefined): string {
  if (!t) return '--'
  const d = new Date(t)
  return Number.isNaN(d.getTime()) ? '--' : hms.format(d)
}

export const SIDE_VIEW = {
  AT_ASK: { glyph: '▲', label: 'at the ask', tone: 'text-up' },
  AT_BID: { glyph: '▼', label: 'at the bid', tone: 'text-down' },
  BETWEEN: { glyph: '•', label: 'between the bid and the ask', tone: 'text-text-1' },
  UNKNOWN: { glyph: '', label: 'side unknown', tone: 'text-text-2' },
} as const

export function sideView(side: Print['side']) {
  return SIDE_VIEW[side ?? 'UNKNOWN']
}
