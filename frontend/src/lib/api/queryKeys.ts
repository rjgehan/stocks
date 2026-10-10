/** TanStack Query keys (§22.6). One place, so invalidations from STOMP events stay consistent. */
import type { ChartRange, HistoryRange, KindFilter, LeaderboardMetric, LeaderboardPeriod, MoverList, Timeframe } from './types'

export type OrdersFilter = 'OPEN' | 'CLOSED' | 'ALL'

export const queryKeys = {
  me: ['me'] as const,
  clock: ['clock'] as const,
  rules: ['rules'] as const,
  account: ['account'] as const,
  positions: ['positions'] as const,
  orders: (status: OrdersFilter, symbol?: string) => (symbol ? (['orders', status, symbol] as const) : (['orders', status] as const)),
  order: (id: string) => ['order', id] as const,
  activities: (filters: Record<string, unknown> = {}) => ['activities', filters] as const,
  history: (range: HistoryRange) => ['history', range] as const,
  runs: ['runs'] as const,
  asset: (symbol: string) => ['asset', symbol] as const,
  assetSearch: (q: string) => ['assetSearch', q] as const,
  quote: (symbol: string) => ['quote', symbol] as const,
  bars: (symbol: string, range: ChartRange, tf?: Timeframe) => ['bars', symbol, range, tf ?? null] as const,
  movers: (list: MoverList) => ['movers', list] as const,
  indices: ['indices'] as const,
  news: (symbols: string) => ['news', symbols] as const,
  watchlists: ['watchlists'] as const,
  leaderboard: (period: LeaderboardPeriod, metric: LeaderboardMetric, kind: KindFilter) => ['leaderboard', period, metric, kind] as const,
  players: ['players'] as const,
  player: (handle: string) => ['player', handle] as const,
  playerHistory: (handle: string, range: HistoryRange) => ['player', handle, 'history', range] as const,
  playerTrades: (handle: string) => ['player', handle, 'trades'] as const,
  feed: ['feed'] as const,
  notifications: ['notifications'] as const,
  apiKeys: ['apiKeys'] as const,
  admin: (...rest: unknown[]) => ['admin', ...rest] as const,
  orderPreview: (request: unknown) => ['orderPreview', request] as const,
  // Phase 2 (§19.1, §19.2)
  alerts: ['alerts'] as const,
  push: ['push'] as const,
} as const
