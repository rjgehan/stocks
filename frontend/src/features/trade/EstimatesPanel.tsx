import type { Preview } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { Skeleton } from '@/components/ui/Skeleton'

function Row({ label, value, strong }: { label: string; value: React.ReactNode; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1">
      <dt className="text-text-2">{label}</dt>
      <dd className={strong ? 'font-semibold text-text-1' : 'text-text-1'}>{value}</dd>
    </div>
  )
}

/** Estimates from POST /orders/preview (§22.5): price, qty for notional, principal, fees, hold, BP before → after. */
export function EstimatesPanel({ preview, loading, notional }: { preview: Preview | undefined; loading: boolean; notional: boolean }) {
  const est = preview?.estimate
  if (!preview && loading) {
    return (
      <div className="space-y-2 py-1">
        <Skeleton className="h-3.5 w-full" />
        <Skeleton className="h-3.5 w-4/5" />
        <Skeleton className="h-3.5 w-3/5" />
      </div>
    )
  }
  if (!est) return null
  const fees = est.fees
  return (
    <dl className="text-table">
      <Row label="Est. price" value={formatPrice(est.price)} />
      {notional ? <Row label="Est. shares" value={formatQty(est.qty)} /> : null}
      <Row label="Est. principal" value={formatMoney(est.principal)} />
      <Row
        label="Fees"
        value={
          <span title={`SEC ${formatMoney(fees.sec)} · TAF ${formatMoney(fees.taf)} · CAT ${formatMoney(fees.cat)}`}>
            {formatMoney(fees.total)}
            <span className="ml-1 text-micro text-text-3">SEC {fees.sec} · TAF {fees.taf} · CAT {fees.cat}</span>
          </span>
        }
      />
      {est.hold !== '0.00' ? <Row label="Hold" value={formatMoney(est.hold)} /> : null}
      <Row
        label="Buying power"
        strong
        value={
          <>
            {formatMoney(preview.buyingPowerBefore)} <span className="text-text-3">→</span> {formatMoney(preview.buyingPowerAfter)}
          </>
        }
      />
      {preview.sessionNote ? <p className="pt-1 text-caption text-session-ext">{preview.sessionNote}</p> : null}
    </dl>
  )
}
