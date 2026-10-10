import { Link, createFileRoute } from '@tanstack/react-router'
import { useAccount } from '@/lib/api/hooks/account'
import { formatDateTimeEt } from '@/lib/format/time'
import { Pill } from '@/components/ui/Pill'
import { BalancesGrid } from '@/features/account/BalancesGrid'
import { EquityCurve } from '@/features/account/EquityCurve'
import { OrdersTable } from '@/features/account/OrdersTable'
import { PositionsTable } from '@/features/account/PositionsTable'

export const Route = createFileRoute('/_app/account/')({
  component: AccountOverview,
})

const RESTRICTION_COPY: Record<string, string> = {
  SETTLED_CASH_ONLY: 'Buys are limited to settled cash because of good-faith violations.',
  LIQUIDATION_ONLY: 'Your account can only sell (liquidation only).',
  FROZEN: 'Trading is frozen for this account. Contact the league admin.',
}

/** Account overview (§22.4): balances, GFV card, equity curve vs SPY, positions and open orders. */
function AccountOverview() {
  const { data: a } = useAccount()
  const restricted = a?.restriction && a.restriction !== 'NONE'
  const gfvActive = a?.gfv?.active ?? 0
  return (
    <div className="space-y-4">
      {restricted ? (
        <div role="alert" className="rounded-lg border border-down/40 bg-down-bg p-3 text-body">
          <p className="font-semibold text-down">Account restricted</p>
          <p className="text-caption text-text-1">
            {a.restrictionReason ?? RESTRICTION_COPY[a.restriction!]}
            {a.restrictionUntil ? ` Until ${formatDateTimeEt(a.restrictionUntil)}.` : ''}
          </p>
        </div>
      ) : null}
      <BalancesGrid />
      <section className="flex items-center justify-between gap-3 rounded-lg border border-divider bg-bg-1 p-4">
        <div>
          <h2 className="text-section font-semibold">Good-faith violations</h2>
          <p className="text-caption text-text-2">
            Selling a stock bought with unsettled cash before that cash settles (T+1) is a violation. {a?.gfv?.settledCashOnlyAt ?? 3} active → settled cash only ·{' '}
            {a?.gfv?.liquidationOnlyAt ?? 4} → liquidation only.
          </p>
          {a?.gfv?.nextExpiry ? <p className="text-micro text-text-3">Next one expires {formatDateTimeEt(a.gfv.nextExpiry)}</p> : null}
        </div>
        <Pill tone={gfvActive > 0 ? 'warn' : 'up'}>{a ? `${gfvActive}/${a.gfv?.settledCashOnlyAt ?? 3} active` : '…'}</Pill>
      </section>
      <EquityCurve />
      <section className="rounded-lg border border-divider bg-bg-1">
        <div className="flex items-center justify-between px-4 pt-3 pb-1">
          <h2 className="text-section font-semibold">Positions</h2>
          <Link to="/account/positions" className="inline-flex min-h-11 items-center text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0">
            All positions
          </Link>
        </div>
        <PositionsTable />
      </section>
      <section className="rounded-lg border border-divider bg-bg-1">
        <div className="flex items-center justify-between px-4 pt-3 pb-1">
          <h2 className="text-section font-semibold">Open orders</h2>
          <Link to="/account/orders" className="inline-flex min-h-11 items-center text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0">
            All orders
          </Link>
        </div>
        <OrdersTable view="OPEN" emptyHint="Limit orders that are waiting for their price show up here." />
      </section>
    </div>
  )
}
