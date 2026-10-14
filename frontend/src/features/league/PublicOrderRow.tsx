import { Link } from '@tanstack/react-router'
import type { PublicOrder, WaitReason } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { Pill } from '@/components/ui/Pill'
import { cx } from '@/components/ui/cx'
import { CANCEL_REASON_LABEL, ORDER_TYPE_LABEL, STATUS_LABEL, STATUS_TONE, WAIT_LABEL, orderPriceLabel, orderSizeLabel } from '@/features/account/orderLabels'
import { GROUP_TYPE_LABEL, ROLE_LABEL } from '@/features/groups/groupLabels'

/**
 * Public mode (§16.7): another player's orders, read-only. The labels reuse the account's (`orderLabels`), worded
 * for a third person ("waiting for its price", "Cancelled by the player").
 */

/** "Buy 10 sh AAPL · Limit 227.40 · GTC · Ext" */
export function publicOrderLine(o: PublicOrder): string {
  const side = o.side === 'SELL' ? 'Sell' : 'Buy'
  const type = o.type ? ORDER_TYPE_LABEL[o.type] : ''
  const price = orderPriceLabel(o)
  return [`${side} ${orderSizeLabel(o)} ${o.symbol ?? ''}`, `${type}${price ? ` ${price}` : ''}`, o.timeInForce === 'GTC' ? 'GTC' : null, o.extendedHours ? 'Ext' : null]
    .filter(Boolean)
    .join(' · ')
}

export const publicWaitLabel = (w: WaitReason): string => WAIT_LABEL[w].replace('your price', 'its price')

/** "Working · waiting for its price", "Cancelled by the player", "Filled". */
export function publicStatusLine(o: PublicOrder): string {
  const status = o.status ? STATUS_LABEL[o.status] : 'Unknown'
  if (o.group?.held && (o.status === 'PENDING' || o.status === 'WORKING')) return 'Waiting for entry'
  if (o.waitReason && (o.status === 'PENDING' || o.status === 'WORKING')) return `${status} · ${publicWaitLabel(o.waitReason)}`
  if (o.status === 'CANCELLED' && o.statusReason) {
    if (o.statusReason === 'USER') return 'Cancelled by the player'
    return CANCEL_REASON_LABEL[o.statusReason] ?? `${status} · ${o.statusReason.toLowerCase().replaceAll('_', ' ')}`
  }
  return status
}

/** "Bracket · stop loss · held": a group leg's type, role and whether it waits for its entry. */
export function publicGroupLabel(g: PublicOrder['group']): string | null {
  if (!g?.type || !g.role) return null
  return `${GROUP_TYPE_LABEL[g.type]} · ${ROLE_LABEL[g.role].toLowerCase()}${g.held ? ' · held' : ''}`
}

export function StatusPill({ o }: { o: Pick<PublicOrder, 'status'> }) {
  return <Pill tone={o.status ? STATUS_TONE[o.status] : 'neutral'}>{o.status ? STATUS_LABEL[o.status] : '--'}</Pill>
}

/**
 * "Manual console" (§20.8): with the player's handle, a link to the session on their History (`?session=mbs_…`);
 * without one, a plain pill.
 */
export function ManualConsolePill({ handle, sessionId, className }: { handle?: string; sessionId: string; className?: string }) {
  const pill = (
    <Pill tone="ai" title="Placed from the bot's manual console (prompt out, reply in)">
      Manual console
    </Pill>
  )
  if (!handle) return className ? <span className={cx('inline-flex', className)}>{pill}</span> : pill
  return (
    <Link to="/players/$handle/history" params={{ handle }} search={{ session: sessionId }} aria-label="Manual console: open the session" className={cx('inline-flex rounded-full', className)}>
      {pill}
    </Link>
  )
}

/**
 * The pills after an order line: its group, "via API" (a key or MCP client placed it) and "Manual console" (§20.8),
 * which links to its session when `handle` is given. `manual={false}` leaves that pill to the caller (a row whose
 * line is a button, where a link cannot go).
 */
export function OrderSourcePills({ o, handle, manual = true }: { o: PublicOrder; handle?: string; manual?: boolean }) {
  const group = publicGroupLabel(o.group)
  return (
    <>
      {group ? (
        <Pill tone="neutral" title={o.group?.held ? 'Waits for its entry to fill' : 'Part of a group order'}>
          {group}
        </Pill>
      ) : null}
      {o.manualSessionId ? (
        manual ? (
          <ManualConsolePill handle={handle} sessionId={o.manualSessionId} />
        ) : null
      ) : o.viaApi ? (
        <Pill tone="neutral">via API</Pill>
      ) : null}
    </>
  )
}

/**
 * The player's note: untrusted text (§17.8), shown as written, never as Markdown or links. `clamp`: its first line
 * only, cut with an ellipsis (`truncate`, not `line-clamp-1`: a clamped box with padding shows the top of the next
 * wrapped line through its bottom padding).
 */
export function RationaleQuote({ text, clamp, className }: { text: string; clamp?: boolean; className?: string }) {
  return (
    <p
      className={cx('rounded-sm bg-bg-2 px-2 py-1 text-caption text-text-2', clamp ? 'truncate' : 'break-words whitespace-pre-wrap', className)}
      title="The player's own words (shown as written)"
    >
      “{clamp ? text.split('\n')[0] : text}”
    </p>
  )
}

/**
 * One open order of another player's portfolio: side · size · symbol · type and prices · TIF/ext, the status pill,
 * when it was placed, its group and source, and the note. Read-only: there is no Cancel (`action` is the admin's).
 */
export function PublicOrderRow({ o, handle, action }: { o: PublicOrder; handle?: string; action?: React.ReactNode }) {
  const note = o.untrusted?.rationale
  return (
    <li className="px-4 py-3">
      <div className="flex items-start gap-3 text-table">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className={cx('font-semibold', o.side === 'SELL' ? 'text-down' : 'text-up')}>{o.side === 'SELL' ? 'Sell' : 'Buy'}</span>
            <span className="font-semibold">{publicOrderLine(o).replace(/^(Buy|Sell) /, '')}</span>
            <StatusPill o={o} />
            <OrderSourcePills o={o} handle={handle} />
          </div>
          <p className="mt-0.5 text-caption text-text-3">
            {publicStatusLine(o)} · placed {formatDateTimeEt(o.receivedAt)}
          </p>
        </div>
        {action}
      </div>
      {note ? <RationaleQuote text={note} className="mt-1.5" /> : null}
    </li>
  )
}
