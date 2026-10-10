import { useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { KindFilter, LeaderboardMetric, LeaderboardPeriod } from '../types'

export function useLeaderboard(period: LeaderboardPeriod, metric: LeaderboardMetric, kind: KindFilter) {
  return useQuery({
    queryKey: queryKeys.leaderboard(period, metric, kind),
    queryFn: () => unwrap(api.GET('/api/v1/leaderboard', { params: { query: { period, metric, kind } } })),
    staleTime: 5_000,
  })
}
