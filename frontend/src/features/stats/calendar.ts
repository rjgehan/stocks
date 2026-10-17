/**
 * P&L calendar helpers (§16.10, §22.4 Phase 2): the Mon–Fri month grid (Mon–Sun once weekends are P&L days, crypto),
 * the 12-month year grid, month navigation and the §22.8 heatmap ramp (`--down-strong → --bg-3 → --up-strong`, clamped at ±3% of the cell's return). Colors
 * are CSS `color-mix` strings; the intensity uses `Number` for display only.
 */
import type { PnlCalendarCell } from '@/lib/api/types'
import { isDecimal, sign } from '@/lib/format/decimal'

type Slot = { date: string; cell: PnlCalendarCell | null } | null

export const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'] as const
export const WEEK_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const

const dayOfWeek = (date: string): number => new Date(`${date}T00:00:00Z`).getUTCDay() // 0 Sun … 6 Sat

/** Whether any cell falls on a Saturday or Sunday (crypto makes every calendar day a P&L day). */
export function hasWeekendCells(cells: ReadonlyArray<PnlCalendarCell>): boolean {
  return cells.some((c) => {
    if (!c.date || !/^\d{4}-\d{2}-\d{2}$/.test(c.date)) return false
    const dow = dayOfWeek(c.date)
    return dow === 0 || dow === 6
  })
}

/**
 * "2026-10" → its days as weeks (null slots before the 1st and after the last day): Mon–Fri, or Mon–Sun when
 * `weekends` (by default: when any cell falls on a weekend, as while crypto trades 24/7).
 */
export function monthGrid(month: string, cells: ReadonlyArray<PnlCalendarCell>, weekends: boolean = hasWeekendCells(cells)): Slot[][] {
  const [y, m] = month.split('-').map(Number) as [number, number]
  const byDate = new Map(cells.map((c) => [c.date ?? '', c]))
  const days = new Date(Date.UTC(y, m, 0)).getUTCDate()
  const width = weekends ? 7 : 5
  const weeks: Slot[][] = []
  let week: Slot[] = []
  for (let d = 1; d <= days; d++) {
    const date = `${month}-${String(d).padStart(2, '0')}`
    const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay()
    if (!weekends && (dow === 0 || dow === 6)) continue
    const col = (dow + 6) % 7 // Mon 0 … Sun 6
    if (week.length === 0) for (let i = 0; i < col; i++) week.push(null)
    week.push({ date, cell: byDate.get(date) ?? null })
    if (col === width - 1) {
      weeks.push(week)
      week = []
    }
  }
  if (week.length) {
    while (week.length < width) week.push(null)
    weeks.push(week)
  }
  return weeks
}

/** "2026-10" ± n months. */
export function shiftMonth(month: string, n: number): string {
  const [y, m] = month.split('-').map(Number) as [number, number]
  const total = y * 12 + (m - 1) + n
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`
}

/** The current ET month, "YYYY-MM". */
export function etMonth(nowMs: number = Date.now()): string {
  return new Date(nowMs).toLocaleDateString('en-CA', { timeZone: 'America/New_York' }).slice(0, 7)
}

export const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const

/** "October 2026". */
export function monthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number) as [number, number]
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' })
}

/** Heat clamp (§22.8): ±3% is full color. */
export const HEAT_CLAMP = 0.03

/**
 * The cell background: the sign comes from the P&L, the intensity from |return| / 3% (at least a tint for any
 * non-zero P&L), so a deposit (never P&L) never colors a day.
 */
export function heatColor(pnl: string | null | undefined, ret: string | null | undefined): string {
  if (pnl == null || !isDecimal(pnl) || sign(pnl) === 0) return 'var(--bg-2)'
  const r = ret != null && isDecimal(ret) ? Math.abs(Number(ret)) : 0
  const intensity = Math.min(1, r / HEAT_CLAMP)
  const pct = Math.round(18 + intensity * 72)                     // 18%..90% of the strong color
  return `color-mix(in srgb, var(${sign(pnl) > 0 ? '--up-strong' : '--down-strong'}) ${pct}%, var(--bg-3))`
}

/** Whether the cell's text should be light (strong backgrounds) for contrast. */
export function heatIsStrong(ret: string | null | undefined): boolean {
  return ret != null && isDecimal(ret) && Math.abs(Number(ret)) / HEAT_CLAMP >= 0.6
}
