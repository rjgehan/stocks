import { Link } from '@tanstack/react-router'
import { usePlayerPortfolio } from '@/lib/api/hooks/league'
import type { Position, PublicPortfolio as Portfolio } from '@/lib/api/types'
import { compare, isDecimal, isZero } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { formatPct, formatPrice, formatQty, moneyDirection } from '@/lib/format/number'
import { formatDateTimeEt, formatTimeSecondsEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { PositionCard, Signed } from '@/features/account/PositionsTable'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { QualityBadge } from '@/features/market/QualityBadge'
import { PublicOrderRow } from './PublicOrderRow'

const RESTRICTION_LABEL: Record<string, string> = {
  SETTLED_CASH_ONLY: 'Settled cash only',
  LIQUIDATION_ONLY: 'Sells only',
  FROZEN: 'Frozen',
}

const nonZero = (d: string | null | undefined): boolean => d != null && isDecimal(d) && !isZero(d)
const dec = (d: string | null | undefined): string => (d != null && isDecimal(d) ? d : '0')

function Cell({ label, children, className }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={cx('min-w-0 bg-bg-1 p-3', className)}>
      <dt className="text-micro tracking-wide text-text-3 uppercase">{label}</dt>
      <dd className="mt-0.5 text-section font-semibold tabular-nums">{children}</dd>
    </div>
  )
}

/**
 * A run's live numbers (public mode, §16.7): equity, cash (settled / unsettled), buying power, open buy holds, day,
 * total and unrealized P&L, plus GFVs, a restriction and dividends receivable when there are any. Also the admin
 * player page's "Live portfolio".
 */
export function PortfolioSummary({ p }: { p: Portfolio }) {
  const extras = [
    p.gfvActive > 0 ? `${p.gfvActive} good-faith violation${p.gfvActive > 1 ? 's' : ''}` : null,
    p.restriction && p.restriction !== 'NONE'
      ? `${RESTRICTION_LABEL[p.restriction] ?? p.restriction}${p.restrictionUntil ? ` until ${formatDateTimeEt(p.restrictionUntil)}` : ''}`
      : null,
    nonZero(p.dividendsReceivable) ? `${formatMoney(p.dividendsReceivable)} in dividends receivable` : null,
  ].filter(Boolean)
  return (
    <div className="space-y-1.5">
      <dl aria-label="Portfolio summary" className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider tablet:grid-cols-4">
        <Cell label="Equity">{formatMoney(p.equity)}</Cell>
        <Cell label="Cash">
          {formatMoney(p.cash)}
          <span className="block text-micro font-normal text-text-3">
            {formatMoney(p.settledCash)} settled · {formatMoney(p.unsettledCash)} unsettled
          </span>
        </Cell>
        <Cell label="Buying power">{formatMoney(p.buyingPower)}</Cell>
        <Cell label="Open buy holds">{formatMoney(p.openBuyHolds)}</Cell>
        <Cell label="Day P&L" className={DIRECTION_TEXT[moneyDirection(p.dayPnl)]}>
          {formatMoney(p.dayPnl, { signed: true })} <span className="text-caption">({formatPct(p.dayReturn)})</span>
        </Cell>
        <Cell label="Total P&L" className={DIRECTION_TEXT[moneyDirection(p.totalPnl)]}>
          {formatMoney(p.totalPnl, { signed: true })} <span className="text-caption">({formatPct(p.totalReturn)})</span>
        </Cell>
        <Cell label="Unrealized P&L" className={DIRECTION_TEXT[moneyDirection(p.unrealizedPnl)]}>
          {formatMoney(p.unrealizedPnl, { signed: true })}
        </Cell>
        <Cell label="Net contributions">{formatMoney(p.netContributions)}</Cell>
      </dl>
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-micro text-text-3">
        <span>
          <span className="mr-1 inline-block size-1.5 animate-pulse rounded-full bg-up align-middle" aria-hidden="true" />
          Live · updated {formatTimeSecondsEt(p.asOf)}
        </span>
        <QualityBadge quality={p.marksQuality} />
        {extras.length ? <span className="text-warn">{extras.join(' · ')}</span> : null}
      </p>
    </div>
  )
}

function HoldingsTable({ positions }: { positions: Position[] }) {
  return (
    <table className="w-full text-table" aria-label="Holdings">
      <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
        <tr className="h-8">
          <th className="px-4 font-medium">Symbol</th>
          <th className="px-2 text-right font-medium">Qty</th>
          <th className="px-2 text-right font-medium">Avg cost</th>
          <th className="px-2 text-right font-medium">Last</th>
          <th className="px-2 text-right font-medium">Mkt value</th>
          <th className="px-2 text-right font-medium">Unrealized P&amp;L</th>
          <th className="px-2 text-right font-medium">Day P&amp;L</th>
          <th className="px-4 text-right font-medium">Weight</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-divider">
        {positions.map((p) => (
          <tr key={p.symbol} className="h-10 hover:bg-bg-2">
            <td className="px-4 font-semibold">
              <Link to="/stock/$symbol" params={{ symbol: p.symbol ?? '' }} className="hover:text-accent">
                {p.symbol}
              </Link>
              {p.name ? <span className="ml-2 hidden font-normal text-text-3 desktop:inline">{p.name}</span> : null}
            </td>
            <td className="px-2 text-right">{formatQty(p.qty)}</td>
            <td className="px-2 text-right">{formatPrice(p.avgCost)}</td>
            <td className="px-2 text-right">
              <span className="inline-flex items-center justify-end gap-1">
                <QualityBadge quality={p.markQuality === 'SIMULATED' ? null : p.markQuality} />
                {formatPrice(p.mark)}
              </span>
            </td>
            <td className="px-2 text-right">{formatMoney(p.marketValue)}</td>
            <td className="px-2 text-right font-medium">
              <Signed value={p.unrealizedPnl} pct={p.unrealizedReturn} />
            </td>
            <td className="px-2 text-right">
              <Signed value={p.dayPnl} pct={p.dayReturn} />
            </td>
            <td className="px-4 text-right text-text-2">{formatPct(p.weight, { signed: false, scale: 1 })}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** Largest holding first. */
export function byMarketValue(positions: ReadonlyArray<Position>): Position[] {
  return [...positions].sort((a, b) => compare(dec(b.marketValue), dec(a.marketValue)))
}

/** The `n` largest live positions by weight (the profile's "Top holdings"; the old end-of-day weights are gone). */
export function topHoldings(positions: ReadonlyArray<Position>, n = 5): Position[] {
  return [...positions].sort((a, b) => compare(dec(b.weight), dec(a.weight))).slice(0, n)
}

/**
 * A player's live portfolio (public mode, §16.7; §22.4 profile → Portfolio): the summary, every position with live
 * marks (a table from tablet up, cards on phones) and the open orders with their notes. Read-only: other players'
 * orders have no Cancel. Polls every 15 s, and a feed item of this player refreshes it at once.
 */
export function PublicPortfolio({ handle }: { handle: string }) {
  const q = usePlayerPortfolio(handle)
  if (q.isPending)
    return (
      <div className="space-y-4" aria-busy="true">
        <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider tablet:grid-cols-4">
          {Array.from({ length: 8 }, (_, i) => (
            <div key={i} className="bg-bg-1 p-3">
              <Skeleton className="h-3 w-16" />
              <Skeleton className="mt-2 h-5 w-24" />
            </div>
          ))}
        </div>
        <div className="rounded-lg border border-divider bg-bg-1 px-4">
          <SkeletonRows rows={3} cols={5} />
        </div>
      </div>
    )
  if (q.isError)
    return (
      <EmptyState
        title="Portfolio unavailable right now"
        body="It refreshes by itself every few seconds."
        action={
          <Button size="sm" onClick={() => void q.refetch()}>
            Retry
          </Button>
        }
      />
    )
  const p = q.data
  if (p.runNo === 0) return <EmptyState title="No active run." body={`@${handle} has no account in the league right now.`} />
  const positions = byMarketValue(p.positions ?? [])
  const orders = p.openOrders ?? []
  return (
    <div className="space-y-4">
      <PortfolioSummary p={p} />
      <section className="rounded-lg border border-divider bg-bg-1">
        <h2 className="px-4 pt-3 pb-1 text-section font-semibold">Holdings</h2>
        {positions.length ? (
          <>
            <ul className="divide-y divide-divider tablet:hidden" aria-label="Holdings">
              {positions.map((pos) => (
                <li key={pos.symbol}>
                  <PositionCard p={pos} />
                </li>
              ))}
            </ul>
            <div className="hidden overflow-x-auto tablet:block">
              <HoldingsTable positions={positions} />
            </div>
          </>
        ) : (
          <p className="px-4 pb-4 text-caption text-text-3">No positions: @{handle} holds only cash.</p>
        )}
      </section>
      <section className="rounded-lg border border-divider bg-bg-1">
        <h2 className="px-4 pt-3 text-section font-semibold">Open orders</h2>
        <p className="px-4 text-micro text-text-3">Working orders with their notes, as the player placed them.</p>
        {orders.length ? (
          <ul className="divide-y divide-divider" aria-label="Open orders">
            {orders.map((o) => (
              <PublicOrderRow key={o.orderId} o={o} handle={handle} />
            ))}
          </ul>
        ) : (
          <p className="px-4 py-3 text-caption text-text-3">No open orders.</p>
        )}
      </section>
      {/* the run number matters after a reset: History shows the earlier runs too */}
      {p.runNo > 1 ? <p className="text-micro text-text-3">Run #{p.runNo}: the earlier runs are under History.</p> : null}
    </div>
  )
}
