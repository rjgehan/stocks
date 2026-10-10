import { Link, createFileRoute } from '@tanstack/react-router'
import { useOrder } from '@/lib/api/hooks/account'
import { isApiError } from '@/lib/api/problem'
import { type Execution, type Order, type OrderEvent, isOpenStatus } from '@/lib/api/types'
import { abs, div, isDecimal, sign, sub } from '@/lib/format/decimal'
import { useQuote } from '@/lib/realtime/useQuote'
import { formatMoney } from '@/lib/format/money'
import { formatDecimal, formatInteger, formatPct, formatPrice, formatQty, moneyDirection, priceScale } from '@/lib/format/number'
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
import {
  CHANNEL_LABEL,
  ORDER_TYPE_LABEL,
  STATUS_LABEL,
  STATUS_TONE,
  eventDetailLine,
  isArmedStop,
  orderPriceLabel,
  orderSizeLabel,
  statusLine,
  trailLabel,
} from '@/features/account/orderLabels'
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
          <dd className={cx(e.realizedPnl ? DIRECTION_TEXT[moneyDirection(e.realizedPnl)] : '')}>{e.realizedPnl ? formatMoney(e.realizedPnl, { signed: true }) : '--'}</dd>
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

/**
 * The stop section of an order (§12.7): its trigger, the limit it becomes, the trail, the live watermark and how far
 * the last price is from the stop. The server owns the watermark and stop; the distance uses the live quote.
 */
function StopPanel({ o }: { o: Order }) {
  const q = useQuote(o.symbol ?? '', 'quote')
  const armed = isArmedStop(o)
  const last = q?.last ?? null
  const stop = o.stopPrice
  const distance = armed && stop && last && isDecimal(last) && sign(last) > 0 ? sub(last, stop) : null
  const distancePct = distance && last ? div(distance, last, 6, 'HALF_UP') : null
  const trailing = o.type === 'TRAILING_STOP'
  const buy = o.side === 'BUY'
  return (
    <section className="rounded-lg border border-divider bg-bg-1 p-4" aria-label="Stop">
      <div className="mb-1 flex items-center justify-between gap-2">
        <h2 className="text-section font-semibold">{trailing ? 'Trailing stop' : 'Stop'}</h2>
        <Pill tone={o.triggered ? 'warn' : armed ? 'accent' : 'neutral'}>{o.triggered ? 'Triggered' : armed ? 'Armed' : 'Not triggered'}</Pill>
      </div>
      <dl>
        {trailing ? <Field label="Trail">{trailLabel(o) ?? '--'}</Field> : null}
        {trailing ? (
          <Field label={buy ? 'Lowest price (watermark)' : 'Highest price (watermark)'}>
            <span className="tabular-nums">{formatPrice(o.watermarkPrice)}</span>
            {armed ? <span className="ml-1.5 inline-block size-1.5 animate-pulse rounded-full bg-up align-middle" aria-label="live" /> : null}
          </Field>
        ) : null}
        <Field label={trailing ? 'Current stop' : 'Stop price'}>{formatPrice(stop)}</Field>
        {o.type === 'STOP_LIMIT' ? <Field label="Limit after trigger">{formatPrice(o.limitPrice)}</Field> : null}
        {armed && last ? (
          <Field label="Last price">
            {formatPrice(last)}
            {distance ? (
              <span className="ml-1.5 text-caption text-text-3">
                {formatDecimal(abs(distance), { scale: priceScale(last) })} ({formatPct(distancePct ? abs(distancePct) : null, { signed: false })}) {sign(distance) >= 0 ? 'above' : 'below'} the stop
              </span>
            ) : null}
          </Field>
        ) : null}
        {o.triggeredAt ? <Field label="Triggered">{formatDateTimeEt(o.triggeredAt)}</Field> : null}
      </dl>
      {armed ? (
        <p className="mt-2 text-caption text-text-3">
          {buy ? 'Triggers when a trade prints at or above the stop' : 'Triggers when a trade prints at or below the stop'}, during regular hours only.
          {trailing ? ` The stop follows the ${buy ? 'lowest' : 'highest'} price since the order started.` : ''}
        </p>
      ) : null}
    </section>
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
              className={cx(
                'absolute top-1.5 -left-[21px] size-2.5 rounded-full border-2 border-bg-1',
                ev.type === 'FILL' || ev.type === 'PARTIAL_FILL' ? 'bg-up' : ev.type === 'CANCELLED' || ev.type === 'EXPIRED' ? 'bg-text-3' : ev.type === 'TRIGGERED' ? 'bg-warn' : 'bg-accent',
              )}
              aria-hidden="true"
            />
            <p className="text-body font-medium">{ev.type ? (EVENT_LABEL[ev.type] ?? ev.type) : 'Event'}</p>
            {eventDetailLine(ev) ? <p className="text-caption text-text-2">{eventDetailLine(ev)}</p> : null}
            <p className="text-caption text-text-3">
              {formatDateTimeEt(ev.at)}
              {ev.statusAfter ? ` · ${STATUS_LABEL[ev.statusAfter]}` : ''}
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
              {orderPriceLabel(o) ? ` ${orderPriceLabel(o)}` : ''} · {o.timeInForce === 'GTC' ? 'Good till cancelled' : 'Day'}
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

      {o.type === 'STOP' || o.type === 'STOP_LIMIT' || o.type === 'TRAILING_STOP' ? <StopPanel o={o} /> : null}

      <div className="grid gap-4 desktop:grid-cols-2">
        <section className="rounded-lg border border-divider bg-bg-1 p-4">
          <h2 className="mb-1 text-section font-semibold">Details</h2>
          <dl>
            <Field label="Filled">
              {formatQty(o.filledQty)}
              {o.qty ? ` of ${formatQty(o.qty)}` : ''} {o.avgFillPrice ? `@ ${formatPrice(o.avgFillPrice)}` : ''}
            </Field>
            {o.notional ? <Field label="Amount">{formatMoney(o.notional)}</Field> : null}
            {o.type === 'LIMIT' ? <Field label="Limit price">{formatPrice(o.limitPrice)}</Field> : null}
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
