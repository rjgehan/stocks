import { useEffect } from 'react'
import { type InfiniteData, type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { CalendarView, FeedItem, FeedReaction, FeedReactions, HistoryRange, KindFilter, LeaderboardPeriod, Reaction } from '../types'
import { realtime } from '../../realtime/stomp'

/**
 * Phase 2 league stats (§16.8–16.11): the race chart, player stats, P&L analysis and calendar, feed reactions.
 * The race chart reads the board's cached computation, so it refetches on `/topic/leaderboard` like the board.
 */

/** `GET /league/race`: refetched on `/topic/leaderboard` (§19); polls every 60 s as a fallback. */
export function useRace(period: LeaderboardPeriod, kind: KindFilter, enabled = true) {
  const qc = useQueryClient()
  useEffect(() => {
    if (!enabled) return
    const hub = realtime()
    const offs = [
      hub.subscribe('/topic/leaderboard', () => void qc.invalidateQueries({ queryKey: ['race'] })),
      hub.onResync(() => void qc.invalidateQueries({ queryKey: ['race'] })),
    ]
    return () => offs.forEach((off) => off())
  }, [qc, enabled])
  return useQuery({
    queryKey: queryKeys.race(period, kind),
    queryFn: () => unwrap(api.GET('/api/v1/league/race', { params: { query: { period, kind } } })),
    staleTime: 5_000,
    refetchInterval: 60_000,
    placeholderData: (prev) => prev,
    enabled,
  })
}

/** `GET /players/{handle}/stats` (another player's best/worst trades come after the feed delay). */
export function usePlayerStats(handle: string, period: LeaderboardPeriod) {
  return useQuery({
    queryKey: queryKeys.playerStats(handle, period),
    queryFn: () => unwrap(api.GET('/api/v1/players/{handle}/stats', { params: { path: { handle }, query: { period } } })),
    staleTime: 30_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

/** `GET /account/stats`: the caller's own league stats. */
export function useMyStats(period: LeaderboardPeriod) {
  return useQuery({
    queryKey: queryKeys.myStats(period),
    queryFn: () => unwrap(api.GET('/api/v1/account/stats', { params: { query: { period } } })),
    staleTime: 30_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

/** `GET /account/pnl-analysis`: My Earnings (vs SPY and QQQ) and P&L by symbol. */
export function usePnlAnalysis(range: HistoryRange) {
  return useQuery({
    queryKey: queryKeys.pnlAnalysis(range),
    queryFn: () => unwrap(api.GET('/api/v1/account/pnl-analysis', { params: { query: { range } } })),
    staleTime: 30_000,
    refetchInterval: 60_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

/** `GET /account/pnl-calendar`: DAY cells of `month` ("YYYY-MM") or MONTH cells of `year`. */
export function usePnlCalendar(view: CalendarView, month: string, year: number) {
  const key = view === 'DAY' ? month : String(year)
  return useQuery({
    queryKey: queryKeys.pnlCalendar(view, key),
    queryFn: () =>
      unwrap(api.GET('/api/v1/account/pnl-calendar', { params: { query: view === 'DAY' ? { view, month } : { view, year } } })),
    staleTime: 30_000,
    refetchInterval: 60_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

type FeedPage = { items: FeedItem[] | null; nextCursor: string | null }

/** A `/topic/feed` message that updates reactions (§19: it has `itemId`, a `FeedItem` has `id`). */
export function isFeedReactions(body: unknown): body is FeedReactions {
  return typeof body === 'object' && body !== null && 'itemId' in body && !('id' in body)
}

/**
 * Puts an item's reactions into the cached feed pages, in place (never prepends). Returns false when the item is
 * not loaded.
 */
export function applyFeedReactions(qc: QueryClient, itemId: string, reactions: FeedReaction[]): boolean {
  let found = false
  qc.setQueryData<InfiniteData<FeedPage>>(queryKeys.feed, (data) => {
    if (!data) return data
    return {
      ...data,
      pages: data.pages.map((p) => ({
        ...p,
        items: (p.items ?? []).map((i) => {
          if (i.id !== itemId) return i
          found = true
          return { ...i, reactions }
        }),
      })),
    }
  })
  return found
}

/**
 * Sets (`reaction`) or removes (null) the caller's reaction on a feed item (§16.11). The caller applies the
 * optimistic state first; the server's answer then replaces it, and a failure restores `previous`.
 */
export function useReact() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ itemId, reaction }: { itemId: string; reaction: Reaction | null; previous: FeedReaction[] }) =>
      reaction
        ? unwrap(api.PUT('/api/v1/feed/{itemId}/reaction', { params: { path: { itemId } }, body: { reaction } }))
        : unwrap(api.DELETE('/api/v1/feed/{itemId}/reaction', { params: { path: { itemId } } })),
    onSuccess: (dto, vars) => {
      applyFeedReactions(qc, dto.itemId ?? vars.itemId, dto.reactions ?? [])
    },
    onError: (_e, vars) => {
      applyFeedReactions(qc, vars.itemId, vars.previous)
    },
  })
}
