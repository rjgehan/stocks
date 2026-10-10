import { Link } from '@tanstack/react-router'
import { useAccount } from '@/lib/api/hooks/account'
import { formatMoney } from '@/lib/format/money'
import { formatPct } from '@/lib/format/number'
import { Skeleton } from '@/components/ui/Skeleton'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { direction, arrow } from '@/lib/format/number'
import { cx } from '@/components/ui/cx'

/** NAV and today's P&L, always from the server's Account (§19: the client never computes money). */
export function NavHero({ size = 'bar' }: { size?: 'bar' | 'hero' }) {
  const { data: account } = useAccount()
  if (!account) return size === 'bar' ? <Skeleton className="h-8 w-28" /> : <Skeleton className="h-14 w-48" />
  const dir = direction(account.dayPnl)
  if (size === 'hero') {
    return (
      <div>
        <div className="text-caption font-medium text-text-2">Net account value</div>
        <div className="text-hero-phone font-semibold tablet:text-[32px]">{formatMoney(account.equity)}</div>
        <div className={cx('text-body font-medium', DIRECTION_TEXT[dir])}>
          {arrow(account.dayPnl)} {formatMoney(account.dayPnl, { signed: true })} ({formatPct(account.dayReturn)}) <span className="text-text-3">today</span>
        </div>
      </div>
    )
  }
  return (
    <Link to="/account" className="flex flex-col items-end rounded-md px-2 py-0.5 leading-tight hover:bg-bg-2" aria-label={`Net account value ${formatMoney(account.equity)}`}>
      <span className="text-body font-semibold text-text-1 transition-all">{formatMoney(account.equity)}</span>
      <span className={cx('text-micro font-medium', DIRECTION_TEXT[dir])}>
        {arrow(account.dayPnl)} {formatMoney(account.dayPnl, { signed: true })} ({formatPct(account.dayReturn)})
      </span>
    </Link>
  )
}
