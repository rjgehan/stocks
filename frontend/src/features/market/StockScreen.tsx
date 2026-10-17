import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react'
import { usePositions } from '@/lib/api/hooks/account'
import { useAsset, useBars } from '@/lib/api/hooks/market'
import { isApiError } from '@/lib/api/problem'
import type { ChartRange, Side, Timeframe } from '@/lib/api/types'
import { useLiveBar } from '@/lib/realtime/useLiveBar'
import { useQuote } from '@/lib/realtime/useQuote'
import { setLastSymbol } from '@/lib/prefs/local'
import { direction } from '@/lib/format/number'
import { type Breakpoint } from '@/app/useBreakpoint'
import { PushedHeader } from '@/app/PushedHeader'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Skeleton } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { BottomDock } from '@/features/account/BottomDock'
import { AlertButton } from '@/features/alerts/AlertButton'
import { MyPositionCard } from '@/features/account/MyPositionCard'
import { OrdersTable } from '@/features/account/OrdersTable'
import { PositionsTable } from '@/features/account/PositionsTable'
import { TradeSheet } from '@/features/trade/TradeSheet'
import { TradeTicket } from '@/features/trade/TradeTicket'
import { canSell } from '@/features/trade/ticketState'
import { StarButton } from '@/features/watchlist/StarButton'
import { ChartTypePicker, IndicatorPicker, useChartType, useIndicatorSettings } from '@/features/charts/IndicatorPicker'
import { activeOscillators } from '@/features/charts/indicatorSettings'
import { ALWAYS_REGULAR, etDateOf, regularSessionTest } from '@/features/charts/sessions'
import { useCalendarDays } from '@/lib/api/hooks/marketExtras'
import { BidAskBar } from './BidAskBar'
import { IntervalPicker, effectiveTimeframe } from './IntervalPicker'
import { KeyStatsGrid } from './KeyStatsGrid'
import { QuoteHeader } from './QuoteHeader'
import { RangeChips } from './RangeChips'
import { isCrypto } from '@/lib/symbols'

const PriceChart = lazy(() => import('./PriceChart'))
// Loaded on demand (review finding: wave A grew this chunk by ~80%): research cards, news and the tape
const AnalystRatings = lazy(() => import('@/features/research/AnalystRatings').then((m) => ({ default: m.AnalystRatings })))
const NewsFeed = lazy(() => import('@/features/research/NewsFeed').then((m) => ({ default: m.NewsFeed })))
const TimeAndSales = lazy(() => import('@/features/tape/TimeAndSales').then((m) => ({ default: m.TimeAndSales })))

export type QuoteTab = 'OVERVIEW' | 'NEWS' | 'TRADES' | 'POSITION'

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

/** Chart heights (px) by breakpoint, plus room for each oscillator pane (§22.10). */
const CHART_HEIGHT = { phone: 240, tablet: 320, desktop: 360 } as const
const PANE_HEIGHT = { phone: 80, tablet: 100, desktop: 110 } as const

/**
 * The quote page (§22.2, §22.4). Desktop: center column (header, chart, tabs) + docked ticket + bottom
 * dock. Tablet: ticket in a right side sheet, dock as tabs under the chart. Phone: pushed screen with a
 * sticky header and a Trade bar that opens a bottom sheet.
 */
export function StockScreen({ symbol, breakpoint, range, tf, tab: requestedTab, ticket, onRange, onTf, onTab, onTicket }: StockScreenProps) {
  const asset = useAsset(symbol)
  const quote = useQuote(symbol, 'quote')
  const bars = useBars(symbol, range, effectiveTimeframe(range, tf))
  const scroller = useRef<HTMLDivElement>(null)
  const desktop = breakpoint === 'desktop' || breakpoint === 'wide'
  const phone = breakpoint === 'phone'
  // desktop shows Time & Sales in the right column, so a `tab=TRADES` link (or a resize) lands on the overview
  const tab: QuoteTab = desktop && requestedTab === 'TRADES' ? 'OVERVIEW' : requestedTab
  const [dockTab, setDockTab] = useState<'POSITIONS' | 'ORDERS'>('POSITIONS')
  const [style, setStyle] = useChartType(phone, range === '1D')
  const [indicators, setIndicators] = useIndicatorSettings()
  const intraday = bars.data?.timeframe ? !['1Day', '1Week', '1Month'].includes(bars.data.timeframe) : range === '1D' || range === '5D'
  // a crypto pair trades 24/7: no exchange, no regular session (so no extended-hours bands), no fundamentals
  const cryptoPair = isCrypto(symbol) || asset.data?.type === 'CRYPTO'
  // §22.10: extended-hours bands use the calendar's regular session of each bar's ET date (early closes included)
  const barList = bars.data?.bars ?? []
  const firstT = barList[0]?.t
  const lastT = barList[barList.length - 1]?.t
  const fromDate = intraday && !cryptoPair && firstT ? etDateOf(Date.parse(firstT) / 1000) : null
  const toDate = intraday && !cryptoPair && lastT ? etDateOf(Date.parse(lastT) / 1000) : null
  const sessionDays = useCalendarDays(fromDate, toDate)
  const isRegular = useMemo(
    () => (!intraday ? undefined : cryptoPair ? ALWAYS_REGULAR : regularSessionTest(sessionDays.data)),
    [intraday, cryptoPair, sessionDays.data],
  )
  const sizeKey = phone ? 'phone' : desktop ? 'desktop' : 'tablet'
  const chartHeight = CHART_HEIGHT[sizeKey] + activeOscillators(indicators).length * PANE_HEIGHT[sizeKey]
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
      <div style={{ height: chartHeight }} data-testid="price-chart">
        <Suspense fallback={<Skeleton className="h-full w-full" />}>
          {bars.isError ? (
            <div className="flex h-full items-center justify-center text-caption text-text-3">Chart data is unavailable right now.</div>
          ) : (
            <PriceChart
              bars={bars.data}
              range={range}
              prevClose={quote?.prevClose}
              up={direction(quote?.change) !== 'down'}
              style={style}
              live={live}
              indicators={indicators}
              isRegular={isRegular}
            />
          )}
        </Suspense>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 px-2 py-2">
        <div className="min-w-0 basis-full tablet:flex-1 tablet:basis-auto">
          <RangeChips value={range} onChange={onRange} />
        </div>
        {onTf ? <IntervalPicker range={range} value={tf} onChange={onTf} /> : null}
        <div className="flex items-center gap-2">
          <ChartTypePicker value={style} onChange={setStyle} />
          <IndicatorPicker value={indicators} onChange={setIndicators} phone={phone} intraday={intraday} />
        </div>
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
          // desktop shows Time & Sales in the right column (§22.10)
          ...(desktop ? [] : [{ value: 'TRADES' as const, label: 'Trades' }]),
          { value: 'POSITION', label: 'My position' },
        ]}
      />
      <div className="pt-3">
        {tab === 'OVERVIEW' ? (
          <div className="space-y-4">
            <KeyStatsGrid quote={quote} asset={asset.data} />
            {breakpoint !== 'wide' ? <BidAskBar quote={quote} /> : null}
            {cryptoPair ? null : (
              <Suspense fallback={null}>
                <AnalystRatings symbol={symbol} />
              </Suspense>
            )}
            <div>
              <h2 className="mb-1 text-section font-semibold">About</h2>
              {asset.data ? (
                <p className="text-body text-text-2">
                  {asset.data.name}
                  {asset.data.fundamentals?.industry ? ` · ${asset.data.fundamentals.industry}` : ''}
                  {asset.data.type === 'ETF' ? ' · Exchange-traded fund' : ''}
                  {cryptoPair ? ' · Crypto · trades 24/7, weekends and holidays included' : asset.data.exchange ? ` · Listed on ${asset.data.exchange}` : ''}.
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
        <Suspense fallback={<Skeleton className="h-24" />}>
          {tab === 'NEWS' ? <NewsFeed symbols={symbol} /> : null}
          {tab === 'TRADES' ? <TimeAndSales symbol={symbol} maxHeight="max-h-[60vh]" /> : null}
        </Suspense>
        {tab === 'POSITION' ? <MyPositionCard symbol={symbol} /> : null}
      </div>
    </section>
  )

  if (phone) {
    return (
      <div className="flex min-h-full flex-col">
        <PushedHeader title={symbol} subtitle={asset.data?.name ?? undefined} actions={<><AlertButton symbol={symbol} size="touch" /><StarButton symbol={symbol} size="touch" /></>} />
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
      <div ref={scroller} className="scrollbar-thin relative h-full overflow-y-auto">
        <QuoteHeader
          symbol={symbol}
          asset={asset.data}
          quote={quote}
          scrollRoot={scroller}
          actions={
            <>
              <AlertButton symbol={symbol} />
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
        <div ref={scroller} className="scrollbar-thin relative min-h-0 flex-1 overflow-y-auto">
          <QuoteHeader symbol={symbol} asset={asset.data} quote={quote} scrollRoot={scroller} actions={<AlertButton symbol={symbol} />} />
          {chart}
          {detailTabs}
        </div>
        <BottomDock />
      </div>
      <aside aria-label={`Trade ${symbol}`} className="scrollbar-thin relative flex w-[340px] shrink-0 flex-col overflow-y-auto border-l border-divider bg-bg-1 wide:w-[380px]">
        <TradeTicket key={symbol} symbol={symbol} initialSide={ticket ?? 'BUY'} />
        {breakpoint === 'wide' ? (
          <div className="border-t border-divider px-4 py-3">
            <h2 className="mb-2 text-caption font-semibold tracking-wide text-text-2 uppercase">Bid / Ask</h2>
            <BidAskBar quote={quote} />
          </div>
        ) : null}
        <div className="border-t border-divider px-4 py-3">
          <Suspense fallback={<Skeleton className="h-40" />}>
            <TimeAndSales symbol={symbol} maxHeight="max-h-[280px]" />
          </Suspense>
        </div>
      </aside>
    </div>
  )
}
