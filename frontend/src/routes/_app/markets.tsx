import { Link, createFileRoute } from '@tanstack/react-router'
import { useMovers } from '@/lib/api/hooks/market'
import type { MoverList } from '@/lib/api/types'
import { useQuote, useQuotesSeed } from '@/lib/realtime/useQuote'
import { abbreviate, direction, formatPct, formatPrice } from '@/lib/format/number'
import { formatTimeEt } from '@/lib/format/time'
import { EmptyState } from '@/components/ui/EmptyState'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { cx } from '@/components/ui/cx'
import { Change } from '@/features/market/Change'
import { INDEX_SYMBOLS } from '@/features/market/IndexStrip'
import { NewsList } from '@/features/market/NewsList'

type MarketsTab = 'MOVERS' | 'NEWS'

export const Route = createFileRoute('/_app/markets')({
  validateSearch: (search: Record<string, unknown>): { tab?: MarketsTab; list?: MoverList } => ({
    tab: search.tab === 'NEWS' ? 'NEWS' : search.tab === 'MOVERS' ? 'MOVERS' : undefined,
    list: search.list === 'LOSERS' || search.list === 'ACTIVES' || search.list === 'GAINERS' ? search.list : undefined,
  }),
  staticData: { chrome: 'watchlist', phone: 'root', title: 'Markets' },
  component: MarketsPage,
})

const INDEX_NAMES: Record<string, string> = { SPY: 'S&P 500', QQQ: 'Nasdaq 100', DIA: 'Dow 30', IWM: 'Russell 2000' }

function IndexCard({ symbol }: { symbol: string }) {
  const q = useQuote(symbol, 'tick', { seed: false })
  const dir = direction(q?.change)
  return (
    <Link
      to="/stock/$symbol"
      params={{ symbol }}
      className={cx('rounded-lg border bg-bg-1 p-3 transition-colors hover:border-border', dir === 'up' ? 'border-up/25' : dir === 'down' ? 'border-down/25' : 'border-divider')}
    >
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-caption font-semibold text-text-1">{symbol}</span>
        <span className="truncate text-micro text-text-3">{INDEX_NAMES[symbol]}</span>
      </div>
      <div className="mt-1 text-[18px] leading-6 font-semibold">{formatPrice(q?.last)}</div>
      <Change change={q?.change} pct={q?.changePct} reference={q?.last} className="text-caption" />
    </Link>
  )
}

function Movers({ list }: { list: MoverList }) {
  const { data, isPending, isError } = useMovers(list)
  if (isPending) return <SkeletonRows rows={8} cols={4} />
  const items = (data?.items ?? []).filter((m) => m.symbol)
  if (isError || !items.length) return <EmptyState title="Movers are unavailable" body="The market-data provider has no movers list right now." />
  return (
    <>
      <ol className="divide-y divide-divider">
        {items.map((m, i) => (
          <li key={m.symbol}>
            <Link to="/stock/$symbol" params={{ symbol: m.symbol! }} className="flex min-h-14 items-center gap-3 px-1 py-2 hover:bg-bg-2">
              <span className="w-6 shrink-0 text-center text-caption text-text-3">{i + 1}</span>
              <span className="min-w-0 flex-1">
                <span className="block font-semibold">{m.symbol}</span>
                <span className="block truncate text-caption text-text-3">{m.name ?? ''}</span>
              </span>
              {list === 'ACTIVES' ? <span className="hidden w-20 text-right text-caption text-text-2 tablet:block">Vol {abbreviate(m.volume)}</span> : null}
              <span className="w-20 text-right font-semibold">{formatPrice(m.price)}</span>
              <span
                className={cx(
                  'w-[76px] shrink-0 rounded-sm py-1 text-center text-caption font-semibold',
                  direction(m.changePct) === 'up' ? 'bg-up text-on-updown' : direction(m.changePct) === 'down' ? 'bg-down text-on-updown' : 'bg-bg-3',
                )}
              >
                {formatPct(m.changePct)}
              </span>
            </Link>
          </li>
        ))}
      </ol>
      {data?.asOf ? (
        <p className="pt-2 text-micro text-text-3">
          As of {formatTimeEt(data.asOf)}
          {data.source ? ` · ${data.source}` : ''}
        </p>
      ) : null}
    </>
  )
}

/** Markets (§22.4): index cards, movers tabs (Gainers / Losers / Most Active), and news when a source exists. */
function MarketsPage() {
  const { tab = 'MOVERS', list = 'GAINERS' } = Route.useSearch()
  const navigate = Route.useNavigate()
  useQuotesSeed(INDEX_SYMBOLS)
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      <div className="mx-auto w-full max-w-5xl space-y-4 px-4 py-4 desktop:px-6">
        <h1 className="hidden text-[20px] leading-7 font-bold tracking-tight tablet:block">Markets</h1>
        <div className="grid grid-cols-2 gap-3 desktop:grid-cols-4">
          {INDEX_SYMBOLS.map((s) => (
            <IndexCard key={s} symbol={s} />
          ))}
        </div>
        <Tabs
          label="Markets sections"
          value={tab}
          onChange={(t) => void navigate({ search: (prev) => ({ ...prev, tab: t }), replace: true })}
          items={[
            { value: 'MOVERS', label: 'Movers' },
            { value: 'NEWS', label: 'News' },
          ]}
        />
        {tab === 'MOVERS' ? (
          <section className="space-y-3">
            <Segmented
              label="Movers list"
              size="sm"
              value={list}
              onChange={(l) => void navigate({ search: (prev) => ({ ...prev, list: l }), replace: true })}
              options={[
                { value: 'GAINERS', label: 'Gainers' },
                { value: 'LOSERS', label: 'Losers' },
                { value: 'ACTIVES', label: 'Most active' },
              ]}
            />
            <Movers list={list} />
          </section>
        ) : (
          <NewsList symbols="" limit={20} />
        )}
      </div>
    </div>
  )
}
