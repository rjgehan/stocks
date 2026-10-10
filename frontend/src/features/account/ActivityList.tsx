import { Link } from '@tanstack/react-router'
import { type ActivityFilter, useActivities } from '@/lib/api/hooks/account'
import type { Activity, ActivityDetailView, ActivityType } from '@/lib/api/types'
import { isDecimal, isZero } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPrice, formatQty } from '@/lib/format/number'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconBolt, IconCash, IconInfo, IconShield } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'

const TYPE_LABEL: Record<ActivityType, string> = {
  FILL: 'Trade',
  INITIAL_FUNDING: 'Starting cash',
  DEPOSIT: 'Deposit',
  WITHDRAWAL: 'Withdrawal',
  ADJUSTMENT: 'Adjustment',
  DIVIDEND: 'Dividend',
  CASH_IN_LIEU: 'Cash in lieu',
  SPLIT: 'Stock split',
  NAME_CHANGE: 'Symbol change',
  GFV: 'Good-faith violation',
  RESET: 'Account reset',
  RESTRICTION: 'Restriction',
  ADMIN_VIEW: 'Admin viewed your live account',
}

function title(a: Activity): string {
  if (a.type === 'FILL') return `${a.side === 'SELL' ? 'Sold' : 'Bought'} ${formatQty(a.qty)} ${a.symbol ?? ''} @ ${formatPrice(a.price)}`
  if (a.type === 'SPLIT' || a.type === 'DIVIDEND' || a.type === 'CASH_IN_LIEU') return `${TYPE_LABEL[a.type]} · ${a.symbol ?? ''}`
  if (a.type === 'NAME_CHANGE') {
    const d = a.detail as ActivityDetailView | null
    return d?.oldSymbol && d.newSymbol ? `${d.oldSymbol} is now ${d.newSymbol}` : TYPE_LABEL.NAME_CHANGE
  }
  return a.type ? TYPE_LABEL[a.type] : 'Activity'
}

function Icon({ type }: { type: Activity['type'] }) {
  const base = 'flex size-9 shrink-0 items-center justify-center rounded-full'
  if (type === 'FILL') return <span className={cx(base, 'bg-accent-bg text-accent')}><IconBolt size={18} /></span>
  if (type === 'ADMIN_VIEW' || type === 'RESTRICTION' || type === 'GFV') return <span className={cx(base, 'bg-warn/15 text-warn')}><IconShield size={18} /></span>
  if (type === 'DEPOSIT' || type === 'INITIAL_FUNDING' || type === 'DIVIDEND' || type === 'WITHDRAWAL' || type === 'ADJUSTMENT' || type === 'CASH_IN_LIEU')
    return <span className={cx(base, 'bg-up-bg text-up')}><IconCash size={18} /></span>
  return <span className={cx(base, 'bg-bg-2 text-text-2')}><IconInfo size={18} /></span>
}

/** One activity row (§22.4): fills with fees, deposits with the memo and a "by admin" badge, account events. */
export function ActivityRow({ a }: { a: Activity }) {
  const detail = a.detail as ActivityDetailView | null
  const memo = a.memo ?? (a.type === 'ADMIN_VIEW' ? detail?.reason : null)
  const fees = a.fees?.total && isDecimal(a.fees.total) && !isZero(a.fees.total) ? a.fees : null
  const amount = a.amount && isDecimal(a.amount) ? a.amount : null
  const body = (
    <div className="flex items-start gap-3 px-4 py-3">
      <Icon type={a.type} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="font-semibold text-text-1">{title(a)}</span>
          {a.by === 'ADMIN' ? <Pill tone="warn">By admin</Pill> : null}
        </div>
        {memo ? <p className="mt-0.5 text-caption text-text-1">“{memo}”</p> : null}
        <p className="mt-0.5 text-caption text-text-3">
          {formatDateTimeEt(a.at)}
          {fees ? ` · fees ${formatMoney(fees.total)} (SEC ${fees.sec} · TAF ${fees.taf} · CAT ${fees.cat})` : ''}
          {a.type === 'FILL' && a.settleDate ? ` · settles ${formatDateEt(a.settleDate)}` : ''}
          {a.type === 'ADMIN_VIEW' && detail?.admin ? ` · @${detail.admin}` : ''}
        </p>
      </div>
      <div className="shrink-0 text-right">
        {amount ? <div className={cx('font-semibold', DIRECTION_TEXT[direction(amount)])}>{formatMoney(amount, { signed: true })}</div> : null}
        {a.balanceAfter ? <div className="text-micro text-text-3">cash {formatMoney(a.balanceAfter)}</div> : null}
      </div>
    </div>
  )
  return a.orderId ? (
    <Link to="/account/orders/$orderId" params={{ orderId: a.orderId }} className="block hover:bg-bg-2">
      {body}
    </Link>
  ) : (
    body
  )
}

/** Activity, newest first, cursor-paged ("Load more"). */
export function ActivityList({ filter = {}, pageSize = 30, compact }: { filter?: ActivityFilter; pageSize?: number; compact?: boolean }) {
  const q = useActivities(filter, pageSize)
  if (q.isPending)
    return (
      <div className="px-4">
        <SkeletonRows rows={4} cols={3} />
      </div>
    )
  if (q.isError) return <EmptyState title="Activity is unavailable right now" body="It will be back shortly." />
  const items = q.data.pages.flatMap((p) => p.items ?? [])
  if (!items.length) return <EmptyState title="No activity yet" body="Trades, deposits and account events appear here, newest first." />
  return (
    <div>
      <ul className="divide-y divide-divider" aria-label="Activity">
        {items.map((a) => (
          <li key={a.id ?? `${a.at}-${a.type}`}>
            <ActivityRow a={a} />
          </li>
        ))}
      </ul>
      {q.hasNextPage && !compact ? (
        <div className="flex justify-center p-3">
          <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
            {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      ) : null}
    </div>
  )
}
