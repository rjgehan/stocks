/**
 * Desktop watchlist columns (§22.10), pure: the column catalog, the stored choice (localStorage, validated on read),
 * the sort cycle and the sort itself (display numbers only; nulls last in both directions, ties keep list order).
 */
import type { Asset, Quote } from '@/lib/api/types'
import { readLocal, writeLocal } from '@/lib/prefs/local'

export type ColumnId = 'last' | 'change' | 'changePct' | 'volume' | 'marketCap' | 'bidAsk' | 'dayRange'
export type SortDir = 'asc' | 'desc'
export interface WatchlistSort {
  col: ColumnId | 'symbol'
  dir: SortDir
}

export const COLUMNS: readonly { id: ColumnId; label: string; header: string; width: number }[] = [
  { id: 'last', label: 'Last price', header: 'Last', width: 72 },
  { id: 'change', label: 'Change ($)', header: 'Chg', width: 64 },
  { id: 'changePct', label: 'Change (%)', header: 'Chg %', width: 76 },
  { id: 'volume', label: 'Volume', header: 'Vol', width: 60 },
  { id: 'marketCap', label: 'Market cap', header: 'Mkt cap', width: 64 },
  { id: 'bidAsk', label: 'Bid / Ask', header: 'Bid / Ask', width: 116 },
  { id: 'dayRange', label: 'Day range', header: 'Day range', width: 116 },
]

export const DEFAULT_COLUMNS: readonly ColumnId[] = ['last', 'changePct']
const IDS = COLUMNS.map((c) => c.id)
export const COLUMNS_KEY = 'stocks.watchlist.columns'
export const SORT_KEY = 'stocks.watchlist.sort'
export const WIDTH_KEY = 'stocks.watchlist.width'

/** The chosen columns in catalog order; the default when nothing valid is stored. */
export function readColumns(): ColumnId[] {
  const raw = readLocal(COLUMNS_KEY, '')
  const chosen = raw.split(',').filter((c): c is ColumnId => (IDS as string[]).includes(c))
  return chosen.length > 0 ? IDS.filter((id) => chosen.includes(id)) : [...DEFAULT_COLUMNS]
}

export function writeColumns(cols: readonly ColumnId[]): void {
  writeLocal(COLUMNS_KEY, IDS.filter((id) => cols.includes(id)).join(','))
}

export function readSort(): WatchlistSort | null {
  const [col, dir] = readLocal(SORT_KEY, '').split(':')
  if ((col === 'symbol' || (IDS as string[]).includes(col ?? '')) && (dir === 'asc' || dir === 'desc')) return { col: col as WatchlistSort['col'], dir }
  return null
}

export function writeSort(sort: WatchlistSort | null): void {
  writeLocal(SORT_KEY, sort ? `${sort.col}:${sort.dir}` : '')
}

/** Header click: descending first (the symbol ascending), then the other direction, then back to list order. */
export function cycleSort(current: WatchlistSort | null, col: WatchlistSort['col']): WatchlistSort | null {
  const first: SortDir = col === 'symbol' ? 'asc' : 'desc'
  if (!current || current.col !== col) return { col, dir: first }
  if (current.dir === first) return { col, dir: first === 'asc' ? 'desc' : 'asc' }
  return null
}

const num = (d: string | null | undefined): number | null => {
  if (d == null) return null
  const n = Number(d)
  return Number.isFinite(n) ? n : null
}

/** The sortable value of a column (display numbers; the bid/ask column sorts by the spread, the range by its width). */
export function columnValue(col: WatchlistSort['col'], symbol: string, quote: Quote | undefined, asset: Asset | undefined): number | string | null {
  switch (col) {
    case 'symbol':
      return symbol
    case 'last':
      return num(quote?.last)
    case 'change':
      return num(quote?.change)
    case 'changePct':
      return num(quote?.changePct)
    case 'volume':
      return quote?.volume ?? null
    case 'marketCap':
      return num(asset?.fundamentals?.marketCap)
    case 'bidAsk': {
      const bid = num(quote?.bid)
      const ask = num(quote?.ask)
      return bid != null && ask != null ? ask - bid : null
    }
    case 'dayRange': {
      const hi = num(quote?.dayHigh)
      const lo = num(quote?.dayLow)
      return hi != null && lo != null ? hi - lo : null
    }
  }
}

/** The symbols sorted by `valueOf` (nulls last in both directions; equal values keep the list order). */
export function sortSymbols(symbols: readonly string[], sort: WatchlistSort | null, valueOf: (symbol: string) => number | string | null): string[] {
  if (!sort) return [...symbols]
  const dir = sort.dir === 'asc' ? 1 : -1
  return symbols
    .map((s, i) => ({ s, i, v: valueOf(s) }))
    .sort((a, b) => {
      if (a.v == null || b.v == null) return a.v == null ? (b.v == null ? a.i - b.i : 1) : -1
      const c = a.v < b.v ? -1 : a.v > b.v ? 1 : 0
      return c !== 0 ? c * dir : a.i - b.i
    })
    .map((x) => x.s)
}

/** Panel width bounds (px) for a viewport: room is kept for the rail, the ticket column and a usable center. */
export function widthBounds(viewport: number, wide: boolean): { min: number; max: number; initial: number } {
  const min = 260
  const max = Math.max(min, Math.min(720, viewport - 56 - (wide ? 380 : 340) - 300))
  return { min, max, initial: wide ? 300 : 260 }
}

export const clampWidth = (w: number, b: { min: number; max: number }): number => Math.round(Math.min(b.max, Math.max(b.min, w)))
