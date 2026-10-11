import { describe, expect, it } from 'vitest'
import type { Quote } from '@/lib/api/types'
import { clampWidth, columnValue, cycleSort, readColumns, readSort, sortSymbols, widthBounds, writeColumns, writeSort } from './columns'

describe('watchlist columns', () => {
  it('stores the chosen columns in catalog order and falls back to the default', () => {
    expect(readColumns()).toEqual(['last', 'changePct'])
    writeColumns(['dayRange', 'last', 'volume'])
    expect(readColumns()).toEqual(['last', 'volume', 'dayRange'])
    localStorage.setItem('stocks.watchlist.columns', 'bogus,,')
    expect(readColumns()).toEqual(['last', 'changePct'])
  })

  it('cycles a header and remembers the sort', () => {
    expect(cycleSort(null, 'volume')).toEqual({ col: 'volume', dir: 'desc' })
    expect(cycleSort({ col: 'volume', dir: 'desc' }, 'volume')).toEqual({ col: 'volume', dir: 'asc' })
    expect(cycleSort({ col: 'volume', dir: 'asc' }, 'volume')).toBeNull()
    expect(cycleSort({ col: 'volume', dir: 'asc' }, 'symbol')).toEqual({ col: 'symbol', dir: 'asc' })
    writeSort({ col: 'marketCap', dir: 'asc' })
    expect(readSort()).toEqual({ col: 'marketCap', dir: 'asc' })
    writeSort(null)
    expect(readSort()).toBeNull()
  })

  it('sorts with nulls last both ways and keeps list order on ties', () => {
    const v: Record<string, number | null> = { A: 3, B: null, C: 3, D: 1 }
    expect(sortSymbols(['A', 'B', 'C', 'D'], { col: 'last', dir: 'desc' }, (s) => v[s]!)).toEqual(['A', 'C', 'D', 'B'])
    expect(sortSymbols(['A', 'B', 'C', 'D'], { col: 'last', dir: 'asc' }, (s) => v[s]!)).toEqual(['D', 'A', 'C', 'B'])
    expect(sortSymbols(['B', 'A'], null, () => 0)).toEqual(['B', 'A'])
  })

  it('reads sortable values from quotes', () => {
    const q = { last: '10.50', change: '-0.25', changePct: '-0.0233', volume: 1200, bid: '10.49', ask: '10.52', dayHigh: '11', dayLow: '10' } as unknown as Quote
    expect(columnValue('last', 'X', q, undefined)).toBe(10.5)
    expect(columnValue('changePct', 'X', q, undefined)).toBe(-0.0233)
    expect(columnValue('bidAsk', 'X', q, undefined)).toBeCloseTo(0.03, 9)
    expect(columnValue('dayRange', 'X', q, undefined)).toBe(1)
    expect(columnValue('volume', 'X', undefined, undefined)).toBeNull()
    expect(columnValue('symbol', 'X', undefined, undefined)).toBe('X')
  })

  it('bounds the panel width by the viewport', () => {
    expect(widthBounds(1440, true)).toEqual({ min: 260, max: 704, initial: 300 })
    expect(widthBounds(1024, false)).toEqual({ min: 260, max: 328, initial: 260 })
    expect(clampWidth(900, widthBounds(1440, true))).toBe(704)
    expect(clampWidth(100, widthBounds(1440, true))).toBe(260)
  })
})
