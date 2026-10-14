import { useEffect, useState } from 'react'
import { type InfiniteData, type QueryClient, useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { type OrdersFilter, queryKeys } from '../queryKeys'
import type { CalendarView, FeedItem, HistoryRange, KindFilter, LeaderboardMetric, LeaderboardPeriod } from '../types'
import { realtime } from '../../realtime/stomp'
import { agentKeys } from './agents'
import { applyFeedReactions, isFeedReactions } from './stats'

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

// ---------------------------------------------------------------- public mode (§16.7)

/** `GET /players/{handle}/portfolio`: a player's live run (cash, positions, open orders); polls every 15 s while visible. */
export function usePlayerPortfolio(handle: string) {
  return useQuery({
    queryKey: queryKeys.playerPortfolio(handle),
    queryFn: () => unwrap(api.GET('/api/v1/players/{handle}/portfolio', { params: { path: { handle } } })),
    staleTime: 5_000,
    refetchInterval: 15_000,
    refetchIntervalInBackground: false,
    retry: false,
  })
}

/** Orders per page of a player's History. */
export const PLAYER_ORDERS_PAGE = 25

/** `GET /players/{handle}/orders`: every stored order of every run, newest first, 25 a page; polls every 30 s while visible. */
export function usePlayerOrders(handle: string, filter: { status?: OrdersFilter; symbol?: string } = {}) {
  const { status, symbol } = filter
  return useInfiniteQuery({
    queryKey: queryKeys.playerOrders(handle, status, symbol),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/players/{handle}/orders', {
          params: {
            path: { handle },
            query: { ...(status && status !== 'ALL' ? { status } : {}), ...(symbol ? { symbol } : {}), ...(pageParam ? { cursor: pageParam } : {}), limit: PLAYER_ORDERS_PAGE },
          },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 5_000,
    refetchInterval: 30_000,
    retry: false,
  })
}

/** Activity rows per page of a player's History → Activity. */
export const PLAYER_ACTIVITY_PAGE = 30

/** `GET /players/{handle}/activities`: the active run's ledger and account events, sanitized; 30 a page, no polling. */
export function usePlayerActivity(handle: string) {
  return useInfiniteQuery({
    queryKey: queryKeys.playerActivity(handle),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/players/{handle}/activities', {
          params: { path: { handle }, query: pageParam ? { cursor: pageParam, limit: PLAYER_ACTIVITY_PAGE } : { limit: PLAYER_ACTIVITY_PAGE } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 15_000,
    retry: false,
  })
}

/** `GET /players/{handle}/pnl-analysis`: their earnings vs SPY and QQQ and P&L by symbol (ACCOUNT_NOT_FOUND without a run). */
export function usePlayerPnlAnalysis(handle: string, range: HistoryRange) {
  return useQuery({
    queryKey: queryKeys.playerPnlAnalysis(handle, range),
    queryFn: () => unwrap(api.GET('/api/v1/players/{handle}/pnl-analysis', { params: { path: { handle }, query: { range } } })),
    staleTime: 60_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

/** `GET /players/{handle}/pnl-calendar`: DAY cells of `month` ("YYYY-MM") or MONTH cells of `year`. */
export function usePlayerPnlCalendar(handle: string, view: CalendarView, month: string, year: number) {
  const key = view === 'DAY' ? month : String(year)
  return useQuery({
    queryKey: queryKeys.playerPnlCalendar(handle, view, key),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/players/{handle}/pnl-calendar', {
          params: { path: { handle }, query: view === 'DAY' ? { view, month } : { view, year } },
        }),
      ),
    staleTime: 60_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

/** At most one refresh per player in this window, however many of their feed items arrive. */
export const LIVE_REFRESH_THROTTLE_MS = 2_000

/** The live data of a player's profile: exact keys plus the paged lists (never the bare ['player', h] prefix). */
function refreshPlayer(qc: QueryClient, handle: string, journal: boolean) {
  void qc.invalidateQueries({ queryKey: queryKeys.player(handle), exact: true })
  void qc.invalidateQueries({ queryKey: queryKeys.playerTrades(handle), exact: true })
  void qc.invalidateQueries({ queryKey: queryKeys.playerPortfolio(handle), exact: true })
  void qc.invalidateQueries({ queryKey: ['player', handle, 'orders'] })
  void qc.invalidateQueries({ queryKey: ['player', handle, 'activity'] })
  if (journal) void qc.invalidateQueries({ queryKey: agentKeys.journal(handle), exact: true })
}

/**
 * Keeps an open profile live without a topic of its own (§19, D7): a `/topic/feed` item of this player (a fill, a
 * closed position, a note…) refreshes the header, trades, portfolio, orders and activity, at most once per 2 s.
 * Reaction updates carry no player and are ignored. Stats and history ranges are left alone (they change at the
 * close), and a reconnect resyncs the same keys.
 */
export function usePlayerLiveRefresh(handle: string) {
  const qc = useQueryClient()
  useEffect(() => {
    const hub = realtime()
    let last = Number.NEGATIVE_INFINITY
    const offs = [
      hub.subscribe('/topic/feed', (body) => {
        if (isFeedReactions(body)) return
        const item = body as FeedItem | null
        if (item?.player?.handle !== handle) return
        const now = Date.now()
        if (now - last < LIVE_REFRESH_THROTTLE_MS) return
        last = now
        refreshPlayer(qc, handle, item.kind === 'JOURNAL')
      }),
      hub.onResync(() => refreshPlayer(qc, handle, true)),
    ]
    return () => offs.forEach((off) => off())
  }, [qc, handle])
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
 * by id); a `FeedReactions` message updates its item in place. Returns the ids that arrived live, so the list can
 * highlight them. A reconnect resyncs by refetching.
 */
export function useLiveFeed(enabled = true): ReadonlySet<string> {
  const qc = useQueryClient()
  const [fresh, setFresh] = useState<ReadonlySet<string>>(() => new Set())
  useEffect(() => {
    if (!enabled) return
    const hub = realtime()
    const offs = [
      hub.subscribe('/topic/feed', (body) => {
        // Phase 2 (§16.11): a reaction change updates the item in place
        if (isFeedReactions(body)) {
          if (body.itemId) applyFeedReactions(qc, body.itemId, body.reactions ?? [])
          return
        }
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
