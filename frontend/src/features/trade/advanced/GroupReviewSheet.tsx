import { warningText } from '@/lib/api/errors'
import type { GroupPreview, PlaceOrderGroupRequest } from '@/lib/api/groupTypes'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { ORDER_TYPE_LABEL } from '@/features/account/orderLabels'
import { GROUP_TYPE_HELP, GROUP_TYPE_LABEL } from '@/features/groups/groupLabels'

/**
 * A leg's type and prices the way the other legs read: "Limit 11.98", "Stop 12.10", "Stop 12.10 / limit 12.15",
 * "Market".
 */
export function legLine(leg: { type?: string | null; stopPrice?: string | null; limitPrice?: string | null }): string {
  const stop = leg.stopPrice ? formatPrice(leg.stopPrice) : null
  const limit = leg.limitPrice ? formatPrice(leg.limitPrice) : null
  switch (leg.type) {
    case 'MARKET':
      return 'Market'
    case 'LIMIT':
      return `Limit${limit ? ` ${limit}` : ''}`
    case 'STOP':
      return `Stop${stop ? ` ${stop}` : ''}`
    case 'STOP_LIMIT':
      return `Stop ${stop ?? ''} / limit ${limit ?? ''}`.replace(/\s+/g, ' ').trim()
    default: {
      const label = leg.type ? (ORDER_TYPE_LABEL[leg.type as keyof typeof ORDER_TYPE_LABEL] ?? leg.type) : ''
      return [label, stop ? `stop ${stop}` : null, limit ? `limit ${limit}` : null].filter(Boolean).join(' · ')
    }
  }
}

/**
 * The group review sheet (§22.5 Advanced): every leg with its type and prices, the bracket's reward to risk, the
 * buying-power effect and the warnings, then Confirm or Edit.
 */
export function GroupReviewSheet({
  open,
  onClose,
  onConfirm,
  pending,
  request,
  preview,
  rewardToRisk,
}: {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  pending: boolean
  request: PlaceOrderGroupRequest
  preview: GroupPreview
  rewardToRisk: string | null
}) {
  const type = request.type ?? 'BRACKET'
  const legs: Array<[string, string]> = []
  if (request.entry) {
    const e = request.entry
    legs.push(['Entry', `Buy ${formatQty(request.qty)} · ${legLine(e)}`])
  }
  const sellSide = type === 'OCO' ? (request.side === 'BUY' ? 'Buy' : 'Sell') : 'Sell'
  if (request.takeProfit) legs.push(['Take profit', `${sellSide} ${formatQty(request.qty)} · Limit ${formatPrice(request.takeProfit.limitPrice)}`])
  if (request.stopLoss) {
    const s = request.stopLoss
    legs.push(['Stop loss', `${sellSide} ${formatQty(request.qty)} · ${s.limitPrice ? `Stop Limit ${formatPrice(s.stopPrice)} / ${formatPrice(s.limitPrice)}` : `Stop ${formatPrice(s.stopPrice)}`}`])
  }
  if (request.secondary) {
    const s = request.secondary
    legs.push(['Then', `Sell ${formatQty(request.qty)} · ${legLine(s)}`])
  }
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Review ${GROUP_TYPE_LABEL[type]} · ${request.symbol ?? ''}`}
      footer={
        <div className="flex gap-2">
          <Button variant="secondary" size="lg" className="flex-1" onClick={onClose}>
            Edit
          </Button>
          <Button variant="primary" size="lg" className="flex-[2]" onClick={onConfirm} disabled={pending}>
            {pending ? 'Sending…' : `Confirm ${GROUP_TYPE_LABEL[type]}`}
          </Button>
        </div>
      }
    >
      <div className="space-y-3 p-4">
        <p className="text-caption text-text-2">{GROUP_TYPE_HELP[type]}</p>
        <dl className="text-table">
          {legs.map(([k, v]) => (
            <div key={k} className="flex justify-between gap-3 py-1">
              <dt className="shrink-0 text-text-2">{k}</dt>
              <dd className="text-right font-semibold">{v}</dd>
            </div>
          ))}
          <div className="flex justify-between py-1">
            <dt className="text-text-2">Time in force</dt>
            <dd className="font-semibold">{request.timeInForce === 'GTC' ? 'GTC (60 days)' : 'Day'}</dd>
          </div>
          {rewardToRisk ? (
            <div className="flex justify-between py-1">
              <dt className="text-text-2">Risk : reward</dt>
              <dd className="font-semibold">{rewardToRisk}</dd>
            </div>
          ) : null}
          <div className="flex justify-between py-1">
            <dt className="text-text-2">Buying power</dt>
            <dd className="font-semibold">
              {formatMoney(preview.buyingPowerBefore)} <span className="text-text-3">→</span> {formatMoney(preview.buyingPowerAfter)}
            </dd>
          </div>
          {request.doNotReduce ? (
            <div className="flex justify-between py-1">
              <dt className="text-text-2">Dividends</dt>
              <dd className="font-semibold">Don't reduce my prices</dd>
            </div>
          ) : null}
        </dl>
        {(preview.warnings ?? []).map((w) => (
          <p key={`${w.code}-${w.message}`} className="rounded-sm bg-warn/10 p-2 text-caption text-warn">
            {warningText(w)}
          </p>
        ))}
        {request.rationale ? <p className="rounded-sm bg-bg-2 p-2 text-caption text-text-2">“{request.rationale}”</p> : null}
        <p className="text-micro text-text-3">
          {type === 'OCO'
            ? 'Both orders work now; the first to execute (even partly) cancels the other. Paper trading only.'
            : 'The follow-up orders wait until the entry fills, then work for the shares it bought. Stops are not guaranteed prices. Paper trading only.'}
        </p>
      </div>
    </Dialog>
  )
}
