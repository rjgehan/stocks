import { warningText } from '@/lib/api/errors'
import type { PlaceOrderRequest, Preview } from '@/lib/api/types'
import { formatMoney, formatTrailAmount } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { ORDER_TYPE_LABEL } from '@/features/account/orderLabels'
import { EstimatesPanel } from './EstimatesPanel'
import { isStopType, stopExplanation, withoutQueueDuplicates } from './ticketState'
import { isCrypto } from '@/lib/symbols'

/** Review sheet (§22.5, on by default via `confirmOrders`): every field and warning, then Confirm or Edit. */
export function OrderReviewSheet({
  open,
  onClose,
  onConfirm,
  pending,
  request,
  preview,
}: {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  pending: boolean
  request: PlaceOrderRequest
  preview: Preview
}) {
  const buy = request.side === 'BUY'
  const rows: Array<[string, string]> = [
    ['Symbol', request.symbol ?? ''],
    ['Side', buy ? 'Buy' : 'Sell'],
    ['Order type', request.type ? ORDER_TYPE_LABEL[request.type] : ''],
    [request.notional ? 'Amount' : isCrypto(request.symbol) ? 'Quantity' : 'Shares', request.notional ? formatMoney(request.notional) : formatQty(request.qty)],
  ]
  if (request.stopPrice) rows.push(['Stop price', formatPrice(request.stopPrice)])
  if (request.limitPrice) rows.push(['Limit price', formatPrice(request.limitPrice)])
  if (request.trailAmount) rows.push(['Trail', `${formatTrailAmount(request.trailAmount)} ${buy ? 'above the low' : 'below the high'}`])
  if (request.trailPercent) rows.push(['Trail', `${request.trailPercent}% ${buy ? 'above the low' : 'below the high'}`])
  rows.push(['Time in force', request.timeInForce === 'GTC' ? 'GTC (60 days)' : 'Day'])
  const explanation = request.type && request.side && isStopType(request.type) ? stopExplanation({ ...request, side: request.side, type: request.type, symbol: request.symbol ?? '' }) : null
  if (request.extendedHours) rows.push(['Extended hours', 'Yes'])
  if (request.doNotReduce) rows.push(['Dividends', "Don't reduce my prices"])
  if (request.rationale) rows.push(['Note', 'Public'])
  const side = buy ? 'buy' : 'sell'

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Review ${side} ${request.symbol ?? ''}`}
      footer={
        <div className="flex gap-2">
          <Button variant="secondary" size="lg" className="flex-1" onClick={onClose}>
            Edit
          </Button>
          <Button variant={buy ? 'buy' : 'sell'} size="lg" className="flex-[2]" onClick={onConfirm} disabled={pending}>
            {pending ? 'Sending…' : `Confirm ${side}`}
          </Button>
        </div>
      }
    >
      <div className="space-y-3 p-4">
        <dl className="text-table">
          {rows.map(([k, v]) => (
            <div key={k} className="flex justify-between py-1">
              <dt className="text-text-2">{k}</dt>
              <dd className="font-semibold">{v}</dd>
            </div>
          ))}
        </dl>
        {explanation ? <p className="rounded-sm bg-accent-bg px-2 py-1.5 text-caption text-text-1">{explanation}</p> : null}
        <div className="rounded-md bg-bg-2/60 px-3 py-2">
          <EstimatesPanel preview={preview} loading={false} notional={Boolean(request.notional)} stop={isStopType(request.type)} />
        </div>
        {withoutQueueDuplicates(preview.warnings ?? [], preview.sessionNote).map((w) => (
          <p key={w.code} className="rounded-sm bg-warn/10 p-2 text-caption text-warn">
            {warningText(w)}
          </p>
        ))}
        {request.rationale ? <p className="rounded-sm bg-bg-2 p-2 text-caption text-text-2">“{request.rationale}”</p> : null}
        <p className="text-micro text-text-3">
          {isStopType(request.type)
            ? 'A stop is not a guaranteed price: once triggered, the order fills at the next available price (a stop limit may not fill). Paper trading only.'
            : 'Market orders fill at the best available price, which may differ from the estimate. Paper trading only.'}
        </p>
      </div>
    </Dialog>
  )
}
