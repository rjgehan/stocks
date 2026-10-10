import { useClock } from '@/lib/api/hooks/market'
import { Pill } from '@/components/ui/Pill'

/** Always shown: this is paper trading (§22.3). */
export function PaperBadge() {
  return (
    <Pill tone="accent" title="Paper trading: fake money, real market prices">
      Paper
    </Pill>
  )
}

/** Shown when market data is SIMULATED (§15.7, §22.3), or when the feed is degraded or down. */
export function SimBadge() {
  const { data } = useClock()
  const feed = data?.feed
  if (feed?.quality === 'SIMULATED') {
    return (
      <Pill tone="ai" title="Simulated market data: prices are not real">
        SIM
      </Pill>
    )
  }
  if (feed?.status === 'DEGRADED' || feed?.status === 'DOWN') {
    return (
      <Pill tone="warn" title={feed.status === 'DOWN' ? 'Market data is down: prices may be stale' : 'Market data is degraded: some prices may be delayed'}>
        {feed.status === 'DOWN' ? 'Data down' : 'Data degraded'}
      </Pill>
    )
  }
  return null
}
