import { Link } from '@tanstack/react-router'
import { useQuote, useQuotesSeed } from '@/lib/realtime/useQuote'
import { formatPrice } from '@/lib/format/number'
import { Change } from './Change'

export const INDEX_SYMBOLS = ['SPY', 'QQQ', 'DIA', 'IWM'] as const

function IndexCell({ symbol }: { symbol: string }) {
  const q = useQuote(symbol, 'tick', { seed: false })
  return (
    <Link
      to="/stock/$symbol"
      params={{ symbol }}
      className="min-w-0 flex-1 rounded-md px-1 py-1.5 hover:bg-bg-2"
      aria-label={`${symbol} ${formatPrice(q?.last)}`}
    >
      <div className="text-micro font-semibold text-text-2">{symbol}</div>
      <div className="truncate text-caption font-semibold">{formatPrice(q?.last)}</div>
      <Change change={q?.change} pct={q?.changePct} className="text-micro" showArrow={false} pctOnly />
    </Link>
  )
}

/** SPY · QQQ · DIA · IWM mini quotes (§22.4 watchlist index strip). */
export function IndexStrip() {
  useQuotesSeed(INDEX_SYMBOLS)
  return (
    <div className="flex gap-0.5 px-2 py-1.5">
      {INDEX_SYMBOLS.map((s) => (
        <IndexCell key={s} symbol={s} />
      ))}
    </div>
  )
}
