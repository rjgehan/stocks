import { Link } from '@tanstack/react-router'
import { usePositions } from '@/lib/api/hooks/account'
import { formatMoney } from '@/lib/format/money'
import { formatPct, formatPrice, formatQty } from '@/lib/format/number'
import { EmptyState } from '@/components/ui/EmptyState'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { Change, DIRECTION_TEXT } from '@/features/market/Change'
import { direction } from '@/lib/format/number'
import { cx } from '@/components/ui/cx'

/** Positions (§22.4). A table on tablet/desktop, cards on phones ("tables become cards", §22.3). */
export function PositionsTable({ compact }: { compact?: boolean }) {
  const { data, isPending } = usePositions()
  if (isPending) return <div className="px-4"><SkeletonRows rows={3} cols={6} /></div>
  if (!data?.length) return <EmptyState title="No positions" body="Buy a stock and it shows up here." />
  return (
    <>
      <ul className="divide-y divide-divider tablet:hidden">
        {data.map((p) => (
          <li key={p.symbol}>
            <Link to="/stock/$symbol" params={{ symbol: p.symbol }} className="flex items-center justify-between gap-3 px-4 py-3">
              <div className="min-w-0">
                <div className="font-semibold">{p.symbol}</div>
                <div className="text-caption text-text-3">
                  {formatQty(p.qty)} sh · avg {formatPrice(p.avgCost)}
                </div>
              </div>
              <div className="text-right">
                <div className="font-semibold">{formatMoney(p.marketValue)}</div>
                <div className={cx('text-caption', DIRECTION_TEXT[direction(p.unrealizedPnl)])}>
                  {formatMoney(p.unrealizedPnl, { signed: true })} ({formatPct(p.unrealizedReturn)})
                </div>
              </div>
            </Link>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto tablet:block">
        <table className="w-full text-table">
          <thead className="text-left text-micro uppercase tracking-wide text-text-3">
            <tr className={compact ? 'h-7' : 'h-8'}>
              <th className="px-4 font-medium">Symbol</th>
              <th className="px-2 text-right font-medium">Qty</th>
              <th className="px-2 text-right font-medium">Avg cost</th>
              <th className="px-2 text-right font-medium">Last</th>
              <th className="px-2 text-right font-medium">Mkt value</th>
              <th className="px-2 text-right font-medium">Day P&amp;L</th>
              <th className="px-4 text-right font-medium">Unrealized P&amp;L</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-divider">
            {data.map((p) => (
              <tr key={p.symbol} className="h-8 hover:bg-bg-2">
                <td className="px-4 font-semibold">
                  <Link to="/stock/$symbol" params={{ symbol: p.symbol }} className="hover:text-accent">
                    {p.symbol}
                  </Link>
                </td>
                <td className="px-2 text-right">{formatQty(p.qty)}</td>
                <td className="px-2 text-right">{formatPrice(p.avgCost)}</td>
                <td className="px-2 text-right">{formatPrice(p.last)}</td>
                <td className="px-2 text-right">{formatMoney(p.marketValue)}</td>
                <td className="px-2 text-right">
                  <Change change={p.dayPnl} showArrow={false} />
                </td>
                <td className={cx('px-4 text-right font-medium', DIRECTION_TEXT[direction(p.unrealizedPnl)])}>
                  {formatMoney(p.unrealizedPnl, { signed: true })} ({formatPct(p.unrealizedReturn)})
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
