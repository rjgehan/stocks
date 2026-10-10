import { useEffect, useState } from 'react'
import { type InfiniteData, type QueryClient, useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { FeedItem, HistoryRange, KindFilter, LeaderboardMetric, LeaderboardPeriod } from '../types'
import { realtime } from '../../realtime/stomp'

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

type FeedPage = { items: FeedItem[] | null; nextCursor: string | null }

/**
 * Puts a live `/topic/feed` item (§19: published when it becomes visible) at the top of the cached first feed page.
 * Returns false when the item is already there (a re-delivery or the REST page already had it).
 */
export function prependFeedItem(qc: QueryClient, item: FeedItem): boolean {
  let added = false
  qc.setQueryData<InfiniteData<FeedPage>>(queryKeys.feed, (data) => {
    if (!data || data.pages.length === 0) return data
    const seen = data.pages.some((p) => (p.items ?? []).some((i) => i.id != null && i.id === item.id))
    if (seen) return data
    added = true
    const [first, ...rest] = data.pages
    return { ...data, pages: [{ ...first!, items: [item, ...(first!.items ?? [])] }, ...rest] }
  })
  return added
}

/**
 * Live league feed while the feed view is open: subscribes to `/topic/feed` and prepends each new item (deduplicated
 * by id). Returns the ids that arrived live, so the list can highlight them. A reconnect resyncs by refetching.
 */
export function useLiveFeed(enabled = true): ReadonlySet<string> {
  const qc = useQueryClient()
  const [fresh, setFresh] = useState<ReadonlySet<string>>(() => new Set())
  useEffect(() => {
    if (!enabled) return
    const hub = realtime()
    const offs = [
      hub.subscribe('/topic/feed', (body) => {
        const item = body as FeedItem
        if (!item?.id) return
        if (prependFeedItem(qc, item)) setFresh((prev) => new Set(prev).add(item.id!))
      }),
      hub.onResync(() => void qc.invalidateQueries({ queryKey: queryKeys.feed })),
    ]
    return () => offs.forEach((off) => off())
  }, [qc, enabled])
  return fresh
}
