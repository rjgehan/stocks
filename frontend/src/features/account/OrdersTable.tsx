import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useCancelOrder, useOrders } from '@/lib/api/hooks/account'
import type { OrdersFilter } from '@/lib/api/queryKeys'
import { type Order, isOpenStatus } from '@/lib/api/types'
import { isDecimal, sign } from '@/lib/format/decimal'
import { formatPrice, formatQty } from '@/lib/format/number'
import { formatDateTimeEt, formatTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { toast } from '@/features/notifications/toastStore'
import { ORDER_TYPE_LABEL, STATUS_LABEL, STATUS_TONE, isArmedStop, orderPriceLabel, orderSizeLabel, statusLine } from './orderLabels'

export type OrderView = 'OPEN' | 'FILLED' | 'CANCELLED' | 'ALL'

/** Which server filter backs a view, and which statuses it shows (Filled / Cancelled split the CLOSED list). */
export function viewFilter(view: OrderView): { status: OrdersFilter; keep: (o: Order) => boolean } {
  switch (view) {
    case 'OPEN':
      return { status: 'OPEN', keep: () => true }
    case 'FILLED':
      // filled, or ended (cancelled / expired) after a partial fill
      return { status: 'CLOSED', keep: (o) => o.status === 'FILLED' || (o.filledQty != null && isDecimal(o.filledQty) && sign(o.filledQty) > 0) }
    case 'CANCELLED':
      return { status: 'CLOSED', keep: (o) => o.status === 'CANCELLED' || o.status === 'EXPIRED' || o.status === 'REPLACED' || o.status === 'REJECTED' }
    case 'ALL':
      return { status: 'ALL', keep: () => true }
  }
}

export function useCancelWithToast() {
  const cancel = useCancelOrder()
  const run = (o: Order) =>
    o.orderId &&
    cancel.mutate(o.orderId, {
      onSuccess: (res) => toast({ tone: 'neutral', title: res.status === 'FILLED' ? `${o.symbol} filled before the cancel arrived` : `Cancel sent: ${o.side === 'BUY' ? 'Buy' : 'Sell'} ${o.symbol}` }),
      onError: (e) => {
        const d = describeError(e)
        toast({ tone: 'error', title: d.title, body: errorBody(d) })
      },
    })
  return { run, pending: cancel.isPending, pendingId: cancel.isPending ? cancel.variables : undefined }
}

/** One order as a row (tablet/desktop) or a card line (phone). */
export function OrderRow({ o, onCancel, cancelling, showDate }: { o: Order; onCancel: (o: Order) => void; cancelling: boolean; showDate?: boolean }) {
  const open = isOpenStatus(o.status)
  return (
    <li className="flex items-center gap-3 px-4 py-2.5 text-table">
      <span className={cx('w-9 shrink-0 font-semibold', o.side === 'BUY' ? 'text-up' : 'text-down')}>{o.side === 'BUY' ? 'Buy' : 'Sell'}</span>
      <Link to="/account/orders/$orderId" params={{ orderId: o.orderId ?? '' }} className="min-w-0 flex-1 hover:text-accent">
        <div className="truncate font-semibold">
          {o.symbol} · {orderSizeLabel(o)} · {o.type ? ORDER_TYPE_LABEL[o.type] : ''}
          {orderPriceLabel(o) ? ` ${orderPriceLabel(o)}` : ''}
          {o.timeInForce === 'GTC' ? ' · GTC' : ''}
          {o.extendedHours ? ' · Ext' : ''}
        </div>
        <div className="truncate text-caption text-text-3">
          {o.status === 'FILLED' || o.status === 'PARTIALLY_FILLED' ? `Filled ${formatQty(o.filledQty)} @ ${formatPrice(o.avgFillPrice)}` : statusLine(o)}
          {o.type === 'TRAILING_STOP' && isArmedStop(o) && o.stopPrice ? ` · stop ${formatPrice(o.stopPrice)}` : ''}
          {' · '}
          {showDate ? formatDateTimeEt(o.receivedAt) : formatTimeEt(o.receivedAt)}
        </div>
      </Link>
      {o.triggered && isOpenStatus(o.status) ? (
        <Pill tone="warn" className="hidden tablet:inline-flex" title="The stop price was reached">
          Triggered
        </Pill>
      ) : null}
      <Pill tone={o.status ? STATUS_TONE[o.status] : 'neutral'} className="hidden tablet:inline-flex">
        {o.status ? STATUS_LABEL[o.status] : '--'}
      </Pill>
      {open ? (
        <Button size="sm" variant="danger" disabled={cancelling} onClick={() => onCancel(o)} aria-label={`Cancel ${o.side === 'BUY' ? 'buy' : 'sell'} ${o.symbol} order`}>
          Cancel
        </Button>
      ) : null}
    </li>
  )
}

/** Orders list with Cancel on open orders (§22.4). `symbol` narrows to one symbol (quote page). */
export function OrdersTable({ view = 'OPEN', symbol, limit, emptyHint }: { view?: OrderView; symbol?: string; limit?: number; emptyHint?: string }) {
  const { status, keep } = viewFilter(view)
  const { data, isPending, isError } = useOrders(status, symbol)
  const cancel = useCancelWithToast()
  if (isPending)
    return (
      <div className="px-4">
        <SkeletonRows rows={3} cols={4} />
      </div>
    )
  if (isError) return <EmptyState title="Orders are unavailable right now" body="Pull to refresh or try again in a moment." />
  const items = (data?.items ?? []).filter(keep).slice(0, limit)
  if (!items.length) return <EmptyState title={view === 'OPEN' ? 'No open orders' : 'No orders'} body={emptyHint} />
  return (
    <ul className="divide-y divide-divider">
      {items.map((o) => (
        <OrderRow key={o.orderId} o={o} onCancel={cancel.run} cancelling={cancel.pendingId === o.orderId} />
      ))}
    </ul>
  )
}
