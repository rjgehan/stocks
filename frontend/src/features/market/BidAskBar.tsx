import type { Quote } from '@/lib/api/types'
import { formatInteger, formatPrice } from '@/lib/format/number'

/** Bid × size | ask × size with a proportional size bar (sizes are integers, safe as numbers). */
export function BidAskBar({ quote }: { quote: Quote | undefined }) {
  const bidSize = quote?.bidSize ?? 0
  const askSize = quote?.askSize ?? 0
  const total = bidSize + askSize
  const bidPct = total > 0 ? Math.round((bidSize / total) * 100) : 50
  return (
    <div className="text-table">
      <div className="flex items-center justify-between">
        <span>
          <span className="text-text-3">Bid </span>
          <span className="font-semibold text-up">{formatPrice(quote?.bid)}</span>
          <span className="text-text-3"> × {formatInteger(quote?.bidSize)}</span>
        </span>
        <span>
          <span className="text-text-3">Ask </span>
          <span className="font-semibold text-down">{formatPrice(quote?.ask)}</span>
          <span className="text-text-3"> × {formatInteger(quote?.askSize)}</span>
        </span>
      </div>
      <div className="mt-1.5 flex h-1.5 overflow-hidden rounded-full bg-bg-3" aria-hidden="true">
        <div className="bg-up/80 transition-[width] duration-300" style={{ width: `${bidPct}%` }} />
        <div className="flex-1 bg-down/80" />
      </div>
    </div>
  )
}
