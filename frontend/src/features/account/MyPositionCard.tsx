import { usePositions } from '@/lib/api/hooks/account'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPct, formatPrice, formatQty } from '@/lib/format/number'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { OrdersTable } from './OrdersTable'

/** "My position" and "my open orders" on the quote page (§22.4). */
export function MyPositionCard({ symbol }: { symbol: string }) {
  const { data } = usePositions()
  const p = data?.find((x) => x.symbol === symbol)
  return (
    <section className="rounded-lg border border-divider bg-bg-1">
      <h2 className="px-4 pt-3 text-section font-semibold">My position</h2>
      {p ? (
        <dl className="grid grid-cols-2 gap-x-6 px-4 py-2 text-table tablet:grid-cols-3">
          {(
            [
              ['Shares', formatQty(p.qty)],
              ['Avg cost', formatPrice(p.avgCost)],
              ['Market value', formatMoney(p.marketValue)],
              ['Day P&L', <span key="d" className={DIRECTION_TEXT[direction(p.dayPnl)]}>{formatMoney(p.dayPnl, { signed: true })}</span>],
              [
                'Total P&L',
                <span key="t" className={cx(DIRECTION_TEXT[direction(p.unrealizedPnl)])}>
                  {formatMoney(p.unrealizedPnl, { signed: true })} ({formatPct(p.unrealizedReturn)})
                </span>,
              ],
              ['Portfolio %', formatPct(p.weight, { signed: false })],
            ] as const
          ).map(([k, v]) => (
            <div key={k} className="flex justify-between border-b border-divider py-1.5">
              <dt className="text-text-2">{k}</dt>
              <dd className="font-medium">{v}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <p className="px-4 py-3 text-caption text-text-3">You don't own {symbol}.</p>
      )}
      <h3 className="px-4 pt-2 text-caption font-semibold text-text-2 uppercase tracking-wide">Open orders</h3>
      <OrdersTable status="OPEN" symbol={symbol} />
    </section>
  )
}
