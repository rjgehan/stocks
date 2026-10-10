import type { LeaderboardRow } from '@/lib/api/types'
import { formatPct, pctDirection } from '@/lib/format/number'
import { cx } from '@/components/ui/cx'
import { Popover } from '@/components/ui/Popover'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { EXTRA_COLUMNS, type ExtraColumn } from './columns'
import { formatRatio } from './format'

/** One optional stat of a leaderboard row (§16.8): Sharpe, profit factor, win rate or max drawdown; "--" when null. */
export function ExtraStatValue({ row, column }: { row: LeaderboardRow; column: ExtraColumn }) {
  switch (column) {
    case 'SHARPE':
      return <span className={cx('tabular-nums', row.sharpe ? DIRECTION_TEXT[pctDirection(row.sharpe)] : 'text-text-3')}>{formatRatio(row.sharpe)}</span>
    case 'PROFIT_FACTOR':
      return <span className={cx('tabular-nums', !row.profitFactor && 'text-text-3')}>{formatRatio(row.profitFactor)}</span>
    case 'WIN_RATE':
      return <span className={cx('tabular-nums', !row.winRate && 'text-text-3')}>{formatPct(row.winRate, { signed: false, scale: 0 })}</span>
    case 'MAX_DRAWDOWN':
      return <span className={cx('tabular-nums', !row.maxDrawdown && 'text-text-3')}>{row.maxDrawdown ? `${formatPct(row.maxDrawdown, { signed: false, scale: 1 })}` : '--'}</span>
  }
}

export const columnLabel = (c: ExtraColumn): string => EXTRA_COLUMNS.find((x) => x.value === c)?.short ?? c

/** "Columns" popover (tablet and desktop) and the phone's one extra stat (§22.4 Phase 2). */
export function ColumnsPicker({ columns, onToggle, phoneStat, onPhoneStat }: { columns: readonly ExtraColumn[]; onToggle: (c: ExtraColumn) => void; phoneStat: ExtraColumn | null; onPhoneStat: (c: ExtraColumn | null) => void }) {
  return (
    <>
      <span className="hidden tablet:inline-flex">
        <Popover
          label="Leaderboard columns"
          renderTrigger={(props) => (
            <button type="button" {...props} className="h-11 rounded-md border border-divider px-2.5 text-caption font-semibold text-text-2 hover:text-text-1 tablet:h-7">
              Columns{columns.length ? ` · ${columns.length}` : ''}
            </button>
          )}
        >
          <fieldset className="px-3 py-2">
            <legend className="pb-1 text-micro text-text-3 uppercase">Extra columns</legend>
            {EXTRA_COLUMNS.map((c) => (
              <label key={c.value} className="flex min-h-9 cursor-pointer items-center gap-2 text-body">
                <input type="checkbox" checked={columns.includes(c.value)} onChange={() => onToggle(c.value)} className="size-4 accent-[var(--accent)]" />
                {c.label}
              </label>
            ))}
            <p className="pt-1 text-micro text-text-3">Ranks stay by return or P&amp;L.</p>
          </fieldset>
        </Popover>
      </span>
      <label className="inline-flex items-center gap-1 text-caption text-text-2 tablet:hidden">
        <span>Show</span>
        <select
          aria-label="Extra stat on each card"
          value={phoneStat ?? ''}
          onChange={(e) => onPhoneStat((e.target.value || null) as ExtraColumn | null)}
          className="h-11 rounded-md border border-divider bg-bg-1 px-2 text-caption text-text-1"
        >
          <option value="">No extra stat</option>
          {EXTRA_COLUMNS.map((c) => (
            <option key={c.value} value={c.value}>
              {c.label}
            </option>
          ))}
        </select>
      </label>
    </>
  )
}
