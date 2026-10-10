/**
 * Chart indicator math (§22.10), pure functions over plotted bars. DISPLAY ONLY: the inputs are the chart's plot
 * numbers (already converted from decimal strings for drawing), never money. A value is `null` until the indicator
 * has enough bars (the chart draws whitespace, never a zero).
 */

/** One plotted bar (seconds since the epoch, numbers for drawing). `vwap` is the bar's own VWAP when the feed sends it. */
export interface PlotOhlcv {
  time: number
  open: number
  high: number
  low: number
  close: number
  volume: number
  vwap?: number | null
}

export type Line = (number | null)[]

const nulls = (n: number): Line => Array.from({ length: n }, () => null)

/** Simple moving average of the last `n` values (null for the first n − 1). */
export function sma(values: readonly number[], n: number): Line {
  const out: Line = nulls(values.length)
  if (n < 1) return out
  let sum = 0
  for (let i = 0; i < values.length; i++) {
    sum += values[i]!
    if (i >= n) sum -= values[i - n]!
    if (i >= n - 1) out[i] = sum / n
  }
  return out
}

/**
 * Exponential moving average with alpha = 2 / (n + 1), seeded with the SMA of the first `n` values (null before).
 * Null inputs (e.g. a MACD line before it starts) are skipped: the EMA starts at the first `n` non-null values.
 */
export function ema(values: readonly (number | null)[], n: number): Line {
  const out: Line = nulls(values.length)
  if (n < 1) return out
  const alpha = 2 / (n + 1)
  let count = 0
  let seed = 0
  let prev: number | null = null
  for (let i = 0; i < values.length; i++) {
    const v = values[i]
    if (v == null) continue
    if (prev === null) {
      seed += v
      count++
      if (count === n) {
        prev = seed / n
        out[i] = prev
      }
    } else {
      prev = alpha * v + (1 - alpha) * prev
      out[i] = prev
    }
  }
  return out
}

export interface Bands {
  mid: Line
  upper: Line
  lower: Line
}

/** Bollinger bands: SMA(n) ± k population standard deviations of the last `n` closes. */
export function bollinger(closes: readonly number[], n: number, k: number): Bands {
  const mid = sma(closes, n)
  const upper: Line = nulls(closes.length)
  const lower: Line = nulls(closes.length)
  for (let i = n - 1; i < closes.length; i++) {
    const m = mid[i]
    if (m == null) continue
    let sq = 0
    for (let j = i - n + 1; j <= i; j++) sq += (closes[j]! - m) ** 2
    const sd = Math.sqrt(sq / n)
    upper[i] = m + k * sd
    lower[i] = m - k * sd
  }
  return { mid, upper, lower }
}

/**
 * Session-anchored VWAP: Σ(price × volume) / Σ(volume), restarting whenever `sessionOf(bar.time)` changes (each ET
 * trading day). The price is the bar's own `vwap` when present, else the typical price (H + L + C) / 3. A session
 * with no volume yet has no VWAP.
 */
export function vwap(bars: readonly PlotOhlcv[], sessionOf: (time: number) => string): Line {
  const out: Line = nulls(bars.length)
  let session: string | null = null
  let pv = 0
  let vol = 0
  for (let i = 0; i < bars.length; i++) {
    const b = bars[i]!
    const s = sessionOf(b.time)
    if (s !== session) {
      session = s
      pv = 0
      vol = 0
    }
    const price = b.vwap != null && Number.isFinite(b.vwap) && b.vwap > 0 ? b.vwap : (b.high + b.low + b.close) / 3
    pv += price * b.volume
    vol += b.volume
    out[i] = vol > 0 ? pv / vol : null
  }
  return out
}

export interface Macd {
  macd: Line
  signal: Line
  hist: Line
}

/** MACD: EMA(fast) − EMA(slow); signal = EMA(signal) of the MACD line; histogram = MACD − signal. */
export function macd(closes: readonly number[], fast: number, slow: number, signalN: number): Macd {
  const f = ema(closes, fast)
  const s = ema(closes, slow)
  const line: Line = closes.map((_, i) => (f[i] != null && s[i] != null ? f[i]! - s[i]! : null))
  const signal = ema(line, signalN)
  const hist: Line = line.map((m, i) => (m != null && signal[i] != null ? m - signal[i]! : null))
  return { macd: line, signal, hist }
}

/**
 * RSI with Wilder smoothing: the first average gain/loss is the mean of the first `n` changes, then
 * avg = (prev × (n − 1) + current) / n. RSI = 100 − 100 / (1 + gain / loss); 100 when there are no losses, 50 when
 * the price did not move at all. Null for the first `n` bars.
 */
export function rsi(closes: readonly number[], n: number): Line {
  const out: Line = nulls(closes.length)
  if (n < 1 || closes.length <= n) return out
  let gain = 0
  let loss = 0
  for (let i = 1; i <= n; i++) {
    const d = closes[i]! - closes[i - 1]!
    if (d > 0) gain += d
    else loss -= d
  }
  gain /= n
  loss /= n
  out[n] = rsiOf(gain, loss)
  for (let i = n + 1; i < closes.length; i++) {
    const d = closes[i]! - closes[i - 1]!
    gain = (gain * (n - 1) + (d > 0 ? d : 0)) / n
    loss = (loss * (n - 1) + (d < 0 ? -d : 0)) / n
    out[i] = rsiOf(gain, loss)
  }
  return out
}

function rsiOf(gain: number, loss: number): number {
  if (loss === 0) return gain === 0 ? 50 : 100
  return 100 - 100 / (1 + gain / loss)
}

export interface Kdj {
  k: Line
  d: Line
  j: Line
}

/**
 * KDJ (stochastic with smoothing): RSV = (C − lowest low of `n`) / (highest high − lowest low) × 100 (50 when the
 * range is zero); K = ((m1 − 1) × K₋₁ + RSV) / m1, D = ((m2 − 1) × D₋₁ + K) / m2, both starting at 50;
 * J = 3K − 2D. Null for the first n − 1 bars.
 */
export function kdj(bars: readonly PlotOhlcv[], n: number, m1: number, m2: number): Kdj {
  const k: Line = nulls(bars.length)
  const d: Line = nulls(bars.length)
  const j: Line = nulls(bars.length)
  let prevK = 50
  let prevD = 50
  for (let i = n - 1; i < bars.length; i++) {
    let hi = -Infinity
    let lo = Infinity
    for (let x = i - n + 1; x <= i; x++) {
      hi = Math.max(hi, bars[x]!.high)
      lo = Math.min(lo, bars[x]!.low)
    }
    const rsv = hi === lo ? 50 : ((bars[i]!.close - lo) / (hi - lo)) * 100
    const kk = ((m1 - 1) * prevK + rsv) / m1
    const dd = ((m2 - 1) * prevD + kk) / m2
    k[i] = kk
    d[i] = dd
    j[i] = 3 * kk - 2 * dd
    prevK = kk
    prevD = dd
  }
  return { k, d, j }
}

/**
 * Heikin-Ashi bars: close = (O + H + L + C) / 4; open = (previous HA open + previous HA close) / 2, the first one
 * (O + C) / 2; high/low = the max/min of the bar's high/low and the HA open and close. Volume and time are kept.
 */
export function heikinAshi(bars: readonly PlotOhlcv[]): PlotOhlcv[] {
  const out: PlotOhlcv[] = []
  for (let i = 0; i < bars.length; i++) {
    const b = bars[i]!
    const close = (b.open + b.high + b.low + b.close) / 4
    const prev = out[i - 1]
    const open = prev ? (prev.open + prev.close) / 2 : (b.open + b.close) / 2
    out.push({ ...b, open, close, high: Math.max(b.high, open, close), low: Math.min(b.low, open, close) })
  }
  return out
}

export interface CandleStyle {
  color: string
  borderColor: string
  wickColor: string
}

/**
 * Hollow candles: the color follows the close against the previous close (up or down; the first bar against its own
 * open), and the body is hollow (transparent, outlined) when the close is at or above the open, filled otherwise.
 */
export function hollowStyles(bars: readonly PlotOhlcv[], up: string, down: string): CandleStyle[] {
  return bars.map((b, i) => {
    const ref = i === 0 ? b.open : bars[i - 1]!.close
    const color = b.close >= ref ? up : down
    return { color: b.close >= b.open ? 'rgba(0,0,0,0)' : color, borderColor: color, wickColor: color }
  })
}

/** A run of consecutive extended-hours bars, as [first bar time, last bar time]. */
export interface TimeSpan {
  from: number
  to: number
}

/** Groups the bars outside the regular session into spans (`isRegular(time)` decides per bar). */
export function extendedSpans(times: readonly number[], isRegular: (time: number) => boolean): TimeSpan[] {
  const spans: TimeSpan[] = []
  let open: TimeSpan | null = null
  for (const t of times) {
    if (isRegular(t)) {
      open = null
      continue
    }
    if (open) open.to = t
    else {
      open = { from: t, to: t }
      spans.push(open)
    }
  }
  return spans
}
