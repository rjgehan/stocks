/** TanStack Query keys (§22.6). One place, so invalidations from STOMP events stay consistent. */
import type { ChartRange, KindFilter, LeaderboardMetric, LeaderboardPeriod } from './types'

export const queryKeys = {
  me: ['me'] as const,
  clock: ['clock'] as const,
  rules: ['rules'] as const,
  account: ['account'] as const,
  positions: ['positions'] as const,
  orders: (status: 'OPEN' | 'CLOSED' | 'ALL') => ['orders', status] as const,
  order: (id: string) => ['order', id] as const,
  activities: (filters: Record<string, unknown> = {}) => ['activities', filters] as const,
  history: (range: string) => ['history', range] as const,
  asset: (symbol: string) => ['asset', symbol] as const,
  quote: (symbol: string) => ['quote', symbol] as const,
  bars: (symbol: string, range: ChartRange, tf?: string) => ['bars', symbol, range, tf ?? null] as const,
  movers: (list: string) => ['movers', list] as const,
  news: (symbols: string) => ['news', symbols] as const,
  watchlists: ['watchlists'] as const,
  leaderboard: (period: LeaderboardPeriod, metric: LeaderboardMetric, kind: KindFilter) =>
    ['leaderboard', period, metric, kind] as const,
  player: (handle: string) => ['player', handle] as const,
  feed: ['feed'] as const,
  notifications: ['notifications'] as const,
  admin: (...rest: unknown[]) => ['admin', ...rest] as const,
  orderPreview: (request: unknown) => ['orderPreview', request] as const,
} as const
