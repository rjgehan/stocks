import { Suspense, lazy, useMemo, useState } from 'react'
import { useAccountHistory } from '@/lib/api/hooks/account'
import type { HistoryRange } from '@/lib/api/types'
import { div, isDecimal, isZero, sub } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPct } from '@/lib/format/number'
import { Segmented } from '@/components/ui/Segmented'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import type { LineSpec } from './LineChart'

const LineChart = lazy(() => import('./LineChart'))

export const HISTORY_RANGES: readonly HistoryRange[] = ['1D', '1W', '1M', '3M', 'YTD', '1Y', 'ALL']
type Metric = 'NAV' | 'PNL' | 'RETURN'

/** Rebases a series of index values to a return from its first point (display only). */
export function rebase(points: ReadonlyArray<{ t: string | null; v: string | null }>): Array<{ t: string | null; v: string | null }> {
  const first = points.find((p) => p.v != null && isDecimal(p.v) && !isZero(p.v))?.v
  if (!first) return []
  return points.map((p) => ({ t: p.t, v: p.v != null && isDecimal(p.v) ? sub(div(p.v, first, 10, 'HALF_EVEN'), '1') : null }))
}

/** Equity curve (§22.4): range chips and NAV / P&L / Return vs SPY, from `GET /account/history`. */
export function EquityCurve() {
  const [range, setRange] = useState<HistoryRange>('1M')
  const [metric, setMetric] = useState<Metric>('NAV')
  const { data, isPending, isError } = useAccountHistory(range)
  const points = useMemo(() => data?.points ?? [], [data])
  const lines = useMemo<LineSpec[]>(() => {
    if (metric === 'NAV') return [{ id: 'nav', color: '--accent', points: points.map((p) => ({ t: p.t, v: p.equity })) }]
    if (metric === 'PNL') return [{ id: 'pnl', color: '--accent', points: points.map((p) => ({ t: p.t, v: p.pnl })) }]
    return [
      { id: 'you', color: '--accent', points: rebase(points.map((p) => ({ t: p.t, v: p.unitValue }))) },
      { id: 'spy', color: '--text-3', dashed: true, points: rebase(points.map((p) => ({ t: p.t, v: p.benchmarkIndex }))) },
    ]
  }, [points, metric])
  const s = data?.summary
  return (
    <section className="rounded-lg border border-divider bg-bg-1 p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-section font-semibold">Performance</h2>
        <Segmented
          label="Chart metric"
          size="sm"
          value={metric}
          onChange={setMetric}
          options={[
            { value: 'NAV', label: 'Value' },
            { value: 'PNL', label: 'P&L' },
            { value: 'RETURN', label: 'vs SPY' },
          ]}
        />
      </div>
      {s ? (
        <dl className="mb-2 flex flex-wrap gap-x-6 gap-y-1 text-table">
          <div>
            <dt className="text-micro text-text-3 uppercase">Return</dt>
            <dd className={cx('font-semibold', DIRECTION_TEXT[direction(s.return)])}>{formatPct(s.return)}</dd>
          </div>
          <div>
            <dt className="text-micro text-text-3 uppercase">P&amp;L</dt>
            <dd className={cx('font-semibold', DIRECTION_TEXT[direction(s.pnl)])}>{formatMoney(s.pnl, { signed: true })}</dd>
          </div>
          {s.excessVsBenchmark != null ? (
            <div>
              <dt className="text-micro text-text-3 uppercase">vs SPY</dt>
              <dd className={cx('font-semibold', DIRECTION_TEXT[direction(s.excessVsBenchmark)])}>{formatPct(s.excessVsBenchmark)}</dd>
            </div>
          ) : null}
          {s.maxDrawdown != null ? (
            <div>
              <dt className="text-micro text-text-3 uppercase">Max drawdown</dt>
              <dd className="font-semibold text-text-1">{formatPct(s.maxDrawdown, { signed: false })}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      <div className="h-56 tablet:h-64">
        {isError ? (
          <div className="flex h-full items-center justify-center rounded-md bg-bg-2/50 px-6 text-center text-caption text-text-3">
            Your performance history appears after the first end-of-day snapshot.
          </div>
        ) : isPending ? (
          <Skeleton className="h-full w-full" />
        ) : (
          <Suspense fallback={<Skeleton className="h-full w-full" />}>
            <LineChart lines={lines} intraday={range === '1D' || range === '1W'} format={metric === 'RETURN' ? 'percent' : 'money'} />
          </Suspense>
        )}
      </div>
      {metric === 'RETURN' ? (
        <p className="mt-1 text-micro text-text-3">
          <span className="text-accent">━</span> You (time-weighted, deposits excluded) · <span>┅</span> SPY total return
        </p>
      ) : null}
      <div role="radiogroup" aria-label="History range" className="scrollbar-none mt-2 flex gap-1 overflow-x-auto">
        {HISTORY_RANGES.map((r) => (
          <button
            key={r}
            type="button"
            role="radio"
            aria-checked={r === range}
            onClick={() => setRange(r)}
            className={cx('h-7 min-w-10 shrink-0 rounded-sm px-2 text-caption font-semibold', r === range ? 'bg-bg-3 text-text-1' : 'text-text-2 hover:bg-bg-2 hover:text-text-1')}
          >
            {r}
          </button>
        ))}
      </div>
    </section>
  )
}
