import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../api/client'
import { queryKeys } from '../api/queryKeys'
import type { Quote, Tick } from '../api/types'
import { selectQuote, useQuoteStore } from './quoteStore'
import { realtime } from './stomp'

export type QuoteMode = 'quote' | 'tick'

/**
 * Live quote for one symbol (§22.6): subscribes to `/topic/quote.{SYM}` (full quote, ≤ 4/s, quote page,
 * ticket) or `/topic/tick.{SYM}` (≤ 1/s, list rows), reference-counted in the hub, and seeds the store
 * from REST (`['quote', sym]`) unless the caller already seeded it in bulk.
 */
export function useQuote(symbol: string | undefined, mode: QuoteMode = 'quote', options: { seed?: boolean } = {}): Quote | undefined {
  const seed = options.seed ?? true
  const sym = symbol?.toUpperCase()

  useQuery({
    queryKey: queryKeys.quote(sym ?? ''),
    enabled: Boolean(sym) && seed,
    staleTime: 30_000,
    queryFn: async () => {
      const { quotes } = await unwrap(api.GET('/api/v1/quotes', { params: { query: { symbols: sym! } } }))
      useQuoteStore.getState().upsertQuotes(quotes)
      return quotes[0] ?? null
    },
  })

  useEffect(() => {
    if (!sym) return
    const store = useQuoteStore.getState()
    return realtime().subscribe(`/topic/${mode}.${sym}`, (body) => {
      if (mode === 'quote') store.upsertQuote(body as Quote)
      else store.applyTick(body as Tick)
    })
  }, [sym, mode])

  return useQuoteStore(selectQuote(sym ?? ''))
}

/** Seed many symbols with one `GET /quotes` (watchlists, index strip). */
export function useQuotesSeed(symbols: readonly string[]): void {
  const key = [...symbols].sort().join(',')
  useQuery({
    queryKey: [...queryKeys.quote(key), 'bulk'],
    enabled: key.length > 0,
    staleTime: 30_000,
    queryFn: async () => {
      const { quotes } = await unwrap(api.GET('/api/v1/quotes', { params: { query: { symbols: key } } }))
      useQuoteStore.getState().upsertQuotes(quotes)
      return quotes.length
    },
  })
}
