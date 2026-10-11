/**
 * Chart type and indicator preferences (§22.10): a UI preference stored in localStorage per player on this device
 * (`stocks.chart.indicators.<playerId>`), never server state. Every stored value is re-validated (clamped) on read, so
 * a hand-edited or older entry can never break the chart.
 */
import { readLocal, writeLocal } from '@/lib/prefs/local'

export type ChartType = 'candles' | 'hollow' | 'heikin' | 'area'
export const CHART_TYPES: readonly ChartType[] = ['candles', 'hollow', 'heikin', 'area']
export const CHART_TYPE_LABELS: Record<ChartType, string> = {
  candles: 'Candles',
  hollow: 'Hollow candles',
  heikin: 'Heikin Ashi',
  area: 'Line',
}

export interface IndicatorSettings {
  ma: { on: boolean; periods: number[] }
  ema: { on: boolean; periods: number[] }
  boll: { on: boolean; n: number; k: number }
  vwap: { on: boolean }
  macd: { on: boolean; fast: number; slow: number; signal: number }
  rsi: { on: boolean; n: number }
  kdj: { on: boolean; n: number; m1: number; m2: number }
}

export type OverlayKey = 'ma' | 'ema' | 'boll' | 'vwap'
export type OscillatorKey = 'macd' | 'rsi' | 'kdj'
export const OVERLAYS: readonly OverlayKey[] = ['ma', 'ema', 'boll', 'vwap']
export const OSCILLATORS: readonly OscillatorKey[] = ['macd', 'rsi', 'kdj']
export const INDICATOR_LABELS: Record<OverlayKey | OscillatorKey, { short: string; long: string }> = {
  ma: { short: 'MA', long: 'Moving average' },
  ema: { short: 'EMA', long: 'Exponential moving average' },
  boll: { short: 'BOLL', long: 'Bollinger bands' },
  vwap: { short: 'VWAP', long: 'Volume-weighted average price (intraday)' },
  macd: { short: 'MACD', long: 'Moving average convergence divergence' },
  rsi: { short: 'RSI', long: 'Relative strength index' },
  kdj: { short: 'KDJ', long: 'Stochastic KDJ' },
}

export const DEFAULT_INDICATORS: IndicatorSettings = {
  ma: { on: false, periods: [5, 10, 20] },
  ema: { on: false, periods: [12, 26] },
  boll: { on: false, n: 20, k: 2 },
  vwap: { on: false },
  macd: { on: false, fast: 12, slow: 26, signal: 9 },
  rsi: { on: false, n: 14 },
  kdj: { on: false, n: 9, m1: 3, m2: 3 },
}

/** Bounds of every parameter (inclusive). */
export const LIMITS = {
  period: { min: 1, max: 250 },
  bollK: { min: 0.5, max: 5 },
  osc: { min: 1, max: 100 },
  rsi: { min: 2, max: 100 },
} as const

const int = (v: unknown, fallback: number, min: number, max: number): number => {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN
  return Number.isFinite(n) ? Math.min(max, Math.max(min, Math.round(n))) : fallback
}

const dec = (v: unknown, fallback: number, min: number, max: number): number => {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN
  return Number.isFinite(n) ? Math.min(max, Math.max(min, Math.round(n * 10) / 10)) : fallback
}

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' ? (v as Record<string, unknown>) : {})

const periods = (v: unknown, fallback: number[]): number[] => {
  if (!Array.isArray(v)) return fallback
  // a period below 1 (or not a number) switches that line off
  const out = v
    .slice(0, 3)
    .map((p) => (typeof p === 'number' ? p : typeof p === 'string' ? Number(p) : NaN))
    .filter((p) => Number.isFinite(p) && p >= LIMITS.period.min)
    .map((p) => Math.min(LIMITS.period.max, Math.round(p)))
  return out.length > 0 ? out : fallback
}

/** A complete, clamped settings object from anything (a stored value, a partial edit). */
export function sanitizeIndicators(raw: unknown): IndicatorSettings {
  const r = obj(raw)
  const d = DEFAULT_INDICATORS
  const ma = obj(r.ma)
  const em = obj(r.ema)
  const bo = obj(r.boll)
  const vw = obj(r.vwap)
  const mc = obj(r.macd)
  const rs = obj(r.rsi)
  const kd = obj(r.kdj)
  const fast = int(mc.fast, d.macd.fast, LIMITS.osc.min, LIMITS.osc.max)
  let slow = int(mc.slow, d.macd.slow, LIMITS.osc.min, LIMITS.osc.max)
  if (slow <= fast) slow = Math.min(LIMITS.osc.max, fast + 1)
  return {
    ma: { on: ma.on === true, periods: periods(ma.periods, d.ma.periods) },
    ema: { on: em.on === true, periods: periods(em.periods, d.ema.periods) },
    boll: { on: bo.on === true, n: int(bo.n, d.boll.n, 2, LIMITS.period.max), k: dec(bo.k, d.boll.k, LIMITS.bollK.min, LIMITS.bollK.max) },
    vwap: { on: vw.on === true },
    macd: { on: mc.on === true, fast, slow, signal: int(mc.signal, d.macd.signal, LIMITS.osc.min, LIMITS.osc.max) },
    rsi: { on: rs.on === true, n: int(rs.n, d.rsi.n, LIMITS.rsi.min, LIMITS.rsi.max) },
    kdj: {
      on: kd.on === true,
      n: int(kd.n, d.kdj.n, LIMITS.osc.min, LIMITS.osc.max),
      m1: int(kd.m1, d.kdj.m1, LIMITS.osc.min, LIMITS.osc.max),
      m2: int(kd.m2, d.kdj.m2, LIMITS.osc.min, LIMITS.osc.max),
    },
  }
}

export const indicatorsKey = (playerId: string | null | undefined): string => `stocks.chart.indicators.${playerId || 'anon'}`

export function loadIndicators(playerId: string | null | undefined): IndicatorSettings {
  try {
    return sanitizeIndicators(JSON.parse(readLocal(indicatorsKey(playerId), 'null')))
  } catch {
    return DEFAULT_INDICATORS
  }
}

export function saveIndicators(playerId: string | null | undefined, settings: IndicatorSettings): void {
  writeLocal(indicatorsKey(playerId), JSON.stringify(settings))
}

/** The oscillators that get a pane, in pane order. */
export function activeOscillators(s: IndicatorSettings): OscillatorKey[] {
  return OSCILLATORS.filter((k) => s[k].on)
}

/** Short labels of the active indicators ("MA 5/10/20", "RSI 14"), for the toolbar and the legend. */
export function activeLabels(s: IndicatorSettings): string[] {
  const out: string[] = []
  if (s.ma.on) out.push(`MA ${s.ma.periods.join('/')}`)
  if (s.ema.on) out.push(`EMA ${s.ema.periods.join('/')}`)
  if (s.boll.on) out.push(`BOLL ${s.boll.n},${s.boll.k}`)
  if (s.vwap.on) out.push('VWAP')
  if (s.macd.on) out.push(`MACD ${s.macd.fast},${s.macd.slow},${s.macd.signal}`)
  if (s.rsi.on) out.push(`RSI ${s.rsi.n}`)
  if (s.kdj.on) out.push(`KDJ ${s.kdj.n},${s.kdj.m1},${s.kdj.m2}`)
  return out
}

export function parseChartType(v: string): ChartType | null {
  return (CHART_TYPES as readonly string[]).includes(v) ? (v as ChartType) : null
}
