import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react'
import { usePositions } from '@/lib/api/hooks/account'
import { useAsset, useBars } from '@/lib/api/hooks/market'
import { isApiError } from '@/lib/api/problem'
import type { ChartRange, Side, Timeframe } from '@/lib/api/types'
import { useLiveBar } from '@/lib/realtime/useLiveBar'
import { useQuote } from '@/lib/realtime/useQuote'
import { readLocal, setLastSymbol, writeLocal } from '@/lib/prefs/local'
import { direction } from '@/lib/format/number'
import { type Breakpoint } from '@/app/useBreakpoint'
import { PushedHeader } from '@/app/PushedHeader'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Segmented } from '@/components/ui/Segmented'
import { Skeleton } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { IconArea, IconCandles } from '@/components/ui/icons'
import { BottomDock } from '@/features/account/BottomDock'
import { MyPositionCard } from '@/features/account/MyPositionCard'
import { OrdersTable } from '@/features/account/OrdersTable'
import { PositionsTable } from '@/features/account/PositionsTable'
import { TradeSheet } from '@/features/trade/TradeSheet'
import { TradeTicket } from '@/features/trade/TradeTicket'
import { canSell } from '@/features/trade/ticketState'
import { StarButton } from '@/features/watchlist/StarButton'
import { BidAskBar } from './BidAskBar'
import { IntervalPicker, effectiveTimeframe } from './IntervalPicker'
import { KeyStatsGrid } from './KeyStatsGrid'
import { NewsList } from './NewsList'
import type { ChartStyle } from './PriceChart'
import { QuoteHeader } from './QuoteHeader'
import { RangeChips } from './RangeChips'

const PriceChart = lazy(() => import('./PriceChart'))

export type QuoteTab = 'OVERVIEW' | 'NEWS' | 'POSITION'

export interface StockScreenProps {
  symbol: string
  breakpoint: Breakpoint
  range: ChartRange
  /** Bar interval (§22.2 `tf`); undefined = the range's default. */
  tf?: Timeframe
  tab: QuoteTab
  ticket: Side | undefined
  onRange: (r: ChartRange) => void
  onTf?: (tf: Timeframe | undefined) => void
  onTab: (t: QuoteTab) => void
  onTicket: (side: Side | undefined) => void
}

const STYLE_KEY = 'stocks.chart.style'

/** Candles by default; area on the phone's 1D view (§22.4). The user's choice is remembered per device. */
function useChartStyle(phone: boolean, range: ChartRange): [ChartStyle, (s: ChartStyle) => void] {
  const [chosen, setChosen] = useState<ChartStyle | null>(() => {
    const v = readLocal(STYLE_KEY, '')
    return v === 'area' || v === 'candles' ? v : null
  })
  const style: ChartStyle = chosen ?? (phone && range === '1D' ? 'area' : 'candles')
  return [
    style,
    (s) => {
      setChosen(s)
      writeLocal(STYLE_KEY, s)
    },
  ]
}

/**
 * The quote page (§22.2, §22.4). Desktop: center column (header, chart, tabs) + docked ticket + bottom
 * dock. Tablet: ticket in a right side sheet, dock as tabs under the chart. Phone: pushed screen with a
 * sticky header and a Trade bar that opens a bottom sheet.
 */
export function StockScreen({ symbol, breakpoint, range, tf, tab, ticket, onRange, onTf, onTab, onTicket }: StockScreenProps) {
  const asset = useAsset(symbol)
  const quote = useQuote(symbol, 'quote')
  const bars = useBars(symbol, range, effectiveTimeframe(range, tf))
  const scroller = useRef<HTMLDivElement>(null)
  const desktop = breakpoint === 'desktop' || breakpoint === 'wide'
  const phone = breakpoint === 'phone'
  const [dockTab, setDockTab] = useState<'POSITIONS' | 'ORDERS'>('POSITIONS')
  const [style, setStyle] = useChartStyle(phone, range)
  // §22.5: Sell is disabled without a position to sell (it used to open a Buy ticket)
  const positions = usePositions()
  const held = positions.data?.find((p) => p.symbol === symbol)
  const sellable = canSell({ fractionable: false, availableQty: held?.availableQty ?? null })
  const sellTitle = sellable ? undefined : `You don't own ${symbol}`
  const liveBar = useLiveBar(symbol, range === '1D')
  const live = useMemo(() => ({ bar: liveBar, last: quote?.last ?? null }), [liveBar, quote?.last])

  useEffect(() => {
    if (asset.isSuccess) setLastSymbol(symbol)
  }, [symbol, asset.isSuccess])

  if (asset.isError && isApiError(asset.error) && (asset.error.code === 'SYMBOL_NOT_FOUND' || asset.error.status === 404)) {
    return (
      <div className="flex h-full flex-col">
        {phone ? <PushedHeader title={symbol} /> : null}
        <EmptyState title={`We can't find ${symbol}`} body="Check the ticker, or search by company name." />
      </div>
    )
  }

  const chart = (
    <section className="px-2 tablet:px-3" aria-label={`${symbol} chart`}>
      <div className="h-[240px] tablet:h-[320px] desktop:h-[360px]">
        <Suspense fallback={<Skeleton className="h-full w-full" />}>
          {bars.isError ? (
            <div className="flex h-full items-center justify-center text-caption text-text-3">Chart data is unavailable right now.</div>
          ) : (
            <PriceChart bars={bars.data} range={range} prevClose={quote?.prevClose} up={direction(quote?.change) !== 'down'} style={style} live={live} />
          )}
        </Suspense>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 px-2 py-2">
        <div className="min-w-0 basis-full tablet:flex-1 tablet:basis-auto">
          <RangeChips value={range} onChange={onRange} />
        </div>
        {onTf ? <IntervalPicker range={range} value={tf} onChange={onTf} /> : null}
        <Segmented
          label="Chart style"
          size="sm"
          value={style}
          onChange={setStyle}
          options={[
            { value: 'candles', label: (<><IconCandles size={16} /><span className="sr-only">Candles</span></>), title: 'Candles' },
            { value: 'area', label: (<><IconArea size={16} /><span className="sr-only">Line</span></>), title: 'Line' },
          ]}
        />
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
            {breakpoint !== 'wide' ? <BidAskBar quote={quote} /> : null}
            <div>
              <h2 className="mb-1 text-section font-semibold">About</h2>
              {asset.data ? (
                <p className="text-body text-text-2">
                  {asset.data.name}
                  {asset.data.fundamentals?.industry ? ` · ${asset.data.fundamentals.industry}` : ''}
                  {asset.data.type === 'ETF' ? ' · Exchange-traded fund' : ''}
                  {asset.data.exchange ? ` · Listed on ${asset.data.exchange}` : ''}.
                  {asset.data.fundamentals?.website ? (
                    <>
                      {' '}
                      <a href={asset.data.fundamentals.website} target="_blank" rel="noopener noreferrer nofollow" className="text-accent hover:text-accent-hover">
                        Website
                      </a>
                    </>
                  ) : null}
                </p>
              ) : (
                <Skeleton className="h-3 w-2/3" />
              )}
            </div>
          </div>
        ) : null}
        {tab === 'NEWS' ? <NewsList symbols={symbol} /> : null}
        {tab === 'POSITION' ? <MyPositionCard symbol={symbol} /> : null}
      </div>
    </section>
  )

  if (phone) {
    return (
      <div className="flex min-h-full flex-col">
        <PushedHeader title={symbol} subtitle={asset.data?.name ?? undefined} actions={<StarButton symbol={symbol} size="touch" />} />
        <div className="flex-1">
          <QuoteHeader symbol={symbol} asset={asset.data} quote={quote} stickyTopClass="top-[calc(48px+env(safe-area-inset-top))]" showStar={false} />
          {chart}
          {detailTabs}
        </div>
        {/* Trade bar → 92%-height bottom sheet */}
        <div className="sticky bottom-0 z-20 flex gap-2 border-t border-divider bg-bg-1/95 px-3 pt-2 pb-[calc(8px+env(safe-area-inset-bottom))] backdrop-blur">
          <Button variant="buy" size="lg" className="flex-1" onClick={() => onTicket('BUY')}>
            Buy
          </Button>
          <Button variant="sell" size="lg" className="flex-1" disabled={!sellable} title={sellTitle} onClick={() => onTicket('SELL')}>
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
              <Button variant="sell" disabled={!sellable} title={sellTitle} onClick={() => onTicket('SELL')}>
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
          <div className="-mx-4">{dockTab === 'POSITIONS' ? <PositionsTable compact /> : <OrdersTable view="ALL" limit={20} />}</div>
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
      <aside aria-label={`Trade ${symbol}`} className="scrollbar-thin flex w-[340px] shrink-0 flex-col overflow-y-auto border-l border-divider bg-bg-1 wide:w-[380px]">
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
