import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import type { ChartRange, Side, Timeframe } from '@/lib/api/types'
import { getLastSymbol } from '@/lib/prefs/local'
import { useBreakpoint } from '@/app/useBreakpoint'
import { StockScreen, type QuoteTab } from '@/features/market/StockScreen'
import { WatchlistPanel } from '@/features/watchlist/WatchlistPanel'

export const Route = createFileRoute('/_app/watchlist')({
  validateSearch: (search: Record<string, unknown>): { list?: string } => ({
    list: typeof search.list === 'string' ? search.list : undefined,
  }),
  staticData: { chrome: 'watchlist', phone: 'root', title: 'Watchlist' },
  component: WatchlistRoute,
})

/** Phone: the Watchlist tab. Tablet/desktop: the panel is always visible and the center shows the last symbol (§22.2). */
function WatchlistRoute() {
  const bp = useBreakpoint()
  const [symbol] = useState(getLastSymbol)
  const [range, setRange] = useState<ChartRange>('1D')
  const [tf, setTf] = useState<Timeframe | undefined>(undefined)
  const [tab, setTab] = useState<QuoteTab>('OVERVIEW')
  const [ticket, setTicket] = useState<Side | undefined>(undefined)
  if (bp === 'phone') return <WatchlistPanel variant="screen" />
  return (
    <StockScreen
      symbol={symbol}
      breakpoint={bp}
      range={range}
      tf={tf}
      tab={tab}
      ticket={ticket}
      onRange={(r) => (setRange(r), setTf(undefined))}
      onTf={setTf}
      onTab={setTab}
      onTicket={setTicket}
    />
  )
}
