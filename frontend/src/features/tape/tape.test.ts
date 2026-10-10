import { describe, expect, it } from 'vitest'
import { formatPrintPrice, formatPrintTime, mergePrints, type Print, sideView } from './tape'

const p = (id: string, t: string, price = '10.00'): Print => ({ id, t, price, size: 100, side: 'BETWEEN', venue: null })

describe('Time & Sales', () => {
  it('merges batches newest first, by id, capped', () => {
    const seed = [p('c', '2026-10-09T14:00:03Z'), p('b', '2026-10-09T14:00:02Z'), p('a', '2026-10-09T14:00:01Z')]
    const merged = mergePrints(seed, [p('e', '2026-10-09T14:00:05Z'), p('d', '2026-10-09T14:00:04Z'), p('c', '2026-10-09T14:00:03Z')])
    expect(merged.map((x) => x.id)).toEqual(['e', 'd', 'c', 'b', 'a'])
    expect(mergePrints(merged, [], 2).map((x) => x.id)).toEqual(['e', 'd'])
    // a late (out-of-order) print lands at its time; same-time prints keep the batch order
    const late = mergePrints(merged, [p('x', '2026-10-09T14:00:03Z'), p('y', '2026-10-09T14:00:03Z')])
    expect(late.map((x) => x.id)).toEqual(['e', 'd', 'x', 'y', 'c', 'b', 'a'])
    expect(mergePrints([], [{ ...p('z', ''), t: null }])).toEqual([])
  })

  it('shows prices as traded and times in ET', () => {
    expect(formatPrintPrice('227.4')).toBe('227.40')
    expect(formatPrintPrice('227.4050')).toBe('227.405')
    expect(formatPrintPrice('0.12345')).toBe('0.1235')
    expect(formatPrintPrice('1234.5')).toBe('1,234.50')
    expect(formatPrintPrice(null)).toBe('--')
    expect(formatPrintTime('2026-10-09T18:31:07.120Z')).toBe('14:31:07')
    expect(formatPrintTime(null)).toBe('--')
  })

  it('never uses color alone: every known side has a glyph', () => {
    expect(sideView('AT_ASK')).toMatchObject({ glyph: '▲', tone: 'text-up' })
    expect(sideView('AT_BID')).toMatchObject({ glyph: '▼', tone: 'text-down' })
    expect(sideView('BETWEEN').glyph).toBe('•')
    expect(sideView(null).label).toBe('side unknown')
  })
})
