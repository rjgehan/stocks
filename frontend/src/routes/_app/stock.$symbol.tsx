import { createFileRoute, redirect } from '@tanstack/react-router'
import type { ChartRange, Side, Timeframe } from '@/lib/api/types'
import { useBreakpoint } from '@/app/useBreakpoint'
import { parseTimeframe } from '@/features/market/IntervalPicker'
import { CHART_RANGES } from '@/features/market/RangeChips'
import { StockScreen, type QuoteTab } from '@/features/market/StockScreen'
import { fromPath, toPath } from '@/lib/symbols'

interface StockSearch {
  range?: ChartRange
  tf?: Timeframe
  tab?: QuoteTab
  ticket?: Side
}

const TABS: readonly QuoteTab[] = ['OVERVIEW', 'NEWS', 'TRADES', 'POSITION']

export const Route = createFileRoute('/_app/stock/$symbol')({
  // §22.2: range=1D, tf?, tab=OVERVIEW|NEWS|TRADES|POSITION (TRADES: phone and tablet, §22.10), ticket=BUY|SELL?
  validateSearch: (search: Record<string, unknown>): StockSearch => ({
    range: CHART_RANGES.find((r) => r === String(search.range ?? '').toUpperCase()),
    tf: parseTimeframe(search.tf),
    tab: TABS.find((t) => t === String(search.tab ?? '').toUpperCase()),
    ticket: search.ticket === 'BUY' || search.ticket === 'SELL' ? search.ticket : undefined,
  }),
  // The URL carries the upper-case path spelling: `BTC-USD` for the pair `BTC/USD` (a `/` cannot sit in a segment).
  beforeLoad: ({ params, search }) => {
    const canonical = toPath(fromPath(params.symbol))
    if (canonical !== params.symbol) throw redirect({ to: '/stock/$symbol', params: { symbol: canonical }, search, replace: true })
  },
  staticData: { chrome: 'watchlist', phone: 'pushed' },
  component: StockRoute,
})

function StockRoute() {
  const symbol = fromPath(Route.useParams().symbol)
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const bp = useBreakpoint()
  const set = (patch: Partial<StockSearch>) => void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })
  return (
    <StockScreen
      key={symbol}
      symbol={symbol}
      breakpoint={bp}
      range={search.range ?? '1D'}
      tf={search.tf}
      tab={search.tab ?? 'OVERVIEW'}
      ticket={search.ticket}
      // A new range starts from its default interval.
      onRange={(range) => set({ range: range === '1D' ? undefined : range, tf: undefined })}
      onTf={(tf) => set({ tf })}
      onTab={(tab) => set({ tab: tab === 'OVERVIEW' ? undefined : tab })}
      onTicket={(ticket) => set({ ticket })}
    />
  )
}
