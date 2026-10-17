import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../api/client'
import { queryKeys } from '../api/queryKeys'
import type { Quote, Tick } from '../api/types'
import { selectQuote, useQuoteStore } from './quoteStore'
import { usePollWhenOffline } from './status'
import { realtime } from './stomp'
import { fromPath, toPath } from '../symbols'

export type QuoteMode = 'quote' | 'tick'

/** `GET /quotes` takes at most 50 symbols (§18.2): larger lists are fetched in chunks. */
const QUOTES_MAX = 50

async function fetchQuotes(symbols: string): Promise<Quote[]> {
  const all = symbols.split(',').filter(Boolean)
  const chunks: string[][] = []
  for (let i = 0; i < all.length; i += QUOTES_MAX) chunks.push(all.slice(i, i + QUOTES_MAX))
  const pages = await Promise.all(
    chunks.map(async (chunk) => (await unwrap(api.GET('/api/v1/quotes', { params: { query: { symbols: chunk.join(',') } } }))).quotes ?? []),
  )
  const list = pages.flat()
  useQuoteStore.getState().upsertQuotes(list)
  return list
}

/**
 * Live quote for one symbol (§22.6): subscribes to `/topic/quote.{SYM}` (full quote, ≤ 4/s, quote page,
 * ticket) or `/topic/tick.{SYM}` (≤ 1/s, list rows), reference-counted in the hub, and seeds the store
 * from REST (`['quote', sym]`) unless the caller already seeded it in bulk. While the socket is down the
 * REST seed polls every 5 s instead (§19 fallback).
 */
export function useQuote(symbol: string | null | undefined, mode: QuoteMode = 'quote', options: { seed?: boolean } = {}): Quote | undefined {
  const seed = options.seed ?? true
  // canonical (`BTC/USD`): the store is keyed by the symbol the payloads carry; the topic takes the path spelling
  const sym = symbol ? fromPath(symbol) : undefined
  const poll = usePollWhenOffline(5_000, 60_000)
  useQuery({
    queryKey: queryKeys.quote(sym ?? ''),
    enabled: Boolean(sym) && seed,
    staleTime: 4_000,
    refetchInterval: poll,
    queryFn: async () => (await fetchQuotes(sym!))[0] ?? null,
  })
  useEffect(() => {
    if (!sym) return
    const store = useQuoteStore.getState()
    return realtime().subscribe(`/topic/${mode}.${toPath(sym)}`, (body) => {
      if (mode === 'quote') store.upsertQuote(body as Quote)
      else store.applyTick(body as Tick)
    })
  }, [sym, mode])
  return useQuoteStore(selectQuote(sym ?? ''))
}

/** Seed many symbols with one `GET /quotes` (watchlists, index strip); polls every 10 s while offline. */
export function useQuotesSeed(symbols: readonly string[]): void {
  const key = [...new Set(symbols.map((s) => s.toUpperCase()))].sort().join(',')
  const poll = usePollWhenOffline(10_000, 60_000)
  useQuery({
    queryKey: [...queryKeys.quote(key), 'bulk'],
    enabled: key.length > 0,
    staleTime: 8_000,
    refetchInterval: poll,
    queryFn: async () => (await fetchQuotes(key)).length,
  })
}
