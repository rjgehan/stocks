import { Link } from '@tanstack/react-router'
import { useAccount } from '@/lib/api/hooks/account'
import { formatMoney } from '@/lib/format/money'
import { arrow, direction, formatPct } from '@/lib/format/number'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { useRollingFlash } from '@/features/market/usePriceFlash'

/** NAV and today's P&L, always from the server's Account (§19: the client never computes money). */
export function NavHero({ size = 'bar' }: { size?: 'bar' | 'hero' }) {
  const { data: account } = useAccount()
  const flash = useRollingFlash(account?.equity)
  if (!account) return size === 'bar' ? <Skeleton className="h-8 w-28" /> : <Skeleton className="h-24 w-64" />
  const dir = direction(account.dayPnl)
  const totalDir = direction(account.totalPnl)
  if (size === 'hero') {
    return (
      <div>
        <div className="text-caption font-medium text-text-2">Net account value</div>
        <div key={flash.key} className={cx('-mx-1 w-fit rounded-sm px-1 text-hero-phone font-semibold tablet:text-[34px] tablet:leading-10', flash.className)}>
          {formatMoney(account.equity)}
        </div>
        <div className="mt-1 flex flex-wrap gap-x-5 gap-y-1 text-body">
          <span className={cx('font-medium', DIRECTION_TEXT[dir])}>
            {arrow(account.dayPnl)} {formatMoney(account.dayPnl, { signed: true })} ({formatPct(account.dayReturn)}) <span className="font-normal text-text-3">today</span>
          </span>
          <span className={cx('font-medium', DIRECTION_TEXT[totalDir])}>
            {arrow(account.totalPnl)} {formatMoney(account.totalPnl, { signed: true })} ({formatPct(account.totalReturn)}){' '}
            <span className="font-normal text-text-3">total</span>
          </span>
        </div>
      </div>
    )
  }
  return (
    <Link
      to="/account"
      className="flex flex-col items-end rounded-md px-2 py-0.5 leading-tight hover:bg-bg-2"
      aria-label={`Net account value ${formatMoney(account.equity)}, today ${formatMoney(account.dayPnl, { signed: true })}`}
    >
      <span key={flash.key} className={cx('rounded-sm px-0.5 text-body font-semibold text-text-1', flash.className)}>
        {formatMoney(account.equity)}
      </span>
      <span className={cx('text-micro font-medium', DIRECTION_TEXT[dir])}>
        {arrow(account.dayPnl)} {formatMoney(account.dayPnl, { signed: true })} ({formatPct(account.dayReturn)})
      </span>
    </Link>
  )
}
