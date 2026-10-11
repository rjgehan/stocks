import { useState } from 'react'
import { useMyStats, usePlayerStats } from '@/lib/api/hooks/stats'
import type { LeaderboardPeriod } from '@/lib/api/types'
import { PlayerStatsCard } from './PlayerStatsCard'

/** A player's league stats on their profile (§22.4 Phase 2), period chips kept on the card. */
export function PlayerStatsSection({ handle }: { handle: string }) {
  const [period, setPeriod] = useState<LeaderboardPeriod>('ALL')
  const q = usePlayerStats(handle, period)
  return <PlayerStatsCard stats={q.data} isPending={q.isPending} isError={q.isError} period={period} onPeriod={setPeriod} delayed />
}

/** The caller's own league stats (P&L Analysis "Your stats"), nothing delayed. */
export function MyStatsSection() {
  const [period, setPeriod] = useState<LeaderboardPeriod>('ALL')
  const q = useMyStats(period)
  return <PlayerStatsCard stats={q.data} isPending={q.isPending} isError={q.isError} period={period} onPeriod={setPeriod} title="Your stats" />
}
