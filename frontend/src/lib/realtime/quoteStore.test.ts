import { beforeEach, describe, expect, it } from 'vitest'
import type { Quote } from '../api/types'
import { useQuoteStore } from './quoteStore'

const quote = (over: Partial<Quote>): Quote => ({ symbol: 'AAPL', bid: '227.10', ask: '227.12', last: '227.11', change: '2.74', changePct: '0.0122', extended: null, asOf: '2026-10-09T14:31:00Z', quality: 'REALTIME_SINGLE_VENUE', ...over }) as Quote

beforeEach(() => useQuoteStore.setState({ quotes: {} }))

describe('quoteStore (§22.6)', () => {
  it('never lets an older REST response replace a fresher pushed quote', () => {
    const s = useQuoteStore.getState()
    s.upsertQuote(quote({ last: '228.00', asOf: '2026-10-09T14:31:05Z' }))
    s.upsertQuotes([quote({ last: '227.00', asOf: '2026-10-09T14:31:00Z' })])
    expect(useQuoteStore.getState().quotes.AAPL?.last).toBe('228.00')
    s.upsertQuote(quote({ last: '229.00', asOf: '2026-10-09T14:31:06Z' }))
    expect(useQuoteStore.getState().quotes.AAPL?.last).toBe('229.00')
  })

  it('applies ticks onto a seeded quote and keeps bid/ask', () => {
    const s = useQuoteStore.getState()
    s.applyTick({ symbol: 'AAPL', last: '1', change: null, changePct: null, extended: null, asOf: null, quality: null })
    expect(useQuoteStore.getState().quotes.AAPL).toBeUndefined() // nothing to apply to yet
    s.upsertQuote(quote({}))
    s.applyTick({ symbol: 'AAPL', last: '227.50', change: '3.13', changePct: '0.0139', extended: null, asOf: '2026-10-09T14:31:01Z', quality: 'REALTIME_SINGLE_VENUE' })
    expect(useQuoteStore.getState().quotes.AAPL).toMatchObject({ last: '227.50', change: '3.13', bid: '227.10', ask: '227.12' })
  })

  it('updates only the symbol that changed', () => {
    const s = useQuoteStore.getState()
    s.upsertQuotes([quote({}), quote({ symbol: 'NVDA', last: '129.00' })])
    const nvda = useQuoteStore.getState().quotes.NVDA
    s.upsertQuote(quote({ last: '230.00', asOf: '2026-10-09T14:32:00Z' }))
    expect(useQuoteStore.getState().quotes.NVDA).toBe(nvda)
  })
})
