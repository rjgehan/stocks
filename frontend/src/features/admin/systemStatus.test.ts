import { describe, expect, it } from 'vitest'
import { entries, isProbeFailure, jobTone, unlimited, usageLabel, usagePct } from './systemStatus'

describe('admin system status helpers', () => {
  it('shows an unlimited budget as ∞ instead of "/-1"', () => {
    expect(unlimited(-1)).toBe(true)
    expect(unlimited(2_147_483_647)).toBe(true)
    expect(unlimited(30)).toBe(false)
    expect(usageLabel(2, -1)).toBe('2/∞')
    expect(usageLabel(12, 30)).toBe('12/30')
    expect(usagePct(2, -1)).toBe(0)
    expect(usagePct(15, 30)).toBe(50)
    expect(usagePct(40, 30)).toBe(100)
  })

  it('colors the statuses the scheduler actually emits', () => {
    expect(jobTone('DONE')).toBe('up')
    expect(jobTone('FAILED')).toBe('down')
    expect(jobTone('RUNNING')).toBe('warn')
    expect(jobTone(null)).toBe('neutral')
  })

  it('flags probe failures', () => {
    expect(isProbeFailure('snapshots', 'HTTP 403')).toBe(true)
    expect(isProbeFailure('stream', 'HTTP 406')).toBe(true)
    expect(isProbeFailure('bars', 'HTTP 200')).toBe(false)
    expect(isProbeFailure('news', 'unreachable')).toBe(true)
    expect(isProbeFailure('logos', 'error: ProviderException')).toBe(true)
    expect(isProbeFailure('sipBars', 'ok')).toBe(false)
    expect(isProbeFailure('realtimeQuotes', false)).toBe(true)
    expect(isProbeFailure('snapshotBatch', 100)).toBe(false)
  })

  it('flattens maps into sorted rows', () => {
    expect(entries({ b: 'HTTP 403', a: true, c: { x: 1 } }, true)).toEqual([
      { key: 'a', value: 'true', failed: false },
      { key: 'b', value: 'HTTP 403', failed: true },
      { key: 'c', value: '{"x":1}', failed: false },
    ])
    expect(entries(null, true)).toEqual([])
    expect(entries({ statuses: false }, false)).toEqual([{ key: 'statuses', value: 'false', failed: false }])
  })
})
