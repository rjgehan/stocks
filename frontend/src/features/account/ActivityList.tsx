import { Link } from '@tanstack/react-router'
import { type ActivityFilter, useActivities } from '@/lib/api/hooks/account'
import type { Activity, ActivityDetailView } from '@/lib/api/types'
import { isDecimal, isZero } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { moneyDirection } from '@/lib/format/number'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconBolt, IconCash, IconCoins, IconInfo, IconRename, IconRotate, IconShield, IconSplit } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { type ActivityIcon, activityView } from './activityView'

const ICON: Record<ActivityIcon, { icon: (p: { size?: number }) => React.ReactNode; className: string }> = {
  trade: { icon: IconBolt, className: 'bg-accent-bg text-accent' },
  cash: { icon: IconCash, className: 'bg-up-bg text-up' },
  dividend: { icon: IconCoins, className: 'bg-up-bg text-up' },
  split: { icon: IconSplit, className: 'bg-ai-bg text-ai' },
  rename: { icon: IconRename, className: 'bg-ai-bg text-ai' },
  shield: { icon: IconShield, className: 'bg-warn/15 text-warn' },
  reset: { icon: IconRotate, className: 'bg-bg-2 text-text-2' },
  info: { icon: IconInfo, className: 'bg-bg-2 text-text-2' },
}

/**
 * One activity row (§22.4): fills with fees, deposits with the memo and a "by admin" badge, dividends (accrued or
 * paid), splits (quantity before → after, cash in lieu), symbol changes and account events. `linkOrders` links a
 * fill to its order detail; another player's activity (public mode) passes false, since that page is yours only.
 */
export function ActivityRow({ a, linkOrders = true }: { a: Activity; linkOrders?: boolean }) {
  const v = activityView(a)
  const detail = a.detail as ActivityDetailView | null
  const memo = a.memo ?? (a.type === 'ADMIN_VIEW' ? detail?.reason : null)
  const fees = a.fees?.total && isDecimal(a.fees.total) && !isZero(a.fees.total) ? a.fees : null
  const amount = a.amount && isDecimal(a.amount) ? a.amount : null
  const { icon: Icon, className } = ICON[v.icon]
  const body = (
    <div className="flex items-start gap-3 px-4 py-3">
      <span className={cx('flex size-9 shrink-0 items-center justify-center rounded-full', className)}>
        <Icon size={18} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="font-semibold text-text-1">{v.title}</span>
          {v.badge ? <Pill tone={v.badge.tone}>{v.badge.label}</Pill> : null}
          {a.by === 'ADMIN' ? <Pill tone="warn">By admin</Pill> : null}
        </div>
        {v.lines.length ? <p className="mt-0.5 text-caption text-text-2">{v.lines.join(' · ')}</p> : null}
        {memo ? <p className="mt-0.5 text-caption text-text-1">“{memo}”</p> : null}
        <p className="mt-0.5 text-caption text-text-3">
          {formatDateTimeEt(a.at)}
          {fees ? ` · fees ${formatMoney(fees.total)} (SEC ${fees.sec} · TAF ${fees.taf} · CAT ${fees.cat})` : ''}
          {a.type === 'FILL' && a.settleDate ? ` · settles ${formatDateEt(a.settleDate)}` : ''}
        </p>
      </div>
      <div className="shrink-0 text-right">
        {v.amount && amount ? (
          <div
            className={cx(
              'font-semibold tabular-nums',
              v.amountState === 'posted' ? DIRECTION_TEXT[moneyDirection(amount)] : 'text-text-3',
              v.amountState === 'void' && 'line-through',
            )}
          >
            {v.amount}
          </div>
        ) : null}
        {v.amountState === 'pending' ? <div className="text-micro text-text-3">pending</div> : null}
        {a.balanceAfter ? <div className="text-micro text-text-3">cash {formatMoney(a.balanceAfter)}</div> : null}
      </div>
    </div>
  )
  return a.orderId && linkOrders ? (
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
