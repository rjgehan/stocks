import { Suspense, lazy, useEffect, useRef, useState } from 'react'
import { useAsset, useBars } from '@/lib/api/hooks/market'
import type { ChartRange, Side } from '@/lib/api/types'
import { useQuote } from '@/lib/realtime/useQuote'
import { setLastSymbol } from '@/lib/prefs/local'
import { direction } from '@/lib/format/number'
import { type Breakpoint } from '@/app/useBreakpoint'
import { PushedHeader } from '@/app/PushedHeader'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { EmptyState } from '@/components/ui/EmptyState'
import { BottomDock } from '@/features/account/BottomDock'
import { MyPositionCard } from '@/features/account/MyPositionCard'
import { OrdersTable } from '@/features/account/OrdersTable'
import { PositionsTable } from '@/features/account/PositionsTable'
import { TradeSheet } from '@/features/trade/TradeSheet'
import { TradeTicket } from '@/features/trade/TradeTicket'
import { BidAskBar } from './BidAskBar'
import { KeyStatsGrid } from './KeyStatsGrid'
import { QuoteHeader } from './QuoteHeader'
import { RangeChips } from './RangeChips'

const PriceChart = lazy(() => import('./PriceChart'))

export type QuoteTab = 'OVERVIEW' | 'NEWS' | 'POSITION'

export interface StockScreenProps {
  symbol: string
  breakpoint: Breakpoint
  range: ChartRange
  tab: QuoteTab
  ticket: Side | undefined
  onRange: (r: ChartRange) => void
  onTab: (t: QuoteTab) => void
  onTicket: (side: Side | undefined) => void
}

/**
 * The quote page (§22.2, §22.4). Desktop: center column (header, chart, tabs) + docked ticket + bottom
 * dock. Tablet: ticket in a right side sheet, dock as tabs under the chart. Phone: pushed screen with a
 * sticky header and a Trade bar that opens a bottom sheet.
 */
export function StockScreen({ symbol, breakpoint, range, tab, ticket, onRange, onTab, onTicket }: StockScreenProps) {
  const asset = useAsset(symbol)
  const quote = useQuote(symbol, 'quote')
  const bars = useBars(symbol, range)
  const scroller = useRef<HTMLDivElement>(null)
  const desktop = breakpoint === 'desktop' || breakpoint === 'wide'
  const phone = breakpoint === 'phone'
  const [dockTab, setDockTab] = useState<'POSITIONS' | 'ORDERS'>('POSITIONS')

  useEffect(() => setLastSymbol(symbol), [symbol])

  const chart = (
    <section className="px-2 tablet:px-3">
      <div className="h-[240px] tablet:h-[320px] desktop:h-[360px]">
        <Suspense fallback={<Skeleton className="h-full w-full" />}>
          <PriceChart bars={bars.data} range={range} prevClose={quote?.prevClose} up={direction(quote?.change) !== 'down'} />
        </Suspense>
      </div>
      <div className="flex items-center justify-between gap-2 px-2 py-2">
        <RangeChips value={range} onChange={onRange} />
      </div>
    </section>
  )

  const detailTabs = (
    <section className="px-4 pb-6 desktop:px-5">
      <Tabs
        label="Quote sections"
        value={tab}
        onChange={onTab}
        items={[
          { value: 'OVERVIEW', label: 'Overview' },
          { value: 'NEWS', label: 'News' },
          { value: 'POSITION', label: 'My position' },
        ]}
      />
      <div className="pt-3">
        {tab === 'OVERVIEW' ? (
          <div className="space-y-4">
            <KeyStatsGrid quote={quote} asset={asset.data} />
            {!desktop || breakpoint !== 'wide' ? <BidAskBar quote={quote} /> : null}
            <div>
              <h2 className="mb-1 text-section font-semibold">About</h2>
              <p className="text-body text-text-2">
                {asset.data ? `${asset.data.name}${asset.data.fundamentals?.industry ? ` · ${asset.data.fundamentals.industry}` : ''}. Company profile and news arrive in Phase 1 when a source is available.` : <Skeleton className="h-3 w-2/3" />}
              </p>
            </div>
          </div>
        ) : null}
        {tab === 'NEWS' ? <EmptyState title="No news source yet" body="News appears here if the market-data probe finds a source (Phase 1)." /> : null}
        {tab === 'POSITION' ? <MyPositionCard symbol={symbol} /> : null}
      </div>
    </section>
  )

  if (phone) {
    return (
      <div className="flex min-h-full flex-col">
        <PushedHeader title={symbol} subtitle={asset.data?.name} />
        <div className="flex-1">
          <QuoteHeader symbol={symbol} asset={asset.data} quote={quote} stickyTopClass="top-[calc(48px+env(safe-area-inset-top))]" />
          {chart}
          {detailTabs}
        </div>
        {/* Trade bar → 92%-height bottom sheet */}
        <div className="sticky bottom-0 z-20 flex gap-2 border-t border-divider bg-bg-1/95 px-3 pt-2 pb-[calc(8px+env(safe-area-inset-bottom))] backdrop-blur">
          <Button variant="buy" size="lg" className="flex-1" onClick={() => onTicket('BUY')}>
            Buy
          </Button>
          <Button variant="sell" size="lg" className="flex-1" onClick={() => onTicket('SELL')}>
            Sell
          </Button>
        </div>
        <TradeSheet symbol={symbol} side={ticket ?? 'BUY'} open={ticket !== undefined} onClose={() => onTicket(undefined)} variant="bottom" />
      </div>
    )
  }

  if (!desktop) {
    // Tablet: content + right side sheet ticket; dock becomes tabs under the chart.
    return (
      <div ref={scroller} className="scrollbar-thin h-full overflow-y-auto">
        <QuoteHeader
          symbol={symbol}
          asset={asset.data}
          quote={quote}
          scrollRoot={scroller}
          actions={
            <>
              <Button variant="buy" onClick={() => onTicket('BUY')}>
                Buy
              </Button>
              <Button variant="sell" onClick={() => onTicket('SELL')}>
                Sell
              </Button>
            </>
          }
        />
        {chart}
        <section className="px-4 pb-2">
          <Tabs
            label="Positions and orders"
            value={dockTab}
            onChange={setDockTab}
            items={[
              { value: 'POSITIONS', label: 'Positions' },
              { value: 'ORDERS', label: 'Orders' },
            ]}
          />
          <div className="-mx-4">{dockTab === 'POSITIONS' ? <PositionsTable /> : <OrdersTable status="ALL" />}</div>
        </section>
        {detailTabs}
        <TradeSheet symbol={symbol} side={ticket ?? 'BUY'} open={ticket !== undefined} onClose={() => onTicket(undefined)} variant="right" />
      </div>
    )
  }

  // Desktop / wide: 3-pane with docked ticket column and bottom dock.
  return (
    <div className="flex h-full min-h-0">
      <div className="flex min-w-0 flex-1 flex-col">
        <div ref={scroller} className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
          <QuoteHeader symbol={symbol} asset={asset.data} quote={quote} scrollRoot={scroller} />
          {chart}
          {detailTabs}
        </div>
        <BottomDock />
      </div>
      <aside aria-label="Trade" className="scrollbar-thin flex w-[340px] shrink-0 flex-col overflow-y-auto border-l border-divider bg-bg-1 wide:w-[380px]">
        <TradeTicket key={symbol} symbol={symbol} initialSide={ticket ?? 'BUY'} />
        {breakpoint === 'wide' ? (
          <div className="border-t border-divider px-4 py-3">
            <h2 className="mb-2 text-caption font-semibold tracking-wide text-text-2 uppercase">Bid / Ask</h2>
            <BidAskBar quote={quote} />
          </div>
        ) : null}
      </aside>
    </div>
  )
}
