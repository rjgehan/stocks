import { describe, expect, it } from 'vitest'
import { CHART_RANGES } from './RangeChips'
import { DEFAULT_TIMEFRAME, INTERVALS, effectiveTimeframe, parseTimeframe } from './IntervalPicker'

describe('interval picker (§22.4, §15.5 defaults)', () => {
  it('offers the default interval for every range', () => {
    for (const r of CHART_RANGES) expect(INTERVALS[r]).toContain(DEFAULT_TIMEFRAME[r])
  })

  it('requests a timeframe only when it differs from the default and suits the range', () => {
    expect(effectiveTimeframe('1D', undefined)).toBeUndefined()
    expect(effectiveTimeframe('1D', '1Min')).toBeUndefined()
    expect(effectiveTimeframe('1D', '5Min')).toBe('5Min')
    expect(effectiveTimeframe('1D', '1Day')).toBeUndefined() // one bar a day is not a 1D chart
    expect(effectiveTimeframe('1Y', '1Week')).toBe('1Week')
  })

  it('parses the tf search param case-insensitively', () => {
    expect(parseTimeframe('1week')).toBe('1Week')
    expect(parseTimeframe('5MIN')).toBe('5Min')
    expect(parseTimeframe('2Min')).toBeUndefined()
    expect(parseTimeframe(5)).toBeUndefined()
  })
})
