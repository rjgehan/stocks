import { describe, expect, it } from 'vitest'
import { bollinger, ema, extendedSpans, heikinAshi, hollowStyles, kdj, macd, type PlotOhlcv, rsi, sma, vwap } from './indicators'

/**
 * Golden vectors (§22.10): the closes are the classic Wilder RSI example data (StockCharts' worked example), and every
 * expected value was computed independently with exact rational arithmetic (Python `fractions`), then rounded.
 */
const CLOSES = '44.34 44.09 44.15 43.61 44.33 44.83 45.10 45.42 45.84 46.08 45.89 46.03 45.61 46.28 46.28 46.00 46.03 46.41 46.22 45.64 46.21 46.25 45.71 46.45 45.78 45.35 44.03 44.18 44.22 44.57 43.42 42.66 43.13'
  .split(' ')
  .map(Number)

const expectClose = (actual: (number | null)[], expected: (number | null)[], digits: number) => {
  expect(actual).toHaveLength(expected.length)
  expect(close(actual, expected, digits)).not.toContain(false)
}

/** Each value within 10^-digits of the golden one (null where the indicator has not started). */
const close = (actual: (number | null)[], expected: (number | null)[], digits: number) =>
  actual.map((v, i) => (expected[i] === null ? v === null : v !== null && Math.abs(v - expected[i]!) < 10 ** -digits))

const regular = (t: number) => t >= 3 && t <= 5

const bars: PlotOhlcv[] = CLOSES.map((c, i) => ({ time: 1_000 + i * 60, open: c, high: c + 0.5, low: c - 0.5, close: c, volume: 100 }))

describe('moving averages', () => {
  it('SMA is null until n values, then the mean of the last n', () => {
    const golden = [null, null, null, null, 44.104, 44.202, 44.404, 44.658, 45.104, 45.454]
    expect(close(sma(CLOSES, 5).slice(0, 10), golden, 9)).toEqual(golden.map(() => true))
  })

  it('EMA is seeded with the SMA of the first n values', () => {
    const golden = [null, 44.779, 44.981, 45.171727, 45.251413, 45.438429]
    expect(close(ema(CLOSES, 10).slice(8, 14), golden, 5)).toEqual(golden.map(() => true))
  })

  it('EMA skips leading nulls (the MACD signal line) and n < 1 yields nothing', () => {
    expect(ema([null, null, 2, 4, 6], 2)).toEqual([null, null, null, 3, 5])
    expect(ema([1, 2, 3], 0)).toEqual([null, null, null])
    expect(sma([1, 2, 3], 0)).toEqual([null, null, null])
  })
})

describe('Bollinger bands', () => {
  it('SMA(20) ± 2 population standard deviations', () => {
    const b = bollinger(CLOSES, 20, 2)
    expect(b.mid[18]).toBeNull()
    expect(b.mid[19]).toBeCloseTo(45.409, 6)
    expect(b.upper[19]).toBeCloseTo(47.115328, 5)
    expect(b.lower[19]).toBeCloseTo(43.702672, 5)
    expect(b.upper[25]).toBeCloseTo(46.651576, 5)
    expect(b.lower[32]).toBeCloseTo(42.86185, 5)
  })
})

describe('RSI (Wilder)', () => {
  it('matches the reference series from the first full window on', () => {
    const r = rsi(CLOSES, 14)
    expect(r.slice(0, 14).every((v) => v === null)).toBe(true)
    expectClose(
      r.slice(14),
      [70.4641, 66.2496, 66.4809, 69.3469, 66.2947, 57.915, 62.8807, 63.2088, 56.0116, 62.3399, 54.671, 50.3868, 40.0194, 41.4926, 41.9024, 45.4995, 37.3228, 33.0905, 37.7888],
      3,
    )
  })

  it('is 100 with no losses, 50 with no movement, and empty for short series', () => {
    expect(rsi([1, 2, 3, 4], 3)[3]).toBe(100)
    expect(rsi([5, 5, 5, 5], 3)[3]).toBe(50)
    expect(rsi([1, 2], 14)).toEqual([null, null])
  })
})

describe('MACD', () => {
  it('EMA(fast) − EMA(slow), its signal EMA and the histogram', () => {
    const m = macd(CLOSES, 3, 6, 4)
    expect(m.macd[4]).toBeNull()
    expectClose(m.macd.slice(5, 12), [0.247917, 0.311458, 0.358229, 0.413757, 0.425909, 0.328691, 0.277014], 5)
    expectClose(m.signal.slice(5, 12), [null, null, null, 0.33284, 0.370068, 0.353517, 0.322916], 5)
    expectClose(m.hist.slice(5, 12), [null, null, null, 0.080917, 0.055841, -0.024826, -0.045902], 5)
  })
})

describe('KDJ', () => {
  it('RSV over n with K and D smoothed from 50, J = 3K − 2D', () => {
    const r = kdj(bars, 9, 3, 3)
    expect(r.k[7]).toBeNull()
    expectClose(r.k.slice(8, 13), [61.506708, 69.534731, 73.061579, 76.757671, 72.747538], 5)
    expectClose(r.d.slice(8, 13), [53.835569, 59.068623, 63.732942, 68.074518, 69.632192], 5)
    expectClose(r.j.slice(8, 13), [76.848985, 90.466947, 91.718853, 94.123977, 78.978232], 5)
  })

  it('a flat window has RSV 50', () => {
    const flat = Array.from({ length: 3 }, (_, i) => ({ time: i, open: 1, high: 1, low: 1, close: 1, volume: 1 }))
    expect(kdj(flat, 3, 3, 3).k[2]).toBe(50)
  })
})

describe('VWAP', () => {
  it('accumulates price × volume per session and restarts at a new session', () => {
    const b: PlotOhlcv[] = [
      { time: 1, open: 10, high: 12, low: 9, close: 9, volume: 100 }, // typical (12+9+9)/3 = 10
      { time: 2, open: 10, high: 13, low: 10, close: 13, volume: 300 }, // typical 12
      { time: 3, open: 10, high: 10, low: 10, close: 10, volume: 0 }, // no volume: unchanged
      { time: 4, open: 20, high: 22, low: 18, close: 20, volume: 50, vwap: 21 }, // new session, the bar's own vwap
      { time: 5, open: 20, high: 20, low: 20, close: 20, volume: 50 },
    ]
    const v = vwap(b, (t) => (t < 4 ? 'd1' : 'd2'))
    expect(v[0]).toBeCloseTo(10, 9)
    expect(v[1]).toBeCloseTo((10 * 100 + 12 * 300) / 400, 9)
    expect(v[2]).toBeCloseTo(11.5, 9)
    expect(v[3]).toBeCloseTo(21, 9)
    expect(v[4]).toBeCloseTo(20.5, 9)
    expect(vwap([{ time: 1, open: 1, high: 1, low: 1, close: 1, volume: 0 }], () => 'd')).toEqual([null])
  })
})

describe('chart types', () => {
  it('Heikin-Ashi bars', () => {
    const ha = heikinAshi([
      { time: 1, open: 10, high: 12, low: 9, close: 11, volume: 5 },
      { time: 2, open: 11, high: 13, low: 10, close: 12, volume: 6 },
    ])
    expect(ha[0]).toMatchObject({ open: 10.5, close: 10.5, high: 12, low: 9, volume: 5 })
    expect(ha[1]!.open).toBeCloseTo(10.5, 9)
    expect(ha[1]!.close).toBeCloseTo(11.5, 9)
    expect(ha[1]!.high).toBe(13)
    expect(ha[1]!.low).toBe(10)
  })

  it('hollow candles: color by previous close, hollow when close ≥ open', () => {
    const s = hollowStyles(
      [
        { time: 1, open: 10, high: 11, low: 9, close: 11, volume: 1 }, // up vs its open, hollow
        { time: 2, open: 12, high: 12, low: 10, close: 10.5, volume: 1 }, // down vs 11, filled
        { time: 3, open: 10, high: 11, low: 9, close: 10.8, volume: 1 }, // up vs 10.5, hollow
        { time: 4, open: 11, high: 11, low: 10, close: 10.6, volume: 1 }, // down vs 10.8, filled red
      ],
      'U',
      'D',
    )
    expect(s.map((x) => x.borderColor)).toEqual(['U', 'D', 'U', 'D'])
    expect(s.map((x) => x.color)).toEqual(['rgba(0,0,0,0)', 'D', 'rgba(0,0,0,0)', 'D'])
  })

  it('groups consecutive extended-hours bars into spans', () => {
    expect(extendedSpans([1, 2, 3, 4, 5, 6, 7, 9], regular)).toEqual([
      { from: 1, to: 2 },
      { from: 6, to: 9 },
    ])
    expect(extendedSpans([3, 4], regular)).toEqual([])
  })
})
