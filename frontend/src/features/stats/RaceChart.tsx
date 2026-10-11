/**
 * The race chart canvas (§16.9, lazy-loaded): one line per player (normalized returns, fractions), SPY dashed, the
 * viewer's line thicker; a highlighted player dims the others. Values are converted to numbers FOR PLOTTING ONLY.
 */
import { useEffect, useRef } from 'react'
import { ColorType, LineSeries, LineStyle, type IChartApi, type ISeriesApi, type Time, type UTCTimestamp, createChart } from 'lightweight-charts'
import { useThemeStore } from '@/lib/theme/theme'
import { axisLabel } from '@/features/account/LineChart'
import { etTickLabel } from '@/features/market/chartTime'

export interface RaceLine {
  id: string
  color: string
  width: number
  dashed?: boolean
  data: ReadonlyArray<{ time: number; value: number }>
}

const etFull = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888'
}

/** `#rrggbb` with an alpha byte (dimmed lines); other color strings pass through. */
function withAlpha(color: string, alpha: number): string {
  return /^#[0-9a-f]{6}$/i.test(color) ? `${color}${Math.round(alpha * 255).toString(16).padStart(2, '0')}` : color
}

export default function RaceChart({ lines, highlight, intraday }: { lines: readonly RaceLine[]; highlight: string | null; intraday: boolean }) {
  const host = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const series = useRef<Map<string, ISeriesApi<'Line'>>>(new Map())
  const theme = useThemeStore((s) => s.theme)

  useEffect(() => {
    if (!host.current) return
    const c = createChart(host.current, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: cssVar('--text-3'), fontFamily: cssVar('--font-sans'), attributionLogo: true },
      grid: { vertLines: { visible: false }, horzLines: { color: cssVar('--chart-grid') } },
      rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.14, bottom: 0.08 } },
      timeScale: { borderVisible: false, fixLeftEdge: true, fixRightEdge: true, tickMarkFormatter: etTickLabel },
      localization: { timeFormatter: (time: Time) => `${etFull.format(new Date((time as number) * 1000))} ET`, priceFormatter: (p: number) => axisLabel(p, 'percent') },
      crosshair: { vertLine: { color: cssVar('--chart-crosshair') }, horzLine: { color: cssVar('--chart-crosshair') } },
      handleScroll: false,
      handleScale: false,
    })
    chart.current = c
    const map = series.current
    return () => {
      c.remove()
      chart.current = null
      map.clear()
    }
  }, [])

  useEffect(() => {
    const c = chart.current
    if (!c || !host.current) return
    host.current.dataset.palette = theme
    c.applyOptions({ layout: { textColor: cssVar('--text-3') }, grid: { horzLines: { color: cssVar('--chart-grid') } }, timeScale: { timeVisible: intraday } })
    const seen = new Set<string>()
    for (const line of lines) {
      seen.add(line.id)
      let s = series.current.get(line.id)
      if (!s) {
        s = c.addSeries(LineSeries, { priceLineVisible: false, lastValueVisible: false, crosshairMarkerRadius: 3 })
        series.current.set(line.id, s)
      }
      const dim = highlight != null && highlight !== line.id
      const base = line.dashed ? cssVar('--text-3') : line.color
      s.applyOptions({
        color: dim ? withAlpha(base, 0.22) : base,
        lineWidth: (highlight === line.id ? line.width + 1 : line.width) as 1 | 2 | 3 | 4,
        lineStyle: line.dashed ? LineStyle.Dashed : LineStyle.Solid,
        lastValueVisible: highlight === line.id,
      })
      s.setData(line.data.map((p) => ({ time: p.time as UTCTimestamp, value: p.value })))
    }
    for (const [id, s] of series.current) {
      if (!seen.has(id)) {
        c.removeSeries(s)
        series.current.delete(id)
      }
    }
    c.timeScale().fitContent()
  }, [lines, highlight, intraday, theme])

  return <div ref={host} className="h-full w-full" data-testid="race-chart" />
}
