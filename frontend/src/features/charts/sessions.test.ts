import { describe, expect, it } from 'vitest'
import { ALWAYS_REGULAR, etDateOf, etOf, regularSessionTest } from './sessions'
import { DEFAULT_INDICATORS, activeLabels, activeOscillators, loadIndicators, sanitizeIndicators, saveIndicators } from './indicatorSettings'

const sec = (iso: string) => Date.parse(iso) / 1000

describe('market time', () => {
  it('gives the ET date and minutes of a bar (DST included)', () => {
    expect(etOf(sec('2026-10-09T13:30:00Z'))).toEqual({ date: '2026-10-09', minutes: 570 })
    expect(etDateOf(sec('2026-10-10T03:59:00Z'))).toBe('2026-10-09') // 23:59 ET
    expect(etOf(sec('2026-12-01T14:30:00Z')).minutes).toBe(570) // EST
  })

  it('tests the regular session from the calendar (early close) or 09:30–16:00 ET', () => {
    const isRegular = regularSessionTest([
      { date: '2026-11-27', rthOpenAt: '2026-11-27T14:30:00.000Z', rthCloseAt: '2026-11-27T18:00:00.000Z' },
    ])
    expect(isRegular(sec('2026-11-27T17:59:00Z'))).toBe(true) // 12:59 ET on the early close
    expect(isRegular(sec('2026-11-27T18:00:00Z'))).toBe(false) // 13:00 ET: post-market
    expect(isRegular(sec('2026-10-09T13:29:00Z'))).toBe(false) // 09:29 ET
    expect(isRegular(sec('2026-10-09T13:30:00Z'))).toBe(true)
    expect(isRegular(sec('2026-10-09T19:59:00Z'))).toBe(true)
    expect(isRegular(sec('2026-10-09T20:00:00Z'))).toBe(false) // 16:00 ET
    expect(regularSessionTest(null)(sec('2026-10-09T15:00:00Z'))).toBe(true)
  })

  it('treats every crypto minute as regular (24/7: nothing is shaded)', () => {
    expect(ALWAYS_REGULAR(sec('2026-10-10T03:00:00Z'))).toBe(true) // a Friday 23:00 ET
    expect(ALWAYS_REGULAR(sec('2026-10-11T16:00:00Z'))).toBe(true) // a Sunday noon ET
  })
})

describe('indicator settings', () => {
  it('fills and clamps anything into a complete settings object', () => {
    expect(sanitizeIndicators(null)).toEqual(DEFAULT_INDICATORS)
    const s = sanitizeIndicators({
      ma: { on: true, periods: [0, 7, '300', 4, 9] },
      boll: { on: true, n: 1, k: 9.87 },
      macd: { on: true, fast: 30, slow: 10, signal: -3 },
      rsi: { on: 'yes', n: 1 },
      kdj: { on: true, n: 'x' },
    })
    expect(s.ma).toEqual({ on: true, periods: [7, 250] })
    expect(s.boll).toEqual({ on: true, n: 2, k: 5 })
    expect(s.macd).toEqual({ on: true, fast: 30, slow: 31, signal: 1 })
    expect(s.rsi).toEqual({ on: false, n: 2 })
    expect(s.kdj).toEqual({ on: true, n: 9, m1: 3, m2: 3 })
    expect(activeOscillators(s)).toEqual(['macd', 'kdj'])
    expect(activeLabels(s)).toEqual(['MA 7/250', 'BOLL 2,5', 'MACD 30,31,1', 'KDJ 9,3,3'])
  })

  it('is stored per player on this device', () => {
    const on = { ...DEFAULT_INDICATORS, rsi: { on: true, n: 10 } }
    saveIndicators('ply_a', on)
    expect(loadIndicators('ply_a').rsi).toEqual({ on: true, n: 10 })
    expect(loadIndicators('ply_b')).toEqual(DEFAULT_INDICATORS)
    localStorage.setItem('stocks.chart.indicators.ply_c', '{not json')
    expect(loadIndicators('ply_c')).toEqual(DEFAULT_INDICATORS)
  })
})
