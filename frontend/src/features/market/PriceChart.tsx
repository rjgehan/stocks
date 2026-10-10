/**
 * Price chart (§22.4, §22.10) on lightweight-charts 5 (TradingView attribution logo kept, §2.2): candles, hollow
 * candles, Heikin Ashi or a line (area), a volume overlay, the dashed previous-close line on 1D, overlay indicators
 * (MA, EMA, BOLL, VWAP) on the price pane and oscillators (MACD, RSI, KDJ) in their own panes, extended-hours bands on
 * intraday charts, a crosshair legend (the REAL O/H/L/C/V in ET, plus the indicator values), and the forming bar kept
 * live from `/topic/bars.{SYM}.1Min` or, failing that, from the quote's last price. Loaded lazily so the chart
 * library is its own chunk.
 *
 * Bars are decimal strings; the chart API needs numbers, so values are converted here FOR PLOTTING ONLY.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AreaSeries,
  CandlestickSeries,
  ColorType,
  HistogramSeries,
  LineSeries,
  LineStyle,
  type MouseEventParams,
  type Time,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type SeriesType,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { Bar, Bars, ChartRange } from '@/lib/api/types'
import { abbreviate, formatPrice } from '@/lib/format/number'
import { useThemeStore } from '@/lib/theme/theme'
import { cx } from '@/components/ui/cx'
import { ExtendedHoursShading } from '@/features/charts/extendedHoursShading'
import { type ChartType, type IndicatorSettings, DEFAULT_INDICATORS, activeOscillators } from '@/features/charts/indicatorSettings'
import { type Line, type PlotOhlcv, bollinger, ema, extendedSpans, heikinAshi, hollowStyles, kdj, macd, rsi, sma, vwap } from '@/features/charts/indicators'
import { etDateOf } from '@/features/charts/sessions'
import { etTickLabel } from './chartTime'

/** Kept for callers of the Phase 1 API: the chart type. */
export type ChartStyle = ChartType

const etFull = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' })
const etDate = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', year: 'numeric' })

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888'
}

/** The forming bar with the last trade as its close (high/low stretched to include it); display only. */
function withLast(bar: PlotBar, last: string): PlotBar {
  const p = plot(last)
  if (!Number.isFinite(p) || p <= 0 || p === bar.close) return bar
  const high = Math.max(bar.high, p)
  const low = Math.min(bar.low, p)
  return {
    ...bar,
    close: p,
    high,
    low,
    raw: { ...bar.raw, c: last, h: high === p ? last : bar.raw.h, l: low === p ? last : bar.raw.l },
  }
}

// Display-only conversion (never used for money arithmetic).
const plot = (d: string | null | undefined): number => Number(d ?? 0)
const ts = (iso: string): UTCTimestamp => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp

interface PlotBar extends PlotOhlcv {
  time: UTCTimestamp
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
    out.push({
      time,
      open: plot(b.o ?? b.c),
      high: plot(b.h ?? b.c),
      low: plot(b.l ?? b.c),
      close: plot(b.c),
      volume: b.v,
      vwap: b.vw ? plot(b.vw) : null,
      raw: b,
    })
  }
  return out
}

/** One indicator line (or histogram) on the chart: its series and how to compute its values from the bars. */
interface IndicatorLine {
  /** `key@pane`: the structure; series are reused while it is unchanged (no pane is torn down on a data refresh). */
  slot: string
  label: string
  color: string
  series: ISeriesApi<'Line'> | ISeriesApi<'Histogram'>
  histogram: boolean
  values: (bars: readonly PlotBar[]) => Line
}

const closes = (b: readonly PlotBar[]): number[] => b.map((x) => x.close)

/** Builds the indicator lines for the settings (pane 0 = overlays; then one pane per active oscillator). */
function indicatorDefs(s: IndicatorSettings, intraday: boolean, palette: { ma: string[]; up: string; down: string; text: string }) {
  type Def = { key: string; label: string; color: string; pane: number; histogram?: boolean; values: (b: readonly PlotBar[]) => Line }
  const defs: Def[] = []
  const ma = palette.ma
  if (s.ma.on) s.ma.periods.forEach((n, i) => defs.push({ key: `ma${i}`, label: `MA${n}`, color: ma[i % ma.length]!, pane: 0, values: (b) => sma(closes(b), n) }))
  if (s.ema.on) s.ema.periods.forEach((n, i) => defs.push({ key: `ema${i}`, label: `EMA${n}`, color: ma[(i + 1) % ma.length]!, pane: 0, values: (b) => ema(closes(b), n) }))
  if (s.boll.on) {
    const { n, k } = s.boll
    defs.push({ key: 'bollU', label: 'UB', color: palette.ma[2]!, pane: 0, values: (b) => bollinger(closes(b), n, k).upper })
    defs.push({ key: 'bollM', label: `BOLL${n}`, color: palette.ma[0]!, pane: 0, values: (b) => bollinger(closes(b), n, k).mid })
    defs.push({ key: 'bollL', label: 'LB', color: palette.ma[2]!, pane: 0, values: (b) => bollinger(closes(b), n, k).lower })
  }
  if (s.vwap.on && intraday) defs.push({ key: 'vwap', label: 'VWAP', color: palette.ma[1]!, pane: 0, values: (b) => vwap(b, etDateOf) })
  activeOscillators(s).forEach((osc, i) => {
    const pane = i + 1
    if (osc === 'macd') {
      const { fast, slow, signal } = s.macd
      defs.push({ key: 'macdH', label: 'HIST', color: palette.text, pane, histogram: true, values: (b) => macd(closes(b), fast, slow, signal).hist })
      defs.push({ key: 'macd', label: `MACD(${fast},${slow},${signal})`, color: palette.ma[1]!, pane, values: (b) => macd(closes(b), fast, slow, signal).macd })
      defs.push({ key: 'macdS', label: 'SIGNAL', color: palette.ma[0]!, pane, values: (b) => macd(closes(b), fast, slow, signal).signal })
    } else if (osc === 'rsi') {
      defs.push({ key: 'rsi', label: `RSI${s.rsi.n}`, color: palette.ma[2]!, pane, values: (b) => rsi(closes(b), s.rsi.n) })
    } else {
      const { n, m1, m2 } = s.kdj
      defs.push({ key: 'kdjK', label: `K(${n},${m1},${m2})`, color: palette.ma[0]!, pane, values: (b) => kdj(b, n, m1, m2).k })
      defs.push({ key: 'kdjD', label: 'D', color: palette.ma[1]!, pane, values: (b) => kdj(b, n, m1, m2).d })
      defs.push({ key: 'kdjJ', label: 'J', color: palette.ma[2]!, pane, values: (b) => kdj(b, n, m1, m2).j })
    }
  })
  return defs
}

const lineData = (bars: readonly PlotBar[], values: Line) =>
  bars.map((b, i) => (values[i] == null || !Number.isFinite(values[i]!) ? { time: b.time } : { time: b.time, value: values[i]! }))

export default function PriceChart({
  bars,
  range,
  prevClose,
  up,
  style,
  live,
  indicators = DEFAULT_INDICATORS,
  isRegular,
}: {
  bars: Bars | undefined
  range: ChartRange
  prevClose: string | null | undefined
  up: boolean
  style: ChartType
  /** The forming bar (bars topic) or the latest trade price; only applied to intraday ranges. */
  live?: { bar: Bar | null; last: string | null }
  indicators?: IndicatorSettings
  /** Intraday only: is a bar starting at this time (epoch seconds) inside the regular session? Shades the rest. */
  isRegular?: (time: number) => boolean
}) {
  const host = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const price = useRef<ISeriesApi<'Area'> | ISeriesApi<'Candlestick'> | null>(null)
  const volume = useRef<ISeriesApi<'Histogram'> | null>(null)
  const shading = useRef<ExtendedHoursShading | null>(null)
  const lines = useRef<IndicatorLine[]>([])
  const plotted = useRef<PlotBar[]>([])
  const [hover, setHover] = useState<{ bar: Bar; index: number } | null>(null)
  const [latest, setLatest] = useState<Bar | null>(null)
  /** The legend's indicator values per bar (the crosshair picks an index; the last bar otherwise). */
  const [legend, setLegend] = useState<{ label: string; color: string; values: Line }[]>([])
  const theme = useThemeStore((s) => s.theme)
  const upDown = useThemeStore((s) => s.upDown)
  const intraday = bars?.timeframe ? !['1Day', '1Week', '1Month'].includes(bars.timeframe) : range === '1D' || range === '5D'
  const oscillators = activeOscillators(indicators).length
  const indicatorKey = useMemo(() => JSON.stringify(indicators), [indicators])

  useEffect(() => {
    if (!host.current) return
    const c = createChart(host.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: cssVar('--text-3'),
        fontFamily: cssVar('--font-sans'),
        attributionLogo: true,
        panes: { separatorColor: cssVar('--divider'), separatorHoverColor: cssVar('--border'), enableResize: false },
      },
      grid: { vertLines: { visible: false }, horzLines: { color: cssVar('--chart-grid') } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, tickMarkFormatter: etTickLabel, fixLeftEdge: true, fixRightEdge: true },
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
      const index = t === undefined ? -1 : plotted.current.findIndex((b) => b.time === t)
      setHover(index >= 0 ? { bar: plotted.current[index]!.raw, index } : null)
    }
    c.subscribeCrosshairMove(onMove)
    chart.current = c
    return () => {
      c.unsubscribeCrosshairMove(onMove)
      c.remove()
      chart.current = null
      price.current = null
      volume.current = null
      shading.current = null
      lines.current = []
    }
  }, [])

  // The price series: recreated when the type changes (area ↔ candles); the shading rides on it.
  const area = style === 'area'
  useEffect(() => {
    const c = chart.current
    if (!c) return
    const s = area ? c.addSeries(AreaSeries, { lineWidth: 2, priceLineVisible: false }) : c.addSeries(CandlestickSeries, { borderVisible: false, priceLineVisible: false })
    const band = new ExtendedHoursShading()
    s.attachPrimitive(band)
    price.current = s
    shading.current = band
    return () => {
      s.detachPrimitive(band)
      if (chart.current) chart.current.removeSeries(s)
      price.current = null
      shading.current = null
    }
  }, [area])

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
      ;(s as ISeriesApi<'Candlestick'>).applyOptions({
        upColor,
        downColor,
        wickUpColor: upColor,
        wickDownColor: downColor,
        borderVisible: style === 'hollow',
        borderUpColor: upColor,
        borderDownColor: downColor,
      })
    }
    c.applyOptions({
      layout: { textColor: cssVar('--text-3'), panes: { separatorColor: cssVar('--divider') } },
      grid: { horzLines: { color: cssVar('--chart-grid') } },
      crosshair: { vertLine: { color: cssVar('--chart-crosshair') }, horzLine: { color: cssVar('--chart-crosshair') } },
      timeScale: { timeVisible: intraday },
    })
    const data = toPlot(bars?.bars ?? [])
    plotted.current = data
    setPriceData(s, style, data, upColor, downColor)
    const upBar = cssVar('--up-bg')
    const downBar = cssVar('--down-bg')
    volume.current.setData(data.map((b) => ({ time: b.time, value: b.volume, color: b.close >= b.open ? upBar : downBar })))
    for (const line of s.priceLines()) s.removePriceLine(line)
    if (range === '1D' && prevClose) {
      s.createPriceLine({ price: plot(prevClose), color: cssVar('--text-3'), lineStyle: LineStyle.Dashed, lineWidth: 1, axisLabelVisible: false, title: 'Prev close' })
    }
    // extended-hours bands (intraday only)
    const times = data.map((b) => b.time as number)
    shading.current?.setData(times, intraday && isRegular ? extendedSpans(times, isRegular) : [], extColor())
    rebuildIndicators(c, lines, JSON.parse(indicatorKey) as IndicatorSettings, intraday, data)
    setLegend(lines.current.filter((l) => !l.histogram).map((l) => ({ label: l.label, color: l.color, values: l.values(data) })))
    c.timeScale().fitContent()
    setHover(null)
    setLatest(data[data.length - 1]?.raw ?? null)
  }, [bars, range, prevClose, up, palette, style, intraday, isRegular, indicatorKey])

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
        next = { time, open: plot(live.bar.o), high: plot(live.bar.h), low: plot(live.bar.l), close: plot(live.bar.c), volume: live.bar.v, vwap: live.bar.vw ? plot(live.bar.vw) : null, raw: live.bar }
        // the quote ticks every second, bars at most once a second: the last trade moves the forming candle in
        // between, so the chart's close never lags the header price
        if (live.last && range === '1D') next = withLast(next, live.last)
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
      updateLastPrice(s, style, data, cssVar('--up'), cssVar('--down'))
      v.update({ time: next.time, value: next.volume, color: next.close >= next.open ? cssVar('--up-bg') : cssVar('--down-bg') })
      for (const l of lines.current) {
        const values = l.values(data)
        const value = values[values.length - 1]
        const point = value == null || !Number.isFinite(value) ? { time: next.time } : { time: next.time, value }
        if (l.histogram && value != null) (l.series as ISeriesApi<'Histogram'>).update({ ...point, color: value >= 0 ? cssVar('--up') : cssVar('--down') } as never)
        else (l.series as ISeriesApi<SeriesType>).update(point as never)
      }
      if (lines.current.length > 0) {
        setLegend(lines.current.filter((l) => !l.histogram).map((l) => ({ label: l.label, color: l.color, values: l.values(data) })))
      }
    } catch {
      /* an out-of-order update is ignored; the next refetch resyncs the series */
    }
  }, [live, intraday, range, style])

  const shown = hover?.bar ?? latest
  const values = legend.map((l) => {
    const index = hover ? hover.index : l.values.length - 1
    const v = l.values[index]
    return { label: l.label, color: l.color, value: v == null || !Number.isFinite(v) ? null : v }
  })
  const empty = bars !== undefined && (bars.bars?.length ?? 0) === 0
  return (
    <div className="relative h-full w-full" data-chart-type={style} data-oscillators={oscillators}>
      {shown ? (
        <div className="pointer-events-none absolute top-1 right-16 left-1 z-10 text-micro text-text-3" aria-hidden="true">
          <div className="flex flex-wrap gap-x-2.5">
            <span className="text-text-2">{shown.t ? (intraday ? `${etFull.format(new Date(shown.t))} ET` : etDate.format(new Date(shown.t))) : ''}</span>
            {style !== 'area' ? (
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
            {style === 'heikin' ? <span className="text-text-3">(real prices; candles are Heikin Ashi)</span> : null}
          </div>
          {values.length > 0 ? (
            <div className="flex flex-wrap gap-x-2.5" data-testid="indicator-legend">
              {values.map((v) => (
                <span key={v.label} style={{ color: v.color }}>
                  {v.label} {v.value == null ? '--' : v.value.toFixed(Math.abs(v.value) >= 1000 ? 0 : 2)}
                </span>
              ))}
            </div>
          ) : null}
        </div>
      ) : null}
      <div ref={host} className={cx('h-full w-full', empty && 'opacity-0')} />
      {empty ? <p className="absolute inset-0 flex items-center justify-center text-caption text-text-3">No bars for this range yet.</p> : null}
    </div>
  )
}

function extColor(): string {
  return document.documentElement.dataset.theme === 'light' ? 'rgba(217,138,0,0.08)' : 'rgba(245,165,36,0.07)'
}

/** Sets the price series' data for the chart type (Heikin-Ashi bars; per-bar colours for hollow candles). */
function setPriceData(s: ISeriesApi<'Area'> | ISeriesApi<'Candlestick'>, style: ChartType, data: readonly PlotBar[], upColor: string, downColor: string) {
  if (style === 'area') {
    ;(s as ISeriesApi<'Area'>).setData(data.map((b) => ({ time: b.time, value: b.close })))
    return
  }
  const candles = s as ISeriesApi<'Candlestick'>
  if (style === 'heikin') {
    candles.setData(heikinAshi(data).map(({ time, open, high, low, close }) => ({ time: time as UTCTimestamp, open, high, low, close })))
  } else if (style === 'hollow') {
    const styles = hollowStyles(data, upColor, downColor)
    candles.setData(data.map(({ time, open, high, low, close }, i) => ({ time, open, high, low, close, ...styles[i]! })))
  } else {
    candles.setData(data.map(({ time, open, high, low, close }) => ({ time, open, high, low, close })))
  }
}

/** Updates the last point of the price series after a live change of the last bar. */
function updateLastPrice(s: ISeriesApi<'Area'> | ISeriesApi<'Candlestick'>, style: ChartType, data: readonly PlotBar[], upColor: string, downColor: string) {
  const last = data[data.length - 1]!
  if (style === 'area') {
    ;(s as ISeriesApi<'Area'>).update({ time: last.time, value: last.close })
    return
  }
  const candles = s as ISeriesApi<'Candlestick'>
  if (style === 'heikin') {
    const ha = heikinAshi(data)
    const h = ha[ha.length - 1]!
    candles.update({ time: last.time, open: h.open, high: h.high, low: h.low, close: h.close })
  } else if (style === 'hollow') {
    const st = hollowStyles(data.slice(-2), upColor, downColor)
    candles.update({ time: last.time, open: last.open, high: last.high, low: last.low, close: last.close, ...st[st.length - 1]! })
  } else {
    candles.update({ time: last.time, open: last.open, high: last.high, low: last.low, close: last.close })
  }
}

/**
 * Fills the indicator series of the chart (pane 0 overlays, then one pane per oscillator). The series are reused
 * while the set of lines is unchanged (a data refresh, a new period); otherwise they are replaced, which also adds or
 * drops the oscillator panes (lightweight-charts removes a pane with its last series).
 */
function rebuildIndicators(c: IChartApi, lines: { current: IndicatorLine[] }, settings: IndicatorSettings, intraday: boolean, data: readonly PlotBar[]) {
  const colors = { ma: [cssVar('--chart-ma1'), cssVar('--chart-ma2'), cssVar('--chart-ma3')], up: cssVar('--up'), down: cssVar('--down'), text: cssVar('--text-3') }
  const defs = indicatorDefs(settings, intraday, colors)
  const slots = defs.map((d) => `${d.key}@${d.pane}`)
  const same = slots.length === lines.current.length && slots.every((slot, i) => lines.current[i]!.slot === slot)
  if (!same) {
    for (const l of lines.current) c.removeSeries(l.series)
    lines.current = []
  }
  defs.forEach((d, i) => {
    let line = same ? lines.current[i]! : null
    if (!line) {
      const opts = { priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false }
      const series = d.histogram
        ? c.addSeries(HistogramSeries, { ...opts, priceFormat: { type: 'price', precision: 4, minMove: 0.0001 } }, d.pane)
        : c.addSeries(LineSeries, { ...opts, color: d.color, lineWidth: 1, ...(d.pane > 0 ? { priceFormat: { type: 'price', precision: 2, minMove: 0.01 } } : {}) }, d.pane)
      if (d.key === 'rsi') {
        for (const level of [70, 30]) {
          series.createPriceLine({ price: level, color: colors.text, lineStyle: LineStyle.Dotted, lineWidth: 1, axisLabelVisible: false, title: '' })
        }
      }
      line = { slot: slots[i]!, label: d.label, color: d.color, series, histogram: Boolean(d.histogram), values: d.values }
      lines.current.push(line)
    } else {
      line.label = d.label
      line.color = d.color
      line.values = d.values
      if (!line.histogram) (line.series as ISeriesApi<'Line'>).applyOptions({ color: d.color })
    }
    const values = d.values(data)
    if (line.histogram) {
      ;(line.series as ISeriesApi<'Histogram'>).setData(
        data.map((b, j) => (values[j] == null ? { time: b.time } : { time: b.time, value: values[j]!, color: values[j]! >= 0 ? colors.up : colors.down })),
      )
    } else {
      ;(line.series as ISeriesApi<'Line'>).setData(lineData(data, values))
    }
  })
  if (!same) c.panes().forEach((p, i) => p.setStretchFactor(i === 0 ? 3 : 1))
}
