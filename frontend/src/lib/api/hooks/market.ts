import { useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { ChartRange, MoverList, Timeframe } from '../types'
import { toPath } from '../../symbols'

/**
 * `GET /rules` (§18.3 `Rules`): the rulebook the bots read, and the ticket's source for which order types this
 * release accepts (`orders.matrix`, one row per type with its sessions and time-in-force). Versioned, rarely changes.
 */
export function useRules() {
  return useQuery({
    queryKey: queryKeys.rules,
    queryFn: () => unwrap(api.GET('/api/v1/rules')),
    staleTime: 10 * 60_000,
    gcTime: 60 * 60_000,
  })
}

export function useClock() {
  return useQuery({
    queryKey: queryKeys.clock,
    queryFn: () => unwrap(api.GET('/api/v1/market/clock')),
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useAsset(symbol: string) {
  return useQuery({
    queryKey: queryKeys.asset(symbol),
    queryFn: () => unwrap(api.GET('/api/v1/assets/{symbol}', { params: { path: { symbol: toPath(symbol) } } })),
    staleTime: 10 * 60_000,
    gcTime: 30 * 60_000,
    enabled: symbol.length > 0,
  })
}

/** Bars for a chart range (§18.2 `GET /bars/{symbol}?range=`), optionally with an explicit interval (`tf`). */
export function useBars(symbol: string, range: ChartRange, tf?: Timeframe) {
  return useQuery({
    queryKey: queryKeys.bars(symbol, range, tf),
    queryFn: () =>
      unwrap(api.GET('/api/v1/bars/{symbol}', { params: { path: { symbol: toPath(symbol) }, query: tf ? { range, timeframe: tf } : { range } } })),
    // intraday ranges move every minute; longer ranges are stable for the session
    staleTime: range === '1D' || range === '5D' ? 30_000 : 5 * 60_000,
    refetchInterval: range === '1D' ? 60_000 : false,
    placeholderData: (prev, prevQuery) => (prevQuery?.queryKey[1] === symbol ? prev : undefined),
    enabled: symbol.length > 0,
  })
}

export function useAssetSearch(q: string) {
  const query = q.trim()
  return useQuery({
    queryKey: queryKeys.assetSearch(query.toUpperCase()),
    queryFn: () => unwrap(api.GET('/api/v1/assets/search', { params: { query: { q: query, limit: 12 } } })),
    enabled: query.length > 0,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  })
}

export function useMovers(list: MoverList) {
  return useQuery({
    queryKey: queryKeys.movers(list),
    queryFn: () => unwrap(api.GET('/api/v1/market/movers', { params: { query: { list, limit: 20 } } })),
    staleTime: 60_000,
    refetchInterval: 120_000,
  })
}

export function useNews(symbols: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.news(symbols),
    queryFn: () => unwrap(api.GET('/api/v1/news', { params: { query: symbols ? { symbols, limit: 10 } : { limit: 10 } } })),
    staleTime: 5 * 60_000,
    enabled,
  })
}

/** `GET /options/{underlying}/expirations` (§30): upcoming expirations, nearest first, plus the feed and the switch. */
export function useOptionExpirations(underlying: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.optionExpirations(underlying),
    queryFn: () => unwrap(api.GET('/api/v1/options/{underlying}/expirations', { params: { path: { underlying } } })),
    staleTime: 10 * 60_000,
    enabled: enabled && underlying.length > 0,
  })
}

/** One expiration's chain (§30), re-polled every 10 s while the page is visible (the chain has no STOMP topic). */
export function useOptionChain(underlying: string, expiration: string | null, enabled = true) {
  return useQuery({
    queryKey: queryKeys.optionChain(underlying, expiration ?? ''),
    queryFn: () => unwrap(api.GET('/api/v1/options/{underlying}/chain', { params: { path: { underlying }, query: { expiration: expiration! } } })),
    staleTime: 8_000,
    refetchInterval: 10_000,
    placeholderData: (prev, prevQuery) => (prevQuery?.queryKey[1] === underlying ? prev : undefined),
    enabled: enabled && underlying.length > 0 && Boolean(expiration),
  })
}
