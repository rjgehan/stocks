import { Suspense, lazy, useMemo } from 'react'
import type { HistoryRange, PnlAnalysis } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPct, moneyDirection, pctDirection } from '@/lib/format/number'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import type { LineSpec } from '@/features/account/LineChart'
import { DIRECTION_TEXT } from '@/features/market/Change'

const LineChart = lazy(() => import('@/features/account/LineChart'))

export const EARNINGS_RANGES: readonly HistoryRange[] = ['1W', '1M', '3M', 'YTD', '1Y', 'ALL']

/** Benchmark line colors: SPY then the extra benchmarks (QQQ). */
const BENCH_COLORS = ['--text-3', '--warn', '--ai'] as const

/**
 * My Earnings (§16.10, Webull "P&L Analysis"): your time-weighted return over the range against SPY and QQQ total
 * returns from the same start, with the three period returns and the P&L (`GET /account/pnl-analysis`).
 */
export function MyEarnings({
  range,
  onRange,
  analysis: a,
  isPending,
  isError,
}: {
  range: HistoryRange
  onRange: (r: HistoryRange) => void
  analysis: PnlAnalysis | undefined
  isPending: boolean
  isError: boolean
}) {
  const lines = useMemo<LineSpec[]>(() => {
    if (!a) return []
    const out: LineSpec[] = [{ id: 'you', color: '--accent', points: (a.points ?? []).map((p) => ({ t: p.t, v: p.return })) }]
    ;(a.benchmarks ?? []).forEach((b, i) => {
      if (b.points?.length) out.push({ id: b.symbol ?? `b${i}`, color: BENCH_COLORS[i] ?? '--text-3', dashed: true, points: b.points.map((p) => ({ t: p.t, v: p.return })) })
    })
    return out
  }, [a])
  return (
    <section aria-label="My earnings" className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
      <h2 className="text-section font-semibold">My earnings</h2>
      <div role="radiogroup" aria-label="Earnings range" className="scrollbar-none flex gap-1 overflow-x-auto">
        {EARNINGS_RANGES.map((r) => (
          <button
            key={r}
            type="button"
            role="radio"
            aria-checked={r === range}
            onClick={() => onRange(r)}
            className={cx('h-11 min-w-11 shrink-0 rounded-sm px-2 text-caption font-semibold tablet:h-7 tablet:min-w-10', r === range ? 'bg-bg-3 text-text-1' : 'text-text-2 hover:bg-bg-2 hover:text-text-1')}
          >
            {r}
          </button>
        ))}
      </div>
      {isError ? (
        <p className="py-6 text-center text-caption text-text-3">Your earnings appear once your account is funded.</p>
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-3 tablet:grid-cols-4">
            <div>
              <dt className="text-micro text-text-3 uppercase">You</dt>
              <dd className={cx('text-section font-semibold tabular-nums', DIRECTION_TEXT[pctDirection(a?.return)])}>{a ? formatPct(a.return) : <Skeleton className="h-5 w-16" />}</dd>
            </div>
            <div>
              <dt className="text-micro text-text-3 uppercase">P&amp;L</dt>
              <dd className={cx('text-section font-semibold tabular-nums', DIRECTION_TEXT[moneyDirection(a?.pnl)])}>{a ? formatMoney(a.pnl, { signed: true }) : <Skeleton className="h-5 w-16" />}</dd>
            </div>
            {(a?.benchmarks ?? []).map((b) => (
              <div key={b.symbol}>
                <dt className="text-micro text-text-3 uppercase">{b.symbol}</dt>
                <dd className={cx('text-section font-semibold tabular-nums', DIRECTION_TEXT[pctDirection(b.return)])}>{formatPct(b.return)}</dd>
              </div>
            ))}
          </dl>
          <div className="h-52 tablet:h-60">
            {isPending ? (
              <Skeleton className="h-full w-full" />
            ) : (
              <Suspense fallback={<Skeleton className="h-full w-full" />}>
                <LineChart lines={lines} intraday={range === '1W'} format="percent" />
              </Suspense>
            )}
          </div>
          <p className="text-micro text-text-3">
            <span className="text-accent">━</span> You (time-weighted, deposits excluded) · <span>┅</span> SPY · <span className="text-warn">┅</span> QQQ (both total
            return, measured from your start, even one in the middle of a trading day)
          </p>
        </>
      )}
    </section>
  )
}
