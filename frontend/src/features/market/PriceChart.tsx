/**
 * Price chart (§22.4) on lightweight-charts 5 (TradingView attribution logo kept, §2.2): candles or area, a volume
 * pane, the dashed previous-close line on 1D, a crosshair legend (O/H/L/C/V in ET), and the forming bar kept live
 * from `/topic/bars.{SYM}.1Min` or, failing that, from the quote's last price. Loaded lazily so the chart library is
 * its own chunk.
 *
 * Bars are decimal strings; the chart API needs numbers, so values are converted here FOR PLOTTING ONLY.
 */
import { useEffect, useRef, useState } from 'react'
import {
  AreaSeries,
  CandlestickSeries,
  ColorType,
  HistogramSeries,
  LineStyle,
  type MouseEventParams,
  TickMarkType,
  type Time,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { Bar, Bars, ChartRange } from '@/lib/api/types'
import { abbreviate, formatPrice } from '@/lib/format/number'
import { useThemeStore } from '@/lib/theme/theme'
import { cx } from '@/components/ui/cx'

export type ChartStyle = 'candles' | 'area'

const etTime = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hour: 'numeric', minute: '2-digit' })
const etDay = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric' })
const etMonth = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', year: '2-digit' })
const etFull = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' })
const etDate = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', year: 'numeric' })

/** Axis labels and the crosshair in market time (ET), not the browser's zone or UTC. */
function tickLabel(time: Time, type: TickMarkType): string {
  const d = new Date((time as number) * 1000)
  if (type === TickMarkType.Year || type === TickMarkType.Month) return etMonth.format(d)
  if (type === TickMarkType.DayOfMonth) return etDay.format(d)
  return etTime.format(d)
}

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888'
}

// Display-only conversion (never used for money arithmetic).
const plot = (d: string | null | undefined): number => Number(d ?? 0)
const ts = (iso: string): UTCTimestamp => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp

interface PlotBar {
  time: UTCTimestamp
  open: number
  high: number
  low: number
  close: number
  volume: number
  raw: Bar
}

function toPlot(bars: readonly Bar[]): PlotBar[] {
  const out: PlotBar[] = []
  let lastTime = -1
  for (const b of bars) {
    if (!b.t || !b.c) continue
    const time = ts(b.t)
    if (time <= lastTime) continue // the chart needs strictly increasing times
    lastTime = time
    out.push({ time, open: plot(b.o ?? b.c), high: plot(b.h ?? b.c), low: plot(b.l ?? b.c), close: plot(b.c), volume: b.v, raw: b })
  }
  return out
}


export default function PriceChart({
  bars,
  range,
  prevClose,
  up,
  style,
  live,
}: {
  bars: Bars | undefined
  range: ChartRange
  prevClose: string | null | undefined
  up: boolean
  style: ChartStyle
  /** The forming bar (bars topic) or the latest trade price; only applied to intraday ranges. */
  live?: { bar: Bar | null; last: string | null }
}) {
  const host = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const price = useRef<ISeriesApi<'Area'> | ISeriesApi<'Candlestick'> | null>(null)
  const volume = useRef<ISeriesApi<'Histogram'> | null>(null)
  const plotted = useRef<PlotBar[]>([])
  const [hover, setHover] = useState<Bar | null>(null)
  const [latest, setLatest] = useState<Bar | null>(null)
  const theme = useThemeStore((s) => s.theme)
  const upDown = useThemeStore((s) => s.upDown)
  const intraday = bars?.timeframe ? !['1Day', '1Week', '1Month'].includes(bars.timeframe) : range === '1D' || range === '5D'

  useEffect(() => {
    if (!host.current) return
    const c = createChart(host.current, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: cssVar('--text-3'), fontFamily: cssVar('--font-sans'), attributionLogo: true },
      grid: { vertLines: { visible: false }, horzLines: { color: cssVar('--chart-grid') } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, tickMarkFormatter: tickLabel, fixLeftEdge: true, fixRightEdge: true },
      localization: { timeFormatter: (time: Time) => `${etFull.format(new Date((time as number) * 1000))} ET` },
      crosshair: { vertLine: { color: cssVar('--chart-crosshair') }, horzLine: { color: cssVar('--chart-crosshair') } },
      handleScroll: false,
      handleScale: false,
    })
    volume.current = c.addSeries(HistogramSeries, { priceScaleId: 'vol', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false })
    c.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } })
    c.priceScale('right').applyOptions({ scaleMargins: { top: 0.08, bottom: 0.22 } })
    const onMove = (p: MouseEventParams) => {
      const t = p.time as number | undefined
      const hit = t === undefined ? undefined : plotted.current.find((b) => b.time === t)
      setHover(hit ? hit.raw : null)
    }
    c.subscribeCrosshairMove(onMove)
    chart.current = c
    return () => {
      c.unsubscribeCrosshairMove(onMove)
      c.remove()
      chart.current = null
      price.current = null
      volume.current = null
    }
  }, [])

  // The price series: recreated when the style changes (area ↔ candles).
  useEffect(() => {
    const c = chart.current
    if (!c) return
    const s = style === 'area' ? c.addSeries(AreaSeries, { lineWidth: 2, priceLineVisible: false }) : c.addSeries(CandlestickSeries, { borderVisible: false, priceLineVisible: false })
    price.current = s
    return () => {
      if (chart.current) chart.current.removeSeries(s)
      price.current = null
    }
  }, [style])

  // Colours and data in one pass. The CSS variables change underneath when the theme or the red-up/green-up
  // preference changes, so `palette` is part of the effect's inputs (and recorded on the host element).
  const palette = `${theme}:${upDown}`
  useEffect(() => {
    const c = chart.current
    const s = price.current
    if (!host.current || !c || !s || !volume.current) return
    host.current.dataset.palette = palette
    const upColor = cssVar('--up')
    const downColor = cssVar('--down')
    if (style === 'area') {
      const color = up ? upColor : downColor
      ;(s as ISeriesApi<'Area'>).applyOptions({ lineColor: color, topColor: `${color}40`, bottomColor: `${color}00` })
    } else {
      ;(s as ISeriesApi<'Candlestick'>).applyOptions({ upColor, downColor, wickUpColor: upColor, wickDownColor: downColor })
    }
    c.applyOptions({
      layout: { textColor: cssVar('--text-3') },
      grid: { horzLines: { color: cssVar('--chart-grid') } },
      crosshair: { vertLine: { color: cssVar('--chart-crosshair') }, horzLine: { color: cssVar('--chart-crosshair') } },
      timeScale: { timeVisible: intraday },
    })
    const data = toPlot(bars?.bars ?? [])
    plotted.current = data
    if (style === 'area') (s as ISeriesApi<'Area'>).setData(data.map((b) => ({ time: b.time, value: b.close })))
    else (s as ISeriesApi<'Candlestick'>).setData(data.map(({ time, open, high, low, close }) => ({ time, open, high, low, close })))
    const upBar = cssVar('--up-bg')
    const downBar = cssVar('--down-bg')
    volume.current.setData(data.map((b) => ({ time: b.time, value: b.volume, color: b.close >= b.open ? upBar : downBar })))
    for (const line of s.priceLines()) s.removePriceLine(line)
    if (range === '1D' && prevClose) {
      s.createPriceLine({ price: plot(prevClose), color: cssVar('--text-3'), lineStyle: LineStyle.Dashed, lineWidth: 1, axisLabelVisible: false, title: 'Prev close' })
    }
    c.timeScale().fitContent()
    setHover(null)
    setLatest(data[data.length - 1]?.raw ?? null)
  }, [bars, range, prevClose, up, palette, style, intraday])

  // Live forming bar (intraday ranges only): the bars topic wins; otherwise the last trade moves the last close.
  useEffect(() => {
    const s = price.current
    const v = volume.current
    const data = plotted.current
    if (!s || !v || !intraday || data.length === 0 || !live) return
    const lastBar = data[data.length - 1]!
    let next: PlotBar | null = null
    if (live.bar?.t && live.bar.c) {
      const time = ts(live.bar.t)
      if (time >= lastBar.time) {
        next = { time, open: plot(live.bar.o), high: plot(live.bar.h), low: plot(live.bar.l), close: plot(live.bar.c), volume: live.bar.v, raw: live.bar }
      }
    } else if (live.last && range === '1D') {
      const p = plot(live.last)
      next = { ...lastBar, close: p, high: Math.max(lastBar.high, p), low: Math.min(lastBar.low, p), raw: { ...lastBar.raw, c: live.last } }
    }
    if (!next) return
    setLatest(next.raw)
    if (next.time === lastBar.time) data[data.length - 1] = next
    else data.push(next)
    try {
      if (style === 'area') (s as ISeriesApi<'Area'>).update({ time: next.time, value: next.close })
      else (s as ISeriesApi<'Candlestick'>).update({ time: next.time, open: next.open, high: next.high, low: next.low, close: next.close })
      v.update({ time: next.time, value: next.volume, color: next.close >= next.open ? cssVar('--up-bg') : cssVar('--down-bg') })
    } catch {
      /* an out-of-order update is ignored; the next refetch resyncs the series */
    }
  }, [live, intraday, range, style])

  const shown = hover ?? latest
  const empty = bars !== undefined && (bars.bars?.length ?? 0) === 0
  return (
    <div className="relative h-full w-full">
      {shown ? (
        <div className="pointer-events-none absolute top-1 right-16 left-1 z-10 flex flex-wrap gap-x-2.5 text-micro text-text-3" aria-hidden="true">
          <span className="text-text-2">{shown.t ? (intraday ? `${etFull.format(new Date(shown.t))} ET` : etDate.format(new Date(shown.t))) : ''}</span>
          {style === 'candles' ? (
            <>
              <span>
                O <span className="text-text-1">{formatPrice(shown.o)}</span>
              </span>
              <span>
                H <span className="text-text-1">{formatPrice(shown.h)}</span>
              </span>
              <span>
                L <span className="text-text-1">{formatPrice(shown.l)}</span>
              </span>
            </>
          ) : null}
          <span>
            C <span className="text-text-1">{formatPrice(shown.c)}</span>
          </span>
          <span>
            V <span className="text-text-1">{abbreviate(shown.v)}</span>
          </span>
        </div>
      ) : null}
      <div ref={host} className={cx('h-full w-full', empty && 'opacity-0')} />
      {empty ? <p className="absolute inset-0 flex items-center justify-center text-caption text-text-3">No bars for this range yet.</p> : null}
    </div>
  )
}
