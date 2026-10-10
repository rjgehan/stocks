import { useAccount } from '@/lib/api/hooks/account'
import { isDecimal, sign } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'

/**
 * Balances (§22.4): buying power, cash, settled, unsettled, settled available, withdrawable, holds, market value, a
 * dividend receivable while there is one, and net contributions, straight from the server's `Account` (§19: the
 * client never computes money). Cash + market value + dividends receivable = equity.
 */
export function BalancesGrid() {
  const { data: a, isError } = useAccount()
  if (isError && !a) {
    return (
      <p role="alert" className="rounded-lg border border-divider bg-bg-1 p-4 text-caption text-text-2">
        Balances are unavailable right now. They reload automatically.
      </p>
    )
  }
  const receivable = a?.dividendsReceivable && isDecimal(a.dividendsReceivable) && sign(a.dividendsReceivable) > 0 ? a.dividendsReceivable : null
  const items: Array<{ label: string; value: string | null | undefined; title: string; strong?: boolean }> = [
    { label: 'Buying power', value: a?.buyingPower, title: 'Cash available for new buys (cash minus open-order holds)', strong: true },
    { label: 'Cash', value: a?.cash?.total, title: 'Total cash, settled and unsettled' },
    { label: 'Settled', value: a?.cash?.settled, title: 'Cash from trades that have settled (T+1)' },
    { label: 'Unsettled', value: a?.cash?.unsettled, title: 'Sale proceeds that settle the next business day' },
    { label: 'Settled available', value: a?.cash?.settledAvailable, title: 'Settled cash not held by open orders' },
    { label: 'Withdrawable', value: a?.cash?.withdrawable, title: 'Settled cash an admin withdrawal could take' },
    { label: 'Open order holds', value: a?.openBuyHolds, title: 'Reserved for your open buy orders' },
    { label: 'Market value', value: a?.longMarketValue, title: 'Value of your positions at current marks' },
    ...(receivable
      ? [{ label: 'Dividends receivable', value: receivable, title: 'Dividends on shares you held at the ex-date, paid in cash on the payable date; they count in your equity until then' }]
      : []),
    { label: 'Net contributions', value: a?.netContributions, title: 'Starting cash plus deposits minus withdrawals' },
  ]
  return (
    <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider tablet:grid-cols-3">
      {items.map(({ label, value, title, strong }) => (
        <div key={label} className={cx('bg-bg-1 p-3', strong && 'col-span-2 tablet:col-span-1')} title={title}>
          <dt className="text-caption text-text-2">{label}</dt>
          <dd className={cx('mt-0.5 font-semibold', strong ? 'text-[18px] leading-6 text-accent' : 'text-section')}>{a ? formatMoney(value) : <Skeleton className="h-5 w-20" />}</dd>
        </div>
      ))}
    </dl>
  )
}
