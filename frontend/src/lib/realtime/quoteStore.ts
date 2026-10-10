/**
 * Live quotes keyed by symbol (§22.6). Components select ONE symbol, so a tick for AAPL re-renders only
 * the AAPL row, never the whole list. Writers: REST seeds (`['quote', sym]`, `/quotes`) and the
 * `/topic/quote.*` / `/topic/tick.*` streams.
 */
import { create } from 'zustand'
import type { Quote, Tick } from '../api/types'

interface QuoteState {
  quotes: Record<string, Quote>
  upsertQuote: (q: Quote) => void
  upsertQuotes: (qs: Quote[]) => void
  applyTick: (t: Tick) => void
}

export const useQuoteStore = create<QuoteState>()((set) => ({
  quotes: {},
  upsertQuote: (q) => set((s) => ({ quotes: { ...s.quotes, [q.symbol]: q } })),
  upsertQuotes: (qs) =>
    set((s) => {
      const next = { ...s.quotes }
      for (const q of qs) next[q.symbol] = q
      return { quotes: next }
    }),
  applyTick: (t) =>
    set((s) => {
      const prev = s.quotes[t.symbol]
      if (!prev) return s // a tick carries no bid/ask; wait for a REST seed or a full quote
      const extended =
        t.extended && prev.extended ? { ...prev.extended, price: t.extended.price, changePct: t.extended.changePct } : prev.extended
      return {
        quotes: {
          ...s.quotes,
          [t.symbol]: { ...prev, last: t.last, change: t.change, changePct: t.changePct, extended, asOf: t.asOf, quality: t.quality },
        },
      }
    }),
}))

export const selectQuote =
  (symbol: string) =>
  (s: QuoteState): Quote | undefined =>
    s.quotes[symbol]
