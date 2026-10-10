import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuote, useQuotesSeed } from '@/lib/realtime/useQuote'
import { formatPrice } from '@/lib/format/number'
import { Tabs } from '@/components/ui/Tabs'
import { PagePlaceholder } from '@/components/PagePlaceholder'
import { Change } from '@/features/market/Change'
import { INDEX_SYMBOLS } from '@/features/market/IndexStrip'

type MarketsTab = 'MOVERS' | 'NEWS'

export const Route = createFileRoute('/_app/markets')({
  validateSearch: (search: Record<string, unknown>): { tab?: MarketsTab } => ({
    tab: search.tab === 'NEWS' ? 'NEWS' : search.tab === 'MOVERS' ? 'MOVERS' : undefined,
  }),
  staticData: { chrome: 'watchlist', phone: 'root', title: 'Markets' },
  component: MarketsPage,
})

function IndexCard({ symbol }: { symbol: string }) {
  const q = useQuote(symbol, 'tick', { seed: false })
  return (
    <Link to="/stock/$symbol" params={{ symbol }} className="rounded-lg border border-divider bg-bg-1 p-3 hover:border-border">
      <div className="text-caption font-semibold text-text-2">{symbol}</div>
      <div className="text-[18px] leading-6 font-semibold">{formatPrice(q?.last)}</div>
      <Change change={q?.change} pct={q?.changePct} reference={q?.last} className="text-caption" />
    </Link>
  )
}

/** Markets (§22.4): index cards, movers tabs (Gainers / Losers / Most Active), news if a source exists. M3. */
function MarketsPage() {
  const { tab = 'MOVERS' } = Route.useSearch()
  const navigate = Route.useNavigate()
  useQuotesSeed(INDEX_SYMBOLS)
  return (
    <PagePlaceholder title="Markets" milestone="M3" description="Index cards are live. Movers (Gainers · Losers · Most Active) and news land with WS2's /market/movers and /news." layout="table">
      <div className="grid grid-cols-2 gap-3 desktop:grid-cols-4">
        {INDEX_SYMBOLS.map((s) => (
          <IndexCard key={s} symbol={s} />
        ))}
      </div>
      <Tabs
        label="Markets sections"
        value={tab}
        onChange={(t) => void navigate({ search: { tab: t }, replace: true })}
        items={[
          { value: 'MOVERS', label: 'Movers' },
          { value: 'NEWS', label: 'News' },
        ]}
      />
    </PagePlaceholder>
  )
}
