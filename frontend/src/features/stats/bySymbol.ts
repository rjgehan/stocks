/**
 * P&L by symbol (§16.10): bar geometry for the diverging chart. Widths are a share of the largest |total| (display
 * only); the "Other" row (P&L corrections) is shown only when it is not zero.
 */
import type { SymbolPnl } from '@/lib/api/types'
import { abs, compare, isDecimal, isZero } from '@/lib/format/decimal'

export interface SymbolBar {
  symbol: string
  total: string
  /** 0..1 of the half width */
  width: number
  positive: boolean
  row: SymbolPnl | null
}

export function symbolBars(rows: ReadonlyArray<SymbolPnl> | null | undefined, other: string | null | undefined): SymbolBar[] {
  const list: Array<{ symbol: string; total: string; row: SymbolPnl | null }> = (rows ?? [])
    .filter((r) => r.symbol && r.total != null && isDecimal(r.total))
    .map((r) => ({ symbol: r.symbol!, total: r.total!, row: r }))
  if (other != null && isDecimal(other) && !isZero(other)) list.push({ symbol: 'Other', total: other, row: null })
  let max = '0'
  for (const r of list) if (compare(abs(r.total), max) > 0) max = abs(r.total)
  const m = Number(max)
  return list.map((r) => ({
    symbol: r.symbol,
    total: r.total,
    width: m > 0 ? Math.max(0.02, Math.abs(Number(r.total)) / m) : 0,
    positive: !r.total.startsWith('-'),
    row: r.row,
  }))
}
