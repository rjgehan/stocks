import { useClock } from '@/lib/api/hooks/market'

/** Data attribution (§22.3). In SIM/mock mode it says the prices are simulated. */
export function DataSourceFooter() {
  const { data } = useClock()
  const simulated = data?.feed.quality === 'SIMULATED'
  return (
    <footer className="flex h-6 shrink-0 items-center justify-between gap-4 border-t border-divider bg-bg-1 px-3 text-micro text-text-3">
      <span className="truncate">
        {simulated ? 'Market data: simulated (SIM), not real prices' : 'Market data: IEX via Alpaca · Fundamentals: Finnhub'}
      </span>
      <span className="hidden shrink-0 tablet:inline">Paper trading only · not investment advice</span>
    </footer>
  )
}
