/**
 * Optional leaderboard columns (§16.8, §22.4 Phase 2): Sharpe, profit factor, win rate and max drawdown, chosen in a
 * "Columns" popover on tablet and desktop and remembered per browser; phones pick one extra stat for the card's
 * second line. Ranks never change (RETURN or P&L).
 */
import { useState } from 'react'

export type ExtraColumn = 'SHARPE' | 'PROFIT_FACTOR' | 'WIN_RATE' | 'MAX_DRAWDOWN'

export const EXTRA_COLUMNS: ReadonlyArray<{ value: ExtraColumn; label: string; short: string }> = [
  { value: 'SHARPE', label: 'Sharpe ratio', short: 'Sharpe' },
  { value: 'PROFIT_FACTOR', label: 'Profit factor', short: 'PF' },
  { value: 'WIN_RATE', label: 'Win rate', short: 'Win' },
  { value: 'MAX_DRAWDOWN', label: 'Max drawdown', short: 'Max DD' },
]

const KEY = 'stocks.league.columns'
const PHONE_KEY = 'stocks.league.phoneStat'

const valid = (v: unknown): v is ExtraColumn => EXTRA_COLUMNS.some((c) => c.value === v)

/** The remembered columns (tablet/desktop), in the canonical order; none by default. */
export function loadColumns(): ExtraColumn[] {
  try {
    const raw = localStorage.getItem(KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    const set = new Set(Array.isArray(parsed) ? parsed.filter(valid) : [])
    return EXTRA_COLUMNS.map((c) => c.value).filter((v) => set.has(v))
  } catch {
    return []
  }
}

export function saveColumns(columns: ReadonlyArray<ExtraColumn>): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(columns))
  } catch {
    /* storage unavailable: the choice lasts for this page only */
  }
}

/** Adds or removes one column, keeping the canonical order. */
export function toggleColumn(columns: ReadonlyArray<ExtraColumn>, column: ExtraColumn): ExtraColumn[] {
  const set = new Set(columns)
  if (set.has(column)) set.delete(column)
  else set.add(column)
  return EXTRA_COLUMNS.map((c) => c.value).filter((v) => set.has(v))
}

export function loadPhoneStat(): ExtraColumn | null {
  try {
    const v = localStorage.getItem(PHONE_KEY)
    return valid(v) ? v : null
  } catch {
    return null
  }
}

export function savePhoneStat(column: ExtraColumn | null): void {
  try {
    if (column) localStorage.setItem(PHONE_KEY, column)
    else localStorage.removeItem(PHONE_KEY)
  } catch {
    /* ignore */
  }
}

/** The board's optional columns and the phone's extra stat, remembered per browser. */
export function useBoardColumns(): {
  columns: ExtraColumn[]
  toggle: (c: ExtraColumn) => void
  phoneStat: ExtraColumn | null
  setPhoneStat: (c: ExtraColumn | null) => void
} {
  const [columns, setColumns] = useState<ExtraColumn[]>(loadColumns)
  const [phoneStat, setPhone] = useState<ExtraColumn | null>(loadPhoneStat)
  return {
    columns,
    toggle: (c) =>
      setColumns((prev) => {
        const next = toggleColumn(prev, c)
        saveColumns(next)
        return next
      }),
    phoneStat,
    setPhoneStat: (c) => {
      savePhoneStat(c)
      setPhone(c)
    },
  }
}
