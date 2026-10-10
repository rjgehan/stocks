import type { Preview } from '@/lib/api/types'
import { isDecimal, isZero } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { formatTimeEt } from '@/lib/format/time'
import { Skeleton } from '@/components/ui/Skeleton'
import { QualityBadge } from '@/features/market/QualityBadge'

function Row({ label, value, strong }: { label: string; value: React.ReactNode; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1">
      <dt className="text-text-2">{label}</dt>
      <dd className={strong ? 'font-semibold text-text-1' : 'text-text-1'}>{value}</dd>
    </div>
  )
}

/** Estimates from POST /orders/preview (§22.5): price, qty for notional, principal, fees, hold, BP before → after, session note. */
export function EstimatesPanel({
  preview,
  loading,
  notional,
  stop = false,
  highlighted = false,
}: {
  preview: Preview | undefined
  loading: boolean
  notional: boolean
  /** A stop order: the estimate is priced at the trigger, not at today's touch. */
  stop?: boolean
  /** Some preview error is shown on an input; otherwise the ticket's general error list explains what is wrong. */
  highlighted?: boolean
}) {
  const est = preview?.estimate
  if (!preview && loading) {
    return (
      <div className="space-y-2 py-1" aria-label="Loading estimate">
        <Skeleton className="h-3.5 w-full" />
        <Skeleton className="h-3.5 w-4/5" />
        <Skeleton className="h-3.5 w-3/5" />
      </div>
    )
  }
  if (!preview) return null
  if (!est) {
    return preview.valid || !highlighted ? null : <p className="py-1 text-caption text-text-3">Fix the highlighted fields to see the estimate.</p>
  }
  const fees = est.fees
  const hold = est.hold && isDecimal(est.hold) && !isZero(est.hold) ? est.hold : null
  return (
    <dl className="text-table">
      <Row
        label={stop ? 'Est. price at trigger' : 'Est. price'}
        value={
          <span className="inline-flex items-center gap-1.5">
            {formatPrice(est.price)}
            <QualityBadge quality={est.quoteQuality === 'REALTIME_SINGLE_VENUE' ? null : est.quoteQuality} />
          </span>
        }
      />
      {notional ? <Row label="Est. shares" value={formatQty(est.qty)} /> : null}
      <Row label="Est. principal" value={formatMoney(est.principal)} />
      <Row
        label="Fees"
        value={
          <span title={`SEC ${formatMoney(fees?.sec)} · TAF ${formatMoney(fees?.taf)} · CAT ${formatMoney(fees?.cat)}`}>
            {formatMoney(fees?.total)}
            <span className="ml-1 text-micro text-text-3">
              SEC {fees?.sec ?? '--'} · TAF {fees?.taf ?? '--'} · CAT {fees?.cat ?? '--'}
            </span>
          </span>
        }
      />
      {hold ? <Row label="Cash held" value={formatMoney(hold)} /> : null}
      <Row
        label="Buying power"
        strong
        value={
          <>
            {formatMoney(preview.buyingPowerBefore)} <span className="text-text-3">→</span> {formatMoney(preview.buyingPowerAfter)}
          </>
        }
      />
      {est.quoteAsOf ? <p className="pt-0.5 text-right text-micro text-text-3">Quote {formatTimeEt(est.quoteAsOf)}</p> : null}
      {preview.sessionNote ? <p className="pt-1 text-caption text-session-ext">{preview.sessionNote}</p> : null}
    </dl>
  )
}
