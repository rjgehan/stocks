import type { Asset, Quote } from '@/lib/api/types'
import { abbreviate, formatDecimal, formatPct, formatPrice } from '@/lib/format/number'
import { formatMoneyCompact } from '@/lib/format/money'

/**
 * Key stats (§22.4): open, high, low, previous close, volume ("IEX vol" when partial), 52-week, cap, P/E. A crypto pair
 * has no fundamentals, so its cap and P/E rows are left out rather than shown empty.
 */
export function KeyStatsGrid({ quote, asset }: { quote: Quote | undefined; asset: Asset | undefined }) {
  const f = asset?.fundamentals
  const crypto = asset?.type === 'CRYPTO'
  const stats: Array<[string, string, string?]> = [
    ['Open', formatPrice(quote?.dayOpen)],
    ['High', formatPrice(quote?.dayHigh)],
    ['Low', formatPrice(quote?.dayLow)],
    ['Prev close', formatPrice(quote?.prevClose)],
    [quote?.volumeIsPartial ? 'IEX vol' : 'Volume', abbreviate(quote?.volume), quote?.volumeIsPartial ? 'Volume on the IEX venue only (partial)' : undefined],
    ['52-wk range', asset?.week52Low && asset.week52High ? `${formatPrice(asset.week52Low)} – ${formatPrice(asset.week52High)}` : '--'],
  ]
  if (!crypto || f?.marketCap) stats.push(['Mkt cap', formatMoneyCompact(f?.marketCap)])
  if (!crypto || f?.peTtm) stats.push(['P/E (TTM)', formatDecimal(f?.peTtm, { scale: 2 })])
  if (f?.dividendYield) stats.push(['Div yield', formatPct(f.dividendYield, { signed: false })])
  if (f?.beta) stats.push(['Beta', formatDecimal(f.beta, { scale: 2 })])
  return (
    <dl className="grid grid-cols-2 gap-x-6 gap-y-0 tablet:grid-cols-4">
      {stats.map(([label, value, title]) => (
        <div key={label} className="flex items-center justify-between gap-2 border-b border-divider py-2 text-table" title={title}>
          <dt className="text-text-2">{label}</dt>
          <dd className="font-medium text-text-1">{value}</dd>
        </div>
      ))}
    </dl>
  )
}
