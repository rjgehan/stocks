import { useState } from 'react'
import { describeError, errorBody } from '@/lib/api/errors'
import { useClosePosition, usePositions } from '@/lib/api/hooks/account'
import { isDecimal, sign } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPct, formatPrice, formatQty } from '@/lib/format/number'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { toast } from '@/features/notifications/toastStore'
import { newClientOrderId } from '@/features/trade/ticketState'
import { OrdersTable } from './OrdersTable'

/** "My position" and "my open orders" on the quote page (§22.4), with Close position (§11.8). */
export function MyPositionCard({ symbol }: { symbol: string }) {
  const { data } = usePositions()
  const close = useClosePosition()
  const [confirming, setConfirming] = useState(false)
  const [clientOrderId, setClientOrderId] = useState(newClientOrderId)
  const p = data?.find((x) => x.symbol === symbol)
  const available = p?.availableQty && isDecimal(p.availableQty) && sign(p.availableQty) > 0 ? p.availableQty : null

  const doClose = () =>
    close.mutate(
      { symbol, clientOrderId },
      {
        onSuccess: () => {
          setConfirming(false)
          setClientOrderId(newClientOrderId())
          toast({ tone: 'down', title: `Sell ${formatQty(available)} ${symbol} Market · Pending` })
        },
        onError: (e) => {
          const d = describeError(e)
          toast({ tone: 'error', title: d.title, body: errorBody(d) })
        },
      },
    )

  return (
    <section className="rounded-lg border border-divider bg-bg-1">
      <div className="flex items-center justify-between px-4 pt-3">
        <h2 className="text-section font-semibold">My position</h2>
        {available ? (
          <Button size="sm" variant="danger" onClick={() => setConfirming(true)}>
            Close position
          </Button>
        ) : null}
      </div>
      {p ? (
        <dl className="grid grid-cols-2 gap-x-6 px-4 py-2 text-table tablet:grid-cols-3">
          {(
            [
              ['Shares', formatQty(p.qty)],
              ['Avg cost', formatPrice(p.avgCost)],
              ['Market value', formatMoney(p.marketValue)],
              [
                'Day P&L',
                <span key="d" className={DIRECTION_TEXT[direction(p.dayPnl)]}>
                  {formatMoney(p.dayPnl, { signed: true })}
                </span>,
              ],
              [
                'Total P&L',
                <span key="t" className={cx(DIRECTION_TEXT[direction(p.unrealizedPnl)])}>
                  {formatMoney(p.unrealizedPnl, { signed: true })} ({formatPct(p.unrealizedReturn)})
                </span>,
              ],
              ['Portfolio %', formatPct(p.weight, { signed: false })],
            ] as const
          ).map(([k, v]) => (
            <div key={k} className="flex justify-between border-b border-divider py-1.5">
              <dt className="text-text-2">{k}</dt>
              <dd className="font-medium">{v}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <p className="px-4 py-3 text-caption text-text-3">You don't own {symbol} yet.</p>
      )}
      <h3 className="px-4 pt-2 text-caption font-semibold tracking-wide text-text-2 uppercase">Open orders</h3>
      <OrdersTable view="OPEN" symbol={symbol} emptyHint={`Working ${symbol} orders show up here with a Cancel button.`} />
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        onConfirm={doClose}
        pending={close.isPending}
        tone="sell"
        title={`Close your ${symbol} position?`}
        confirmLabel={`Sell ${formatQty(available)} ${symbol}`}
      >
        This places a <strong className="text-text-1">market sell</strong> for all {formatQty(available)} available shares at the current bid. Fractional shares only sell during regular hours; outside them
        the order waits for the open.
      </ConfirmDialog>
    </section>
  )
}
