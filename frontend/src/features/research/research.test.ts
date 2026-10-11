import { describe, expect, it } from 'vitest'
import { consensus, monthLabel, safeHttpUrl, timeAgo } from './research'

describe('analyst consensus', () => {
  it('maps the weighted mean to a label', () => {
    expect(consensus({ strongBuy: 10, buy: 0, hold: 0, sell: 0, strongSell: 0 })).toEqual({ label: 'Strong buy', score: 1 })
    expect(consensus({ strongBuy: 12, buy: 20, hold: 9, sell: 1, strongSell: 0 })?.label).toBe('Buy') // 2.0
    expect(consensus({ strongBuy: 0, buy: 1, hold: 1, sell: 0, strongSell: 0 })?.label).toBe('Buy') // 2.5 boundary
    expect(consensus({ strongBuy: 0, buy: 0, hold: 5, sell: 1, strongSell: 0 })?.label).toBe('Hold')
    expect(consensus({ strongBuy: 0, buy: 0, hold: 0, sell: 1, strongSell: 3 })?.label).toBe('Strong sell')
    expect(consensus({ strongBuy: 0, buy: 0, hold: 0, sell: 0, strongSell: 0 })).toBeNull()
  })
})

describe('news helpers', () => {
  const now = Date.parse('2026-10-09T15:00:00Z')
  it('shows relative times', () => {
    expect(timeAgo('2026-10-09T14:59:30Z', now)).toBe('now')
    expect(timeAgo('2026-10-09T14:48:00Z', now)).toBe('12m ago')
    expect(timeAgo('2026-10-09T12:00:00Z', now)).toBe('3h ago')
    expect(timeAgo('2026-10-07T15:00:00Z', now)).toBe('2d ago')
    expect(timeAgo('2026-09-20T15:00:00Z', now)).toBe('Sep 20')
    expect(timeAgo(null, now)).toBe('--')
    expect(timeAgo('2026-10-09T16:00:00Z', now)).toBe('now') // a clock skew never shows "in the future"
  })

  it('opens only http(s) links', () => {
    expect(safeHttpUrl('https://example.com/a?b=1')).toBe('https://example.com/a?b=1')
    expect(safeHttpUrl('http://example.com')).toBe('http://example.com/')
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(safeHttpUrl('data:text/html,hi')).toBeNull()
    expect(safeHttpUrl('/relative')).toBeNull()
    expect(safeHttpUrl('')).toBeNull()
  })

  it('labels recommendation months', () => {
    expect(monthLabel('2026-10-01')).toBe('Oct 2026')
    expect(monthLabel('2026-10-01', true)).toBe('Oct')
    expect(monthLabel('bad')).toBe('--')
  })
})
