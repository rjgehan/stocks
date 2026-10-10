/**
 * Price chart (§22.4) on lightweight-charts 5 (TradingView attribution logo kept, §2.2). Phase 0 draws an
 * area series plus a volume pane and the dashed previous-close line on 1D; candles, the interval picker
 * and the crosshair legend land in Phase 1 (M2). Loaded lazily so the chart library is its own chunk.
 *
 * Bars are decimal strings; the chart API needs numbers, so values are converted here FOR PLOTTING ONLY.
 */
import { useEffect, useRef } from 'react'
import {
  AreaSeries,
  ColorType,
  HistogramSeries,
  LineStyle,
  TickMarkType,
  type Time,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { Bars, ChartRange } from '@/lib/api/types'
import { useThemeStore } from '@/lib/theme/theme'

const etTime = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hour: 'numeric', minute: '2-digit' })
const etDay = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric' })
const etMonth = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', year: '2-digit' })
const etFull = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' })

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
const plot = (d: string): number => Number(d)

export default function PriceChart({ bars, range, prevClose, up }: { bars: Bars | undefined; range: ChartRange; prevClose: string | null | undefined; up: boolean }) {
  const host = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const area = useRef<ISeriesApi<'Area'> | null>(null)
  const volume = useRef<ISeriesApi<'Histogram'> | null>(null)
  const theme = useThemeStore((s) => s.theme)
  const upDown = useThemeStore((s) => s.upDown)

  useEffect(() => {
    if (!host.current) return
    const c = createChart(host.current, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: cssVar('--text-3'), fontFamily: cssVar('--font-sans'), attributionLogo: true },
      grid: { vertLines: { visible: false }, horzLines: { color: cssVar('--chart-grid') } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, tickMarkFormatter: tickLabel },
      localization: { timeFormatter: (time: Time) => `${etFull.format(new Date((time as number) * 1000))} ET` },
      crosshair: { vertLine: { color: cssVar('--chart-crosshair') }, horzLine: { color: cssVar('--chart-crosshair') } },
      handleScroll: false,
      handleScale: false,
    })
    area.current = c.addSeries(AreaSeries, { lineWidth: 2, priceLineVisible: false })
    volume.current = c.addSeries(HistogramSeries, { priceScaleId: 'vol', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false })
    c.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } })
    c.priceScale('right').applyOptions({ scaleMargins: { top: 0.08, bottom: 0.22 } })
    chart.current = c
    return () => {
      c.remove()
      chart.current = null
    }
  }, [])

  // Colours and data in one pass. The CSS variables change underneath when the theme or the red-up/green-up
  // preference changes, so `palette` is part of the effect's inputs (and recorded on the host element).
  const palette = `${theme}:${upDown}`
  useEffect(() => {
    const c = chart.current
    if (!host.current || !c || !area.current || !volume.current) return
    host.current.dataset.palette = palette
    const color = cssVar(up ? '--up' : '--down')
    area.current.applyOptions({ lineColor: color, topColor: `${color}40`, bottomColor: `${color}00` })
    c.applyOptions({
      layout: { textColor: cssVar('--text-3') },
      grid: { horzLines: { color: cssVar('--chart-grid') } },
      crosshair: { vertLine: { color: cssVar('--chart-crosshair') }, horzLine: { color: cssVar('--chart-crosshair') } },
    })
    if (!bars) return
    area.current.setData(bars.bars.map((b) => ({ time: (Date.parse(b.t) / 1000) as UTCTimestamp, value: plot(b.c) })))
    const upBar = cssVar('--up-bg')
    const downBar = cssVar('--down-bg')
    volume.current.setData(
      bars.bars.map((b) => ({ time: (Date.parse(b.t) / 1000) as UTCTimestamp, value: b.v, color: plot(b.c) >= plot(b.o) ? upBar : downBar })),
    )
    for (const line of area.current.priceLines()) area.current.removePriceLine(line)
    if (range === '1D' && prevClose) {
      area.current.createPriceLine({ price: plot(prevClose), color: cssVar('--text-3'), lineStyle: LineStyle.Dashed, lineWidth: 1, axisLabelVisible: false, title: 'Prev close' })
    }
    c.timeScale().fitContent()
  }, [bars, range, prevClose, up, palette])

  return <div ref={host} className="h-full w-full" />
}
