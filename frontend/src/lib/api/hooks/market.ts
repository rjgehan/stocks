import { useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { ChartRange } from '../types'

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
    queryFn: () => unwrap(api.GET('/api/v1/assets/{symbol}', { params: { path: { symbol } } })),
    staleTime: 5 * 60_000,
    enabled: symbol.length > 0,
  })
}

export function useBars(symbol: string, range: ChartRange) {
  return useQuery({
    queryKey: queryKeys.bars(symbol, range),
    queryFn: () => unwrap(api.GET('/api/v1/bars/{symbol}', { params: { path: { symbol }, query: { range } } })),
    staleTime: 60_000,
    enabled: symbol.length > 0,
  })
}

export function useAssetSearch(q: string) {
  return useQuery({
    queryKey: ['assetSearch', q],
    queryFn: () => unwrap(api.GET('/api/v1/assets/search', { params: { query: { q, limit: 10 } } })),
    enabled: q.trim().length > 0,
    staleTime: 60_000,
  })
}
