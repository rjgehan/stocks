import { useAccount } from '@/lib/api/hooks/account'
import { formatMoney } from '@/lib/format/money'
import { Skeleton } from '@/components/ui/Skeleton'

/** Balances (§22.4): cash, settled, unsettled, settled available, withdrawable, buying power, holds, net contributions. */
export function BalancesGrid() {
  const { data: a } = useAccount()
  const items: Array<[string, string | undefined, string]> = [
    ['Cash', a?.cash.total, 'Total cash, settled and unsettled'],
    ['Settled', a?.cash.settled, 'Cash from trades that have settled (T+1)'],
    ['Unsettled', a?.cash.unsettled, 'Sale proceeds that settle next business day'],
    ['Settled available', a?.cash.settledAvailable, 'Settled cash not held by open orders'],
    ['Withdrawable', a?.cash.withdrawable, 'What an admin withdrawal could take'],
    ['Buying power', a?.buyingPower, 'Cash available for new buys'],
    ['Open order holds', a?.openBuyHolds, 'Reserved for open buy orders'],
    ['Net contributions', a?.netContributions, 'Starting cash plus deposits minus withdrawals'],
  ]
  return (
    <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider desktop:grid-cols-4">
      {items.map(([label, value, title]) => (
        <div key={label} className="bg-bg-1 p-3" title={title}>
          <dt className="text-caption text-text-2">{label}</dt>
          <dd className="mt-0.5 text-section font-semibold">{a ? formatMoney(value) : <Skeleton className="h-5 w-20" />}</dd>
        </div>
      ))}
    </dl>
  )
}
