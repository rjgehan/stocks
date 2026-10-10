import type { Asset, Quote } from '@/lib/api/types'
import { abbreviate, formatDecimal, formatPrice } from '@/lib/format/number'
import { formatMoneyCompact } from '@/lib/format/money'

/** Key stats (§22.4): open, high, low, previous close, volume ("IEX vol" when partial), 52-week, cap, P/E. */
export function KeyStatsGrid({ quote, asset }: { quote: Quote | undefined; asset: Asset | undefined }) {
  const stats: Array<[string, string]> = [
    ['Open', formatPrice(quote?.dayOpen)],
    ['High', formatPrice(quote?.dayHigh)],
    ['Low', formatPrice(quote?.dayLow)],
    ['Prev close', formatPrice(quote?.prevClose)],
    [quote?.volumeIsPartial ? 'IEX vol' : 'Volume', abbreviate(quote?.volume)],
    ['52-wk range', asset?.week52Low && asset.week52High ? `${formatPrice(asset.week52Low)} – ${formatPrice(asset.week52High)}` : '--'],
    ['Mkt cap', formatMoneyCompact(asset?.fundamentals?.marketCap)],
    ['P/E (TTM)', formatDecimal(asset?.fundamentals?.peTtm, { scale: 2 })],
  ]
  return (
    <dl className="grid grid-cols-2 gap-x-6 gap-y-0 tablet:grid-cols-4">
      {stats.map(([label, value]) => (
        <div key={label} className="flex items-center justify-between gap-2 border-b border-divider py-2 text-table">
          <dt className="text-text-2">{label}</dt>
          <dd className="font-medium text-text-1">{value}</dd>
        </div>
      ))}
    </dl>
  )
}
