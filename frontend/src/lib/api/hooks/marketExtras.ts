/**
 * Phase 2 market hooks (§15.10, §22.10): the trading calendar for chart sessions, Time & Sales, analyst
 * recommendation trends, paged news and the screener. Query keys live here (namespaced by feature) so the shared
 * `queryKeys.ts` stays as it is.
 */
import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import type { Schemas } from '../contract'
import { toPath } from '../../symbols'

export type TimeAndSales = Schemas['TimeAndSales']
export type TapePrint = Schemas['TapePrint']
export type Recommendations = Schemas['Recommendations']
export type RecommendationPeriod = Schemas['RecommendationPeriod']
export type ScreenResult = Schemas['ScreenResult']
export type ScreenerRow = Schemas['ScreenerRow']
export type TradingDay = Schemas['TradingDay']

export type ScreenPreset = NonNullable<ScreenResult['preset']>
export type ScreenSort = NonNullable<ScreenResult['sort']>
export type ScreenOrder = NonNullable<ScreenResult['order']>
export type Sector = NonNullable<ScreenerRow['sector']>

/** Trading days between two ET dates (inclusive), for the chart's session bands; stable for hours. */
export function useCalendarDays(from: string | null, to: string | null) {
  return useQuery({
    queryKey: ['calendarDays', from, to] as const,
    queryFn: () => unwrap(api.GET('/api/v1/market/calendar', { params: { query: { from: from!, to: to! } } })),
    enabled: Boolean(from && to),
    staleTime: 6 * 60 * 60_000,
    gcTime: 12 * 60 * 60_000,
  })
}

/** REST seed of the Time & Sales tape (newest first); live prints arrive on `/topic/trades.{SYM}`. */
export function useTimeAndSales(symbol: string, limit = 60, refetchInterval: number | false = false) {
  return useQuery({
    queryKey: ['trades', symbol, limit] as const,
    queryFn: () => unwrap(api.GET('/api/v1/assets/{symbol}/trades', { params: { path: { symbol: toPath(symbol) }, query: { limit } } })),
    enabled: symbol.length > 0,
    staleTime: 30_000,
    refetchInterval,
  })
}

/** Analyst recommendation trends (Finnhub, cached 12 h on the server). */
export function useRecommendations(symbol: string) {
  return useQuery({
    queryKey: ['recommendations', symbol] as const,
    queryFn: () => unwrap(api.GET('/api/v1/assets/{symbol}/recommendations', { params: { path: { symbol: toPath(symbol) } } })),
    enabled: symbol.length > 0,
    staleTime: 60 * 60_000,
    gcTime: 2 * 60 * 60_000,
  })
}

/** Symbol news with "Load more": each page asks for items published before the oldest one shown. */
export function useNewsPages(symbols: string, pageSize = 10) {
  return useInfiniteQuery({
    queryKey: ['newsPages', symbols, pageSize] as const,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/news', {
          params: { query: { ...(symbols ? { symbols } : {}), limit: pageSize, ...(pageParam ? { before: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => {
      const items = last.items ?? []
      if (items.length < pageSize) return undefined
      return items[items.length - 1]?.publishedAt ?? undefined
    },
    staleTime: 5 * 60_000,
  })
}

/** Screener query parameters (§15.10); undefined members are not sent. */
export interface ScreenParams {
  preset?: ScreenPreset
  minPrice?: string
  maxPrice?: string
  minMarketCap?: string
  maxMarketCap?: string
  minChangePct?: string
  maxChangePct?: string
  minVolume?: number
  minAvgVolume?: number
  minRelVolume?: string
  sectors?: Sector[]
  types?: ('COMMON_STOCK' | 'ETF' | 'ADR')[]
  sort?: ScreenSort
  order?: ScreenOrder
}

/** The screener, paged by `nextCursor`; refetched every 30 s and every 5 s while the server is still warming. */
export function useScreener(params: ScreenParams, pageSize = 25) {
  const query = Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== '' && !(Array.isArray(v) && v.length === 0)),
  ) as ScreenParams
  return useInfiniteQuery({
    queryKey: ['screener', query, pageSize] as const,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/market/screener', {
          params: { query: { ...query, limit: pageSize, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
    refetchInterval: (q) => (q.state.data?.pages[0]?.warming ? 5_000 : 30_000),
  })
}
