import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { HistoryRange, KindFilter, LeaderboardMetric, LeaderboardPeriod } from '../types'

/** `GET /leaderboard`: refetched on `/topic/leaderboard` (§19); polls every 30 s as a fallback. */
export function useLeaderboard(period: LeaderboardPeriod, metric: LeaderboardMetric, kind: KindFilter) {
  return useQuery({
    queryKey: queryKeys.leaderboard(period, metric, kind),
    queryFn: () => unwrap(api.GET('/api/v1/leaderboard', { params: { query: { period, metric, kind, limit: 100 } } })),
    staleTime: 5_000,
    refetchInterval: 30_000,
    placeholderData: (prev) => prev,
  })
}

export function usePlayerProfile(handle: string) {
  return useQuery({
    queryKey: queryKeys.player(handle),
    queryFn: () => unwrap(api.GET('/api/v1/players/{handle}', { params: { path: { handle } } })),
    staleTime: 30_000,
  })
}

export function usePlayerHistory(handle: string, range: HistoryRange) {
  return useQuery({
    queryKey: queryKeys.playerHistory(handle, range),
    queryFn: () => unwrap(api.GET('/api/v1/players/{handle}/history', { params: { path: { handle }, query: { range } } })),
    staleTime: 60_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

export function usePlayerTrades(handle: string) {
  return useInfiniteQuery({
    queryKey: queryKeys.playerTrades(handle),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/players/{handle}/trades', {
          params: { path: { handle }, query: pageParam ? { cursor: pageParam, limit: 25 } : { limit: 25 } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 30_000,
    retry: false,
  })
}

export function useFeed() {
  return useInfiniteQuery({
    queryKey: queryKeys.feed,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => unwrap(api.GET('/api/v1/feed', { params: { query: pageParam ? { cursor: pageParam, limit: 30 } : { limit: 30 } } })),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 15_000,
    retry: false,
  })
}
