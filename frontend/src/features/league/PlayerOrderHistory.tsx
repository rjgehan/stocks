import { Fragment, useEffect, useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { usePlayerOrders } from '@/lib/api/hooks/league'
import type { OrdersFilter } from '@/lib/api/queryKeys'
import type { PublicOrder } from '@/lib/api/types'
import { isDecimal, isZero, sign } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty, moneyDirection } from '@/lib/format/number'
import { formatDateTimeEt, formatTimeEt } from '@/lib/format/time'
import { useDebounced } from '@/lib/useDebounced'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconChevronDown } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { GROUP_TYPE_LABEL, ROLE_LABEL } from '@/features/groups/groupLabels'
import { feedDayLabel } from './FeedList'
import { OrderSourcePills, RationaleQuote, StatusPill, publicOrderLine, publicStatusLine, publicWaitLabel } from './PublicOrderRow'

const positive = (d: string | null | undefined): boolean => d != null && isDecimal(d) && sign(d) > 0
const nonZero = (d: string | null | undefined): boolean => d != null && isDecimal(d) && !isZero(d)

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-2">
      <dt className="w-28 shrink-0 text-text-3">{label}</dt>
      <dd className="min-w-0 break-words text-text-1">{children}</dd>
    </div>
  )
}

/** The expanded part of a row: the whole note, the fills, the replace chain, the group and the run. */
function OrderDetail({ o }: { o: PublicOrder }) {
  const fills = o.fills ?? []
  const g = o.group
  return (
    <div className="space-y-2 pt-2 pl-0 text-caption tablet:pl-[4.5rem]">
      {o.untrusted?.rationale ? <RationaleQuote text={o.untrusted.rationale} /> : null}
      {fills.length ? (
        <table className="w-full text-caption" aria-label="Fills">
          <thead className="text-left text-micro text-text-3 uppercase">
            <tr>
              <th className="py-1 font-medium">Time</th>
              <th className="py-1 text-right font-medium">Qty</th>
              <th className="py-1 text-right font-medium">Price</th>
              <th className="py-1 text-right font-medium">Fees</th>
              <th className="py-1 text-right font-medium">Realized P&amp;L</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-divider">
            {fills.map((f) => (
              <tr key={f.executionId}>
                <td className="py-1">{formatTimeEt(f.executedAt)}</td>
                <td className="py-1 text-right">{formatQty(f.qty)}</td>
                <td className="py-1 text-right">{formatPrice(f.price)}</td>
                <td className="py-1 text-right">{formatMoney(f.fees)}</td>
                <td className={cx('py-1 text-right', f.realizedPnl ? DIRECTION_TEXT[moneyDirection(f.realizedPnl)] : 'text-text-3')}>
                  {f.realizedPnl ? formatMoney(f.realizedPnl, { signed: true }) : '--'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      <dl className="space-y-0.5">
        <Fact label="Status">{publicStatusLine(o)}</Fact>
        {o.waitReason && (o.status === 'PENDING' || o.status === 'WORKING' || o.status === 'PARTIALLY_FILLED') ? <Fact label="Waiting">{publicWaitLabel(o.waitReason)}</Fact> : null}
        {o.replacesOrderId ? <Fact label="Replaces">{o.replacesOrderId}</Fact> : null}
        {o.replacedByOrderId ? <Fact label="Replaced by">{o.replacedByOrderId}</Fact> : null}
        {g?.type && g.role ? (
          <Fact label="Group">
            {GROUP_TYPE_LABEL[g.type]} · {ROLE_LABEL[g.role].toLowerCase()}
            {g.parentOrderId ? ` · after ${g.parentOrderId}` : ''}
            {g.held ? ' · held until the entry fills' : ''}
          </Fact>
        ) : null}
        {o.doneAt ? <Fact label="Done">{formatDateTimeEt(o.doneAt)}</Fact> : o.expiresAt ? <Fact label="Expires">{formatDateTimeEt(o.expiresAt)}</Fact> : null}
        <Fact label="Run">#{o.runNo}</Fact>
        <Fact label="Order">
          {o.orderId}
          {o.symbol ? (
            <>
              {' · '}
              <Link to="/stock/$symbol" params={{ symbol: o.symbol }} className="text-accent">
                {o.symbol} quote
              </Link>
            </>
          ) : null}
        </Fact>
      </dl>
    </div>
  )
}

/**
 * One order of a player's History: placed time (ET), the order line, status, average fill and fees, its source and
 * the first line of the note; tapping it shows the rest (fills, replace chain, group, run).
 */
export function OrderHistoryRow({ o }: { o: PublicOrder }) {
  const [open, setOpen] = useState(false)
  const note = o.untrusted?.rationale
  return (
    <li className="px-4 py-2.5">
      <button type="button" aria-expanded={open} onClick={() => setOpen((v) => !v)} className="flex w-full items-start gap-3 text-left text-table">
        <span className="w-16 shrink-0 pt-px text-caption text-text-3 tabular-nums">{formatTimeEt(o.receivedAt).replace(' ET', '')}</span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-1.5">
            <span className={cx('font-semibold', o.side === 'SELL' ? 'text-down' : 'text-up')}>{o.side === 'SELL' ? 'Sell' : 'Buy'}</span>
            <span className="font-semibold">{publicOrderLine(o).replace(/^(Buy|Sell) /, '')}</span>
            <StatusPill o={o} />
            <OrderSourcePills o={o} />
          </span>
          <span className="mt-0.5 block text-caption text-text-3">
            {positive(o.filledQty) ? `Filled ${formatQty(o.filledQty)} @ ${formatPrice(o.avgFillPrice)}` : publicStatusLine(o)}
            {nonZero(o.fees) ? ` · fees ${formatMoney(o.fees)}` : ''}
          </span>
          {note && !open ? <RationaleQuote text={note} clamp className="mt-1" /> : null}
        </span>
        <IconChevronDown size={16} className={cx('mt-0.5 shrink-0 text-text-3 transition-transform', open && 'rotate-180')} />
      </button>
      {open ? <OrderDetail o={o} /> : null}
    </li>
  )
}

type Entry = { kind: 'run'; runNo: number } | { kind: 'order'; o: PublicOrder }

/** Day groups (ET) of the loaded orders, with a "Run #N" divider where the run changes. */
export function groupOrders(orders: ReadonlyArray<PublicOrder>, nowMs: number): Array<{ label: string; entries: Entry[] }> {
  const out: Array<{ label: string; entries: Entry[] }> = []
  let run: number | null = null
  for (const o of orders) {
    const label = feedDayLabel(o.receivedAt, nowMs)
    let group = out.at(-1)
    if (!group || group.label !== label) {
      group = { label, entries: [] }
      out.push(group)
    }
    if (run !== null && o.runNo !== run) group.entries.push({ kind: 'run', runNo: o.runNo })
    run = o.runNo
    group.entries.push({ kind: 'order', o })
  }
  return out
}

const STATUS_OPTIONS = [
  { value: 'ALL', label: 'All' },
  { value: 'OPEN', label: 'Open' },
  { value: 'CLOSED', label: 'Closed' },
] as const

/**
 * A player's order history (public mode, §16.7; profile → History → Orders): every stored order of every run, newest
 * first and grouped by ET day, 25 at a time. Filters: All / Open / Closed and a symbol. Orders rejected at entry
 * were never stored, so they are not here. Polls every 30 s while visible.
 */
export function PlayerOrderHistory({
  handle,
  status = 'ALL',
  symbol,
  onFilter,
}: {
  handle: string
  status?: OrdersFilter
  symbol?: string
  onFilter: (f: { status?: OrdersFilter; symbol?: string }) => void
}) {
  const q = usePlayerOrders(handle, { status, symbol })
  const [text, setText] = useState(symbol ?? '')
  const typed = useDebounced(text.trim().toUpperCase(), 300)
  useEffect(() => {
    if ((typed || undefined) !== symbol) onFilter({ status, symbol: typed || undefined })
  }, [typed, symbol, status, onFilter])
  const [now] = useState(() => Date.now())
  const orders = useMemo(() => q.data?.pages.flatMap((p) => p.items ?? []) ?? [], [q.data])
  const groups = useMemo(() => groupOrders(orders, now), [orders, now])
  const filtered = status !== 'ALL' || Boolean(symbol)

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Segmented label="Order status" size="sm" value={status} onChange={(s) => onFilter({ status: s, symbol })} options={STATUS_OPTIONS} />
        <label className="flex h-11 items-center gap-2 rounded-md border border-border bg-bg-2 px-3 tablet:h-8">
          <span className="text-caption text-text-3">Symbol</span>
          <input
            value={text}
            onChange={(e) => setText(e.target.value.toUpperCase())}
            placeholder="Any"
            maxLength={12}
            autoCapitalize="characters"
            aria-label="Filter by symbol"
            className="w-20 bg-transparent text-body text-text-1 outline-none placeholder:text-text-3"
          />
        </label>
      </div>
      <div className="-mx-4 bg-bg-1 tablet:mx-0 tablet:rounded-lg tablet:border tablet:border-divider">
        {q.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={4} cols={4} />
          </div>
        ) : q.isError && !orders.length ? (
          <p role="alert" className="flex flex-wrap items-center justify-center gap-2 px-4 py-6 text-caption text-text-2">
            Orders are unavailable right now.
            <Button size="sm" onClick={() => void q.refetch()}>
              Retry
            </Button>
          </p>
        ) : orders.length === 0 ? (
          <EmptyState title={filtered ? 'No orders match.' : 'No orders yet.'} body={filtered ? 'Try another status or symbol.' : undefined} />
        ) : (
          <>
            <ul aria-label="Orders">
              {groups.map((g) => (
                <li key={g.label}>
                  <h3 className="border-b border-divider bg-bg-1 px-4 py-1.5 text-micro font-semibold tracking-wide text-text-3 uppercase">{g.label}</h3>
                  <ul className="divide-y divide-divider">
                    {g.entries.map((e, i) =>
                      e.kind === 'run' ? (
                        <li key={`run-${e.runNo}-${i}`} className="bg-bg-2 px-4 py-1 text-micro font-semibold text-text-2">
                          Run #{e.runNo}
                        </li>
                      ) : (
                        <Fragment key={e.o.orderId}>
                          <OrderHistoryRow o={e.o} />
                        </Fragment>
                      ),
                    )}
                  </ul>
                </li>
              ))}
            </ul>
            <div className="flex flex-col items-center gap-1 border-t border-divider p-3">
              {q.hasNextPage ? (
                <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
                  {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
                </Button>
              ) : (
                <p className="text-micro text-text-3">That&apos;s everything.</p>
              )}
              {q.isFetchNextPageError ? (
                <p role="alert" className="text-micro text-down">
                  The next page did not load. Try again.
                </p>
              ) : null}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
