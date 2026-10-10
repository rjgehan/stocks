import { useMemo, useState } from 'react'
import { useAddToWatchlist, useWatchlists } from '@/lib/api/hooks/watchlists'
import { usePositions } from '@/lib/api/hooks/account'
import { useQuotesSeed } from '@/lib/realtime/useQuote'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconStar } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { IndexStrip } from '@/features/market/IndexStrip'
import { type PillMode, WatchlistRow } from './WatchlistRow'

export const POPULAR = ['AAPL', 'NVDA', 'MSFT', 'TSLA', 'AMZN'] as const
const HOLDINGS = '__holdings__'

/**
 * The watchlist (§22.4): list tabs ("My List", an automatic "Holdings" list, custom lists), the index
 * strip, streaming rows and the "Add 5 popular stocks" empty state. Used as the desktop left panel, the
 * tablet drawer and the phone Watchlist tab.
 */
export function WatchlistPanel({ activeSymbol, variant = 'panel' }: { activeSymbol?: string; variant?: 'panel' | 'screen' }) {
  const lists = useWatchlists()
  const positions = usePositions()
  const [selected, setSelected] = useState(() => readLocal('stocks.watchlist.tab', ''))
  const [pillMode, setPillMode] = useState<PillMode>(() => (readLocal('stocks.watchlist.pill', 'pct') === 'change' ? 'change' : 'pct'))
  const addSymbol = useAddToWatchlist()

  const tabs = useMemo(() => {
    const l = lists.data ?? []
    const [first, ...rest] = l
    return [
      ...(first ? [{ id: first.id, name: first.name }] : []),
      { id: HOLDINGS, name: 'Holdings' },
      ...rest.map((w) => ({ id: w.id, name: w.name })),
    ]
  }, [lists.data])

  const currentId = tabs.some((t) => t.id === selected) ? selected : (tabs[0]?.id ?? '')
  const symbols = useMemo(() => {
    if (currentId === HOLDINGS) return (positions.data ?? []).map((p) => p.symbol)
    return lists.data?.find((w) => w.id === currentId)?.symbols ?? []
  }, [currentId, lists.data, positions.data])

  useQuotesSeed(symbols)

  const cyclePill = () => {
    const next: PillMode = pillMode === 'pct' ? 'change' : 'pct'
    setPillMode(next)
    writeLocal('stocks.watchlist.pill', next)
  }
  const select = (id: string) => {
    setSelected(id)
    writeLocal('stocks.watchlist.tab', id)
  }
  const addPopular = async () => {
    if (!currentId || currentId === HOLDINGS) return
    for (const symbol of POPULAR) await addSymbol.mutateAsync({ id: currentId, symbol })
  }

  const loading = lists.isPending || (currentId === HOLDINGS && positions.isPending)
  return (
    <section aria-label="Watchlist" className="flex h-full min-h-0 flex-col bg-bg-1">
      {variant === 'panel' ? (
        <div className="flex h-11 shrink-0 items-center justify-between px-3">
          <h2 className="text-section font-semibold">Watchlist</h2>
        </div>
      ) : null}
      <div className="shrink-0 border-b border-divider">
        <IndexStrip />
      </div>
      <div role="tablist" aria-label="Lists" className="scrollbar-none flex shrink-0 gap-1 overflow-x-auto border-b border-divider px-2 py-1.5">
        {tabs.map((t) => (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={t.id === currentId}
            onClick={() => select(t.id)}
            className={cx(
              'h-7 shrink-0 rounded-sm px-2.5 text-caption font-semibold transition-colors',
              t.id === currentId ? 'bg-bg-3 text-text-1' : 'text-text-2 hover:bg-bg-2 hover:text-text-1',
            )}
          >
            {t.name}
          </button>
        ))}
      </div>
      <div className="flex h-7 shrink-0 items-center justify-between px-3 text-micro font-medium uppercase tracking-wide text-text-3">
        <span>Symbol</span>
        <span className="flex gap-6">
          <span>Last</span>
          <button type="button" onClick={cyclePill} className="w-[76px] text-right uppercase hover:text-text-1">
            {pillMode === 'pct' ? 'Chg %' : 'Chg $'}
          </button>
        </span>
      </div>
      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
        {loading ? (
          <div className="px-3">
            <SkeletonRows rows={7} cols={3} />
          </div>
        ) : symbols.length === 0 ? (
          currentId === HOLDINGS ? (
            <EmptyState title="No holdings yet" body="Positions you buy appear here automatically." />
          ) : (
            <EmptyState
              icon={<IconStar size={28} />}
              title="Your list is empty"
              body="Start with a few of the most-watched names. You can remove them any time."
              action={
                <Button variant="primary" size="sm" onClick={addPopular} disabled={addSymbol.isPending}>
                  Add 5 popular stocks
                </Button>
              }
            />
          )
        ) : (
          <div className="divide-y divide-divider">
            {symbols.map((s) => (
              <WatchlistRow key={s} symbol={s} active={s === activeSymbol} pillMode={pillMode} onCyclePill={cyclePill} dense={variant === 'panel'} />
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
