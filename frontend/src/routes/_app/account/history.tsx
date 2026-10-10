import { createFileRoute } from '@tanstack/react-router'
import { useRuns } from '@/lib/api/hooks/account'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPct } from '@/lib/format/number'
import { formatDateTimeEt } from '@/lib/format/time'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { EquityCurve } from '@/features/account/EquityCurve'
import { DIRECTION_TEXT } from '@/features/market/Change'

export const Route = createFileRoute('/_app/account/history')({
  validateSearch: (s: Record<string, unknown>): { range?: string } => ({ range: typeof s.range === 'string' ? s.range : undefined }),
  component: HistoryPage,
})

/** History (§22.4): the equity curve vs SPY and your runs (an admin or self reset starts a new run). */
function HistoryPage() {
  const runs = useRuns()
  return (
    <div className="space-y-4">
      <EquityCurve />
      <section className="rounded-lg border border-divider bg-bg-1">
        <h2 className="px-4 pt-3 text-section font-semibold">Runs</h2>
        <p className="px-4 text-caption text-text-3">A reset closes a run and opens a new one; the league keeps counting across runs, so a reset never erases a loss.</p>
        {runs.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={2} cols={4} />
          </div>
        ) : runs.isError || !runs.data?.length ? (
          <p className="px-4 py-4 text-caption text-text-3">Run history is not available yet.</p>
        ) : (
          <ul className="divide-y divide-divider">
            {runs.data.map((r) => (
              <li key={r.accountId ?? r.runNo} className="flex items-center gap-3 px-4 py-3 text-table">
                <span className="w-14 shrink-0 font-semibold">Run {r.runNo}</span>
                <span className="min-w-0 flex-1 text-caption text-text-2">
                  {formatDateTimeEt(r.openedAt)} → {r.closedAt ? formatDateTimeEt(r.closedAt) : 'now'} · started with {formatMoney(r.startingCash)}
                </span>
                {r.status === 'ACTIVE' ? <Pill tone="up">Active</Pill> : null}
                <span className={cx('w-24 text-right font-semibold', DIRECTION_TEXT[direction(r.return)])}>{formatPct(r.return)}</span>
                <span className={cx('hidden w-24 text-right tablet:inline', DIRECTION_TEXT[direction(r.pnl)])}>{formatMoney(r.pnl, { signed: true })}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
