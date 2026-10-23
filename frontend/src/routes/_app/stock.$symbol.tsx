import { createFileRoute, redirect } from '@tanstack/react-router'
import type { ChartRange, Side, Timeframe } from '@/lib/api/types'
import { useBreakpoint } from '@/app/useBreakpoint'
import { parseTimeframe } from '@/features/market/IntervalPicker'
import { CHART_RANGES } from '@/features/market/RangeChips'
import { StockScreen, type QuoteTab } from '@/features/market/StockScreen'
import { fromPath, toPath } from '@/lib/symbols'
import { isOption, underlyingOf } from '@/lib/options'

interface StockSearch {
  range?: ChartRange
  tf?: Timeframe
  tab?: QuoteTab
  ticket?: Side
  /** §30: the option contract (OCC symbol) selected on the Options tab, which the ticket trades. */
  contract?: string
}

const TABS: readonly QuoteTab[] = ['OVERVIEW', 'OPTIONS', 'NEWS', 'TRADES', 'POSITION']

export const Route = createFileRoute('/_app/stock/$symbol')({
  // §22.2: range=1D, tf?, tab=OVERVIEW|OPTIONS|NEWS|TRADES|POSITION (TRADES: phone and tablet, §22.10), ticket=BUY|SELL?,
  // contract=<OCC>? (§30, with tab=OPTIONS)
  validateSearch: (search: Record<string, unknown>): StockSearch => ({
    range: CHART_RANGES.find((r) => r === String(search.range ?? '').toUpperCase()),
    tf: parseTimeframe(search.tf),
    tab: TABS.find((t) => t === String(search.tab ?? '').toUpperCase()),
    ticket: search.ticket === 'BUY' || search.ticket === 'SELL' ? search.ticket : undefined,
    contract: isOption(String(search.contract ?? '').toUpperCase()) ? String(search.contract).toUpperCase() : undefined,
  }),
  // The URL carries the upper-case path spelling: `BTC-USD` for the pair `BTC/USD` (a `/` cannot sit in a segment).
  beforeLoad: ({ params, search }) => {
    // §30: a contract has no quote page of its own: it opens on its underlying's Options tab
    const occ = params.symbol.toUpperCase()
    if (isOption(occ)) throw redirect({ to: '/stock/$symbol', params: { symbol: underlyingOf(occ) }, search: { ...search, tab: 'OPTIONS', contract: occ }, replace: true })
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
      contract={search.contract}
      // A new range starts from its default interval.
      onRange={(range) => set({ range: range === '1D' ? undefined : range, tf: undefined })}
      onTf={(tf) => set({ tf })}
      // leaving the Options tab drops the selected contract (the ticket trades the stock again)
      onTab={(tab) => set(tab === 'OPTIONS' ? { tab } : { tab: tab === 'OVERVIEW' ? undefined : tab, contract: undefined })}
      onTicket={(ticket) => set({ ticket })}
      // choosing a contract can open the ticket for it in the same navigation (phone and tablet sheets)
      onContract={(contract, ticket) => set(ticket ? { contract, ticket } : { contract })}
    />
  )
}
