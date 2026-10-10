import { Link } from '@tanstack/react-router'
import { usePositions } from '@/lib/api/hooks/account'
import type { Position } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPct, formatPrice, formatQty, moneyDirection } from '@/lib/format/number'
import { EmptyState } from '@/components/ui/EmptyState'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { QualityBadge } from '@/features/market/QualityBadge'

function Signed({ value, pct, className }: { value: string | null | undefined; pct?: string | null; className?: string }) {
  return (
    <span className={cx(DIRECTION_TEXT[moneyDirection(value)], className)}>
      {formatMoney(value, { signed: true })}
      {pct !== undefined ? <span className="text-[0.92em]"> ({formatPct(pct)})</span> : null}
    </span>
  )
}

function PositionCard({ p }: { p: Position }) {
  return (
    <Link to="/stock/$symbol" params={{ symbol: p.symbol ?? '' }} className="flex items-center justify-between gap-3 px-4 py-3 active:bg-bg-2">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5 font-semibold">
          {p.symbol}
          <QualityBadge quality={p.markQuality === 'SIMULATED' ? null : p.markQuality} />
        </div>
        <div className="text-caption text-text-3">
          {formatQty(p.qty)} sh · avg {formatPrice(p.avgCost)}
        </div>
        <div className="text-caption">
          <span className="text-text-3">Today </span>
          <Signed value={p.dayPnl} />
        </div>
      </div>
      <div className="text-right">
        <div className="font-semibold">{formatMoney(p.marketValue)}</div>
        <div className="text-caption text-text-3">{formatPrice(p.mark)}</div>
        <Signed value={p.unrealizedPnl} pct={p.unrealizedReturn} className="text-caption" />
      </div>
    </Link>
  )
}

/** Positions (§22.4). A table on tablet/desktop, cards on phones ("tables become cards", §22.3). */
export function PositionsTable({ compact }: { compact?: boolean }) {
  const { data, isPending, isError } = usePositions()
  if (isPending)
    return (
      <div className="px-4">
        <SkeletonRows rows={3} cols={6} />
      </div>
    )
  if (isError) return <EmptyState title="Positions are unavailable right now" body="They will reload automatically." />
  if (!data?.length) return <EmptyState title="No positions yet" body="Buy a stock or ETF and it shows up here with live P&L." />
  return (
    <>
      <ul className="divide-y divide-divider tablet:hidden" aria-label="Positions">
        {data.map((p) => (
          <li key={p.symbol}>
            <PositionCard p={p} />
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto tablet:block">
        <table className="w-full text-table" aria-label="Positions">
          <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
            <tr className={compact ? 'h-7' : 'h-8'}>
              <th className="px-4 font-medium">Symbol</th>
              <th className="px-2 text-right font-medium">Qty</th>
              <th className="px-2 text-right font-medium">Avg cost</th>
              <th className="px-2 text-right font-medium">Price</th>
              <th className="px-2 text-right font-medium">Mkt value</th>
              <th className="px-2 text-right font-medium">Day P&amp;L</th>
              <th className="px-2 text-right font-medium">Unrealized P&amp;L</th>
              <th className="px-4 text-right font-medium">Weight</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-divider">
            {data.map((p) => (
              <tr key={p.symbol} className={cx('hover:bg-bg-2', compact ? 'h-8' : 'h-10')}>
                <td className="px-4 font-semibold">
                  <Link to="/stock/$symbol" params={{ symbol: p.symbol ?? '' }} className="hover:text-accent">
                    {p.symbol}
                  </Link>
                  {p.name && !compact ? <span className="ml-2 hidden font-normal text-text-3 desktop:inline">{p.name}</span> : null}
                </td>
                <td className="px-2 text-right">{formatQty(p.qty)}</td>
                <td className="px-2 text-right">{formatPrice(p.avgCost)}</td>
                <td className="px-2 text-right">{formatPrice(p.mark)}</td>
                <td className="px-2 text-right">{formatMoney(p.marketValue)}</td>
                <td className="px-2 text-right">
                  <Signed value={p.dayPnl} />
                </td>
                <td className="px-2 text-right font-medium">
                  <Signed value={p.unrealizedPnl} pct={p.unrealizedReturn} />
                </td>
                <td className="px-4 text-right text-text-2">{formatPct(p.weight, { signed: false, scale: 1 })}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
