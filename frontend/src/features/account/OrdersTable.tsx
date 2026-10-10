import { Link } from '@tanstack/react-router'
import { useCancelOrder, useOrders } from '@/lib/api/hooks/account'
import type { Order } from '@/lib/api/types'
import { OPEN_STATUSES } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { formatTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill, type PillTone } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'

const STATUS_TONE: Record<Order['status'], PillTone> = {
  PENDING: 'warn',
  WORKING: 'accent',
  PARTIALLY_FILLED: 'accent',
  FILLED: 'up',
  CANCELLED: 'flat',
  EXPIRED: 'flat',
  REPLACED: 'flat',
  REJECTED: 'down',
}

const TYPE: Record<Order['type'], string> = { MARKET: 'Market', LIMIT: 'Limit', STOP: 'Stop', STOP_LIMIT: 'Stop Lmt', TRAILING_STOP: 'Trail' }

export function orderSizeLabel(o: Order): string {
  return o.notional ? formatMoney(o.notional) : `${formatQty(o.qty)} sh`
}

/** Orders list with Cancel on open orders (§22.4). `symbol` narrows to one symbol (quote page). */
export function OrdersTable({ status = 'OPEN', symbol }: { status?: 'OPEN' | 'CLOSED' | 'ALL'; symbol?: string }) {
  const { data, isPending } = useOrders(status)
  const cancel = useCancelOrder()
  if (isPending) return <div className="px-4"><SkeletonRows rows={3} cols={5} /></div>
  const items = (data?.items ?? []).filter((o) => !symbol || o.symbol === symbol)
  if (!items.length) return <EmptyState title={status === 'OPEN' ? 'No open orders' : 'No orders'} />
  return (
    <ul className="divide-y divide-divider">
      {items.map((o) => {
        const open = OPEN_STATUSES.includes(o.status)
        return (
          <li key={o.orderId} className="flex items-center gap-3 px-4 py-2.5 text-table">
            <span className={cx('w-10 shrink-0 font-semibold', o.side === 'BUY' ? 'text-up' : 'text-down')}>{o.side === 'BUY' ? 'Buy' : 'Sell'}</span>
            <Link to="/account/orders/$orderId" params={{ orderId: o.orderId }} className="min-w-0 flex-1 hover:text-accent">
              <div className="truncate font-semibold">
                {o.symbol} · {orderSizeLabel(o)} · {TYPE[o.type]}
                {o.limitPrice ? ` ${formatPrice(o.limitPrice)}` : ''}
                {o.stopPrice ? ` stop ${formatPrice(o.stopPrice)}` : ''}
              </div>
              <div className="truncate text-caption text-text-3">
                {o.status === 'FILLED' ? `Filled ${formatQty(o.filledQty)} @ ${formatPrice(o.avgFillPrice)}` : (o.waitReason?.replaceAll('_', ' ').toLowerCase() ?? o.timeInForce)}
                {' · '}
                {formatTimeEt(o.receivedAt)}
              </div>
            </Link>
            <Pill tone={STATUS_TONE[o.status]}>{o.status.replace('_', ' ')}</Pill>
            {open ? (
              <Button size="sm" variant="danger" disabled={cancel.isPending} onClick={() => cancel.mutate(o.orderId)}>
                Cancel
              </Button>
            ) : null}
          </li>
        )
      })}
    </ul>
  )
}
