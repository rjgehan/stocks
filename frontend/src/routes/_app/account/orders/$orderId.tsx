import { Link, createFileRoute } from '@tanstack/react-router'
import { useOrder } from '@/lib/api/hooks/account'
import { isApiError } from '@/lib/api/problem'
import { type Execution, type OrderEvent, isOpenStatus } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { direction, formatInteger, formatPrice, formatQty } from '@/lib/format/number'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { IconChevronLeft } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { useCancelWithToast } from '@/features/account/OrdersTable'
import { CHANNEL_LABEL, ORDER_TYPE_LABEL, STATUS_LABEL, STATUS_TONE, orderSizeLabel, statusLine } from '@/features/account/orderLabels'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { QualityBadge } from '@/features/market/QualityBadge'

export const Route = createFileRoute('/_app/account/orders/$orderId')({
  staticData: { phone: 'pushed' },
  component: OrderDetail,
})

const EVENT_LABEL: Record<string, string> = {
  ACCEPTED: 'Order accepted',
  QUEUED: 'Queued for the session open',
  WORKING: 'Working',
  PAUSED: 'Paused (outside its session)',
  RESUMED: 'Resumed',
  TRIGGERED: 'Stop triggered',
  TRAIL_UPDATED: 'Trail updated',
  AWAITING_QUOTE: 'Waiting for a fresh quote',
  BEYOND_COLLAR: 'Price beyond the 5% collar',
  PARTIAL_FILL: 'Partially filled',
  FILL: 'Filled',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  REPLACED: 'Replaced',
  HOLD_ADJUSTED: 'Cash hold adjusted',
}

const LIQUIDITY_LABEL: Record<string, string> = {
  TOUCH: 'At the touch',
  WALK: 'Walked the book',
  RESTING: 'Resting limit',
  PRINT: 'Trade-through',
  OPEN: 'Opening',
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-divider py-2 text-table">
      <dt className="shrink-0 text-text-2">{label}</dt>
      <dd className="min-w-0 truncate text-right font-medium text-text-1">{children}</dd>
    </div>
  )
}

function FillCard({ e }: { e: Execution }) {
  const q = e.refQuote
  return (
    <li className="rounded-md border border-divider p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="font-semibold">
          {e.side === 'BUY' ? 'Bought' : 'Sold'} {formatQty(e.qty)} @ {formatPrice(e.price)}
        </span>
        <Pill tone="neutral">{e.liquidity ? LIQUIDITY_LABEL[e.liquidity] : '--'}</Pill>
      </div>
      <dl className="mt-1 grid grid-cols-2 gap-x-4 text-caption">
        <div className="flex justify-between py-0.5">
          <dt className="text-text-3">Principal</dt>
          <dd>{formatMoney(e.principal)}</dd>
        </div>
        <div className="flex justify-between py-0.5">
          <dt className="text-text-3">Net</dt>
          <dd>{formatMoney(e.netAmount)}</dd>
        </div>
        <div className="flex justify-between py-0.5">
          <dt className="text-text-3">Fees</dt>
          <dd title={`SEC ${e.fees?.sec ?? '--'} · TAF ${e.fees?.taf ?? '--'} · CAT ${e.fees?.cat ?? '--'}`}>{formatMoney(e.fees?.total)}</dd>
        </div>
        <div className="flex justify-between py-0.5">
          <dt className="text-text-3">Realized P&amp;L</dt>
          <dd className={cx(e.realizedPnl ? DIRECTION_TEXT[direction(e.realizedPnl)] : '')}>{e.realizedPnl ? formatMoney(e.realizedPnl, { signed: true }) : '--'}</dd>
        </div>
        <div className="col-span-2 flex justify-between py-0.5">
          <dt className="text-text-3">Settles</dt>
          <dd>{formatDateEt(e.settleDate)}</dd>
        </div>
      </dl>
      <p className="mt-1 text-micro text-text-3">
        {formatDateTimeEt(e.executedAt)} · SEC {e.fees?.sec ?? '--'} · TAF {e.fees?.taf ?? '--'} · CAT {e.fees?.cat ?? '--'}
      </p>
      {q ? (
        <div className="mt-2 rounded-sm bg-bg-2 px-2 py-1.5 text-micro text-text-2">
          <span className="font-semibold text-text-1">Quote used</span> · bid {formatPrice(q.bid)} × {formatInteger(q.bidSize)} · ask {formatPrice(q.ask)} × {formatInteger(q.askSize)} · last{' '}
          {formatPrice(q.last)} <QualityBadge quality={q.quality} />
          {q.clamped ? <span className="ml-1 text-warn">(clamped to the IEX spread limit)</span> : null}
          {q.quoteAt ? <span className="block">at {formatDateTimeEt(q.quoteAt)}</span> : null}
        </div>
      ) : null}
    </li>
  )
}

function Timeline({ events }: { events: OrderEvent[] }) {
  if (!events.length) return <p className="text-caption text-text-3">No events yet.</p>
  return (
    <ol className="relative ml-1.5 space-y-3 border-l border-divider pl-4">
      {[...events]
        .sort((a, b) => a.seq - b.seq)
        .map((ev) => (
          <li key={ev.seq} className="relative">
            <span
              className={cx('absolute top-1.5 -left-[21px] size-2.5 rounded-full border-2 border-bg-1', ev.type === 'FILL' || ev.type === 'PARTIAL_FILL' ? 'bg-up' : ev.type === 'CANCELLED' || ev.type === 'EXPIRED' ? 'bg-text-3' : 'bg-accent')}
              aria-hidden="true"
            />
            <p className="text-body font-medium">{ev.type ? (EVENT_LABEL[ev.type] ?? ev.type) : 'Event'}</p>
            <p className="text-caption text-text-3">
              {formatDateTimeEt(ev.at)}
              {ev.statusAfter ? ` · ${STATUS_LABEL[ev.statusAfter]}` : ''}
              {ev.detail && typeof ev.detail.reason === 'string' ? ` · ${ev.detail.reason.toLowerCase().replaceAll('_', ' ')}` : ''}
            </p>
          </li>
        ))}
    </ol>
  )
}

/** Order detail (§22.4): summary, cancel, every event, fills with liquidity tags, fee breakdown, quote used, rationale. */
function OrderDetail() {
  const { orderId } = Route.useParams()
  const bp = useBreakpoint()
  const { data: o, isPending, error } = useOrder(orderId)
  const cancel = useCancelWithToast()
  const header = bp === 'phone' ? <PushedHeader title={o ? `${o.side === 'SELL' ? 'Sell' : 'Buy'} ${o.symbol ?? ''}` : 'Order'} subtitle={o?.status ? STATUS_LABEL[o.status] : undefined} /> : null
  if (isPending)
    return (
      <div>
        {header}
        <div className="space-y-3 px-4 pt-4 tablet:px-0 tablet:pt-0">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      </div>
    )
  if (!o)
    return (
      <div>
        {header}
        <EmptyState title={error && isApiError(error) && error.code === 'ORDER_NOT_FOUND' ? 'Order not found' : 'This order is unavailable'} body="It may belong to a previous run of your account." />
      </div>
    )
  const open = isOpenStatus(o.status)
  const executions = o.executions ?? []
  return (
    <div className="pb-6">
      {header}
      <div className="space-y-4 px-4 pt-4 tablet:px-0 tablet:pt-0">
      <Link to="/account/orders" className="hidden items-center gap-1 text-caption text-text-2 hover:text-text-1 tablet:inline-flex">
        <IconChevronLeft size={16} /> Orders
      </Link>
      <section className="rounded-lg border border-divider bg-bg-1 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[20px] leading-7 font-bold tracking-tight">
              <span className={o.side === 'BUY' ? 'text-up' : 'text-down'}>{o.side === 'BUY' ? 'Buy' : 'Sell'}</span> {orderSizeLabel(o)}{' '}
              <Link to="/stock/$symbol" params={{ symbol: o.symbol ?? '' }} className="hover:text-accent">
                {o.symbol}
              </Link>
            </h1>
            <p className="text-caption text-text-2">
              {o.type ? ORDER_TYPE_LABEL[o.type] : ''}
              {o.limitPrice ? ` ${formatPrice(o.limitPrice)}` : ''} · {o.timeInForce === 'GTC' ? 'Good till cancelled' : 'Day'}
              {o.extendedHours ? ' · Extended hours' : ''}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Pill tone={o.status ? STATUS_TONE[o.status] : 'neutral'}>{o.status ? STATUS_LABEL[o.status] : '--'}</Pill>
            {open ? (
              <Button variant="danger" size="sm" disabled={cancel.pending} onClick={() => cancel.run(o)}>
                Cancel order
              </Button>
            ) : null}
          </div>
        </div>
        <p className="mt-2 text-body text-text-1">{statusLine(o)}</p>
        {o.rationale ? <p className="mt-2 rounded-sm bg-bg-2 p-2 text-caption text-text-2">Note: “{o.rationale}”</p> : null}
      </section>

      <div className="grid gap-4 desktop:grid-cols-2">
        <section className="rounded-lg border border-divider bg-bg-1 p-4">
          <h2 className="mb-1 text-section font-semibold">Details</h2>
          <dl>
            <Field label="Filled">
              {formatQty(o.filledQty)}
              {o.qty ? ` of ${formatQty(o.qty)}` : ''} {o.avgFillPrice ? `@ ${formatPrice(o.avgFillPrice)}` : ''}
            </Field>
            {o.notional ? <Field label="Amount">{formatMoney(o.notional)}</Field> : null}
            <Field label="Principal">{formatMoney(o.filledPrincipal)}</Field>
            <Field label="Fees">{formatMoney(o.fees)}</Field>
            {open ? <Field label="Cash held">{formatMoney(o.reservedCash)}</Field> : null}
            <Field label="Received">{formatDateTimeEt(o.receivedAt)}</Field>
            {o.doneAt ? <Field label="Completed">{formatDateTimeEt(o.doneAt)}</Field> : <Field label="Expires">{formatDateTimeEt(o.expiresAt)}</Field>}
            <Field label="Session date">{formatDateEt(o.sessionDate)}</Field>
            <Field label="Placed via">{o.channel ? (CHANNEL_LABEL[o.channel] ?? o.channel) : '--'}</Field>
            <Field label="Order id">
              <span className="font-mono text-caption" title={o.clientOrderId ?? undefined}>
                {o.orderId}
              </span>
            </Field>
          </dl>
        </section>
        <section className="rounded-lg border border-divider bg-bg-1 p-4">
          <h2 className="mb-2 text-section font-semibold">Timeline</h2>
          <Timeline events={o.events ?? []} />
        </section>
      </div>

      <section className="rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="mb-2 text-section font-semibold">Fills</h2>
        {executions.length ? (
          <ul className="space-y-2">
            {executions.map((e) => (
              <FillCard key={e.executionId} e={e} />
            ))}
          </ul>
        ) : (
          <p className="text-caption text-text-3">{open ? 'No fills yet.' : 'This order did not fill.'}</p>
        )}
      </section>
      </div>
    </div>
  )
}
