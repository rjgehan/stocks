import type { CalendarView, PnlCalendar, PnlCalendarCell } from '@/lib/api/types'
import { isDecimal, isZero } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { formatPct, moneyDirection, pctDirection } from '@/lib/format/number'
import { IconButton } from '@/components/ui/Button'
import { Segmented } from '@/components/ui/Segmented'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { MONTH_NAMES, heatColor, heatIsStrong, monthGrid, monthLabel } from './calendar'

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'] as const

/** A day cell's tooltip: P&L, return, and any deposit or withdrawal (never part of the P&L). */
function cellTitle(date: string, c: PnlCalendarCell): string {
  const flows = c.flows != null && isDecimal(c.flows) && !isZero(c.flows) ? ` · ${formatMoney(c.flows, { signed: true })} deposited or withdrawn (not P&L)` : ''
  return `${date}: ${formatMoney(c.pnl, { signed: true })} (${formatPct(c.return)})${c.live ? ' · live' : ''}${flows}`
}

/** "+$12.40" compact in a cell: whole dollars from $1,000 ("+$1,240"). */
function cellMoney(pnl: string | null | undefined): string {
  if (pnl == null) return '--'
  const abs = pnl.replace('-', '')
  return formatMoney(pnl, { signed: true, scale: Number(abs.split('.')[0]) >= 1000 ? 0 : 2 })
}

/**
 * P&L calendar (§16.10, Webull "P&L Analysis"): Day view (a month of trading days, Mon–Fri, ◀ ▶ months) or Month
 * view (12 months of a year). Cells use the §22.8 heatmap ramp with the sign and value written in every cell; today
 * is marked live. A day's P&L never counts deposits or withdrawals, nor splits or renames.
 */
export function PnlCalendarCard({
  view,
  onView,
  month,
  year,
  onShift,
  calendar,
  isPending,
  isError,
}: {
  view: CalendarView
  onView: (v: CalendarView) => void
  month: string
  year: number
  onShift: (n: number) => void
  calendar: PnlCalendar | undefined
  isPending: boolean
  isError: boolean
}) {
  const cells = calendar?.cells ?? []
  const title = view === 'DAY' ? monthLabel(month) : String(year)
  return (
    <section aria-label="P&L calendar" className="rounded-lg border border-divider bg-bg-1 p-4">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="text-section font-semibold">P&amp;L calendar</h2>
        <div className="flex-1" />
        <Segmented label="Calendar view" size="sm" value={view} onChange={onView} options={[{ value: 'DAY', label: 'Day' }, { value: 'MONTH', label: 'Month' }]} />
      </div>
      <div className="mb-2 flex items-center justify-between">
        <IconButton label={view === 'DAY' ? 'Previous month' : 'Previous year'} onClick={() => onShift(-1)} size="touch">
          <span aria-hidden="true">◀</span>
        </IconButton>
        <span className="text-body font-semibold" aria-live="polite">
          {title}
        </span>
        <IconButton label={view === 'DAY' ? 'Next month' : 'Next year'} onClick={() => onShift(1)} size="touch">
          <span aria-hidden="true">▶</span>
        </IconButton>
      </div>
      {isError ? (
        <p className="py-6 text-center text-caption text-text-3">The calendar appears once your account is funded.</p>
      ) : isPending && !calendar ? (
        <Skeleton className="h-56 w-full" />
      ) : view === 'DAY' ? (
        <table className="w-full table-fixed border-separate border-spacing-1 text-center" aria-label={`Daily P&L, ${title}`}>
          <thead>
            <tr>
              {WEEKDAYS.map((d) => (
                <th key={d} scope="col" className="text-micro font-medium text-text-3">
                  {d}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {monthGrid(month, cells).map((week, wi) => (
              <tr key={wi}>
                {week.map((slot, di) =>
                  slot ? (
                    <td
                      key={slot.date}
                      className={cx('h-14 rounded-md align-top tablet:h-16', slot.cell?.live && 'ring-2 ring-accent ring-inset')}
                      style={{ background: slot.cell ? heatColor(slot.cell.pnl, slot.cell.return) : 'transparent' }}
                      title={slot.cell ? cellTitle(slot.date, slot.cell) : slot.date}
                    >
                      <div className={cx('flex h-full flex-col items-stretch justify-between p-1 text-left', slot.cell && heatIsStrong(slot.cell.return) ? 'text-white' : 'text-text-1')}>
                        <span className={cx('text-micro', slot.cell ? 'opacity-80' : 'text-text-3')}>
                          {Number(slot.date.slice(8))}
                          {slot.cell?.live ? <span className="ml-1 font-semibold">live</span> : null}
                        </span>
                        {slot.cell ? (
                          <span className="truncate text-right text-[11px] leading-tight font-semibold tabular-nums tablet:text-caption">{cellMoney(slot.cell.pnl)}</span>
                        ) : null}
                      </div>
                    </td>
                  ) : (
                    <td key={`e${wi}-${di}`} aria-label="No trading day" />
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <ul className="grid grid-cols-3 gap-1 tablet:grid-cols-4" aria-label={`Monthly P&L, ${title}`}>
          {MONTH_NAMES.map((name, i) => {
            const date = `${year}-${String(i + 1).padStart(2, '0')}-01`
            const c = cells.find((x) => x.date === date) ?? null
            return (
              <li
                key={name}
                className={cx('flex h-16 flex-col justify-between rounded-md p-1.5', c?.live && 'ring-2 ring-accent ring-inset', c && heatIsStrong(c.return) ? 'text-white' : 'text-text-1')}
                style={{ background: c ? heatColor(c.pnl, c.return) : 'var(--bg-2)' }}
                title={c ? `${name} ${year}: ${formatMoney(c.pnl, { signed: true })} (${formatPct(c.return)})` : `${name} ${year}`}
              >
                <span className={cx('text-micro', !c && 'text-text-3')}>{name}</span>
                {c ? (
                  <>
                    <span className="text-right text-caption font-semibold tabular-nums">{cellMoney(c.pnl)}</span>
                    <span className="text-right text-micro opacity-80">{formatPct(c.return)}</span>
                  </>
                ) : null}
              </li>
            )
          })}
        </ul>
      )}
      <div className="mt-3 flex items-center justify-between border-t border-divider pt-2 text-table">
        <span className="text-text-2">{view === 'DAY' ? 'Month total' : 'Year total'}</span>
        <span className="flex items-baseline gap-2">
          <b className={cx('tabular-nums', DIRECTION_TEXT[moneyDirection(calendar?.total?.pnl)])}>{formatMoney(calendar?.total?.pnl, { signed: true })}</b>
          <span className={cx('text-caption', DIRECTION_TEXT[pctDirection(calendar?.total?.return)])}>{formatPct(calendar?.total?.return)}</span>
        </span>
      </div>
      <p className="mt-1 text-micro text-text-3">Deposits and withdrawals never color a day; splits and renames are not P&amp;L.</p>
    </section>
  )
}
