import type { PlaceOrderRequest, Preview } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { EstimatesPanel } from './EstimatesPanel'

const TYPE_LABEL: Record<PlaceOrderRequest['type'], string> = {
  MARKET: 'Market',
  LIMIT: 'Limit',
  STOP: 'Stop',
  STOP_LIMIT: 'Stop Limit',
  TRAILING_STOP: 'Trailing Stop',
}

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
    ['Side', buy ? 'Buy' : 'Sell'],
    ['Order type', TYPE_LABEL[request.type]],
    [request.notional ? 'Amount' : 'Shares', request.notional ? formatMoney(request.notional) : formatQty(request.qty)],
  ]
  if (request.limitPrice) rows.push(['Limit price', formatPrice(request.limitPrice)])
  if (request.stopPrice) rows.push(['Stop price', formatPrice(request.stopPrice)])
  if (request.trailAmount) rows.push(['Trail', formatMoney(request.trailAmount)])
  if (request.trailPercent) rows.push(['Trail', `${request.trailPercent}%`])
  rows.push(['Time in force', request.timeInForce === 'GTC' ? 'GTC (60 days)' : 'Day'])
  if (request.extendedHours) rows.push(['Extended hours', 'Yes'])
  if (request.rationale) rows.push(['Note', request.shareNote ? 'Shared publicly' : 'Private'])

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Review ${buy ? 'buy' : 'sell'} ${request.symbol}`}
      footer={
        <div className="flex gap-2">
          <Button variant="secondary" size="lg" className="flex-1" onClick={onClose}>
            Edit
          </Button>
          <Button variant={buy ? 'buy' : 'sell'} size="lg" className="flex-[2]" onClick={onConfirm} disabled={pending}>
            {pending ? 'Sending…' : `Confirm ${buy ? 'buy' : 'sell'}`}
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
        <div className="rounded-md bg-bg-2/60 px-3 py-2">
          <EstimatesPanel preview={preview} loading={false} notional={Boolean(request.notional)} />
        </div>
        {preview.warnings.map((w) => (
          <p key={w.code} className="rounded-sm bg-warn/10 p-2 text-caption text-warn">
            {w.message}
          </p>
        ))}
        {request.rationale ? <p className="rounded-sm bg-bg-2 p-2 text-caption text-text-2">“{request.rationale}”</p> : null}
      </div>
    </Dialog>
  )
}
