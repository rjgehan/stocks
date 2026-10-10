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

/** Shown when market data is SIMULATED (§15.7, §22.3). */
export function SimBadge() {
  const { data } = useClock()
  if (data?.feed.quality !== 'SIMULATED') return null
  return (
    <Pill tone="ai" title="Simulated market data: prices are not real">
      SIM
    </Pill>
  )
}
