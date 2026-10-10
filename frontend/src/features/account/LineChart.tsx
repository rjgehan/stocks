/**
 * A small multi-line chart on lightweight-charts (account equity curve, profile unit value vs SPY). Lazy-loaded.
 * Values are decimal strings converted to numbers FOR PLOTTING ONLY.
 */
import { useEffect, useRef } from 'react'
import { ColorType, LineSeries, LineStyle, type IChartApi, type ISeriesApi, type Time, type UTCTimestamp, createChart } from 'lightweight-charts'
import { useThemeStore } from '@/lib/theme/theme'

export interface LineSpec {
  id: string
  /** CSS variable name for the colour, e.g. "--accent" */
  color: string
  points: ReadonlyArray<{ t: string | null; v: string | null }>
  dashed?: boolean
  format?: 'money' | 'percent'
}

const etFull = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888'
}

export default function LineChart({ lines, intraday, format = 'money' }: { lines: readonly LineSpec[]; intraday: boolean; format?: 'money' | 'percent' }) {
  const host = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const series = useRef<Map<string, ISeriesApi<'Line'>>>(new Map())
  const theme = useThemeStore((s) => s.theme)
  const upDown = useThemeStore((s) => s.upDown)

  useEffect(() => {
    if (!host.current) return
    const c = createChart(host.current, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: cssVar('--text-3'), fontFamily: cssVar('--font-sans'), attributionLogo: true },
      grid: { vertLines: { visible: false }, horzLines: { color: cssVar('--chart-grid') } },
      rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.1, bottom: 0.08 } },
      timeScale: { borderVisible: false, fixLeftEdge: true, fixRightEdge: true },
      localization: { timeFormatter: (time: Time) => `${etFull.format(new Date((time as number) * 1000))} ET` },
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

  const palette = `${theme}:${upDown}`
  useEffect(() => {
    const c = chart.current
    if (!c || !host.current) return
    // the CSS variables change underneath with the theme / red-up preference: recorded so the effect re-reads them
    host.current.dataset.palette = palette
    c.applyOptions({
      layout: { textColor: cssVar('--text-3') },
      grid: { horzLines: { color: cssVar('--chart-grid') } },
      timeScale: { timeVisible: intraday },
      localization: {
        priceFormatter: (p: number) => (format === 'percent' ? `${(p * 100).toFixed(2)}%` : `$${p.toFixed(2)}`),
      },
    })
    const seen = new Set<string>()
    for (const line of lines) {
      seen.add(line.id)
      let s = series.current.get(line.id)
      if (!s) {
        s = c.addSeries(LineSeries, { lineWidth: 2, priceLineVisible: false, lastValueVisible: true })
        series.current.set(line.id, s)
      }
      s.applyOptions({ color: cssVar(line.color), lineStyle: line.dashed ? LineStyle.Dashed : LineStyle.Solid, lineWidth: line.dashed ? 1 : 2 })
      let last = -1
      const data: Array<{ time: UTCTimestamp; value: number }> = []
      for (const p of line.points) {
        if (!p.t || p.v == null) continue
        const time = Math.floor(Date.parse(p.t) / 1000)
        if (!Number.isFinite(time) || time <= last) continue
        last = time
        data.push({ time: time as UTCTimestamp, value: Number(p.v) })
      }
      s.setData(data)
    }
    for (const [id, s] of series.current) {
      if (!seen.has(id)) {
        c.removeSeries(s)
        series.current.delete(id)
      }
    }
    c.timeScale().fitContent()
  }, [lines, intraday, format, palette])

  return <div ref={host} className="h-full w-full" />
}
